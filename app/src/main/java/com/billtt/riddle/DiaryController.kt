package com.billtt.riddle

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.data.TouchPointList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * State machine: writing -> ink absorption -> awaiting reply -> reply reveal ->
 * linger -> reply fade -> writing.
 *
 * During writing, the SDK's render layer draws the live stroke through the NeoPen
 * hardware fast path (zero latency, like the stock Notes app), while the app receives
 * every point via callbacks and renders the finished stroke in software on pen-up.
 * Requires the app-class bootstrap in RiddleApp (hidden-API exemption; without it the
 * raw-touch region mapping fails with "Empty region detected when mapping" and the pen
 * delivers no points) and the local onyxsdk AAR set bundled in app/libs (the maven
 * builds lack the firmware's native fast-path classes — see app/build.gradle).
 */
class DiaryController(
    private val activity: Activity,
    private val view: DiaryView,
    private val prefs: Prefs,
    val engine: ChatEngine,
    val onChanged: () -> Unit,
) {

    enum class State { WRITING, ABSORBING, AWAITING_REPLY, REVEALING, LINGERING, FADING_REPLY }

    @Volatile var state = State.WRITING
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var touchHelper: TouchHelper? = null
    private var cycleJob: Job? = null
    private var resumed = true
    @Volatile private var skipLingerRequested = false

    /** Set on pen-up; onPenUpRefresh (exact moment the hardware preview clears) beats the
     *  delayed fallback to repaint the finished stroke with the app's own rendering. */
    @Volatile private var pendingPenUpRefresh = false

    // The stroke currently being written (accumulated from move callbacks; used as a
    // fallback on pen-up if the full point list wasn't delivered).
    private val pendingPoints = ArrayList<StrokePoint>()

    private val idleRunnable = Runnable { onIdle() }

    // ------------------------------------------------------------- lifecycle

    /** Call after the view is laid out; if the size is still 0, defer until layout completes. */
    fun attach() {
        if (view.width == 0 || view.height == 0) {
            Log.i(TAG, "attach: view not laid out yet (${view.width}x${view.height}), deferring")
            view.addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: android.view.View, l: Int, t: Int, r: Int, b: Int,
                    ol: Int, ot: Int, or_: Int, ob: Int,
                ) {
                    if (v.width > 0 && v.height > 0) {
                        v.removeOnLayoutChangeListener(this)
                        attach()
                    }
                }
            })
            return
        }
        val limit = Rect(0, 0, view.width, view.height)
        val ok = runCatching {
            // Zero-latency hardware ink (Boox-EinkDraw recipe; the order is critical):
            // the 4-arg create with touchListener=false keeps the helper from installing
            // its own OnTouchListener (MainActivity's listener must survive — it forwards
            // events to the pen via forwardTouchToPen, which also lets the long-press
            // settings gesture coexist with raw pen input).
            val h = TouchHelper.create(view, TouchHelper.FEATURE_ALL_TOUCH_RENDER, rawCallback, false)
            h.setStrokeWidth(view.baseStrokeWidth)
            h.enableFingerTouch(false)          // stylus only: palm/finger touches must not draw
            h.onlyEnableFingerTouch(false)
            h.setStrokeColor(Color.BLACK)
            h.setLimitRect(limit, arrayListOf(Rect((view.width - 180 * view.resources.displayMetrics.density).toInt(), (view.height - 48 * view.resources.displayMetrics.density).toInt(), view.width, view.height)))
            h.openRawDrawing()
            h.setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL)
            h.setStrokeWidth(view.baseStrokeWidth)
            h.setStrokeColor(Color.BLACK)
            h.setRawDrawingRenderEnabled(false)
            h.setRawDrawingEnabled(true)
            touchHelper = h
        }.isSuccess
        Log.i(TAG, "attach: limit=$limit touchHelper=${if (touchHelper != null) "ok" else "null"} ok=$ok")
        if (!ok) {
            touchHelper = null
            Toast.makeText(activity, R.string.toast_not_boox, Toast.LENGTH_LONG).show()
        }
        EInk.beginAnimation(view)
    }

    /** Forward MotionEvents to the pen helper. The helper's own OnTouchListener is
     *  disabled (4-arg create in attach), so MainActivity's listener must feed it. */
    fun forwardTouchToPen(event: MotionEvent) {
        touchHelper?.onTouchEvent(event)
    }

    /** Resume writing: re-enable raw pen input, only in the writing state. Called when the
     *  window regains focus / a dialog is dismissed. */
    fun onResume() {
        resumed = true
        Log.i(TAG, "onResume: state=$state touchHelper=${touchHelper != null}")
        if (state == State.WRITING) {
            touchHelper?.setRawDrawingEnabled(true)
        }
    }

    fun onPause() {
        resumed = false
        if (state == State.WRITING) saveDraft()
        touchHelper?.setRawDrawingEnabled(false)
        view.removeCallbacks(idleRunnable)
    }

    fun onDestroy() {
        runCatching { touchHelper?.closeRawDrawing() }
        scope.cancel()
    }

    // ------------------------------------------- debug touch input (non-BOOX)

    /** True when the raw pen driver is unavailable (emulator); fall back to touch events. */
    val debugTouchFallback: Boolean get() = touchHelper == null

    fun debugAddPoint(x: Float, y: Float, pressure: Float, up: Boolean) {
        if (state != State.WRITING || !resumed) return
        pendingPoints.add(StrokePoint(x, y, pressure))
        if (up) {
            view.addStroke(Stroke(ArrayList(pendingPoints)))
            pendingPoints.clear()
            EInk.animateFrame(view)
            scheduleIdleCheck()
        }
    }

    /** Any touch during the linger phase makes the reply fade early. */
    fun requestSkipLinger() {
        if (state == State.LINGERING) skipLingerRequested = true
    }

    // --------------------------------------------------------- raw pen callbacks
    // Note: these fire on Onyx SDK background threads and are all posted to the main thread.

    private val rawCallback = object : RawInputCallback() {

        override fun onBeginRawDrawing(shortcut: Boolean, point: TouchPoint) {
            val p = StrokePoint(point.x, point.y, normalizePressure(point.pressure))
            view.post {
                if (state != State.WRITING || !resumed) return@post
                view.removeCallbacks(idleRunnable)
                pendingPoints.clear()
                pendingPoints.add(p)
                // Live ink is drawn by the hardware render layer; the app only collects
                // points and renders the finished stroke on pen-up.
            }
        }

        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint) {
            val p = StrokePoint(point.x, point.y, normalizePressure(point.pressure))
            view.post {
                if (state != State.WRITING || !resumed) return@post
                pendingPoints.add(p)
            }
        }

        override fun onRawDrawingTouchPointListReceived(pointList: TouchPointList) {
            val pts = pointList.points.map {
                StrokePoint(it.x, it.y, normalizePressure(it.pressure))
            }
            view.post {
                if (state != State.WRITING || !resumed) return@post
                view.addStroke(Stroke(pts))
                pendingPoints.clear()
            }
        }

        override fun onEndRawDrawing(outLimitRegion: Boolean, point: TouchPoint) {
            Log.i(TAG, "onEndRawDrawing strokes=${view.strokes.size} pending=${pendingPoints.size}")
            view.post {
                if (state != State.WRITING || !resumed) return@post
                if (view.strokes.isEmpty() && pendingPoints.size >= 2) {
                    view.addStroke(Stroke(ArrayList(pendingPoints)))
                }
                pendingPoints.clear()
                // The hardware preview clears on pen-up; repaint the finished stroke with
                // the app's own rendering. onPenUpRefresh fires at the exact moment if the
                // firmware supports it — fall back to a delayed refresh if it never comes.
                pendingPenUpRefresh = true
                view.postDelayed({
                    if (pendingPenUpRefresh) {
                        pendingPenUpRefresh = false
                        EInk.animateFrame(view)
                    }
                }, 120)
                scheduleIdleCheck()
            }
        }

        override fun onPenUpRefresh(rect: RectF?) {
            if (!pendingPenUpRefresh) return
            pendingPenUpRefresh = false
            view.post { EInk.animateFrame(view) }
        }

        override fun onBeginRawErasing(shortcut: Boolean, point: TouchPoint) {
            view.post { view.removeCallbacks(idleRunnable) }
        }

        override fun onRawErasingTouchPointMoveReceived(point: TouchPoint) {}

        override fun onRawErasingTouchPointListReceived(pointList: TouchPointList) {
            val pts = pointList.points.map { StrokePoint(it.x, it.y, 1f) }
            view.post {
                if (state != State.WRITING || !resumed) return@post
                if (view.eraseAt(pts, ERASER_RADIUS)) refreshAfterErase()
                scheduleIdleCheck()
            }
        }

        override fun onEndRawErasing(outLimitRegion: Boolean, point: TouchPoint) {
            view.post { scheduleIdleCheck() }
        }
    }

    private fun normalizePressure(raw: Float): Float =
        (raw / MAX_PRESSURE).coerceIn(0.05f, 1f)

    /** After erasing, briefly leave raw pen mode to redraw the remaining strokes and full-refresh. */
    private fun refreshAfterErase() {
        touchHelper?.setRawDrawingEnabled(false)
        view.invalidate()          // redraw remaining strokes (erased ones are gone)
        EInk.fullRefresh(view)     // GC full refresh to clear ghosting of erased ink
        view.postDelayed({
            if (state == State.WRITING && resumed) touchHelper?.setRawDrawingEnabled(true)
        }, 300)
    }

    // ------------------------------------------------------------- idle detection

    private fun scheduleIdleCheck() {
        if (state != State.WRITING) return
        view.removeCallbacks(idleRunnable)
        saveDraft()
        if (resumed && prefs.autoSend && state == State.WRITING && view.strokes.isNotEmpty()) {
            view.postDelayed(idleRunnable, IDLE_MS)
        }
    }

    private fun onIdle() {
        if (state != State.WRITING || view.strokes.isEmpty()) return
        startCycle()
    }

    // --------------------------------------------------------- main cycle (one round)

    fun saveDraft() {
        if (state != State.WRITING) return
        engine.chat.draft = org.json.JSONArray(view.strokes.map { stroke ->
            org.json.JSONArray(stroke.points.map { org.json.JSONArray(listOf(it.x, it.y, it.pressure)) })
        })
        engine.save()
    }

    fun restoreDraft() {
        view.clearStrokes(); view.clearReply()
        val draft = engine.chat.draft
        for (i in 0 until draft.length()) {
            val points = draft.getJSONArray(i)
            view.addStroke(Stroke((0 until points.length()).map { j ->
                val pt = points.getJSONArray(j)
                StrokePoint(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat(), pt.getDouble(2).toFloat())
            }))
        }
        view.invalidate(); onChanged()
    }

    fun sendText(text: String) = startCycle(text = text)
    fun sendPage() { if (view.strokes.isNotEmpty()) startCycle() }
    fun retry() { if (engine.chat.turns.lastOrNull()?.role == "user") startCycle(retry = true) }
    fun saveNote() {
        if (state != State.WRITING || view.strokes.isEmpty()) return
        engine.note(view.capturePagePng(), activity.getString(R.string.handwritten_note))
        view.clearStrokes(); EInk.fullRefresh(view); onChanged()
    }
    fun turnPage(session: ChatSession? = null) {
        if (state != State.WRITING) return
        saveDraft(); view.removeCallbacks(idleRunnable); touchHelper?.setRawDrawingEnabled(false); state = State.ABSORBING
        scope.launch {
            try {
                for (i in 1..6) { view.pageTurn = i / 6f; EInk.animateFrame(view); delay(90) }
                if (session == null) engine.newChat() else engine.select(session)
                restoreDraft()
            } finally {
                view.pageTurn = 0f; state = State.WRITING
                if (resumed) touchHelper?.setRawDrawingEnabled(true)
                EInk.fullRefresh(view); onChanged()
            }
        }
    }
    fun compact() {
        if (state != State.WRITING) return
        view.removeCallbacks(idleRunnable); touchHelper?.setRawDrawingEnabled(false); state = State.AWAITING_REPLY; onChanged()
        scope.launch {
            try { withContext(Dispatchers.IO) { val active = coroutineContext; engine.compact(checkActive = { active.ensureActive() }) } }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; silentReply(e) }
            finally { state = State.WRITING; if (resumed) touchHelper?.setRawDrawingEnabled(true); onChanged() }
        }
    }

    private fun startCycle(text: String? = null, retry: Boolean = false) {
        if (state != State.WRITING) return
        if (!prefs.configured) { Toast.makeText(activity, R.string.toast_need_key, Toast.LENGTH_LONG).show(); return }
        view.removeCallbacks(idleRunnable)
        saveDraft()
        val png = if (!retry && view.strokes.isNotEmpty()) view.capturePagePng() else null
        state = State.AWAITING_REPLY
        touchHelper?.setRawDrawingEnabled(false)
        view.clearReply(); onChanged()
        cycleJob = scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) { val active = coroutineContext; engine.answer(png, text ?: OraclePrompts.USER_INSTRUCTION, retry) { active.ensureActive() } }
                state = State.ABSORBING
                animateAbsorb(); view.clearStrokes()
                // The full response remains selectable/scrollable in history.
                state = State.REVEALING
                view.setReply(if (reply.length > 900) reply.take(850) + "…\n" + activity.getString(R.string.reply_preview_tail) else reply)
                animateReveal()
                state = State.LINGERING
                skipLingerRequested = false
                lingerInterruptibly(9000)
                view.clearReply()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                silentReply(e)
                // Archived question can be retried; handwriting remains visible on failure.
            } finally {
                state = State.WRITING
                if (resumed) touchHelper?.setRawDrawingEnabled(true)
                EInk.fullRefresh(view); onChanged()
            }
        }
    }

    private fun silentReply(cause: Throwable?): String {
        cause?.let {
            Toast.makeText(activity, activity.getString(R.string.error_title, UiError.describe(activity, it)), Toast.LENGTH_LONG).show()
        }
        return "……"
    }

    // ------------------------------------------------------------- animations
    //
    // E-ink refresh is slow, so a continuous per-frame gradient stutters. Ink level is
    // quantized to 5 steps; we scan the timeline with a fine sample step and only issue
    // a real DU4 fast refresh when some element crosses a step — every refresh is a
    // visible grayscale jump, dead frames are skipped, and the result is a crisp,
    // stepped absorb / reveal.

    /** Quantize to steps 0..4, consistent with DiaryView's quantization. */
    private fun level(a: Float): Int = Math.round(a.coerceIn(0f, 1f) * 4f)

    /**
     * Generic stepped-fade driver.
     * @param count   number of elements
     * @param totalMs total curve duration
     * @param setA    write element i's ink level in place
     * @param curve   given element i and time t, return its target level in [0,1]
     */
    private suspend fun runStagedFade(
        count: Int, totalMs: Long, setA: (Int, Float) -> Unit, curve: (Int, Long) -> Float,
    ) {
        if (count == 0) return
        val prev = IntArray(count) { Int.MIN_VALUE }
        var t = 0L
        while (t <= totalMs) {
            var changed = false
            for (i in 0 until count) {
                val a = curve(i, t)
                setA(i, a)
                val lv = level(a)
                if (lv != prev[i]) { prev[i] = lv; changed = true }
            }
            if (changed) {
                EInk.animateFrame(view)
                delay(FRAME_MS)
            }
            t += SAMPLE_MS
        }
    }

    /**
     * Ink absorption: split the strokes into bands in write order (offscreen bitmaps)
     * and fade the bands out with a stagger. Keeps the "absorbed head to tail" ordering
     * while drawing only a few bitmaps per frame — continuous and smooth.
     */
    private suspend fun animateAbsorb() {
        if (view.strokes.isEmpty()) return
        view.prepareAbsorb()
        val k = view.absorbBandAlphas.size
        if (k == 0) return
        val totalMs = ABSORB_BAND_STAGGER_MS * (k - 1) + ABSORB_BAND_FADE_MS
        runStagedFade(k, totalMs, { i, a -> view.absorbBandAlphas[i] = a }) { i, t ->
            val local = (t - i * ABSORB_BAND_STAGGER_MS).coerceAtLeast(0L)
            (1f - local.toFloat() / ABSORB_BAND_FADE_MS).coerceIn(0f, 1f)
        }
        view.finishAbsorb()
    }

    /** Reply reveal: words rise in order from faint gray to full ink. */
    private suspend fun animateReveal() {
        val n = view.replyWords.size
        runStagedFade(n, REVEAL_WORD_MS * (n - 1) + FADE_MS, { i, a -> view.wordAlphas[i] = a }) { i, t ->
            val local = (t - i * REVEAL_WORD_MS).coerceAtLeast(0L)
            (local.toFloat() / FADE_MS).coerceIn(0f, 1f)
        }
        for (i in 0 until n) view.wordAlphas[i] = 1f
        EInk.animateFrame(view)
    }

    /** Reply fade: the reverse of reveal — words sink back into the page in order. */
    private suspend fun animateReplyFade() {
        val n = view.replyWords.size
        val stagger = if (n > 0) (ABSORB_TOTAL_STAGGER_MS / n).coerceIn(50L, REVEAL_WORD_MS) else 0L
        runStagedFade(n, stagger * (n - 1) + FADE_MS, { i, a -> view.wordAlphas[i] = a }) { i, t ->
            val local = (t - i * stagger).coerceAtLeast(0L)
            (1f - local.toFloat() / FADE_MS).coerceIn(0f, 1f)
        }
    }

    private fun lingerMillisFor(wordCount: Int): Long =
        (1000L + wordCount * 110L).coerceIn(1800L, 9000L)

    private suspend fun lingerInterruptibly(millis: Long) {
        var waited = 0L
        while (waited < millis && !skipLingerRequested) {
            delay(120)
            waited += 120
        }
    }

    companion object {
        const val TAG = "RiddleDiary"

        /** How long the pen must rest to count as "a passage finished" and trigger absorption
         *  (the original riddle project uses 2.8s). */
        const val IDLE_MS = 2800L

        /** Minimum interval after each real refresh — DU4 fast refresh is ~150ms. */
        const val FRAME_MS = 130L

        /** Timeline sampling step (does not refresh; only used to detect step crossings). */
        const val SAMPLE_MS = 30L

        const val FADE_MS = 420L                  // reply: one word from full to 0 (or reverse)
        const val ABSORB_BAND_FADE_MS = 300L      // absorb: one band from full down to 0
        const val ABSORB_BAND_STAGGER_MS = 75L    // absorb: gap between adjacent bands starting to fade (ordering)
        const val ABSORB_TOTAL_STAGGER_MS = 1100L // reply fade: total stagger budget
        const val ABSORB_STAGGER_MAX_MS = 90L

        const val REVEAL_WORD_MS = 55L            // gap between adjacent words starting to reveal

        const val REPLY_TIMEOUT_MS = 150_000L
        const val ERASER_RADIUS = 24f
        const val MAX_PRESSURE = 4096f
    }
}
