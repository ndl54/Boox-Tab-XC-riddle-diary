package com.billtt.riddle

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Wire format shared by Codex browser/device login and the subscription Responses service. */
object CodexProtocol {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val AUTH = "https://auth.openai.com"
    const val API = "https://chatgpt.com/backend-api/codex"
    const val REDIRECT = "http://localhost:1455/auth/callback"
    const val DEVICE_REDIRECT = "$AUTH/deviceauth/callback"
    const val DEVICE_PAGE = "$AUTH/codex/device"
    // Catalog compatibility version, not the Android app's version.
    const val CLIENT_VERSION = "0.158.0"

    fun randomSecret(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authorizationUrl(verifier: String, state: String): String =
        "$AUTH/oauth/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("redirect_uri", REDIRECT)
            .addQueryParameter("scope", "openid profile email offline_access")
            .addQueryParameter("code_challenge", challenge(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .addQueryParameter("id_token_add_organizations", "true")
            .addQueryParameter("codex_cli_simplified_flow", "true")
            .addQueryParameter("originator", "boox_riddle_diary")
            .build().toString()

    fun callbackCode(target: String, expectedState: String): String {
        if (!target.startsWith("/auth/callback?")) throw IOException("Invalid callback path")
        val url = ("http://localhost:1455$target").toHttpUrl()
        val states = url.queryParameterValues("state")
        if (states.size != 1 || !MessageDigest.isEqual(
                states[0].orEmpty().toByteArray(), expectedState.toByteArray())) {
            throw IOException("Invalid login state. Please restart login.")
        }
        if (url.queryParameter("error") != null) throw IOException("Login was declined. Please try again.")
        return url.queryParameterValues("code").singleOrNull()?.takeIf { it.isNotBlank() }
            ?: throw IOException("No authorization code received")
    }

    fun claims(jwt: String): JSONObject = runCatching {
        JSONObject(String(Base64.getUrlDecoder().decode(jwt.split('.')[1]), Charsets.UTF_8))
    }.getOrDefault(JSONObject())

    data class Model(val id: String, val label: String) {
        override fun toString() = if (label == id) id else "$label ($id)"
    }

    fun models(json: JSONObject): List<Model> {
        val entries = json.getJSONArray("models")
        return (0 until entries.length()).map { entries.getJSONObject(it) }.filter { m ->
            val modalities = m.optJSONArray("input_modalities")
            m.optString("visibility", "list") == "list" &&
                (modalities == null || (0 until modalities.length()).any { modalities.optString(it) == "image" })
        }.mapNotNull { m ->
            m.optString("slug").takeIf { it.isNotBlank() }?.let {
                Model(it, m.optString("display_name", it))
            }
        }.distinctBy { it.id }
    }

    fun request(model: String, png: ByteArray): JSONObject = JSONObject()
        .put("model", model).put("store", false).put("stream", true)
        .put("instructions", OraclePrompts.PERSONA)
        .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "input_text").put("text", OraclePrompts.USER_INSTRUCTION))
            .put(JSONObject().put("type", "input_image").put("image_url",
                "data:image/png;base64," + Base64.getEncoder().encodeToString(png))))))

    /** Require a terminal success event: truncated streams must never look like complete replies. */
    fun readReply(reader: BufferedReader): String {
        val deltas = StringBuilder()
        val event = StringBuilder()
        fun consume(): String? {
            if (event.isEmpty()) return null
            val data = event.toString(); event.setLength(0)
            if (data == "[DONE]") return null
            val json = JSONObject(data)
            when (json.optString("type")) {
                "response.output_text.delta" -> {
                    deltas.append(json.optString("delta"))
                    if (deltas.length > 100_000) throw IOException("Codex reply is too large")
                }
                "error", "response.failed", "response.incomplete" ->
                    throw IOException("Codex could not finish the reply. Please try again or choose another model.")
                "response.completed", "response.done" -> {
                    val response = json.optJSONObject("response")
                    if (response != null && response.optString("status", "completed") != "completed")
                        throw IOException("Codex reply did not complete")
                    val output = response?.optJSONArray("output") ?: JSONArray()
                    val text = buildString {
                        for (i in 0 until output.length()) {
                            val item = output.getJSONObject(i)
                            if (item.optString("type") != "message") continue
                            val content = item.optJSONArray("content") ?: continue
                            for (j in 0 until content.length()) {
                                val part = content.getJSONObject(j)
                                when (part.optString("type")) {
                                    "output_text" -> append(part.optString("text"))
                                    "refusal" -> append(part.optString("refusal"))
                                }
                            }
                        }
                    }.ifBlank { deltas.toString() }.trim()
                    return text.ifEmpty { throw IOException("Codex returned no text") }
                }
            }
            return null
        }
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) consume()?.let { return it }
            else if (line.startsWith("data:")) {
                if (event.isNotEmpty()) event.append('\n')
                event.append(line.removePrefix("data:").trimStart())
                if (event.length > 2_000_000) throw IOException("Codex event is too large")
            }
        }
        consume()?.let { return it }
        throw IOException("Codex connection ended before the reply completed")
    }
}
