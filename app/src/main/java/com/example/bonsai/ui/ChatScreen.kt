package com.example.bonsai.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bonsai.chat.ChatUiMessage
import com.example.bonsai.chat.ChatViewModel
import com.example.bonsai.chat.ModelStatus
import com.example.bonsai.chat.Role
import com.example.bonsai.chat.ToolActivity
import com.example.bonsai.llm.DownloadState
import com.example.bonsai.llm.ModelDownloader
import com.example.bonsai.llm.ModelVariant
import com.example.bonsai.util.DebugLog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRoute(viewModel: ChatViewModel = viewModel()) {
    val state by viewModel.ui.collectAsState()
    val debugLines by DebugLog.lines.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.snackbar) {
        state.snackbar?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeSnackbar()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.toggleListening()
        else viewModel.onMicPermissionDenied()
    }

    // Android 11+: pantalla "Acceso a todos los archivos".
    val storageSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.onStorageAccessResult() }

    // Android 10 y anteriores: permisos clásicos.
    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.onStorageAccessResult() }

    LaunchedEffect(state.requestStorageAccess) {
        if (!state.requestStorageAccess) return@LaunchedEffect
        viewModel.consumeStorageAccessRequest()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            val ok = runCatching { storageSettingsLauncher.launch(intent) }.isSuccess
            if (!ok) {
                runCatching {
                    storageSettingsLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                    )
                }.onFailure { viewModel.onStorageAccessResult() }
            }
        } else {
            legacyStorageLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
            )
        }
    }

    val onMicClick: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.toggleListening()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Eliminar modelo") },
            text = {
                Text(
                    "Se borrarán los modelos .gguf descargados en " +
                        "Descargas/Bonsai. Podrás volver a descargarlos cuando quieras.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteModel()
                    },
                ) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancelar")
                }
            },
        )
    }

    if (showLogDialog) {
        val logText = debugLines.joinToString("\n").ifBlank { "Sin eventos todavía." }
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text("Registro de depuración") },
            text = {
                Text(
                    text = logText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { showLogDialog = false }) { Text("Cerrar") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(
                                ClipboardManager::class.java,
                            )
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("bonsai-log", logText),
                            )
                        },
                    ) {
                        Text("Copiar")
                    }
                    TextButton(
                        onClick = {
                            DebugLog.clear()
                            showLogDialog = false
                        },
                    ) {
                        Text("Limpiar")
                    }
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Bonsai")
                        val status = state.modelStatus
                        Text(
                            text = when (status) {
                                is ModelStatus.Ready ->
                                    "${status.fileName} · ${status.sizeMb} MB en RAM"
                                is ModelStatus.Loading -> "Cargando modelo…"
                                is ModelStatus.Error -> "Modelo no cargado"
                                ModelStatus.NotLoaded -> "Sin modelo"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (state.modelStatus !is ModelStatus.NotLoaded) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Text("🗑", fontSize = 18.sp)
                        }
                    }
                    IconButton(onClick = { showLogDialog = true }) {
                        Text("🐞", fontSize = 18.sp)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            ModelBanner(
                status = state.modelStatus,
                download = state.downloadState,
                storageAccess = state.storageAccess,
                variants = ModelDownloader.VARIANTS,
                selectedVariantId = state.selectedVariantId,
                installedVariantIds = state.installedVariantIds,
                onSelectVariant = viewModel::selectVariant,
                onRetry = viewModel::autoLoadModel,
                onDownload = viewModel::downloadModel,
                onCancelDownload = viewModel::cancelDownload,
            )

            val listState = rememberLazyListState()
            val itemCount = state.messages.size + state.toolActivities.size +
                if (state.isGenerating) 1 else 0
            LaunchedEffect(itemCount) {
                if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (itemCount == 0) {
                    item {
                        Text(
                            text = "Toca 🎤 y habla, o escribe abajo.\nTodo corre en tu teléfono.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
                items(state.toolActivities, key = { "tool-${it.id}" }) { activity ->
                    ToolCard(activity)
                }
                if (state.isGenerating) {
                    item {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(8.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }

            InputBar(
                enabled = state.modelStatus is ModelStatus.Ready,
                isGenerating = state.isGenerating,
                speechActive = state.speechActive,
                partialTranscript = state.partialTranscript,
                onSend = viewModel::sendPrompt,
                onMic = onMicClick,
                onStop = viewModel::stopGeneration,
            )
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun ModelBanner(
    status: ModelStatus,
    download: DownloadState,
    storageAccess: Boolean,
    variants: List<ModelVariant>,
    selectedVariantId: String,
    installedVariantIds: Set<String>,
    onSelectVariant: (String) -> Unit,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    when (status) {
        is ModelStatus.Ready -> Unit

        is ModelStatus.Loading -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                "Cargando ${status.fileName} en memoria…",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        is ModelStatus.Error -> Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("No se pudo cargar el modelo", style = MaterialTheme.typography.titleSmall)
                Text(status.message, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetry) { Text("Reintentar") }
                Text(
                    "Pulsa 🐞 arriba para ver el registro detallado.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        ModelStatus.NotLoaded -> if (download is DownloadState.InProgress) {
            DownloadCard(download, onCancelDownload)
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Falta el modelo", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))

                    if (download is DownloadState.Failed) {
                        Text(
                            "La descarga falló: ${download.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    if (!storageAccess) {
                        Text(
                            "El modelo se guarda en Descargas/Bonsai para que no tengas " +
                                "que descargarlo otra vez al reinstalar la app. " +
                                "Concede acceso a archivos para continuar.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Si ya lo descargaste antes, se reutilizará al conceder el permiso.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onDownload) {
                            Text("Permitir acceso y continuar")
                        }
                    } else {
                        Text(
                            "Elige el modelo. Wi-Fi recomendado; puedes cerrar la app " +
                                "y la descarga continúa.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(4.dp))

                        variants.forEach { variant ->
                            val selected = variant.id == selectedVariantId
                            val installed = variant.id in installedVariantIds
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectVariant(variant.id) },
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        variant.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        if (installed) {
                                            "Descargado · ${variant.sizeLabel}"
                                        } else {
                                            "${variant.hint} · ${variant.sizeLabel}"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        val selectedVariant = variants.firstOrNull {
                            it.id == selectedVariantId
                        }
                        val selectedInstalled = selectedVariantId in installedVariantIds
                        Button(onClick = onDownload) {
                            Text(
                                when {
                                    download is DownloadState.Failed -> "Reintentar descarga"
                                    selectedInstalled ->
                                        "Cargar ${selectedVariant?.label ?: "modelo"}"
                                    else -> "Descargar " +
                                        "${selectedVariant?.label ?: "modelo"} " +
                                        "(${selectedVariant?.sizeLabel ?: "?"})"
                                },
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Ruta: Descargas/Bonsai/",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRetry) { Text("Buscar de nuevo") }
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    download: DownloadState.InProgress,
    onCancel: () -> Unit,
) {
    val totalMb = download.totalBytes / (1024 * 1024)
    val doneMb = download.downloadedBytes / (1024 * 1024)
    val fraction = if (download.totalBytes > 0) {
        (download.downloadedBytes.toFloat() / download.totalBytes).coerceIn(0f, 1f)
    } else {
        0f
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Descargando modelo…", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                if (totalMb > 0) "$doneMb / $totalMb MB" else "$doneMb MB",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            if (download.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Puedes cerrar la app; la descarga sigue en segundo plano.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) { Text("Cancelar") }
            }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun MessageBubble(message: ChatUiMessage) {
    val isUser = message.role == Role.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
        ) {
            Text(
                text = message.text.ifBlank { "…" },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun ToolCard(activity: ToolActivity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (activity.ok) {
                        true -> "✅"
                        false -> "⚠️"
                        null -> "⚙️"
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = activity.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (activity.output == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        "${activity.durationMs} ms",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "args: ${activity.arguments}",
                style = MaterialTheme.typography.bodySmall,
            )
            activity.output?.let { output ->
                Spacer(Modifier.height(2.dp))
                Text(text = output, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun InputBar(
    enabled: Boolean,
    isGenerating: Boolean,
    speechActive: Boolean,
    partialTranscript: String,
    onSend: (String) -> Unit,
    onMic: () -> Unit,
    onStop: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (partialTranscript.isNotBlank()) {
            Text(
                text = "“$partialTranscript”",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                enabled = enabled && !isGenerating,
                placeholder = {
                    Text(if (enabled) "Pregunta algo…" else "Cargando modelo…")
                },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (text.isNotBlank()) {
                            onSend(text)
                            text = ""
                        }
                    },
                ),
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(
                onClick = onMic,
                enabled = enabled && !isGenerating,
            ) {
                Text(if (speechActive) "⏹" else "🎤", fontSize = 20.sp)
            }
            Spacer(Modifier.width(8.dp))
            if (isGenerating) {
                FilledIconButton(onClick = onStop) {
                    Text("⏹", fontSize = 18.sp)
                }
            } else {
                FilledIconButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            onSend(text)
                            text = ""
                        }
                    },
                    enabled = enabled,
                ) {
                    Text("➤", fontSize = 18.sp)
                }
            }
        }
    }
}
