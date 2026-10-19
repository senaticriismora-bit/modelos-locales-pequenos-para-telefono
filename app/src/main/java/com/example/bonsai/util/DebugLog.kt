package com.example.bonsai.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Registro en memoria visible desde la propia app (diálogo 🐞).
 *
 * Se escribe también en logcat con el tag `Bonsai/<tag>` para poder
 * capturarlo por adb. Máximo [MAX_LINES] líneas para no crecer sin límite.
 */
object DebugLog {

    private const val MAX_LINES = 400
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val timeLock = Any()

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun add(tag: String, message: String) {
        val clean = message.trim()
        if (clean.isEmpty()) return

        val stamp = synchronized(timeLock) { timeFormat.format(Date()) }
        Log.i("Bonsai/$tag", clean)

        val newLines = clean.lineSequence()
            .filter { it.isNotBlank() }
            .map { "$stamp [$tag] $it" }
            .toList()
        if (newLines.isEmpty()) return

        _lines.update { current ->
            (current + newLines).takeLast(MAX_LINES)
        }
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
