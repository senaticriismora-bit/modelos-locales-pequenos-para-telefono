package com.example.bonsai.chat

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bonsai.llm.DownloadState
import com.example.bonsai.llm.LlamaBridge
import com.example.bonsai.llm.ModelDownloader
import com.example.bonsai.speech.SpeechRecognizerManager
import com.example.bonsai.speech.SpeechState
import com.example.bonsai.tools.ToolRegistry
import com.example.bonsai.tools.buildAndroidTools
import com.example.bonsai.util.DebugLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Mensaje tal y como lo pinta la UI. */
data class ChatUiMessage(
    val id: Long,
    val role: Role,
    val text: String,
)

/** Tarjeta de herramienta: se crea antes de ejecutar y se rellena después. */
data class ToolActivity(
    val id: Long,
    val name: String,
    val arguments: String,
    val output: String? = null,
    val ok: Boolean? = null,
    val durationMs: Long = 0L,
)

sealed interface ModelStatus {
    data object NotLoaded : ModelStatus
    data class Loading(val fileName: String) : ModelStatus
    data class Ready(val fileName: String, val sizeMb: Long) : ModelStatus
    data class Error(val message: String) : ModelStatus
}

data class ChatUiState(
    val modelStatus: ModelStatus = ModelStatus.NotLoaded,
    val downloadState: DownloadState = DownloadState.Idle,
    val storageAccess: Boolean = false,
    val requestStorageAccess: Boolean = false,
    val messages: List<ChatUiMessage> = emptyList(),
    val toolActivities: List<ToolActivity> = emptyList(),
    val isGenerating: Boolean = false,
    val speechActive: Boolean = false,
    val partialTranscript: String = "",
    val contextUsed: Int = 0,
    val snackbar: String? = null,
    val selectedVariantId: String = ModelDownloader.DEFAULT_VARIANT_ID,
    val installedVariantIds: Set<String> = emptySet(),
)

