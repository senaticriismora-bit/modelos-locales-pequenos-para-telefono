// llama_jni.cpp — Bonsai JNI wrapper sobre llama.cpp (fork PrismML, quants ternarios)
//
// El .gguf se carga vía mmap: los pesos se paginan bajo demanda desde el
// page cache, así que la RAM residente real converge a grafo + KV cache
// (~1 GB para el 4B cuantizado PQ2_0), no al tamaño completo del archivo.

#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "bonsai-jni"

namespace {

// Buffer circular de logs nativo accesible desde Kotlin (nativeDrainLog).
std::mutex g_logMutex;
std::deque<std::string> g_logLines;
constexpr size_t kMaxLogLines = 300;

// Mínimo de tokens de prefijo común para reutilizar el KV en vez de re-prefill.
constexpr size_t kMinReuseTokens = 16;

void logMsg(int priority, const char *fmt, ...) {
    char buffer[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);

    __android_log_print(priority, TAG, "%s", buffer);
    std::lock_guard<std::mutex> lock(g_logMutex);
    if (g_logLines.size() >= kMaxLogLines) g_logLines.pop_front();
    g_logLines.emplace_back(buffer);
}

} // namespace

#define LOGI(...) logMsg(ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGE(...) logMsg(ANDROID_LOG_ERROR, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------------------
// Estado de sesión
// ---------------------------------------------------------------------------
struct LlamaSession {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *smpl = nullptr;
    const llama_vocab *vocab = nullptr;

    // Flujo de generación (máquina de estados por llamada a nativeNext)
    std::vector<llama_token> prompt_tokens;
    size_t prompt_pos = 0;          // tokens de prompt ya decodificados
    llama_token pending_token = 0;  // token muestreado pendiente de decodificar
    bool has_pending_token = false; // false justo tras decodificar el prompt
    bool generating = false;
    int generated_count = 0;
    int max_generated = 0;          // budget de tokens generados por turno

    // Secuencia exacta que contiene el KV (para reutilizar prefijos).
    std::vector<llama_token> kv_tokens;
    bool kv_valid = false;          // false si el KV quedó parcial (abort/error)

    // Parámetros de decodificación
    int n_ctx = 4096;
    int n_ubatch = 256;
    int n_threads = 4;

    std::mutex mtx;
};

inline LlamaSession *asSession(jlong handle) {
    return reinterpret_cast<LlamaSession *>(static_cast<uintptr_t>(handle));
}

// Tokeniza texto incluyendo tokens especiales (<|im_start|>, <tool_call>, …)
std::vector<llama_token> jstringToTokens(JNIEnv *env, LlamaSession *s,
                                         const jstring &text, bool special) {
    const jchar *jstr = env->GetStringChars(text, nullptr);
    jsize jlen = env->GetStringLength(text);
    // llama_tokenizado pide bytes UTF-8
    std::string utf8;
    utf8.reserve(jlen * 2);
    {
        const jchar *p = jstr;
        for (jsize i = 0; i < jlen; ++i) {
            unsigned int c = p[i];
            if (c < 0x80) {
                utf8.push_back(static_cast<char>(c));
            } else if (c < 0x800) {
                utf8.push_back(static_cast<char>(0xC0 | (c >> 6)));
                utf8.push_back(static_cast<char>(0x80 | (c & 0x3F)));
            } else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < jlen &&
                       p[i + 1] >= 0xDC00 && p[i + 1] <= 0xDFFF) {
                // Par suplementario → U+10000..U+10FFFF
                unsigned int cp = 0x10000 + ((c - 0xD800) << 10) + (p[i + 1] - 0xDC00);
                utf8.push_back(static_cast<char>(0xF0 | (cp >> 18)));
                utf8.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
                utf8.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
                utf8.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
                ++i;
            } else {
                utf8.push_back(static_cast<char>(0xE0 | (c >> 12)));
                utf8.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
                utf8.push_back(static_cast<char>(0x80 | (c & 0x3F)));
            }
        }
    }
    env->ReleaseStringChars(text, jstr);

    const int n = llama_tokenize(s->vocab, utf8.data(), (int32_t)utf8.size(),
                                 nullptr, 0, /*add_special=*/false, special);
    std::vector<llama_token> tokens((size_t)std::abs(n));
    if (n < 0) {
        llama_tokenize(s->vocab, utf8.data(), (int32_t)utf8.size(),
                       tokens.data(), (int32_t)tokens.size(),
                       /*add_special=*/false, special);
    }
    return tokens; // vacío si falló la tokenización
}

