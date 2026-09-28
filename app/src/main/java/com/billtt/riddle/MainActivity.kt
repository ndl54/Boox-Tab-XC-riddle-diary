package com.billtt.riddle

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var diaryView: DiaryView
    private lateinit var controller: DiaryController
    private lateinit var prefs: Prefs
    private lateinit var companionUi: CompanionUi
    private lateinit var gestureDetector: GestureDetector

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        diaryView = DiaryView(this)
        val engine = ChatEngine(this, prefs)
        controller = DiaryController(this, diaryView, prefs, engine) { if (::companionUi.isInitialized) companionUi.refresh() }
        companionUi = CompanionUi(this, controller, { showSettingsDialog() }, { settingsOpen = it })
        val frame = android.widget.FrameLayout(this)
        frame.addView(diaryView)
        frame.addView(companionUi.badge, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.END))
        setContentView(frame)
        engine.refreshEstimate()
        controller.restoreDraft()
        companionUi.refresh()
        companionUi.exportSession = savedInstanceState?.getString("export_session")

        // Long-press with a finger -> settings; any touch during linger -> skip the wait.
        // The pen helper runs with its own listener disabled (see DiaryController.attach),
        // so this listener stays in place and forwards every event to the helper.
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) companionUi.menu()
            }
        })
        diaryView.setOnTouchListener { _, event -> handleTouch(event) }

        // Attaching TouchHelper requires the window to have focus (so the view position is final);
        // see onWindowFocusChanged.
    }

    private var penAttached = false
    private var settingsOpen = false
    private var codexSettings: CodexSettingsView? = null

    private fun tryAttachPen() {
        if (penAttached || !::controller.isInitialized || !hasWindowFocus()) return
        penAttached = true
        controller.attach()
        if (!prefs.configured) showSettingsDialog()
    }

    private var swipeX = 0f
    private var swipeY = 0f

    private fun handleTouch(event: MotionEvent): Boolean {
        val finger = event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        if (finger) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) controller.requestSkipLinger()
            gestureDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_DOWN) { swipeX = event.x; swipeY = event.y }
            if (event.actionMasked == MotionEvent.ACTION_UP && !settingsOpen &&
                swipeX > diaryView.width * 0.85f && swipeX - event.x > diaryView.width * 0.30f &&
                kotlin.math.abs(event.y - swipeY) < diaryView.height * 0.15f) controller.turnPage()
            return true
        }
        controller.forwardTouchToPen(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            controller.requestSkipLinger()
        }
        // Debug fallback for non-BOOX environments: simulate strokes with touch events.
        if (controller.debugTouchFallback &&
            (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP)
        ) {
            controller.debugAddPoint(
                event.x, event.y, event.pressure.coerceIn(0.1f, 1f),
                up = event.actionMasked == MotionEvent.ACTION_UP,
            )
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        if (::controller.isInitialized && !settingsOpen) controller.onResume()
    }

    /**
     * Attach the pen driver when the window first gains focus (only then is the view's
     * screen position final); on every subsequent focus gain (dialog / IME dismissed)
     * resume writing.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !::controller.isInitialized) return
        hideSystemUi()
        if (penAttached) { if (!settingsOpen) controller.onResume() } else tryAttachPen()
    }

    override fun onPause() {
        super.onPause()
        if (::controller.isInitialized) controller.onPause()
    }

    override fun onDestroy() {
        companionUi.close()
        codexSettings?.close()
        super.onDestroy()
        if (::controller.isInitialized) controller.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("export_session", companionUi.exportSession)
    }
    @Deprecated("Android activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CompanionUi.EXPORT_REQUEST && resultCode == RESULT_OK) {
            val text = companionUi.exportText() ?: return
            runCatching {
                contentResolver.openOutputStream(data?.data ?: return)?.use { it.write(text.toByteArray()) }
            }.onFailure { Toast.makeText(this, UiError.describe(this, it), Toast.LENGTH_LONG).show() }
        }
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    // ------------------------------------------------------------- settings UI

    private fun showSettingsDialog() {
        if (settingsOpen) return
        settingsOpen = true
        // Pause raw pen mode while settings are open, so the dialog isn't covered by the ink layer.
        controller.onPause()

        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }

        layout.addView(TextView(this).apply { text = getString(R.string.settings_language) })
        val language = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.language_system), getString(R.string.language_en), getString(R.string.language_vi)))
            setSelection(AppLanguage.choices.indexOf(AppLanguage.selected(this@MainActivity)))
        }
        layout.addView(language)
        var languageChanged = false

        layout.addView(TextView(this).apply { text = getString(R.string.role_label) })
        val role = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                Roles.names.map { getString(it) })
            setSelection(controller.engine.chat.role.coerceIn(0, Roles.names.lastIndex))
        }
        val custom = EditText(this).apply {
            hint = getString(R.string.custom_prompt_hint); minLines = 3
            setText(controller.engine.chat.custom)
        }
        val continuous = android.widget.CheckBox(this).apply { text = getString(R.string.continuous); isChecked = prefs.continuous }
        val autoSend = android.widget.CheckBox(this).apply { text = getString(R.string.auto_send); isChecked = prefs.autoSend }
        val budget = EditText(this).apply {
            hint = getString(R.string.context_budget); inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.contextBudget.toString())
        }
        layout.addView(role); layout.addView(custom); layout.addView(continuous); layout.addView(autoSend)
        layout.addView(TextView(this).apply { text = getString(R.string.context_budget) }); layout.addView(budget)
        layout.addView(TextView(this).apply { text = getString(R.string.companion_help); textSize = 13f })

        // ---- backend selection ----
        val anthropicRadio = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.settings_provider_anthropic)
        }
        val openaiRadio = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.settings_provider_openai)
        }
        val codexRadio = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.settings_provider_codex)
        }
        val providerGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(anthropicRadio)
            addView(openaiRadio)
            addView(codexRadio)
        }

        // ---- Anthropic fields ----
        val anthropicKeyInput = EditText(this).apply {
            hint = getString(R.string.settings_api_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.apiKey)
        }
        val anthropicModelInput = EditText(this).apply {
            hint = getString(R.string.settings_model_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.model)
        }
        val anthropicFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(anthropicKeyInput)
            addView(anthropicModelInput)
        }

        // ---- OpenAI fields ----
        val openaiKeyInput = EditText(this).apply {
            hint = getString(R.string.settings_openai_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.openaiKey)
        }
        val openaiModelInput = EditText(this).apply {
            hint = getString(R.string.settings_openai_model_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.openaiModel)
        }
        val openaiBaseUrlInput = EditText(this).apply {
            hint = getString(R.string.settings_openai_base_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.openaiBaseUrl)
        }
        val openaiFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(openaiKeyInput)
            addView(openaiModelInput)
            addView(openaiBaseUrlInput)
        }

        val codexFields = CodexSettingsView(this, prefs)
        codexSettings = codexFields

        val hint = TextView(this).apply {
            text = getString(R.string.settings_hint_gesture)
            textSize = 12f
            setPadding(0, pad / 2, 0, 0)
        }

        layout.addView(providerGroup)
        layout.addView(anthropicFields)
        layout.addView(openaiFields)
        layout.addView(codexFields)
        layout.addView(hint)

        fun applyVisibility(selected: Int) {
            anthropicFields.visibility = if (selected == anthropicRadio.id) View.VISIBLE else View.GONE
            openaiFields.visibility = if (selected == openaiRadio.id) View.VISIBLE else View.GONE
            codexFields.visibility = if (selected == codexRadio.id) View.VISIBLE else View.GONE
        }
        providerGroup.setOnCheckedChangeListener { _, selected -> applyVisibility(selected) }
        providerGroup.check(when (prefs.provider) {
            Prefs.PROVIDER_CODEX -> codexRadio.id
            Prefs.PROVIDER_OPENAI -> openaiRadio.id
            else -> anthropicRadio.id
        })
        applyVisibility(providerGroup.checkedRadioButtonId)

        val scroll = ScrollView(this).apply { addView(layout) }

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(scroll)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                val chosenLanguage = AppLanguage.choices[language.selectedItemPosition]
                languageChanged = chosenLanguage != AppLanguage.selected(this)
                if (languageChanged) AppLanguage.apply(this, chosenLanguage)
                prefs.role = role.selectedItemPosition
                prefs.customPrompt = custom.text.toString()
                prefs.continuous = continuous.isChecked
                prefs.autoSend = autoSend.isChecked
                prefs.contextBudget = budget.text.toString().toIntOrNull() ?: 32000
                controller.engine.chat.role = prefs.role
                controller.engine.chat.custom = prefs.customPrompt
                controller.engine.save()
                controller.engine.refreshEstimate()
                companionUi.refresh()
                prefs.provider = when (providerGroup.checkedRadioButtonId) {
                    codexRadio.id -> Prefs.PROVIDER_CODEX
                    openaiRadio.id -> Prefs.PROVIDER_OPENAI
                    else -> Prefs.PROVIDER_ANTHROPIC
                }
                prefs.apiKey = anthropicKeyInput.text.toString()
                prefs.model = anthropicModelInput.text.toString()
                prefs.openaiKey = openaiKeyInput.text.toString()
                prefs.openaiModel = openaiModelInput.text.toString()
                prefs.openaiBaseUrl = openaiBaseUrlInput.text.toString()
                if (!prefs.configured) {
                    Toast.makeText(this, if (prefs.provider == Prefs.PROVIDER_CODEX) getString(R.string.codex_need_model) else getString(R.string.toast_need_key), Toast.LENGTH_LONG).show()
                }
            }
            .setOnDismissListener {
                codexFields.close()
                codexSettings = null
                settingsOpen = false
                if (languageChanged) {
                    title = getString(R.string.app_name)
                    showSettingsDialog()
                } else controller.onResume()
            }
            .show()
    }
}
