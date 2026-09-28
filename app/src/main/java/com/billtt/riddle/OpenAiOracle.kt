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

    override fun ask(input: AiRequest): String {
        val body = JSONObject().put("model", model).put("messages", ConversationWire.openai(input))
        if (baseUrl.contains("bigmodel", true) || baseUrl.contains("z.ai", true))
            body.put("thinking", JSONObject().put("type", "disabled"))

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
            val choice = JSONObject(text).getJSONArray("choices").getJSONObject(0)
            if (choice.optString("finish_reason") == "length") throw UiError(R.string.error_reply_incomplete)
            val reply = JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
            return reply.ifEmpty { throw UiError(R.string.error_reply_empty) }
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
