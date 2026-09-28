package com.billtt.riddle

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class CodexOracle(private val auth: CodexAuth, private val model: String) : Oracle {
    override fun ask(pagePng: ByteArray): String {
        if (model.isBlank()) throw UiError(R.string.error_choose_model)
        val body = CodexProtocol.request(model, pagePng).toString()
            .toRequestBody("application/json".toMediaType())
        return auth.authorized {
            Request.Builder().url("${CodexProtocol.API}/responses")
                .header("Accept", "text/event-stream").post(body)
        }.use {
            CodexAuth.checkStatus(it)
            val reader = it.body?.charStream()?.buffered() ?: throw UiError(R.string.error_response_empty)
            CodexProtocol.readReply(reader)
        }
    }
}
