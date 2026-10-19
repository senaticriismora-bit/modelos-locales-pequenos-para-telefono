package com.example.bonsai.chat

/** Rol de un turno del historial. */
enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

/**
 * Turno del historial. Para [Role.TOOL], `toolName` identifica qué función
 * produjo la respuesta (se usa solo para logging/UI; el template de Qwen
 * espera el contenido dentro de <tool_response>).
 */
data class ChatMessage(
    val role: Role,
    val content: String,
    val toolName: String? = null,
)

/**
 * Chat template de Qwen3.5 (ChatML + bloque de herramientas).
 *
 * Estructura:
 *   <|im_start|>system
 *   ...instrucciones + <tools>[...]</tools>...
 *   <|im_end|>
 *   <|im_start|>user
 *   hola
 *   <|im_end|>
 *   <|im_start|>assistant
 *   <tool_call>{"name":"...","arguments":{...}}</tool_call>
 *   <|im_end|>
 *   <|im_start|>user
 *   <tool_response>...</tool_response>
 *   <|im_end|>
 *   <|im_start|>assistant
 *
 * El prompt de generación termina en el rol del asistente SIN <|im_end|>,
 * para que el modelo continúe.
 */
object QwenTemplate {

    private const val IM_START = "<|im_start|>"
    private const val IM_END = "<|im_end|>"

    /**
     * System prompt fijo. Las instrucciones de tool-calling van en inglés
     * (los modelos Qwen siguen mejor el formato), el idioma de respuesta
     * se delega al idioma del usuario.
     */
    fun systemPrompt(toolsJson: String): String = """
        You are Bonsai, an offline voice assistant on this phone.
        Reply in the user's language (Spanish by default), short and clear.
        Never claim to have done something you did not do via a tool.

        You have these functions:

        <tools>
        $toolsJson
        </tools>

        Rules:
        - To call a function reply with EXACTLY ONE tool call and nothing else:
        <tool_call>
        {"name": "functionName", "arguments": {"arg": "value"}}
        </tool_call>
        - Use only listed functions; arguments must match the schema.
        - After a <tool_response>, answer the user in natural language.
        - If no tool is needed, reply normally without any <tool_call>.
    """.trimIndent()

    /**
     * Serializa un historial completo a ChatML.
     *
     * El primer mensaje debe ser el system prompt (ya renderizado con las
     * tools); [render] lo añade automáticamente si no está.
     */
    fun render(
        history: List<ChatMessage>,
        toolsJson: String,
        /** false = respuesta directa (recomendado para voz); true = reasoning visible. */
        enableThinking: Boolean = false,
        /**
         * true = familia Qwen3 (Bonsai): el prompt de generación abre el bloque
         * ` thinking`; false = ChatML puro (Qwen2.5 y similares).
         */
        supportsThinking: Boolean = true,
    ): String = buildString {
        val messages = if (history.firstOrNull()?.role == Role.SYSTEM) {
            history
        } else {
            listOf(ChatMessage(Role.SYSTEM, systemPrompt(toolsJson))) + history
        }

        messages.forEach { message ->
            append(IM_START)
            append(
                when (message.role) {
                    Role.SYSTEM -> "system"
                    Role.USER -> "user"
                    Role.ASSISTANT -> "assistant"
                    Role.TOOL -> "user" // Qwen embebe el resultado como <tool_response>
                },
            )
            append('\n')

            when (message.role) {
                Role.TOOL -> {
                    append("<tool_response>\n")
                    append(message.content)
                    append("\n</tool_response>")
                }
                else -> append(message.content)
            }
            append(IM_END)
            append('\n')
        }

        // Prefijo de generación: Qwen3.5 abre siempre un bloque <think>.
        // Non-thinking = bloque vacío ya cerrado; el modelo responde directo.
        append(IM_START).append("assistant\n")
        if (supportsThinking && enableThinking) {
            append("<think>\n")
        } else if (supportsThinking) {
            append("<think>\n\n</think>\n\n")
        }
    }
}
