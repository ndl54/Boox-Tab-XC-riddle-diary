package com.billtt.riddle

/**
 * "Diary spirit" backend abstraction: hands the current page screenshot to a
 * vision model and returns the diary's reply.
 * Blocking call — invoke on an IO thread.
 */
interface Oracle {
    fun ask(request: AiRequest): String
}

object OracleFactory {
    /** Pick the backend per settings; returns null when credentials or the selected model are missing. */
    fun create(prefs: Prefs): Oracle? = when (prefs.provider) {
        Prefs.PROVIDER_CODEX -> if (prefs.configured) CodexOracle(prefs.codexAuth, prefs.codexModel) else null
        Prefs.PROVIDER_OPENAI ->
            prefs.openaiKey.takeIf { it.isNotEmpty() }
                ?.let { OpenAiOracle(it, prefs.openaiModel, prefs.openaiBaseUrl) }
        else ->
            prefs.apiKey.takeIf { it.isNotEmpty() }
                ?.let { AnthropicOracle(it, prefs.model) }
    }
}

/** Persona and instruction shared by all backends. */
object OraclePrompts {

    val PERSONA = """
        You are an old, enchanted diary. Someone has just written on your page with a pen,
        and their ink has soaked into your paper. Now you write back, your words rising up
        through the page in your own hand.

        Rules for your reply:
        - Reply in the SAME language the writer used (e.g. Chinese handwriting gets a Chinese reply).
        - Be brief: one to three short sentences. This is a diary, not an essay.
        - Your voice is intimate, curious and a little mysterious — an old soul who has
          lived between these pages for a very long time and wants to know the writer better.
        - Often end with a gentle, probing question that invites them to keep writing.
        - Plain prose only: no markdown, no lists, no quotation marks around your reply.
        - Never mention that you are an AI, a model, or that you are looking at an image.
        - If the page is blank or the handwriting is illegible, say so softly, in character
          (e.g. that the ink blurred before it reached you), and invite them to try again.
    """.trimIndent()

    const val USER_INSTRUCTION =
        "This image is the current page of the diary, just written by hand. " +
            "Read the handwriting and write the diary's reply."
}
