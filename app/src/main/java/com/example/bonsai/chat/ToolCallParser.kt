package com.example.bonsai.chat

import org.json.JSONObject

/** Eventos que emite el parser al consumir piezas del stream del modelo. */
sealed interface ParseEvent {
    /** Texto normal para mostrar en el chat. */
    data class Text(val chunk: String) : ParseEvent

    /** Bloque <tool_call>…</tool_call> completo (contenido JSON interno). */
    data class ToolCall(val json: String) : ParseEvent

    /** Bloque abierto que nunca se cerró (modelo cortado por budget/abort). */
    data class Malformed(val raw: String) : ParseEvent
}

/**
 * Parser incremental, pensado para alimentarse con los tokens del Flow.
 *
 * Mantiene un buffer con los caracteres que podrían formar parte de un tag
 * partido entre tokens y NO emite como texto el contenido de <tool_call>.
 * Así la UI nunca pinta JSON crudo y la tool se ejecuta en cuanto llega el
 * cierre, sin esperar a que termine todo el turno.
 */
class ToolCallStreamParser {

    private val buffer = StringBuilder()
    private var insideToolCall = false

    /** Alimenta una pieza del stream y devuelve los eventos completos. */
    fun feed(piece: String): List<ParseEvent> {
        if (piece.isEmpty()) return emptyList()
        buffer.append(piece)

        val events = mutableListOf<ParseEvent>()
        while (true) {
            if (!insideToolCall) {
                val open = buffer.indexOf(OPEN_TAG)
                if (open >= 0) {
                    if (open > 0) events += ParseEvent.Text(buffer.substring(0, open))
                    buffer.delete(0, open + OPEN_TAG.length)
                    insideToolCall = true
                    continue
                }
                // Retiene el sufijo que podría ser el inicio de "<tool_call>".
                val hold = partialPrefixLength(buffer, OPEN_TAG)
                val safe = buffer.length - hold
                if (safe > 0) {
                    events += ParseEvent.Text(buffer.substring(0, safe))
                    buffer.delete(0, safe)
                }
                break
            } else {
                val close = buffer.indexOf(CLOSE_TAG)
                if (close >= 0) {
                    events += ParseEvent.ToolCall(buffer.substring(0, close))
                    buffer.delete(0, close + CLOSE_TAG.length)
                    insideToolCall = false
                    continue
                }
                break
            }
        }
        return events
    }

    /** Vacía el remanente al cerrar el stream (texto final o bloque roto). */
    fun flush(): List<ParseEvent> {
        if (buffer.isEmpty()) {
            insideToolCall = false
            return emptyList()
        }
        val events = if (insideToolCall) {
            listOf(ParseEvent.Malformed(buffer.toString()))
        } else {
            listOf(ParseEvent.Text(buffer.toString()))
        }
        buffer.clear()
        insideToolCall = false
        return events
    }

    private fun partialPrefixLength(text: CharSequence, tag: String): Int {
        val max = minOf(text.length, tag.length - 1)
        for (len in max downTo 1) {
            val suffix = text.subSequence(text.length - len, text.length).toString()
            if (suffix == tag.substring(0, len)) return len
        }
        return 0
    }

    private companion object {
        const val OPEN_TAG = "<tool_call>"
        const val CLOSE_TAG = "</tool_call>"
    }
}

/** Utilidades para convertir el JSON del bloque a (nombre, argumentos). */
object ToolCallJson {

    /**
     * Parsea el contenido interno de <tool_call>. Acepta los dos formatos:
     *
     *  1. JSON (el que pide nuestro system prompt):
     *     {"name":"setAlarm","arguments":{"hour":7,"minute":0}}
     *
     *  2. XML nativo de Qwen3.5 (por si el modelo ignora el formato JSON):
     *     <function=setAlarm>
     *     <parameter=hour>7</parameter>
     *     </function>
     */
    fun parse(raw: String): Pair<String, JSONObject>? =
        parseJson(raw) ?: parseXml(raw)

    private fun parseJson(raw: String): Pair<String, JSONObject>? = runCatching {
        val root = JSONObject(raw.trim())
        val name = root.getString("name")
        val arguments = when (val value = root.opt("arguments")) {
            is JSONObject -> value
            is String -> if (value.isBlank()) JSONObject() else JSONObject(value)
            null -> JSONObject()
            else -> JSONObject(value.toString())
        }
        name to arguments
    }.getOrNull()

    private fun parseXml(raw: String): Pair<String, JSONObject>? {
        val function = FUNCTION_REGEX.find(raw) ?: return null
        val name = function.groupValues[1]
        val body = raw.substring(function.range.last + 1).substringBefore("</function>")
        val arguments = JSONObject()

        PARAMETER_REGEX.findAll(body).forEach { match ->
            arguments.put(match.groupValues[1], coerce(match.groupValues[2].trim()))
        }
        return name to arguments
    }

    /** Convierte los valores XML (siempre strings) al tipo JSON adecuado. */
    private fun coerce(value: String): Any = when {
        value.equals("true", ignoreCase = true) -> true
        value.equals("false", ignoreCase = true) -> false
        value.toLongOrNull() != null -> value.toLong()
        value.toDoubleOrNull() != null -> value.toDouble()
        else -> value
    }

    private val FUNCTION_REGEX = Regex("""<function=([^>\s]+)>""")
    private val PARAMETER_REGEX =
        Regex("""<parameter=([^>\s]+)>\s*([\s\S]*?)\s*</parameter>""")
}
