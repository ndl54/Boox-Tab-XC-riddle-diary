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
    private val device = button("Sign in with device code (recommended)") { login(false) }
    private val browser = button("Sign in on this BOOX") { login(true) }
    private val open = button("Open OpenAI sign-in page") { openPage(CodexProtocol.DEVICE_PAGE) }
    private val refresh = button("Refresh model list") { loadModels() }
    private val cancel = button("Cancel sign-in") {
        work?.cancel()
        setBusy(false)
        status.text = "Sign-in cancelled."
    }
    private val logout = button("Sign out on this device") {
        work?.cancel()
        scope.launch {
            withContext(Dispatchers.IO) { prefs.codexAuth.signOut() }
            prefs.codexModel = ""
            prefs.codexModels = emptyList()
            populateModels()
            setBusy(false)
            status.text = "Signed out."
        }
    }

    init {
        orientation = VERTICAL
        addView(TextView(activity).apply {
            text = "Use your ChatGPT/Codex subscription. Device code can be approved on a phone, Mac or this BOOX. Enable device code in ChatGPT security settings if needed."
        })
        addView(status)
        addView(device); addView(browser); addView(open); addView(cancel)
        addView(TextView(activity).apply { text = "Model for reading handwriting" })
        addView(models); addView(refresh); addView(logout)
        populateModels()
        setBusy(false)
        status.text = if (prefs.codexAuth.connected) "Signed in ${prefs.codexAuth.account}" else "Not signed in"
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
        status.text = "Starting Codex sign-in…"
        work = scope.launch {
            try {
                val tokens = if (useBrowser) prefs.codexAuth.browserLogin { url ->
                    withContext(Dispatchers.Main) {
                        status.text = "Complete sign-in in the browser, then return here. Keep this app open."
                        openPage(url)
                    }
                } else prefs.codexAuth.deviceLogin { code ->
                    withContext(Dispatchers.Main) {
                        status.text = "Open ${CodexProtocol.DEVICE_PAGE}\nEnter code: $code\nWaiting for approval (up to 15 minutes)…"
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
                status.text = "Signed in. Loading models…"
                fetchModels()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = safeError(e) }
            finally { if (currentCoroutineContext().isActive) setBusy(false) }
        }
    }

    private fun loadModels() {
        work?.cancel()
        setBusy(true)
        status.text = "Loading models…"
        work = scope.launch {
            try { fetchModels() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                status.text = safeError(e) + if (prefs.codexModels.isNotEmpty()) "\nShowing the previously loaded list." else ""
            }
            finally { if (currentCoroutineContext().isActive) setBusy(false) }
        }
    }

    private suspend fun fetchModels() {
        val list = withContext(Dispatchers.IO) { prefs.codexAuth.models() }
        prefs.codexModels = list
        populateModels()
        status.text = "Signed in ${prefs.codexAuth.account}\n${list.size} models available. Choose one, then Save."
    }

    private fun openPage(url: String) {
        try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { status.text = "No browser available. Install a browser or approve the device code on another device." }
    }

    private fun safeError(e: Exception): String = when (e) {
        is java.io.IOException -> e.message?.takeIf { !it.contains("https://") && !it.contains("token=") }
            ?: "Cannot connect to Codex. Check your connection and try again."
        else -> "Codex returned an unexpected response. Please try signing in again."
    }

    fun close() { scope.cancel() }
}
