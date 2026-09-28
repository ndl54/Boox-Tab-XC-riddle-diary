package com.billtt.riddle

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Dialog-owned jobs survive a browser round trip, but never outlive the dialog/activity. */
class CodexSettingsView(private val activity: Activity, private val prefs: Prefs) : LinearLayout(activity) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var work: Job? = null
    private val status = TextView(activity).apply { setTextIsSelectable(true) }
    private val models = Spinner(activity)
    private val device = button(context.getString(R.string.codex_device)) { login(false) }
    private val browser = button(context.getString(R.string.codex_browser)) { login(true) }
    private val open = button(context.getString(R.string.codex_open)) { openPage(CodexProtocol.DEVICE_PAGE) }
    private val refresh = button(context.getString(R.string.codex_refresh)) { loadModels() }
    private val cancel = button(context.getString(R.string.codex_cancel)) {
        work?.cancel()
        setBusy(false)
        status.text = context.getString(R.string.codex_cancelled)
    }
    private val logout = button(context.getString(R.string.codex_logout)) {
        work?.cancel()
        scope.launch {
            withContext(Dispatchers.IO) { prefs.codexAuth.signOut() }
            prefs.codexModel = ""
            prefs.codexModels = emptyList()
            populateModels()
            setBusy(false)
            status.text = context.getString(R.string.codex_logged_out)
        }
    }

    init {
        orientation = VERTICAL
        addView(TextView(activity).apply {
            text = context.getString(R.string.codex_intro)
        })
        addView(status)
        addView(device); addView(browser); addView(open); addView(cancel)
        addView(TextView(activity).apply { text = context.getString(R.string.codex_model_label) })
        addView(models); addView(refresh); addView(logout)
        populateModels()
        setBusy(false)
        status.text = if (prefs.codexAuth.connected) context.getString(R.string.codex_signed_in, prefs.codexAuth.account) else context.getString(R.string.codex_not_signed_in)
        if (prefs.codexAuth.connected) loadModels()
    }

    private fun button(label: String, action: () -> Unit) = Button(activity).apply {
        text = label; setOnClickListener { action() }
    }

    private fun setBusy(busy: Boolean) {
        device.isEnabled = !busy
        browser.isEnabled = !busy
        refresh.isEnabled = !busy && prefs.codexAuth.connected
        models.isEnabled = !busy && prefs.codexModels.isNotEmpty()
        logout.isEnabled = !busy && prefs.codexAuth.connected
        cancel.visibility = if (busy) View.VISIBLE else View.GONE
        if (!busy) open.visibility = View.GONE
    }

    private fun populateModels() {
        val available = prefs.codexModels
        models.onItemSelectedListener = null
        models.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, available)
        val selected = available.indexOfFirst { it.id == prefs.codexModel }
        if (available.isNotEmpty()) {
            val index = selected.coerceAtLeast(0)
            models.setSelection(index)
            prefs.codexModel = available[index].id
        } else prefs.codexModel = ""
        models.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                available.getOrNull(position)?.let { prefs.codexModel = it.id }
            }
        }
    }

    private fun login(useBrowser: Boolean) {
        work?.cancel()
        setBusy(true)
        open.visibility = View.GONE
        status.text = context.getString(R.string.codex_starting)
        work = scope.launch {
            try {
                val tokens = if (useBrowser) prefs.codexAuth.browserLogin { url ->
                    withContext(Dispatchers.Main) {
                        status.text = context.getString(R.string.codex_browser_wait)
                        openPage(url)
                    }
                } else prefs.codexAuth.deviceLogin { code ->
                    withContext(Dispatchers.Main) {
                        status.text = context.getString(R.string.codex_device_wait, CodexProtocol.DEVICE_PAGE, code)
                        open.visibility = View.VISIBLE
                    }
                }
                ensureActive()
                // No suspension between the cancellation check and committing a new login.
                prefs.codexAuth.saveLogin(tokens)
                prefs.codexModels = emptyList()
                prefs.codexModel = ""
                populateModels()
                open.visibility = View.GONE
                status.text = context.getString(R.string.codex_loading_after_login)
                fetchModels()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = safeError(e) }
            finally { if (currentCoroutineContext().isActive) setBusy(false) }
        }
    }

    private fun loadModels() {
        work?.cancel()
        setBusy(true)
        status.text = context.getString(R.string.codex_loading)
        work = scope.launch {
            try { fetchModels() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                status.text = safeError(e) + if (prefs.codexModels.isNotEmpty()) "\n" + context.getString(R.string.codex_cached) else ""
            }
            finally { if (currentCoroutineContext().isActive) setBusy(false) }
        }
    }

    private suspend fun fetchModels() {
        val list = withContext(Dispatchers.IO) { prefs.codexAuth.models() }
        prefs.codexModels = list
        populateModels()
        status.text = context.getString(R.string.codex_models_ready, prefs.codexAuth.account, list.size)
    }

    private fun openPage(url: String) {
        try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { status.text = context.getString(R.string.codex_no_browser) }
    }

    private fun safeError(e: Exception): String = UiError.describe(context, e)

    fun close() { scope.cancel() }
}
