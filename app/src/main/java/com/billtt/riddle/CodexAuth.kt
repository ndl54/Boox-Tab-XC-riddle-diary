package com.billtt.riddle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class CodexAuth(private val store: CodexStore) {
    private val lock = Any()
    val connected: Boolean get() = store.read() != null
    val account: String get() = store.read()?.optString("email").orEmpty()

    fun signOut() = synchronized(lock) { store.clear() }
    fun saveLogin(tokens: JSONObject) = synchronized(lock) { store.write(tokens) }

    private fun tokenForm(vararg pairs: Pair<String, String>): FormBody = FormBody.Builder().apply {
        add("client_id", CodexProtocol.CLIENT_ID)
        pairs.forEach { (k, v) -> add(k, v) }
    }.build()

    private fun exchange(code: String, verifier: String, redirect: String): JSONObject =
        decodeTokens(json("${CodexProtocol.AUTH}/oauth/token", tokenForm(
            "grant_type" to "authorization_code", "code" to code,
            "code_verifier" to verifier, "redirect_uri" to redirect)))

    private fun decodeTokens(json: JSONObject, old: JSONObject? = null): JSONObject {
        val access = json.getString("access_token")
        val claims = CodexProtocol.claims(access)
        val idClaims = CodexProtocol.claims(json.optString("id_token"))
        val accountId = claims.optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_account_id")
            ?.takeIf { it.isNotBlank() }
            ?: idClaims.optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_account_id")
                ?.takeIf { it.isNotBlank() }
            ?: old?.optString("account_id")?.takeIf { it.isNotBlank() }
            ?: throw IOException("Codex account was not included in the login")
        val refresh = json.optString("refresh_token").ifBlank { old?.optString("refresh_token").orEmpty() }
        if (access.isBlank() || refresh.isBlank()) throw IOException("Incomplete Codex login response")
        val expires = if (json.optLong("expires_in") > 0) System.currentTimeMillis() + json.getLong("expires_in") * 1000
            else claims.optLong("exp") * 1000
        if (expires <= System.currentTimeMillis()) throw IOException("Codex returned an expired login")
        return JSONObject().put("access_token", access).put("refresh_token", refresh)
            .put("expires_at", expires).put("account_id", accountId)
            .put("email", idClaims.optString("email").ifBlank {
                claims.optJSONObject("https://api.openai.com/profile")?.optString("email")
                    ?.takeIf { it.isNotBlank() } ?: old?.optString("email").orEmpty()
            })
    }

    /** Serialize refresh and logout so rotating tokens cannot overwrite each other. */
    private fun session(rejectedToken: String? = null): JSONObject = synchronized(lock) {
        val saved = store.read() ?: throw IOException("Please sign in to Codex in Settings")
        if (saved.getLong("expires_at") > System.currentTimeMillis() + 60_000 &&
            (rejectedToken == null || saved.getString("access_token") != rejectedToken)) return@synchronized saved
        try {
            val refreshed = decodeTokens(json("${CodexProtocol.AUTH}/oauth/token", tokenForm(
                "grant_type" to "refresh_token", "refresh_token" to saved.getString("refresh_token"))), saved)
            store.write(refreshed)
            refreshed
        } catch (e: HttpFailure) {
            if (e.status == 400 || e.status == 401) {
                store.clear()
                throw IOException("Codex login expired. Please sign in again.")
            }
            throw e
        }
    }

    fun authorized(builder: () -> Request.Builder): Response {
        var credentials = session()
        fun send(): Response = client.newCall(builder()
            .header("Authorization", "Bearer ${credentials.getString("access_token")}")
            .header("ChatGPT-Account-Id", credentials.getString("account_id"))
            .header("originator", "boox_riddle_diary")
            .header("User-Agent", "boox_riddle_diary/0.2.0")
            .build()).execute()
        var response = send()
        if (response.code == 401) {
            response.close()
            credentials = session(credentials.getString("access_token"))
            response = send()
        }
        return response
    }

    fun models(): List<CodexProtocol.Model> = authorized {
        Request.Builder().url("${CodexProtocol.API}/models?client_version=${CodexProtocol.CLIENT_VERSION}")
    }.use { response ->
        checkStatus(response)
        CodexProtocol.models(JSONObject(response.body?.string() ?: throw IOException("Empty model list")))
            .ifEmpty { throw IOException("No image-capable Codex models were returned for this account") }
    }

    suspend fun deviceLogin(showCode: suspend (String) -> Unit): JSONObject = withContext(Dispatchers.IO) {
        val device = json("${CodexProtocol.AUTH}/api/accounts/deviceauth/usercode",
            JSONObject().put("client_id", CodexProtocol.CLIENT_ID).body())
        val code = device.optString("user_code").ifBlank { device.getString("usercode") }
        val id = device.getString("device_auth_id")
        val interval = device.optLong("interval", 5).coerceIn(1, 60) * 1000
        val deadline = android.os.SystemClock.elapsedRealtime() + 15 * 60_000
        showCode(code)
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            currentCoroutineContext().ensureActive()
            val response = raw("${CodexProtocol.AUTH}/api/accounts/deviceauth/token",
                JSONObject().put("device_auth_id", id).put("user_code", code).body())
            response.use {
                if (it.isSuccessful) {
                    val authorized = JSONObject(it.body!!.string())
                    currentCoroutineContext().ensureActive()
                    return@withContext exchange(authorized.getString("authorization_code"),
                        authorized.getString("code_verifier"), CodexProtocol.DEVICE_REDIRECT)
                }
                if (it.code != 403 && it.code != 404) checkStatus(it)
            }
            delay(interval)
        }
        throw IOException("Login code expired. Please start again.")
    }

    /** Loopback receiver is bound before opening the system browser and closed on every exit. */
    suspend fun browserLogin(openBrowser: suspend (String) -> Unit): JSONObject = withContext(Dispatchers.IO) {
        val verifier = CodexProtocol.randomSecret()
        val state = CodexProtocol.randomSecret()
        val server = try { ServerSocket(1455, 4, InetAddress.getByName("127.0.0.1")) }
            catch (_: IOException) { throw IOException("Login port is busy. Use device code or try again.") }
        server.use {
            it.soTimeout = 500
            openBrowser(CodexProtocol.authorizationUrl(verifier, state))
            val deadline = android.os.SystemClock.elapsedRealtime() + 15 * 60_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                currentCoroutineContext().ensureActive()
                val socket = try { it.accept() } catch (_: SocketTimeoutException) { continue }
                socket.use { incoming ->
                    incoming.soTimeout = 3000
                    val line = try { incoming.getInputStream().bufferedReader().readLine().orEmpty() }
                        catch (_: IOException) { "" }
                    val target = line.split(' ').takeIf { parts -> parts.size == 3 && parts[0] == "GET" }?.get(1).orEmpty()
                    val result = runCatching { CodexProtocol.callbackCode(target, state) }
                    val message = if (result.isSuccess) "Login received. Return to BOOX Diary to finish." else "Invalid or declined login. Return to BOOX Diary and try again."
                    val body = "<!doctype html><meta name=viewport content='width=device-width'><p>$message</p>".toByteArray()
                    runCatching {
                        incoming.getOutputStream().apply {
                            write(("HTTP/1.1 ${if (result.isSuccess) "200 OK" else "400 Bad Request"}\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
                            write(body); flush()
                        }
                    }
                    if (result.isSuccess) {
                        currentCoroutineContext().ensureActive()
                        return@withContext exchange(result.getOrThrow(), verifier, CodexProtocol.REDIRECT)
                    }
                }
            }
            throw IOException("Browser login timed out. Please start again.")
        }
    }

    private fun JSONObject.body() = toString().toRequestBody("application/json".toMediaType())
    private fun raw(url: String, body: RequestBody) = authClient.newCall(Request.Builder().url(url)
        .header("originator", "boox_riddle_diary").header("User-Agent", "boox_riddle_diary/0.2.0")
        .post(body).build()).execute()
    private fun json(url: String, body: RequestBody): JSONObject = raw(url, body).use {
        checkStatus(it)
        JSONObject(it.body?.string() ?: throw IOException("Empty login response"))
    }

    class HttpFailure(val status: Int, message: String) : IOException(message)
    companion object {
        // Never follow a redirect while carrying OAuth credentials.
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS).build()
        private val authClient = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
        fun checkStatus(response: Response) {
            if (!response.isSuccessful) throw HttpFailure(response.code, when (response.code) {
                401 -> "Codex session was rejected. Please sign in again."
                403 -> "Codex access denied. Check your plan/workspace permissions or try browser login."
                404 -> "Codex endpoint unavailable. For login, try the other sign-in method."
                429 -> "Codex usage limit reached. Please try again later."
                else -> "Codex request failed (HTTP ${response.code}). Please try again."
            })
        }
    }
}