// Token → pieza UTF-8 (para el streaming)
std::string tokenToPiece(LlamaSession *s, llama_token tok) {
    char buf[192];
    const int n = llama_token_to_piece(s->vocab, tok, buf, sizeof(buf),
                                       /*lstrip=*/0, /*special=*/false);
    if (n < 0) {
        std::string out((size_t)(-n), '\0');
        llama_token_to_piece(s->vocab, tok, out.data(), (int32_t)out.size(), 0, false);
        return out;
    }
    return std::string(buf, (size_t)n);
}

void freeSession(LlamaSession *s) {
    if (s == nullptr) return;
    std::lock_guard<std::mutex> lock(s->mtx);
    LOGI("liberando sesión (ctx=%p)", (void *) s->ctx);
    if (s->smpl != nullptr) llama_sampler_free(s->smpl);
    if (s->ctx != nullptr) llama_free(s->ctx);
    if (s->model != nullptr) llama_model_free(s->model);
    delete s;
}

// --------------------------------------------------------------------------
// JNI
// --------------------------------------------------------------------------
extern "C" {

/**
 * Carga el .gguf y crea el contexto.
 *
 * @param modelPath ruta absoluta del .gguf en almacenamiento interno
 * @param nCtx      ventana de contexto (4096 recomendado, 8192 MAX con 2 GB libres)
 * @param nThreads  hilos CPU (sugerido: #cores de rendimiento)
 * @param nGpuLayers 0 para CPU-only; -1 activa el backend compilado (Vulkan) si existe
 * @param flashAttn activa FA en el grafo (menos VRAM/DRAM en prefill largo)
 * @param temp/topP/topK parámetros del sampler chain
 * @param seed semilla (-1 = aleatoria)
 * @return handle de sesión o 0 en error
 */
JNIEXPORT jlong JNICALL
Java_com_example_bonsai_llm_LlamaBridge_nativeInit(
        JNIEnv *env, jobject /*thiz*/, jstring modelPath, jint nCtx, jint nThreads,
        jint nGpuLayers, jboolean flashAttn, jfloat temp, jfloat topP, jint topK,
        jint seed, jint maxGenerated) {

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) return 0;

    // Inicializa ggml/llama una sola vez por proceso.
    static std::once_flag backendOnce;
    std::call_once(backendOnce, [] { llama_backend_init(); });

    LOGI("nativeInit: n_ctx=%d threads=%d gpu_layers=%d flash=%d", nCtx, nThreads,
         nGpuLayers, flashAttn == JNI_TRUE ? 1 : 0);
    LOGI("cargando modelo: %s", path);

    auto *s = new LlamaSession();
    s->n_ctx = nCtx;
    s->n_threads = nThreads > 0 ? nThreads : 4;
    s->max_generated = maxGenerated;

    llama_model_params mparams = llama_model_default_params();
    // mmap: carga diferida desde el page cache; residente real ≈ grafo + KV.
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
    mparams.n_gpu_layers = nGpuLayers;

    s->model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (s->model == nullptr) {
        LOGE("llama_model_load_from_file falló (modelo null)");
        freeSession(s);
        return 0;
    }
    s->vocab = llama_model_get_vocab(s->model);
    LOGI("modelo cargado: vocab=%d tokens", llama_vocab_n_tokens(s->vocab));

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t)nCtx;      // ventana lógica
    cparams.n_batch = (uint32_t)s->n_ubatch; // prefill en chunks
    cparams.n_ubatch = (uint32_t)s->n_ubatch;
    cparams.n_threads = s->n_threads;
    cparams.n_threads_batch = s->n_threads;
    cparams.flash_attn_type = (flashAttn == JNI_TRUE)
        ? LLAMA_FLASH_ATTN_TYPE_ENABLED
        : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    cparams.no_perf = true;

    LOGI("creando contexto (n_ctx=%u n_batch=%u n_ubatch=%u)…",
         cparams.n_ctx, cparams.n_batch, cparams.n_ubatch);
    s->ctx = llama_init_from_model(s->model, cparams);
    if (s->ctx == nullptr) {
        LOGE("llama_init_from_model falló (¿n_ctx=%d demasiado grande?)", nCtx);
        freeSession(s);
        return 0;
    }
    LOGI("contexto listo");

    // Cadena de muestreo alineada con la model card de Bonsai 2 (non-thinking):
    // temp 0.7 / top-k 20 / top-p 0.8 / presence 1.5 sobre los últimos 64 tokens.
    auto *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(
            llama_vocab_n_tokens(s->vocab), 64, 1.0f, 0.0f, 1.5f));
    if (topK > 0) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(topK));
    }
    if (topP < 1.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(topP, 1));
    }
    llama_sampler_chain_add(chain, llama_sampler_init_temp_ext(temp, 1.0f, 0.0f));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));
    s->smpl = chain;

    LOGI("modelo cargado: ctx=%d threads=%d", nCtx, s->n_threads);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_example_bonsai_llm_LlamaBridge_nativeFree(JNIEnv * /*env*/, jobject, jlong handle) {
    freeSession(asSession(handle));
}

