package com.example.bonsai.llm

import android.util.Log
import com.example.bonsai.util.DebugLog
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Puente JNI hacia llama.cpp (fork PrismML, quants ternarios PQ2_0).
 *
 * Un solo handle vivo y una sola generación a la vez: si ya hay un stream
 * corriendo, el segundo intento falla rápido con [IllegalStateException].
 * La inferencia corre en un dispatcher single-thread dedicado para que el
 * decode secuencial no compita con nada más por los cores de rendimiento.
 */
class LlamaBridge private constructor(
    private val handle: Long,
    private val dispatcher: CoroutineDispatcher,
) {
    private val busy = AtomicBoolean(false)

    /** Texto acumulado del turno completo, para el historial del chat. */
    @Volatile
    var lastFullText: String = ""
        private set

    /**
     * `true` si el stream terminó en EOG real (no abortado ni cortado por
     * budget). Un `false` avisa al chat de que puede quedar un <tool_call> sin cerrar.
     */
    @Volatile
    var endedCleanly: Boolean = false
        private set

    /**
     * Genera de forma streaming: emite piezas UTF-8 seguras y cierra
     * normalmente en EOG, budget de tokens o aborto manual.
     */
    fun generateStream(prompt: String, maxTokens: Int = 512): Flow<String> = flow {
        require(busy.compareAndSet(false, true)) {
            "LlamaBridge ya está generando; no se permite doble generación."
        }
        aborted = false
        lastFullText = ""
        endedCleanly = false
        var chunks = 0
        try {
            DebugLog.add(TAG, "generateStream: prompt=${prompt.length} chars, maxTokens=$maxTokens")
            if (!nativeStart(handle, prompt, maxTokens)) {
                throw IllegalStateException(
                    "No se pudo iniciar el decode: ¿prompt demasiado largo para n_ctx?"
                )
            }
            while (true) {
                val piece: String? = nativeNext(handle)
                when {
                    // EOG / budget / abort / error de decode → cerrar el flow
                    piece == null -> break
                    // prefill aún en curso → seguir hasta la fase de decode
                    piece.isEmpty() -> Unit
                    // pieza visible → acumular y emitir
                    else -> {
                        chunks++
                        lastFullText += piece
                        emit(piece)
                    }
                }
                if (aborted) break
            }
            endedCleanly = !aborted
            DebugLog.add(
                TAG,
                "fin del stream: $chunks piezas, ${lastFullText.length} chars, " +
                    "abortado=$aborted",
            )
        } catch (e: Exception) {
            DebugLog.add(TAG, "ERROR en el stream: ${e.message}")
            Log.e(TAG, "Error en el stream de generación", e)
            throw e
        } finally {
            // Fuerza generating=false en C++; no destruye la sesión.
            nativeAbort(handle)
            busy.set(false)
            drainNativeLog().takeIf { it.isNotBlank() }?.let {
                DebugLog.add("native", it)
            }
        }
    }
        .catch { e ->
            Log.e(TAG, "Error en el stream de generación", e)
            throw e
        }
        .flowOn(dispatcher)

    /** Botón "detener" de la UI: corta el stream en la próxima iteración. */
    fun abort() {
        aborted = true
        nativeAbort(handle)
    }

    /** Libera modelo + contexto + sampler y el hilo de inferencia. */
    fun close() {
        DebugLog.add(TAG, "close(): liberando modelo y contexto")
        nativeFree(handle)
        (dispatcher as? ExecutorCoroutineDispatcher)?.close()
    }

    /** ¿Se compiló el backend Vulkan en este .so? */
    val hasGpuBackend: Boolean
        get() = nativeHasGpuBackend()

    /** Tokens de contexto ocupados en el turno en curso (medidor de UI). */
    val ctxUsed: Int
        get() = nativeCtxUsed(handle)

    @Volatile
    private var aborted: Boolean = false

    // ------------------------------------------------- bindings externos

    private external fun nativeStart(handle: Long, prompt: String, maxTokens: Int): Boolean
    private external fun nativeNext(handle: Long): String?
    private external fun nativeAbort(handle: Long)
    private external fun nativeFree(handle: Long)
    private external fun nativeCtxUsed(handle: Long): Int
    private external fun nativeHasGpuBackend(): Boolean

    companion object {
        private const val TAG = "LlamaBridge"

        init {
            System.loadLibrary("bonsai-jni")
        }

        /**
         * Carga el modelo desde almacenamiento interno. El wrapper C++ hace
         * mmap del archivo, así que la ruta tiene que ser un File accesible
         * por el kernel (no un URI de SAF).
         *
         * @param modelFile el .gguf en filesDir (cópialo con adb o Explorer)
         * @param contextWindow 4096 recomendado; 8192 si hay ≥2 GB libres
         * @param threads nº de cores de rendimiento (2..6)
         * @param gpuLayers 0 = CPU-only; -1 si compilaste el backend Vulkan
         * @param flashAttention menos DRAM en el prefill de prompts largos
         * @throws ModelLoadException si llama.cpp no puede abrir el .gguf
         */
        fun load(
            modelFile: File,
            contextWindow: Int = 4096,
            threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
            gpuLayers: Int = 0,
            flashAttention: Boolean = false,
            temperature: Float = 0.7f,
            topP: Float = 0.8f,
            topK: Int = 20,
            seed: Int = -1,
            maxGenerated: Int = 512,
        ): LlamaBridge {
            require(modelFile.exists()) {
                "El .gguf no existe en ${modelFile.absolutePath}. " +
                    "Cópialo con adb push al filesDir de la app."
            }

            DebugLog.add(
                TAG,
                "load: ${modelFile.absolutePath} " +
                    "(${modelFile.length() / (1024 * 1024)} MB), ctx=$contextWindow, " +
                    "threads=$threads, flashAttn=$flashAttention",
            )

            val handle = nativeInit(
                modelPath = modelFile.absolutePath,
                nCtx = contextWindow,
                nThreads = threads,
                nGpuLayers = gpuLayers,
                flashAttn = flashAttention,
                temp = temperature,
                topP = topP,
                topK = topK,
                seed = seed,
                maxGenerated = maxGenerated,
            )
            val nativeLog = drainNativeLog()
            if (nativeLog.isNotBlank()) DebugLog.add("native", nativeLog)

            if (handle == 0L) {
                throw ModelLoadException(
                    "llama_model_load_from_file devolvió null: .gguf corrupto, " +
                        "sin soporte PQ2_0 en el fork, o sin RAM libre suficiente.",
                )
            }

            val dispatcher = Executors.newSingleThreadExecutor { r ->
                Thread(r, "bonsai-llama").apply { priority = Thread.MAX_PRIORITY }
            }.asCoroutineDispatcher()

            DebugLog.add(TAG, "modelo cargado correctamente")
            return LlamaBridge(handle, dispatcher)
        }

        // @JvmStatic es imprescindible: sin él el símbolo JNI sería
        // Java_com_example_bonsai_llm_LlamaBridge_00024Companion_nativeInit.
        @JvmStatic
        private external fun nativeInit(
            modelPath: String,
            nCtx: Int,
            nThreads: Int,
            nGpuLayers: Int,
            flashAttn: Boolean,
            temp: Float,
            topP: Float,
            topK: Int,
            seed: Int,
            maxGenerated: Int,
        ): Long

        /** Extrae los logs acumulados en C++ (modelo, prefill, tokens, EOG). */
        fun drainNativeLog(): String = runCatching { nativeDrainLog() }.getOrDefault("")

        @JvmStatic
        private external fun nativeDrainLog(): String
    }
}

class ModelLoadException(message: String) : RuntimeException(message)
