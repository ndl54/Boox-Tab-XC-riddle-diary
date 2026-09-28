package com.billtt.riddle

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AnthropicOracle(private val apiKey: String, private val model: String) : Oracle {
    override fun ask(request: AiRequest): String {
        val body = JSONObject().put("model", model).put("max_tokens", 4096)
            .put("system", request.prompt).put("messages", ConversationWire.anthropic(request))
        return client.newCall(Request.Builder().url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey).header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
            if (!response.isSuccessful) throw UiError(R.string.error_http, response.code)
            val result = JSONObject(response.body?.string() ?: throw UiError(R.string.error_response_empty))
            if (result.optString("stop_reason") == "max_tokens") throw UiError(R.string.error_reply_incomplete)
            val content = result.getJSONArray("content")
            (0 until content.length()).joinToString("\n") { content.getJSONObject(it).optString("text") }
                .trim().ifEmpty { throw UiError(R.string.error_reply_empty) }
        }
    }
    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    }
}
