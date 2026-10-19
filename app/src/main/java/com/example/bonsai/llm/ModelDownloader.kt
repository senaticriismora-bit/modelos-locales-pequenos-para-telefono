package com.example.bonsai.llm

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

/** Estado de la descarga del .gguf. */
sealed interface DownloadState {
    data object Idle : DownloadState
    data class InProgress(val downloadedBytes: Long, val totalBytes: Long) : DownloadState
    data class Completed(val file: File) : DownloadState
    data class Failed(val message: String) : DownloadState
}

/** Modelo GGUF que la app puede descargar y cargar. */
data class ModelVariant(
    val id: String,
    val label: String,
    val hint: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
) {
    /** Tamaño legible (GB/MB) para la UI. */
    val sizeLabel: String
        get() = if (sizeBytes >= 1_000_000_000L) {
            "%.2f GB".format(sizeBytes / 1_000_000_000.0)
        } else {
            "%.0f MB".format(sizeBytes / 1_000_000.0)
        }
}

/**
 * Gestiona los modelos en almacenamiento **público** (`Descargas/Bonsai/`),
 * que sobrevive a desinstalar/reinstalar la app.
 *
 * En Android 11+ el acceso por ruta a Descargas requiere el permiso especial
 * "Acceso a todos los archivos" (MANAGE_EXTERNAL_STORAGE); en versiones
 * anteriores se usan los permisos clásicos de almacenamiento.
 *
 * También se lee la ubicación antigua en `Android/data/<pkg>/files` para no
 * perder modelos ya descargados con versiones previas.
 */
class ModelDownloader(private val context: Context) {

    private val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private val prefs =
        context.getSharedPreferences("bonsai-download", Context.MODE_PRIVATE)

    private var downloadId: Long = prefs.getLong(KEY_DOWNLOAD_ID, -1L)

