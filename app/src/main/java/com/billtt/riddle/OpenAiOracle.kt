package com.billtt.riddle

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * OpenAI backend: Chat Completions + vision (data-URI image).
 * baseUrl can point at any OpenAI-compatible endpoint (e.g. a local vLLM/Ollama
 * gateway, or a third-party proxy).
 */
class OpenAiOracle(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String,
) : Oracle {

    override fun ask(pagePng: ByteArray): String {
        val dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(pagePng)

        val body = JSONObject()
            .put("model", model)
        // GLM 4.5+/5.x are reasoning models: without this they "think" for seconds before
        // replying (measured 10s vs 1.2s for a one-line diary reply). Only sent to known
        // GLM endpoints — strict OpenAI-compatible servers reject unknown top-level fields.
        if (baseUrl.contains("bigmodel", ignoreCase = true) ||
            baseUrl.contains("z.ai", ignoreCase = true)
        ) {
            body.put("thinking", JSONObject().put("type", "disabled"))
        }
        body.put(
            "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", OraclePrompts.PERSONA)
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("type", "image_url")
                                            .put("image_url", JSONObject().put("url", dataUri))
                                    )
                                    .put(
                                        JSONObject()
                                            .put("type", "text")
                                            .put("text", OraclePrompts.USER_INSTRUCTION)
                                    )
                            )
                    )
            )

        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw UiError(R.string.error_http, response.code)
            }
            val reply = JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
            return reply.ifEmpty { "……" }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"

        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