/**
 * Prepara una generación: reutiliza el prefijo común del KV, tokeniza el
 * prompt (con tokens especiales) y arma el estado de prefill. Llamar 1 vez
 * por turno y luego consumir con nativeNext() hasta null.
 */
JNIEXPORT jboolean JNICALL
Java_com_example_bonsai_llm_LlamaBridge_nativeStart(
        JNIEnv *env, jobject, jlong handle, jstring prompt, jint maxTokens) {
    auto *s = asSession(handle);
    if (s == nullptr || s->model == nullptr || s->ctx == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(s->mtx);

    if (maxTokens > 0) s->max_generated = maxTokens;

    LOGI("nativeStart: prompt=%d bytes, max_gen=%d, n_ctx=%d",
         (int) env->GetStringUTFLength(prompt), s->max_generated, s->n_ctx);

    auto tokens = jstringToTokens(env, s, prompt, /*special=*/true);
    if (tokens.empty()) {
        LOGE("tokenización devolvió 0 tokens");
        return JNI_FALSE;
    }
    if (tokens.size() + (size_t)s->max_generated >= (size_t)s->n_ctx) {
        LOGE("prompt (%zu) + max_gen (%d) excede n_ctx (%d)", tokens.size(),
             s->max_generated, s->n_ctx);
        return JNI_FALSE;
    }

    // Prefijo común con el KV anterior: solo se decodifica lo nuevo.
    size_t common = 0;
    if (s->kv_valid) {
        const size_t limit = std::min(s->kv_tokens.size(), tokens.size());
        while (common < limit && s->kv_tokens[common] == tokens[common]) ++common;
    }
    // Hace falta decodificar al menos 1 token para poder muestrear.
    if (common >= tokens.size()) common = tokens.size() - 1;

    if (common >= kMinReuseTokens &&
        llama_memory_seq_rm(llama_get_memory(s->ctx), 0, (llama_pos) common, -1)) {
        s->kv_tokens.resize(common);
        LOGI("KV reutilizado: prefijo %zu tokens, nuevos %zu (total %zu)",
             common, tokens.size() - common, tokens.size());
    } else {
        llama_memory_clear(llama_get_memory(s->ctx), /*data=*/true);
        s->kv_tokens.clear();
        common = 0;
        LOGI("KV limpiado: re-prefill completo de %zu tokens", tokens.size());
    }

    s->prompt_tokens = std::move(tokens);
    s->prompt_pos = common;
    s->pending_token = 0;
    s->has_pending_token = false;
    s->generated_count = 0;
    s->generating = true;
    s->kv_valid = true;
    if (s->smpl != nullptr) llama_sampler_reset(s->smpl);

    LOGI("prompt tokenizado: %zu tokens (sampler reseteado)",
         s->prompt_tokens.size());
    return JNI_TRUE;
}

/**
 * Siguiente token del stream.
 *
 * @return pieza UTF-8 del token decodificado, "" si aún sigue el prefill, y
 *         nullptr (NULL) al llegar a EOG, exceder el budget o ante error.
 */
JNIEXPORT jstring JNICALL
Java_com_example_bonsai_llm_LlamaBridge_nativeNext(JNIEnv *env, jobject, jlong handle) {
    auto *s = asSession(handle);
    if (s == nullptr) return nullptr;

    std::lock_guard<std::mutex> lock(s->mtx);
    if (!s->generating) return nullptr;

    // --- fase 1: prefill por chunks / decode del token pendiente -----------
    if (s->prompt_pos < s->prompt_tokens.size()) {
        const int remaining = (int)(s->prompt_tokens.size() - s->prompt_pos);
        const int chunk = std::min(remaining, s->n_ubatch);
        llama_batch batch = llama_batch_get_one(&s->prompt_tokens[s->prompt_pos], chunk);
        const int rc = llama_decode(s->ctx, batch);
        if (rc != 0) {
            LOGE("llama_decode falló durante el prefill (rc=%d, pos=%zu/%zu)",
                 rc, s->prompt_pos, s->prompt_tokens.size());
            s->generating = false;
            s->kv_valid = false;
            return nullptr;
        }
        s->kv_tokens.insert(
            s->kv_tokens.end(),
            s->prompt_tokens.begin() + (long) s->prompt_pos,
            s->prompt_tokens.begin() + (long) (s->prompt_pos + chunk));
        s->prompt_pos += (size_t)chunk;
        LOGI("prefill: %zu/%zu tokens", s->prompt_pos, s->prompt_tokens.size());
    } else if (s->has_pending_token) {
        llama_batch batch = llama_batch_get_one(&s->pending_token, 1);
        const int rc = llama_decode(s->ctx, batch);
        if (rc != 0) {
            LOGE("llama_decode falló en el decode paso a paso (rc=%d, token=%d)",
                 rc, (int) s->pending_token);
            s->generating = false;
            s->kv_valid = false;
            return nullptr;
        }
        s->kv_tokens.push_back(s->pending_token);
    } else {
        // imposible: nativeStart garantiza prompt no vacío
        s->generating = false;
        return nullptr;
    }

    // todavía en medio del prefill → no hay logits finales todavía
    if (s->prompt_pos < s->prompt_tokens.size()) {
        return env->NewStringUTF("");
    }

    // --- fase 2: muestreo --------------------------------------------------
    llama_token tok = llama_sampler_sample(s->smpl, s->ctx, /*idx=*/-1);
    llama_sampler_accept(s->smpl, tok);
    s->pending_token = tok;
    s->has_pending_token = true;
    s->generated_count++;

    if (s->generated_count == 1) {
        LOGI("primer token muestreado: id=%d", (int) tok);
    } else if ((s->generated_count % 64) == 0) {
        LOGI("generados %d tokens", s->generated_count);
    }

    if (llama_vocab_is_eog(s->vocab, tok)) {
        LOGE("EOG con solo %d tokens generados (id=%d)", s->generated_count, (int) tok);
        s->generating = false;
        return nullptr;
    }
    if (s->generated_count >= s->max_generated) {
        LOGI("límite de %d tokens alcanzado", s->max_generated);
        s->generating = false;
        return nullptr;
    }

    const std::string piece = tokenToPiece(s, tok);
    if (piece.empty()) {
        LOGI("token %d sin pieza visible (token especial)", (int) tok);
    }
    return env->NewStringUTF(piece.c_str());
}

/** Aborta el stream en curso (desde el botón "detener" de la UI). */
JNIEXPORT void JNICALL
Java_com_example_bonsai_llm_LlamaBridge_nativeAbort(JNIEnv * /*env*/, jobject, jlong handle) {
    auto *s = asSession(handle);
    if (s == nullptr) return;
    std::lock_guard<std::mutex> lock(s->mtx);
    s->generating = false;
    s->kv_valid = false; // el KV puede quedar a medias
}

/** Tokens de contexto ya ocupados (para el medidor de la UI). */
JNIEXPORT jint JNICALL
Java_com_example_bonsai_llm_LlamaBridge_nativeCtxUsed(JNIEnv * /*env*/, jobject, jlong handle) {
    auto *s = asSession(handle);
    if (s == nullptr) return 0;
    return (jint)(s->prompt_tokens.size() + (size_t)s->generated_count);
}

/** Devuelve y vacía el buffer de logs nativo (visor de depuración de la app). */
JNIEXPORT jstring JNICALL
Java_com_example_bonsai_llm_LlamaBridge_nativeDrainLog(JNIEnv *env, jobject) {
    std::string out;
    {
        std::lock_guard<std::mutex> lock(g_logMutex);
        for (const auto &line : g_logLines) {
            out += line;
            out += '\n';
        }
        g_logLines.clear();
    }
    return env->NewStringUTF(out.c_str());
}

/** ¿El backend con aceleración (Vulkan/SYCL) está disponible en este build? */
JNIEXPORT jboolean JNICALL
Java_com_example_bonsai_llm_LlamaBridge_nativeHasGpuBackend(JNIEnv * /*env*/, jobject) {
#ifdef GGML_USE_VULKAN
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

} // extern "C"

} // namespace