    /** Carpeta pública persistente. */
    @Suppress("DEPRECATION")
    val publicDir: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Bonsai",
        )

    /** Id de la variante elegida en la UI. */
    val selectedVariantId: String
        get() = prefs.getString(KEY_VARIANT, DEFAULT_VARIANT_ID) ?: DEFAULT_VARIANT_ID

    /** Variante elegida (o la primera como respaldo). */
    val selectedVariant: ModelVariant
        get() = VARIANTS.firstOrNull { it.id == selectedVariantId } ?: VARIANTS.first()

    fun selectVariant(id: String) {
        prefs.edit().putString(KEY_VARIANT, id).apply()
    }

    /** Archivo final de una variante en Descargas/Bonsai. */
    fun fileFor(variant: ModelVariant): File = File(publicDir, variant.fileName)

    /** Ubicación antigua (se borra al desinstalar). Solo compatibilidad. */
    fun legacyFileFor(variant: ModelVariant): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, variant.fileName)

    /** Devuelve el .gguf de la variante si ya está descargado. */
    fun installedFileFor(variant: ModelVariant): File? {
        val public = fileFor(variant)
        if (hasStorageAccess() && public.isValidModel()) return public
        val legacy = legacyFileFor(variant)
        return if (legacy.isValidModel()) legacy else null
    }

    fun isInstalled(variant: ModelVariant): Boolean = installedFileFor(variant) != null

    /** Ids de las variantes ya descargadas. */
    fun installedVariantIds(): Set<String> =
        VARIANTS.filter { isInstalled(it) }.map { it.id }.toSet()

    /**
     * Devuelve el .gguf a cargar: preferencia por la variante elegida,
     * luego cualquier otra ya descargada.
     */
    fun completedFile(): File? {
        val installed = VARIANTS.mapNotNull { variant ->
            installedFileFor(variant)?.let { variant to it }
        }
        if (installed.isEmpty()) return null
        val chosen = installed.firstOrNull { it.first.id == selectedVariantId }
            ?: installed.first()
        return chosen.second
    }

    fun hasStorageAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            Environment.isExternalStorageManager()

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)

        else ->
            isGranted(Manifest.permission.READ_EXTERNAL_STORAGE) &&
                isGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Si hay modelos en la ubicación antigua y ya tenemos acceso a Descargas,
     * intenta moverlos (rename en el mismo volumen; si no, se quedan donde están).
     */
    fun migrateLegacyIfPossible(): List<String> {
        if (!hasStorageAccess()) return emptyList()

        val migrated = mutableListOf<String>()
        VARIANTS.forEach { variant ->
            val target = fileFor(variant)
            if (target.isValidModel()) return@forEach
            val legacy = legacyFileFor(variant)
            if (!legacy.isValidModel()) return@forEach

            val moved = runCatching {
                publicDir.mkdirs()
                if (target.exists()) target.delete()
                legacy.renameTo(target)
            }.getOrDefault(false)

            if (moved && target.isValidModel()) migrated += variant.fileName
        }
        return migrated
    }

    fun hasPendingDownload(): Boolean = downloadId != -1L

    /** Encola la descarga de la variante elegida. false = ya está o falta permiso. */
    fun enqueue(): Boolean {
        val variant = selectedVariant
        if (isInstalled(variant)) return false
        if (!hasStorageAccess()) return false

        publicDir.mkdirs()
        val target = fileFor(variant)
        if (target.exists()) target.delete()

        val request = DownloadManager.Request(Uri.parse(variant.url))
            .setTitle(variant.label)
            .setDescription("${variant.sizeLabel} · Descargas/Bonsai")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "Bonsai/${variant.fileName}",
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        downloadId = downloadManager.enqueue(request)
        prefs.edit()
            .putLong(KEY_DOWNLOAD_ID, downloadId)
            .putString(KEY_DOWNLOAD_VARIANT, variant.id)
            .apply()
        return true
    }

    /** Cancela la descarga en curso (no toca modelos ya completos). */
    fun cancel() {
        if (downloadId != -1L) downloadManager.remove(downloadId)
        downloadId = -1L
        prefs.edit().remove(KEY_DOWNLOAD_ID).remove(KEY_DOWNLOAD_VARIANT).apply()
    }

    /** Borra todos los modelos descargados de las ubicaciones conocidas. */
    fun deleteModelFiles() {
        cancel()

        VARIANTS.forEach { variant ->
            runCatching {
                val file = fileFor(variant)
                if (file.exists()) file.delete()
            }
            runCatching {
                val file = legacyFileFor(variant)
                if (file.exists()) file.delete()
            }
        }

        // Cualquier otro .gguf copiado a mano a las carpetas privadas.
        listOfNotNull(context.filesDir, context.getExternalFilesDir(null)).forEach { dir ->
            runCatching {
                dir.listFiles { file ->
                    file.isFile && file.extension.equals("gguf", ignoreCase = true)
                }?.forEach { it.delete() }
            }
        }

        // Elimina la carpeta pública si quedó vacía.
        runCatching {
            val children = publicDir.listFiles()
            if (children != null && children.isEmpty()) publicDir.delete()
        }
    }

    /**
     * Emite el progreso cada ~750 ms hasta completar o fallar.
     * Si la app murió y se relanzó, el id persistido permite reengancharse.
     */
    fun observe(): Flow<DownloadState> = flow {
        if (downloadId == -1L) {
            emit(DownloadState.Idle)
            return@flow
        }
        while (true) {
            val state = withContext(Dispatchers.IO) { query() }
            emit(state)
            when (state) {
                is DownloadState.Completed, is DownloadState.Failed -> break
                else -> delay(750)
            }
        }
    }

    private fun query(): DownloadState {
        val cursor = downloadManager.query(
            DownloadManager.Query().setFilterById(downloadId),
        )
        cursor.use { c ->
            if (!c.moveToFirst()) {
                return DownloadState.Failed("Descarga no encontrada")
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val variant = enqueuedVariant()
                    clearPersistedId()
                    DownloadState.Completed(fileFor(variant))
                }

                DownloadManager.STATUS_FAILED -> {
                    val message = failureReason(c)
                    clearPersistedId()
                    DownloadState.Failed(message)
                }

                else -> {
                    val downloaded = c.getLong(
                        c.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR,
                        ),
                    )
                    val total = c.getLong(
                        c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                    )
                    DownloadState.InProgress(
                        downloadedBytes = downloaded.coerceAtLeast(0L),
                        totalBytes = total,
                    )
                }
            }
        }
    }

    /** Variante cuya descarga está en curso (persistida al encolar). */
    private fun enqueuedVariant(): ModelVariant {
        val id = prefs.getString(KEY_DOWNLOAD_VARIANT, null)
        return VARIANTS.firstOrNull { it.id == id } ?: selectedVariant
    }

    private fun failureReason(cursor: android.database.Cursor): String {
        val reason = cursor.getInt(
            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON),
        )
        return when (reason) {
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Sin espacio suficiente"
            DownloadManager.ERROR_FILE_ERROR -> "Error de archivo"
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "Error de red"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Demasiadas redirecciones"
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Respuesta HTTP no válida"
            else -> "Error de descarga ($reason)"
        }
    }

    private fun clearPersistedId() {
        downloadId = -1L
        prefs.edit().remove(KEY_DOWNLOAD_ID).remove(KEY_DOWNLOAD_VARIANT).apply()
    }

    private fun File.isValidModel(): Boolean = runCatching {
        isFile && length() > MIN_VALID_BYTES
    }.getOrDefault(false)

    companion object {
        /** Qwen2.5 0.5B: el más rápido; recomendado para este teléfono. */
        val QWEN_05B = ModelVariant(
            id = "qwen05",
            label = "Qwen2.5 0.5B",
            hint = "Rapidísimo (recomendado)",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF" +
                "/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeBytes = 491_400_032L,
        )

        /** Qwen2.5 1.5B: mejor calidad, más lento. */
        val QWEN_15B = ModelVariant(
            id = "qwen15",
            label = "Qwen2.5 1.5B",
            hint = "Más listo, más lento",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF" +
                "/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 1_117_320_736L,
        )

        /** Bonsai 2 4B ternario: demasiado pesado para CPUs Cortex-A53. */
        val BONSAI_4B = ModelVariant(
            id = "bonsai4b",
            label = "Bonsai 2 4B ternario",
            hint = "Demasiado lento en este teléfono",
            fileName = "Ternary-Bonsai-2-4B-PQ2_0.gguf",
            url = "https://huggingface.co/prism-ml/Ternary-Bonsai-2-4B-gguf" +
                "/resolve/main/Ternary-Bonsai-2-4B-PQ2_0.gguf",
            sizeBytes = 1_146_465_120L,
        )

        val VARIANTS = listOf(QWEN_05B, QWEN_15B, BONSAI_4B)

        const val DEFAULT_VARIANT_ID = "qwen05"

        private const val KEY_DOWNLOAD_ID = "id"
        private const val KEY_DOWNLOAD_VARIANT = "dl_variant"
        private const val KEY_VARIANT = "variant"
        private const val MIN_VALID_BYTES = 100L * 1024L * 1024L
    }
}
