package com.example.bonsai.tools

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Descripción de un parámetro de herramienta, en el subconjunto de JSON
 * Schema que entienden Qwen y la mayoría de modelos con function calling.
 */
data class ToolParam(
    val type: String,          // "string" | "integer" | "number" | "boolean"
    val description: String,
    val required: Boolean = true,
)

/** Herramienta que el modelo puede invocar por nombre. */
class Tool(
    val name: String,
    val description: String,
    val parameters: Map<String, ToolParam> = emptyMap(),
    /** Debe ser rápida y no bloquear; se ejecuta en el hilo principal. */
    val handler: suspend (JSONObject) -> String,
)

/** Resultado de una llamada, para pintarlo en la UI y devolverlo al modelo. */
data class ToolCallResult(
    val name: String,
    val arguments: JSONObject,
    val ok: Boolean,
    val output: String,
    val durationMs: Long,
)

/**
 * Registro de herramientas nativas.
 *
 * El schema se serializa en el formato `{"type":"function","function":{...}}`
 * que Qwen espera dentro de su bloques de tools del system prompt.
 */
class ToolRegistry {

    private val tools = linkedMapOf<String, Tool>()

    fun register(tool: Tool): ToolRegistry = apply { tools[tool.name] = tool }

    fun toolNames(): Set<String> = tools.keys.toSet()

    /** Array JSON completo para inyectar en el system prompt. */
    fun toolsJson(): String {
        val array = JSONArray()
        tools.values.forEach { tool ->
            val properties = JSONObject()
            val required = JSONArray()
            tool.parameters.forEach { (name, param) ->
                properties.put(
                    name,
                    JSONObject()
                        .put("type", param.type)
                        .put("description", param.description),
                )
                if (param.required) required.put(name)
            }

            array.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.name)
                            .put("description", tool.description)
                            .put(
                                "parameters",
                                JSONObject()
                                    .put("type", "object")
                                    .put("properties", properties)
                                    .put("required", required),
                            ),
                    ),
            )
        }
        return array.toString()
    }

    /** Busca y ejecuta una tool; nunca lanza: devuelve ok=false con el error. */
    suspend fun execute(name: String, arguments: JSONObject): ToolCallResult {
        val started = SystemClock.elapsedRealtime()
        val tool = tools[name]
            ?: return ToolCallResult(
                name, arguments, false,
                "Herramienta desconocida: $name. Disponibles: ${tools.keys.joinToString()}",
                0L,
            )

        return runCatching { tool.handler(arguments) }.fold(
            onSuccess = { output ->
                ToolCallResult(
                    name, arguments, true, output,
                    SystemClock.elapsedRealtime() - started,
                )
            },
            onFailure = { error ->
                ToolCallResult(
                    name, arguments, false,
                    error.message ?: error.javaClass.simpleName,
                    SystemClock.elapsedRealtime() - started,
                )
            },
        )
    }
}
