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
    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        diaryView = DiaryView(this)
        controller = DiaryController(this, diaryView, prefs)
        setContentView(diaryView)

        // Long-press with a finger -> settings; any touch during linger -> skip the wait.
        // The pen helper runs with its own listener disabled (see DiaryController.attach),
        // so this listener stays in place and forwards every event to the helper.
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                showSettingsDialog()
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

    private fun handleTouch(event: MotionEvent): Boolean {
        controller.forwardTouchToPen(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            controller.requestSkipLinger()
        }
        // Debug fallback for non-BOOX environments: simulate strokes with touch events.
        if (controller.debugTouchFallback &&
            (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP)
        ) {
            controller.debugAddPoint(
                event.x, event.y, event.pressure.coerceIn(0.1f, 1f) * DiaryController.MAX_PRESSURE,
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
        codexSettings?.close()
        super.onDestroy()
        if (::controller.isInitialized) controller.onDestroy()
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
            text = "Codex (ChatGPT login)"
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
                    Toast.makeText(this, if (prefs.provider == Prefs.PROVIDER_CODEX) "Sign in and choose a Codex model first" else getString(R.string.toast_need_key), Toast.LENGTH_LONG).show()
                }
            }
            .setOnDismissListener {
                codexFields.close()
                codexSettings = null
                settingsOpen = false
                controller.onResume()
            }
            .show()
    }
}
