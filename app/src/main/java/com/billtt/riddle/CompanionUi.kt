package com.billtt.riddle

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import java.text.DateFormat
import java.util.Date

/** All navigation originates from the page's long-press menu. */
class CompanionUi(private val activity: Activity, private val controller: DiaryController,
                  private val settings: () -> Unit, private val modal: (Boolean) -> Unit) {
    private val engine get() = controller.engine
    private val prefs get() = engine.prefs
    private val dp get() = activity.resources.displayMetrics.density
    private fun s(id: Int) = activity.getString(id)
    val badge = TextView(activity).apply {
        textSize = 14f; setTextColor(Color.BLACK); setBackgroundColor(Color.WHITE)
        setPadding(12, 8, 12, 8)
    }
    private var popup: PopupWindow? = null
    private var afterDismiss: (() -> Unit)? = null
    init {
        badge.setOnHoverListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> showContext()
                MotionEvent.ACTION_HOVER_EXIT -> { popup?.dismiss(); popup = null }
            }; true
        }
        badge.setOnLongClickListener { menu(); true }
        badge.setOnClickListener { showContext() }
    }
    fun close() { popup?.dismiss() }
    fun refresh() {
        val percentage = (engine.estimated.toLong() * 100 / prefs.contextBudget).coerceAtMost(999)
        badge.text = if (controller.state != DiaryController.State.WRITING) s(R.string.busy_short)
            else "◔ ~$percentage% · ↻${engine.chat.compressions}"
        badge.contentDescription = s(R.string.context_title)
    }
    private fun showContext() {
        popup?.dismiss()
        val label = TextView(activity).apply {
            textSize = 18f; setTextColor(Color.WHITE); setBackgroundColor(Color.DKGRAY)
            setPadding(24, 20, 24, 20)
            text = activity.getString(R.string.context_details, engine.estimated, prefs.contextBudget, engine.chat.compressions, engine.estimated.toLong() * 100 / prefs.contextBudget) +
                "\n\n" + s(if (prefs.continuous) R.string.continuous else R.string.single_turn)
        }
        popup = PopupWindow(label, (320 * dp).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isOutsideTouchable = true; setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.DKGRAY))
            elevation = 8f
            showAtLocation(badge, Gravity.BOTTOM or Gravity.END, (12 * dp).toInt(), (55 * dp).toInt())
        }
    }
    private fun dialog(builder: AlertDialog.Builder): AlertDialog {
        modal(true); controller.onPause()
        return builder.setOnDismissListener {
            val next = afterDismiss; afterDismiss = null
            modal(false); controller.onResume(); refresh()
            next?.invoke()
        }.create().also { it.show() }
    }
    fun menu() {
        close()
        if (controller.state != DiaryController.State.WRITING) {
            controller.requestSkipLinger()
            Toast.makeText(activity, R.string.busy_wait, Toast.LENGTH_SHORT).show(); return
        }
        val actions = listOf(R.string.send_page, R.string.type_message, R.string.save_note, R.string.history,
            R.string.new_chat, R.string.retry_question, R.string.compact_context, R.string.settings_title)
        dialog(AlertDialog.Builder(activity).setTitle(engine.chat.title.ifBlank { s(R.string.app_name) })
            .setItems(actions.map { s(it) }.toTypedArray()) { _, which ->
                // The next action runs from onDismiss, after the modal flag has been cleared.
                afterDismiss = {
                    when (which) {
                        0 -> controller.sendPage()
                        1 -> compose()
                        2 -> controller.saveNote()
                        3 -> history()
                        4 -> controller.turnPage()
                        5 -> controller.retry()
                        6 -> controller.compact()
                        7 -> settings()
                    }
                }
            })
    }
    private fun compose() {
        val input = EditText(activity).apply { hint = s(R.string.type_message); minLines = 4; gravity = Gravity.TOP }
        dialog(AlertDialog.Builder(activity).setTitle(R.string.type_message).setView(input)
            .setPositiveButton(R.string.send_page) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotBlank()) afterDismiss = { controller.sendText(text) }
            }.setNeutralButton(R.string.save_note) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotBlank()) { engine.note(null, text); refresh() }
            }.setNegativeButton(android.R.string.cancel, null))
    }
    private fun title(chat: ChatSession) = chat.title.ifBlank {
        s(Roles.names[chat.role.coerceIn(0, Roles.names.lastIndex)]) + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(chat.updated))
    }
    private fun history() {
        val sessions = engine.store.sessions()
        dialog(AlertDialog.Builder(activity).setTitle(R.string.history)
            .setItems(sessions.map { title(it) }.toTypedArray()) { _, index -> afterDismiss = { transcript(sessions[index]) } }
            .setNegativeButton(android.R.string.cancel, null))
    }
    private fun transcript(chat: ChatSession, page: Int = 0) {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 12, 20, 12) }
        val start = (chat.turns.size - (page + 1) * 20).coerceAtLeast(0)
        val end = (chat.turns.size - page * 20).coerceAtLeast(0)
        var current: AlertDialog? = null
        fun next(action: () -> Unit) { afterDismiss = action; current?.dismiss() }
        fun button(label: Int, action: () -> Unit) { column.addView(Button(activity).apply { text = s(label); setOnClickListener { next(action) } }) }
        button(R.string.rename_chat) { rename(chat) }
        button(R.string.export_chat) { export(chat) }
        button(R.string.delete_chat) { delete(chat) }
        if (chat.turns.isEmpty()) column.addView(TextView(activity).apply { text = s(R.string.empty_history) })
        if (start > 0) button(R.string.older_messages) { transcript(chat, page + 1) }
        chat.turns.subList(start, end).forEach { turn ->
            column.addView(TextView(activity).apply {
                text = s(when (turn.role) { "assistant" -> R.string.speaker_assistant; "note" -> R.string.speaker_note; else -> R.string.speaker_you }) +
                    " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(turn.time))
                textSize = 14f; setPadding(0, 24, 0, 6)
            })
            if (turn.image.isNotBlank()) {
                val bmp = BitmapFactory.decodeFile(engine.store.imageFile(turn.image).path, BitmapFactory.Options().apply { inSampleSize = 2 })
                column.addView(ImageView(activity).apply { setImageBitmap(bmp); adjustViewBounds = true; contentDescription = s(R.string.handwritten_note) })
            }
            column.addView(TextView(activity).apply {
                text = if (turn.image.isNotBlank() && turn.role == "user") s(R.string.handwritten_note) else turn.text
                textSize = 19f; setTextIsSelectable(true)
            })
        }
        if (page > 0) button(R.string.newer_messages) { transcript(chat, page - 1) }
        val scroll = ScrollView(activity).apply { addView(column) }
        current = dialog(AlertDialog.Builder(activity).setTitle(title(chat)).setView(scroll)
            .setPositiveButton(R.string.continue_chat) { _, _ -> afterDismiss = { controller.turnPage(chat) } }
            .setNegativeButton(android.R.string.cancel, null))
    }
    private fun rename(chat: ChatSession) {
        val input = EditText(activity).apply { setText(chat.title) }
        dialog(AlertDialog.Builder(activity).setTitle(R.string.rename_chat).setView(input)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                chat.title = input.text.toString().trim().take(120)
                engine.store.save(chat)
                if (chat.id == engine.chat.id) engine.chat.title = chat.title
            }.setNegativeButton(android.R.string.cancel, null))
    }
    private fun delete(chat: ChatSession) {
        dialog(AlertDialog.Builder(activity).setTitle(R.string.delete_chat).setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete_chat) { _, _ ->
                engine.store.delete(chat)
                if (chat.id == engine.chat.id) { engine.newChat(); controller.restoreDraft() }
            }.setNegativeButton(android.R.string.cancel, null))
    }
    private fun export(chat: ChatSession) {
        // Android's document picker writes only to the destination chosen by the user.
        exportSession = chat.id
        activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"; putExtra(Intent.EXTRA_TITLE, "boox-${chat.id.take(8)}.txt")
        }, EXPORT_REQUEST)
    }
    var exportSession: String? = null
    fun exportText(): String? {
        val chat = exportSession?.let { engine.store.load(it) } ?: return null
        return buildString {
            appendLine(title(chat)); appendLine()
            chat.turns.forEach { turn ->
                appendLine(s(when(turn.role) { "assistant" -> R.string.speaker_assistant; "note" -> R.string.speaker_note; else -> R.string.speaker_you }))
                if (turn.image.isNotBlank()) appendLine(s(R.string.export_image_notice))
                if (turn.text != OraclePrompts.USER_INSTRUCTION) appendLine(turn.text)
                appendLine()
            }
        }
    }
    companion object { const val EXPORT_REQUEST = 104 }
}