/**
 * Orquesta el ciclo completo:
 *  audio → SpeechRecognizer → prompt → LlamaBridge (stream) → parser
 *  → ToolRegistry.execute → <tool_response> → segunda pasada → respuesta.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private val tools: ToolRegistry = buildAndroidTools(app)
    private val toolsJson: String = tools.toolsJson()
    private val history = mutableListOf<ChatMessage>()

    private var bridge: LlamaBridge? = null
    private var generationJob: Job? = null
    private var downloadJob: Job? = null
    private var pendingDownload = false
    private var idCounter = 0L

    private val downloader = ModelDownloader(app)

    private val powerManager =
        app.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var modelSupportsThinking = true

    private val speech: SpeechRecognizerManager? =
        SpeechRecognizerManager.create(app).also { manager ->
            manager?.onFinalTranscript = { transcript ->
                // Requisito: al terminar de hablar se envía sola al modelo.
                sendPrompt(transcript)
            }
        }

    init {
        speech?.let { manager ->
            viewModelScope.launch {
                manager.state.collect { state ->
                    _ui.update {
                        it.copy(
                            speechActive = state is SpeechState.Ready ||
                                state is SpeechState.Listening ||
                                state is SpeechState.Buffering,
                            partialTranscript =
                                (state as? SpeechState.Partial)?.text.orEmpty(),
                        )
                    }
                }
            }
            viewModelScope.launch {
                manager.errors.collect { error ->
                    _ui.update { it.copy(snackbar = error.userMessage) }
                }
            }
        }
        val app = getApplication<Application>()
        DebugLog.add(
            "app",
            "Android ${Build.VERSION.SDK_INT} (${Build.SUPPORTED_ABIS.joinToString()}), " +
                "heap=${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB",
        )
        DebugLog.add("storage", "carpeta del modelo: ${downloader.publicDir.absolutePath}")

        val access = downloader.hasStorageAccess()
        _ui.update {
            it.copy(
                storageAccess = access,
                selectedVariantId = downloader.selectedVariantId,
                installedVariantIds = downloader.installedVariantIds(),
            )
        }
        if (access) {
            val migrated = downloader.migrateLegacyIfPossible()
            if (migrated.isNotEmpty()) {
                DebugLog.add("storage", "modelos migrados a Descargas/Bonsai: $migrated")
            }
        }

        val existingModel = findModelFile()
        if (existingModel != null) {
            loadModel(existingModel)
        } else if (downloader.hasPendingDownload()) {
            // La descarga seguía viva tras cerrar la app: reengancha el progreso.
            monitorDownload()
        }
    }

    // ------------------------------------------------------------- modelo

    /** Busca el .gguf descargado o cualquiera copiado a mano. */
    private fun findModelFile(): File? {
        downloader.completedFile()?.let { file ->
            DebugLog.add("storage", "modelo encontrado: ${file.absolutePath}")
            return file
        }

        val app = getApplication<Application>()
        return listOfNotNull(app.filesDir, app.getExternalFilesDir(null))
            .asSequence()
            .flatMap { dir ->
                dir.listFiles { file ->
                    file.isFile && file.extension.equals("gguf", ignoreCase = true)
                }?.asSequence() ?: emptySequence()
            }
            .firstOrNull()
    }

    /** Reintenta la carga del .gguf (botón "Buscar de nuevo" / tras error). */
    fun autoLoadModel() {
        if (_ui.value.modelStatus is ModelStatus.Ready ||
            _ui.value.modelStatus is ModelStatus.Loading
        ) return

        val modelFile = findModelFile()
        if (modelFile == null) {
            _ui.update { it.copy(modelStatus = ModelStatus.NotLoaded) }
            return
        }
        loadModel(modelFile)
    }

    /** Cambia la variante de modelo elegida en el selector. */
    fun selectVariant(id: String) {
        downloader.selectVariant(id)
        DebugLog.add("model", "variante seleccionada: $id")
        _ui.update { it.copy(selectedVariantId = id) }
    }

    /**
     * Descarga la variante elegida (o la carga si ya está descargada),
     * guardándola en Descargas/Bonsai.
     */
    fun downloadModel() {
        val variant = downloader.selectedVariant

        val installed = downloader.installedFileFor(variant)
        if (installed != null) {
            val loadedName = (_ui.value.modelStatus as? ModelStatus.Ready)?.fileName
            if (loadedName == installed.name) return
            reloadModel(installed)
            return
        }

        if (_ui.value.downloadState is DownloadState.InProgress) return

        if (!downloader.hasStorageAccess()) {
            pendingDownload = true
            DebugLog.add("storage", "se necesita permiso de acceso a Descargas")
            _ui.update { it.copy(storageAccess = false, requestStorageAccess = true) }
            return
        }

        if (!hasEnoughSpace(variant.sizeBytes)) {
            _ui.update {
                it.copy(snackbar = "Necesitas ~${variant.sizeLabel} libres para descargar")
            }
            return
        }

        if (!downloader.enqueue()) {
            _ui.update { it.copy(snackbar = "No se pudo iniciar la descarga") }
            return
        }
        DebugLog.add(
            "storage",
            "descarga encolada: ${variant.label} → Descargas/Bonsai/${variant.fileName}",
        )
        _ui.update { it.copy(downloadState = DownloadState.InProgress(0L, 0L)) }
        monitorDownload()
    }

    /** La UI pide este flag para lanzar el intent de permisos. */
    fun consumeStorageAccessRequest() =
        _ui.update { it.copy(requestStorageAccess = false) }

    /** Se llama al volver de la pantalla de permisos de almacenamiento. */
    fun onStorageAccessResult() {
        val granted = downloader.hasStorageAccess()
        DebugLog.add("storage", "permiso de almacenamiento concedido=$granted")
        _ui.update { it.copy(storageAccess = granted, requestStorageAccess = false) }

        if (!granted) {
            pendingDownload = false
            _ui.update {
                it.copy(snackbar = "Sin acceso a Descargas no se puede guardar el modelo")
            }
            return
        }

        val migrated = downloader.migrateLegacyIfPossible()
        if (migrated.isNotEmpty()) DebugLog.add("storage", "migración: $migrated")
        _ui.update { it.copy(installedVariantIds = downloader.installedVariantIds()) }

        val existing = findModelFile()
        if (existing != null && _ui.value.modelStatus is ModelStatus.NotLoaded) {
            pendingDownload = false
            loadModel(existing)
            return
        }
        if (pendingDownload) {
            pendingDownload = false
            downloadModel()
        }
    }

    fun cancelDownload() {
        downloader.cancel()
        downloadJob?.cancel()
        _ui.update { it.copy(downloadState = DownloadState.Idle) }
        DebugLog.add("storage", "descarga cancelada")
    }

    /** Borra el modelo de Descargas/Bonsai y de ubicaciones antiguas. */
    fun deleteModel() {
        generationJob?.cancel()
        downloadJob?.cancel()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { bridge?.close() }
                bridge = null
                downloader.deleteModelFiles()
            }
            history.clear()
            pendingDownload = false
            _ui.update {
                it.copy(
                    modelStatus = ModelStatus.NotLoaded,
                    downloadState = DownloadState.Idle,
                    installedVariantIds = emptySet(),
                    isGenerating = false,
                    messages = emptyList(),
                    toolActivities = emptyList(),
                    contextUsed = 0,
                    snackbar = "Modelo eliminado de Descargas/Bonsai",
                )
            }
            DebugLog.add("model", "modelo eliminado por el usuario")
        }
    }

    private fun monitorDownload() {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            downloader.observe().collect { download ->
                _ui.update { it.copy(downloadState = download) }
                if (download is DownloadState.Completed) {
                    _ui.update {
                        it.copy(installedVariantIds = downloader.installedVariantIds())
                    }
                    loadModel(download.file)
                }
            }
        }
    }

    private fun hasEnoughSpace(requiredBytes: Long): Boolean {
        val dir = if (downloader.hasStorageAccess()) {
            downloader.publicDir.also { it.mkdirs() }
        } else {
            return true
        }
        return runCatching {
            StatFs(dir.absolutePath).availableBytes > requiredBytes * 13 / 10
        }.getOrDefault(true)
    }

    private fun loadModel(file: File) {
        DebugLog.add(
            "model",
            "cargando ${file.absolutePath} (${file.length() / (1024 * 1024)} MB)",
        )
        modelSupportsThinking = file.name.lowercase().contains("qwen3") ||
            file.name.lowercase().contains("bonsai")
        _ui.update { it.copy(modelStatus = ModelStatus.Loading(file.name)) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    LlamaBridge.load(
                        modelFile = file,
                        contextWindow = 4096,
                        threads = Runtime.getRuntime()
                            .availableProcessors()
                            .coerceIn(2, 6),
                        gpuLayers = 0,
                        flashAttention = false,
                        temperature = 0.7f,
                        topP = 0.8f,
                        topK = 20,
                    )
                }
            }.onSuccess { loaded ->
                bridge = loaded
                DebugLog.add("model", "modelo listo para responder")
                _ui.update {
                    it.copy(
                        modelStatus = ModelStatus.Ready(
                            fileName = file.name,
                            sizeMb = file.length() / (1024L * 1024L),
                        ),
                    )
                }
            }.onFailure { error ->
                DebugLog.add("model", "ERROR al cargar: ${error.message}")
                _ui.update {
                    it.copy(
                        modelStatus = ModelStatus.Error(
                            error.message ?: "Error desconocido al cargar el .gguf",
                        ),
                    )
                }
            }
        }
    }

    /** Cierra el modelo actual y carga otro (cambio de variante en el selector). */
    private fun reloadModel(file: File) {
        DebugLog.add("model", "cambiando a ${file.name}")
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { bridge?.close() } }
            bridge = null
            _ui.update { it.copy(modelStatus = ModelStatus.NotLoaded, contextUsed = 0) }
            loadModel(file)
        }
    }

    // --------------------------------------------------------------- chat

    fun sendPrompt(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty() || _ui.value.isGenerating) return

        if (bridge == null) {
            DebugLog.add("chat", "sendPrompt ignorado: el modelo no está cargado")
            _ui.update { it.copy(snackbar = "El modelo aún no está cargado") }
            return
        }

        DebugLog.add("chat", "usuario (${text.length} chars): ${text.take(200)}")
        history += ChatMessage(Role.USER, text)
        _ui.update {
            it.copy(
                messages = it.messages + ChatUiMessage(nextId(), Role.USER, text),
                snackbar = null,
            )
        }

        generationJob = viewModelScope.launch { runAssistantLoop() }
    }

    fun stopGeneration() {
        DebugLog.add("chat", "stopGeneration solicitado")
        bridge?.abort()
        generationJob?.cancel()
        _ui.update { it.copy(isGenerating = false) }
    }

    fun consumeSnackbar() = _ui.update { it.copy(snackbar = null) }

    fun onMicPermissionDenied() =
        _ui.update { it.copy(snackbar = "Se necesita el micrófono para dictar") }

    // -------------------------------------------------------------- voz

    fun toggleListening() {
        val manager = speech
        if (manager == null) {
            _ui.update {
                it.copy(snackbar = "Este dispositivo no tiene reconocimiento de voz")
            }
            return
        }
        if (_ui.value.modelStatus !is ModelStatus.Ready) {
            _ui.update { it.copy(snackbar = "Espera a que el modelo termine de cargar") }
            return
        }
        if (_ui.value.speechActive) {
            manager.stopListening()
        } else {
            manager.startListening()
        }
    }

    // ----------------------------------------------------- bucle LLM/tools

    private suspend fun runAssistantLoop() {
        val llm = bridge ?: return
        _ui.update { it.copy(isGenerating = true, contextUsed = 0) }
        acquireWakeLock()
        DebugLog.add("loop", "turno iniciado (historial=${history.size} mensajes)")

        try {
            var toolRound = 0
            while (true) {
                val assistantId = nextId()
                _ui.update {
                    it.copy(
                        messages = it.messages +
                            ChatUiMessage(assistantId, Role.ASSISTANT, ""),
                    )
                }

                val prompt = QwenTemplate.render(
                    history,
                    toolsJson,
                    supportsThinking = modelSupportsThinking,
                )
                DebugLog.add(
                    "loop",
                    "ronda ${toolRound + 1}: prompt=${prompt.length} chars, " +
                        "historial=${history.size} mensajes",
                )
                val parser = ToolCallStreamParser()
                val visible = StringBuilder()
                var requestedTool: Pair<String, JSONObject>? = null

                suspend fun handle(events: List<ParseEvent>) {
                    events.forEach { event ->
                        when (event) {
                            is ParseEvent.Text -> {
                                visible.append(event.chunk)
                                updateMessage(assistantId, visible.toString())
                            }
                            is ParseEvent.ToolCall -> {
                                DebugLog.add("tool", "tool_call: ${event.json.take(500)}")
                                requestedTool = ToolCallJson.parse(event.json)
                                if (requestedTool == null) {
                                    DebugLog.add(
                                        "tool",
                                        "no se pudo interpretar el tool_call anterior",
                                    )
                                    _ui.update {
                                        it.copy(snackbar = "El modelo emitió un tool_call inválido")
                                    }
                                }
                            }
                            is ParseEvent.Malformed -> {
                                DebugLog.add(
                                    "tool",
                                    "tool_call sin cerrar: ${event.raw.take(500)}",
                                )
                            }
                        }
                    }
                }

                var pieces = 0
                llm.generateStream(prompt, maxTokens = 768).collect { piece ->
                    pieces++
                    handle(parser.feed(piece))
                }
                handle(parser.flush())

                DebugLog.add(
                    "loop",
                    "ronda ${toolRound + 1} terminada: $pieces piezas, " +
                        "${visible.length} chars visibles, tool=${requestedTool?.first}",
                )

                val tool = requestedTool
                if (tool == null) {
                    if (visible.isBlank()) {
                        DebugLog.add("loop", "el modelo terminó sin generar texto visible")
                        _ui.update {
                            it.copy(
                                snackbar = "El modelo no generó texto. Abre 🐞 para ver el registro.",
                            )
                        }
                    }
                    history += ChatMessage(Role.ASSISTANT, visible.toString())
                    break
                }

                // Historial: el bloque crudo del assistant tal y como lo generó.
                val callJson = JSONObject()
                    .put("name", tool.first)
                    .put("arguments", tool.second)
                    .toString()
                history += ChatMessage(
                    Role.ASSISTANT,
                    "<tool_call>\n$callJson\n</tool_call>",
                )

                // La UI no muestra el JSON: si no hubo texto previo, fuera burbuja.
                if (visible.isEmpty()) removeMessage(assistantId)

                val result = executeTool(tool.first, tool.second)
                history += ChatMessage(Role.TOOL, result, toolName = tool.first)

                toolRound++
                if (toolRound > MAX_TOOL_ROUNDS) {
                    _ui.update {
                        it.copy(snackbar = "Demasiadas llamadas a herramientas; corto el turno")
                    }
                    break
                }
            }
        } catch (error: Exception) {
            DebugLog.add("loop", "ERROR: ${error.message}")
            _ui.update {
                it.copy(snackbar = error.message ?: "Error durante la generación")
            }
        } finally {
            releaseWakeLock()
            DebugLog.add("loop", "turno finalizado")
            _ui.update {
                it.copy(
                    isGenerating = false,
                    contextUsed = bridge?.ctxUsed ?: 0,
                    messages = it.messages.filterNot {
                        it.role == Role.ASSISTANT && it.text.isBlank()
                    },
                )
            }
        }
    }

    /** Ejecuta la tool y actualiza la tarjeta en tiempo real. */
    private suspend fun executeTool(name: String, args: JSONObject): String {
        val activityId = nextId()
        DebugLog.add("tool", "ejecutando $name $args")
        _ui.update {
            it.copy(
                toolActivities = it.toolActivities + ToolActivity(
                    id = activityId,
                    name = name,
                    arguments = args.toString(),
                ),
            )
        }

        val result = tools.execute(name, args)
        DebugLog.add(
            "tool",
            "resultado $name: ok=${result.ok}, ${result.output.take(300)}",
        )

        _ui.update { state ->
            state.copy(
                toolActivities = state.toolActivities.map { activity ->
                    if (activity.id == activityId) {
                        activity.copy(
                            output = result.output,
                            ok = result.ok,
                            durationMs = result.durationMs,
                        )
                    } else {
                        activity
                    }
                },
            )
        }

        // El modelo recibe el resultado (o el error) como <tool_response>.
        return JSONObject()
            .put("tool", name)
            .put("success", result.ok)
            .put("result", result.output)
            .toString()
    }

    // ----------------------------------------------------------- helpers

    /**
     * Mantiene la CPU despierta mientras el modelo genera; MIUI ralentiza
     * o suspende apps en segundo plano (pantalla apagada) si no hay wakelock.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            wakeLock = powerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bonsai:generacion")
                .also {
                    it.setReferenceCounted(false)
                    it.acquire(10 * 60 * 1000L)
                }
            DebugLog.add("loop", "wakelock parcial adquirido")
        }.onFailure { error ->
            DebugLog.add("loop", "wakelock no disponible: ${error.message}")
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
    }

    private fun updateMessage(id: Long, text: String) {
        _ui.update { state ->
            state.copy(
                messages = state.messages.map {
                    if (it.id == id) it.copy(text = text) else it
                },
            )
        }
    }

    private fun removeMessage(id: Long) {
        _ui.update { state ->
            state.copy(messages = state.messages.filterNot { it.id == id })
        }
    }

    private fun nextId(): Long = ++idCounter

    override fun onCleared() {
        speech?.destroy()
        releaseWakeLock()
        bridge?.close()
        super.onCleared()
    }

    private companion object {
        const val MAX_TOOL_ROUNDS = 3
    }
}
