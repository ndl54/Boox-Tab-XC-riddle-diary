package com.billtt.riddle

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Private, backup-excluded local archive. Atomic metadata replacement survives interrupted writes. */
class ChatStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "conversations").apply { mkdirs() }
    private fun file(id: String, ext: String): File {
        require(id.matches(Regex("[a-f0-9-]+")))
        return File(root, "$id.$ext")
    }
    fun save(chat: ChatSession) {
        chat.updated = System.currentTimeMillis()
        write(file(chat.id, "json"), chat.json().toString().toByteArray())
    }
    fun load(id: String): ChatSession? = runCatching {
        ChatSession.from(JSONObject(AtomicFile(file(id, "json")).openRead().use { it.readBytes().toString(Charsets.UTF_8) }))
    }.getOrNull()
    fun sessions(): List<ChatSession> = root.listFiles().orEmpty().filter { it.extension == "json" || it.extension == "bak" }
        .map { it.name.substringBefore('.') }.distinct().mapNotNull { load(it) }.sortedByDescending { it.updated }
    fun image(bytes: ByteArray): String = java.util.UUID.randomUUID().toString().also { write(file(it, "png"), bytes) }
    fun imageFile(id: String): File = file(id, "png")
    fun imageBytes(id: String): ByteArray? = if (id.isBlank()) null else file(id, "png").readBytes()
    fun delete(chat: ChatSession) {
        chat.turns.filter { it.image.isNotBlank() }.forEach { file(it.image, "png").delete() }
        AtomicFile(file(chat.id, "json")).delete()
    }
    private fun write(target: File, bytes: ByteArray) {
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
}
