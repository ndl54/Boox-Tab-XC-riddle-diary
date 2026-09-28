package com.billtt.riddle

import android.content.Context

class Prefs(context: Context) {
    val codexAuth = CodexAuth(CodexStore(context.applicationContext), context)
    private val sp = context.getSharedPreferences("riddle", Context.MODE_PRIVATE)

    /** Backend selection: PROVIDER_ANTHROPIC / PROVIDER_OPENAI / PROVIDER_CODEX */
    var provider: String
        get() = sp.getString("provider", PROVIDER_ANTHROPIC) ?: PROVIDER_ANTHROPIC
        set(value) = sp.edit().putString("provider", value).apply()

    // ---- Anthropic ----
    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(value) = sp.edit().putString("api_key", value.trim()).apply()

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = sp.edit().putString("model", value.trim().ifEmpty { DEFAULT_MODEL }).apply()

    // ---- OpenAI / compatible endpoint ----
    var openaiKey: String
        get() = sp.getString("openai_key", "") ?: ""
        set(value) = sp.edit().putString("openai_key", value.trim()).apply()

    var openaiModel: String
        get() = sp.getString("openai_model", OpenAiOracle.DEFAULT_MODEL) ?: OpenAiOracle.DEFAULT_MODEL
        set(value) = sp.edit()
            .putString("openai_model", value.trim().ifEmpty { OpenAiOracle.DEFAULT_MODEL }).apply()

    var openaiBaseUrl: String
        get() = sp.getString("openai_base_url", OpenAiOracle.DEFAULT_BASE_URL)
            ?: OpenAiOracle.DEFAULT_BASE_URL
        set(value) = sp.edit()
            .putString("openai_base_url", value.trim().ifEmpty { OpenAiOracle.DEFAULT_BASE_URL }).apply()

    /** Whether the selected backend has credentials and a model configured. */
    val configured: Boolean
        get() = when (provider) {
            PROVIDER_CODEX -> codexAuth.connected && codexModel.isNotBlank()
            PROVIDER_OPENAI -> openaiKey.isNotEmpty()
            else -> apiKey.isNotEmpty()
        }

    var codexModel: String
        get() = sp.getString("codex_model", "").orEmpty()
        set(value) = sp.edit().putString("codex_model", value).apply()

    var codexModels: List<CodexProtocol.Model>
        get() = runCatching {
            val array = org.json.JSONArray(sp.getString("codex_models", "[]"))
            (0 until array.length()).map { array.getJSONObject(it).let { m ->
                CodexProtocol.Model(m.getString("id"), m.getString("label"))
            } }
        }.getOrDefault(emptyList())
        set(value) {
            val array = org.json.JSONArray()
            value.forEach { array.put(org.json.JSONObject().put("id", it.id).put("label", it.label)) }
            sp.edit().putString("codex_models", array.toString()).apply()
        }

    var activeChat: String
        get() = sp.getString("active_chat", "").orEmpty()
        set(v) = sp.edit().putString("active_chat", v).apply()
    var role: Int
        get() = sp.getInt("role", 1)
        set(v) = sp.edit().putInt("role", v).apply()
    var customPrompt: String
        get() = sp.getString("custom_prompt", "").orEmpty()
        set(v) = sp.edit().putString("custom_prompt", v).apply()
    var continuous: Boolean
        get() = sp.getBoolean("continuous", true)
        set(v) = sp.edit().putBoolean("continuous", v).apply()
    var autoSend: Boolean
        get() = sp.getBoolean("auto_send", false)
        set(v) = sp.edit().putBoolean("auto_send", v).apply()
    var contextBudget: Int
        get() = sp.getInt("context_budget", 32000)
        set(v) = sp.edit().putInt("context_budget", v.coerceIn(8000, 1000000)).apply()

    companion object {
        const val PROVIDER_ANTHROPIC = "anthropic"
        const val PROVIDER_CODEX = "codex"
        const val PROVIDER_OPENAI = "openai"
        const val DEFAULT_MODEL = "claude-opus-4-8"
    }
}
