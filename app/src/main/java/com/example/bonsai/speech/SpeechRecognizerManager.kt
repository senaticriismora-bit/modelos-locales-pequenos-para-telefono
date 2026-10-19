package com.example.bonsai.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estado observable del reconocedor para la UI. */
sealed interface SpeechState {
    /** Reconocedor inactivo (no se está escuchando). */
    data object Idle : SpeechState

    /** Sonando el beep inicial de "puede hablar". */
    data object Ready : SpeechState

    /** Escuchando voz; nivel RMS en dB para animar el micrófono. */
    data class Listening(val rmsDb: Float) : SpeechState

    /** El usuario dejó de hablar; esperando al resultado final. */
    data object Buffering : SpeechState

    /** Transcripción parcial en tiempo real (dictado en vivo). */
    data class Partial(val text: String) : SpeechState
}

/** Errores de SpeechRecognizer mapeados a mensajes accionables. */
enum class SpeechError(val userMessage: String) {
    NO_MATCH("No te entendí, inténtalo de nuevo"),
    NETWORK_TIMEOUT("Tiempo agotado, habla más cerca del micrófono"),
    AUDIO("Error de audio (¿micrófono bloqueado?)"),
    SERVER("Error del servicio de reconocimiento"),
    CLIENT("Error del cliente de reconocimiento"),
    INSUFFICIENT_PERMISSIONS("Falta el permiso RECORD_AUDIO"),
    BUSY("Reconocedor ocupado, espera un momento"),
    LANGUAGE_NOT_SUPPORTED("Idioma no soportado"),
}

/**
 * Dictado local sin coste extra de RAM: delega en el servicio del sistema
 * (`SpeechRecognizer`), que corre fuera del proceso de la app. A diferencia
 * de Vosk/whisper.cpp embebidos, no añade ni un MB al footprint del modelo.
 *
 * Configurado con `EXTRA_PREFER_OFFLINE = true` para que la inferencia sea
 * 100 % en el dispositivo y funcione sin red.
 *
 * NOTA: desde Android 13 sólo puede existir una instancia por proceso.
 * Crea UNA sola vía [create] y llama [destroy] en `onCleared()` del VM.
 */
class SpeechRecognizerManager private constructor(
    private val recognizer: SpeechRecognizer,
) : RecognitionListener {

    private val _state = MutableStateFlow<SpeechState>(SpeechState.Idle)

    /** Estado hot: la UI lo observa directamente con `collectAsStateWithLifecycle`. */
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    private val _errors = MutableSharedFlow<SpeechError>(extraBufferCapacity = 4)

    /** Errores hot: muéstralos como Snackbars. */
    val errors: SharedFlow<SpeechError> = _errors.asSharedFlow()

    /**
     * Se dispara en `onResults` con la transcripción final y de mayor
     * confianza. Conéctalo al prompt del modelo:
     *
     * ```
     * manager.onFinalTranscript = { text -> chatViewModel.send(text) }
     * ```
     */
    var onFinalTranscript: ((String) -> Unit)? = null

    /** Evita doble startListening (el servicio responde ERROR_RECOGNIZER_BUSY). */
    private var listening = false

    /** Intent de dictado: modelo libre, offline, parciales en vivo. */
    private val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        // Requisito clave: inferencia 100 % on-device.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        // Partiales continuos para pintar el dictado mientras se habla.
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // Un único resultado: la mejor hipótesis.
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, "com.example.bonsai")
    }

    // ------------------------------------------------------------------ API

    fun startListening() {
        if (listening) return
        listening = true
        recognizer.startListening(recognizerIntent)
    }

    fun stopListening() {
        // stopListening() cierra tras procesar el buffer; cancel() descarta.
        recognizer.stopListening()
    }

    fun destroy() {
        onFinalTranscript = null
        listening = false
        recognizer.destroy()
        _state.value = SpeechState.Idle
    }

    // ------------------------------------------------- RecognitionListener

    override fun onReadyForSpeech(params: Bundle?) {
        _state.value = SpeechState.Ready
    }

    override fun onBeginningOfSpeech() {
        _state.value = SpeechState.Listening(rmsDb = 0f)
    }

    override fun onRmsChanged(rmsdB: Float) {
        val current = _state.value
        if (current is SpeechState.Listening) {
            _state.value = current.copy(rmsDb = rmsdB)
        }
    }

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        _state.value = SpeechState.Buffering
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = pickBest(partialResults, RecognizerIntent.EXTRA_PARTIAL_RESULTS)
        if (!text.isNullOrBlank()) {
            _state.value = SpeechState.Partial(text)
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onResults(results: Bundle?) {
        listening = false
        _state.value = SpeechState.Idle
        // Al terminar de hablar: la mejor hipótesis va directa al prompt.
        val text = pickBest(results, SpeechRecognizer.RESULTS_RECOGNITION)
        if (!text.isNullOrBlank()) {
            onFinalTranscript?.invoke(text.trim())
        }
    }

    override fun onError(error: Int) {
        listening = false
        _state.value = SpeechState.Idle
        _errors.tryEmit(
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> SpeechError.NO_MATCH
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                -> SpeechError.NETWORK_TIMEOUT
                SpeechRecognizer.ERROR_AUDIO -> SpeechError.AUDIO
                SpeechRecognizer.ERROR_SERVER -> SpeechError.SERVER
                SpeechRecognizer.ERROR_CLIENT -> SpeechError.CLIENT
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    SpeechError.INSUFFICIENT_PERMISSIONS
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechError.BUSY
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                -> SpeechError.LANGUAGE_NOT_SUPPORTED
                else -> SpeechError.CLIENT
            }
        )
    }

    // ------------------------------------------------------------- helpers

    /**
     * Elige la hipótesis de mayor confianza del bundle (ordenada por
     * [SpeechRecognizer.CONFIDENCE_SCORES] si viene poblado).
     */
    private fun pickBest(bundle: Bundle?, key: String): String? {
        val candidates = bundle?.getStringArrayList(key) ?: return null
        if (candidates.isEmpty()) return null
        val scores = bundle.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        if (scores != null && scores.size == candidates.size) {
            val bestIdx = scores.indices.maxBy { scores[it] }
            return candidates[bestIdx]
        }
        // Sin scores: la lista ya viene ordenada de mejor a peor.
        return candidates.first()
    }

    companion object {
        /** ¿Existe servicio de reconocimiento en este dispositivo? */
        fun isAvailable(context: Context): Boolean =
            SpeechRecognizer.isRecognitionAvailable(context)

        /** ¿El servicio on-device (API 31+) soporta el modo 100 % local? */
        fun isOnDeviceAvailable(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        /**
         * Devuelve null si el dispositivo no tiene servicio de speech
         * (común en ROMs de Huawei/Xiaomi sin GApps).
         */
        fun create(context: Context): SpeechRecognizerManager? {
            if (!isAvailable(context)) return null

            // En API 31+ preferimos el recognizador on-device: garantiza
            // el modo offline incluso si el servicio de red está caído.
            val recognizer =
                if (isOnDeviceAvailable(context)) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    @Suppress("DEPRECATION")
                    SpeechRecognizer.createSpeechRecognizer(context)
                }

            return SpeechRecognizerManager(recognizer).also { manager ->
                recognizer.setRecognitionListener(manager)
            }
        }
    }
}
