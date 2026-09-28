package com.billtt.riddle

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ConversationTest {
    private fun engine(oracle: Oracle): ChatEngine {
        val context = RuntimeEnvironment.getApplication()
        context.noBackupFilesDir.deleteRecursively()
        context.getSharedPreferences("riddle", 0).edit().clear().commit()
        return ChatEngine(context, Prefs(context)) { oracle }
    }
    @Test fun conversationPersistsAndResumesWithImagesAndRoles() {
        val requests = mutableListOf<AiRequest>()
        val engine = engine(object : Oracle { override fun ask(request: AiRequest): String { requests.add(request); return "answer" } })
        val id = engine.chat.id
        engine.answer(byteArrayOf(1,2,3), "question 1")
        engine.answer(null, "question 2")
        assertEquals(listOf("user", "assistant", "user"), requests.last().messages.map { it.role })
        assertArrayEquals(byteArrayOf(1,2,3), requests.last().messages.first().png)
        val restored = engine.store.load(id)!!
        assertEquals(4, restored.turns.size)
        engine.newChat()
        assertNotEquals(id, engine.chat.id)
        engine.select(restored)
        assertEquals(id, engine.prefs.activeChat)
        assertEquals(4, engine.request().messages.size)
    }
    @Test fun notesNeverGoToTheProviderAndSingleTurnOmitsHistory() {
        val requests = mutableListOf<AiRequest>()
        val engine = engine(object : Oracle { override fun ask(request: AiRequest): String { requests.add(request); return "ok" } })
        engine.note(null, "private local note")
        engine.answer(null, "first")
        engine.prefs.continuous = false
        engine.answer(null, "second")
        assertEquals(listOf("second"), requests.last().messages.map { it.text })
        assertFalse(engine.request().messages.any { it.text.contains("private") })
    }
    @Test fun compressionKeepsArchiveAndRecentQuestionsAndCountsOnlySuccess() {
        val engine = engine(object : Oracle { override fun ask(request: AiRequest) = "answer" })
        repeat(5) { engine.answer(null, "question $it") }
        val before = engine.chat.turns.map { it.json().toString() }
        var summaryRequest: AiRequest? = null
        engine.compact(object : Oracle { override fun ask(request: AiRequest): String { summaryRequest = request; return "reference summary" } })
        assertEquals(before, engine.chat.turns.map { it.json().toString() })
        assertEquals(1, engine.chat.compressions)
        assertEquals(6, engine.chat.summarized)
        assertEquals("user", summaryRequest!!.messages.last().role)
        assertTrue(engine.request().messages.any { it.text == "question 3" })
        assertFalse(engine.request().messages.any { it.text == "question 0" })
        engine.answer(null, "next")
        try { engine.compact(object : Oracle { override fun ask(request: AiRequest): String = throw java.io.IOException("offline") }); fail() }
        catch (_: java.io.IOException) {}
        assertEquals(1, engine.chat.compressions)
        assertEquals(6, engine.chat.summarized)
    }
    @Test fun failedRequestIsArchivedAndRetryDoesNotDuplicateQuestion() {
        var fail = true
        val engine = engine(object : Oracle { override fun ask(request: AiRequest): String { if (fail) throw java.io.IOException(); return "ok" } })
        try { engine.answer(null, "saved before network"); fail() } catch (_: java.io.IOException) {}
        assertEquals(1, engine.store.load(engine.chat.id)!!.turns.size)
        fail = false
        engine.answer(null, "", retry = true)
        assertEquals(listOf("user", "assistant"), engine.chat.turns.map { it.role })
    }
    @Test fun providerPayloadsPreserveHistoryAndSelectedInstructions() {
        val input = AiRequest("custom instructions", listOf(AiMessage("user", "first", byteArrayOf(1)), AiMessage("assistant", "answer"), AiMessage("user", "next")))
        val codex = ConversationWire.codex("model", input)
        assertEquals("custom instructions", codex.getString("instructions"))
        assertFalse(codex.getBoolean("store"))
        assertEquals("output_text", codex.getJSONArray("input").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("type"))
        val openai = ConversationWire.openai(input)
        assertEquals(4, openai.length())
        assertEquals("custom instructions", openai.getJSONObject(0).getString("content"))
        val anthropic = ConversationWire.anthropic(input)
        assertEquals(3, anthropic.length())
        assertEquals("image", anthropic.getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("type"))
    }
    @Test fun deleteRemovesImagesAndSessionButNotOtherSessions() {
        val engine = engine(object : Oracle { override fun ask(request: AiRequest) = "ok" })
        engine.answer(byteArrayOf(1), "image")
        val old = engine.chat
        val image = engine.store.imageFile(old.turns.first().image)
        engine.newChat()
        engine.store.delete(old)
        assertNull(engine.store.load(old.id)); assertFalse(image.exists())
        assertNotNull(engine.store.load(engine.chat.id))
    }
}
