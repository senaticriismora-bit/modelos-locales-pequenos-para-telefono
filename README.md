# 🌳 Modelos locales pequeños para teléfono — Bonsái

Asistente de voz **100% local** para Android: el reconocimiento de voz, el
modelo de lenguaje y las herramientas corren **en el teléfono**. Nada sale
a internet una vez descargado el modelo.

Este repositorio contiene la app Android (Kotlin + Jetpack Compose) y la
integración nativa (NDK/JNI) para ejecutar modelos GGUF pequeños con
[llama.cpp](https://github.com/PrismML-Eng/llama.cpp) en un móvil modesto
(probado en un Redmi con Helio G36 — 8× Cortex-A53, 3.7 GB de RAM).

## Modelos probados

| Variante (selector en la app) | Modelo | Tamaño | Velocidad medida* | Notas |
|---|---|---|---|---|
| `qwen05` (por defecto) | Qwen2.5 0.5B Instruct (Q4_K_M) | ~490 MB | prefill ~13 tok/s · generación ~7 tok/s | La opción práctica para este hardware; soporta herramientas con el prompt adecuado |
| `qwen15` | Qwen2.5 1.5B Instruct (Q4_K_M) | ~1.1 GB | ~3-4 tok/s | Mejor calidad, notablemente más lento |
| `bonsai4b` | Bonsai 2 4B ternario (PQ2_0) | ~1.15 GB | ~2 tok/s | Demasiado lento en Cortex-A53 |

\*Medido en Helio G36. El **prefill se acelera con reutilización de la caché KV**
entre turnos de conversación: solo se procesan los tokens nuevos.

Los modelos se descargan desde la propia app (Hugging Face) y se guardan en
`Descargas/Bonsai/` para que sobrevivan a reinstalaciones.

## Requisitos

- Android 7.0+ (minSdk 24), arquitectura **arm64-v8a** únicamente
- ~1 GB de RAM libre para el modelo de 0.5B
- Dictado offline con `SpeechRecognizer` del sistema (opcional: también se puede escribir)

## Compilar

```bash
git clone --recursive https://github.com/senaticriismora-bit/modelos-locales-pequenos-para-telefono.git
cd modelos-locales-pequenos-para-telefono
./gradlew :app:assembleDebug
```

> El submódulo `app/src/main/cpp/llama.cpp` apunta al fork
> [PrismML-Eng/llama.cpp](https://github.com/PrismML-Eng/llama.cpp) (rama `prism`),
> que expone las APIs `llama_memory_*` usadas por el wrapper JNI. Ojo: ese fork
> es grande (~200 MB en disco). Si clonaste sin `--recursive`:
> `git submodule update --init --recursive`.

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Uso

1. Abre la app, elige un modelo y descárgalo (el 0.5B es el más rápido).
2. Toca el **micrófono** y dicta (se envía solo al terminar), o escribe.
3. Herramientas disponibles: **poner alarma**, **abrir apps** y **nivel de batería**.
   El modelo las invoca con el formato ChatML extendido:

   ```text
   <tool_call>{"name": "getBatteryLevel", "arguments": {}}</tool_call>
   ```

   y el resultado se devuelve como `<tool_response>…</tool_response>`.
4. 🐞 abre el visor de logs interno; 🗑 borra los modelos descargados.

## Estructura

```text
app/src/main/
├─ cpp/
│  ├─ llama_jni.cpp      # Wrapper JNI: carga, prefill, decode, reuso de KV
│  ├─ CMakeLists.txt
│  └─ llama.cpp/         # Submódulo (fork con APIs llama_memory_*)
├─ java/com/example/bonsai/
│  ├─ chat/              # Plantilla ChatML (Qwen) + parser de tool calls
│  ├─ llm/               # LlamaBridge (JNI) + ModelDownloader (catálogo de variantes)
│  ├─ speech/            # Dictado offline (SpeechRecognizer)
│  ├─ tools/             # Alarma, abrir apps, batería
│  └─ ui/                # Chat en Compose (selector de modelo, logs, borrado)
```

## Notas técnicas

- Compilado para Cortex-A53 (sin dotprod/FP16), `-O3`, 6 hilos de inferencia.
- `n_ctx = 4096`; sampling temp 0.7 / top-k 20 / top-p 0.8 / presence 1.5.
- Reuso de prefijo KV en `llama_jni.cpp`: entre turnos se recortan de la caché
  solo los tokens ya procesados (`llama_memory_seq_rm`) y el prefill continúa
  desde ahí; si no hay prefijo común suficiente, se re-procesa todo.
- `WAKE_LOCK` parcial durante la generación para que MIUI/HyperOS no la congele.
- Licencias de componentes: llama.cpp (MIT), Qwen2.5 (Apache-2.0).
  El código de esta app no tiene licencia definida todavía.
