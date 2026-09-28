package com.billtt.riddle

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CodexProtocolTest {
    @Test fun pkceMatchesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            CodexProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
    @Test fun callbackDecodesCodeOnlyWithMatchingState() {
        assertEquals("a+b", CodexProtocol.callbackCode("/auth/callback?code=a%2Bb&state=expected", "expected"))
    }
    @Test(expected = IOException::class) fun callbackRejectsWrongState() {
        CodexProtocol.callbackCode("/auth/callback?code=secret&state=attacker", "expected")
    }
    @Test(expected = IOException::class) fun callbackRejectsDuplicateState() {
        CodexProtocol.callbackCode("/auth/callback?code=secret&state=expected&state=attacker", "expected")
    }
    @Test(expected = IOException::class) fun callbackRejectsWrongPath() {
        CodexProtocol.callbackCode("/other?code=secret&state=expected", "expected")
    }
    @Test fun catalogFiltersHiddenAndTextOnlyModelsWithoutExcludingSubscriptionModels() {
        val list = CodexProtocol.models(JSONObject("""{"models":[
          {"slug":"vision","display_name":"Vision","input_modalities":["text","image"],"supported_in_api":false},
          {"slug":"hidden","visibility":"hide"},
          {"slug":"text","input_modalities":["text"]},
          {"slug":"legacy"}, {"slug":"vision"}
        ]}"""))
        assertEquals(listOf("vision", "legacy"), list.map { it.id })
    }
    @Test fun requestUsesResponsesVisionAndDisablesStorage() {
        val request = CodexProtocol.request("selected-model", byteArrayOf(1, 2, 3))
        assertFalse(request.getBoolean("store"))
        assertTrue(request.getBoolean("stream"))
        assertEquals("selected-model", request.getString("model"))
        val content = request.getJSONArray("input").getJSONObject(0).getJSONArray("content")
        assertEquals("input_image", content.getJSONObject(1).getString("type"))
        assertEquals("data:image/png;base64,AQID", content.getJSONObject(1).getString("image_url"))
    }
    @Test fun streamingCompletionUsesFinalTextWithoutDuplicatingDeltas() {
        val stream = """data: {"type":"response.output_text.delta","delta":"Hello"}

data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Hello world"}]}]}}

"""
        assertEquals("Hello world", CodexProtocol.readReply(stream.reader().buffered()))
    }
    @Test(expected = IOException::class) fun truncatedStreamIsNotSuccess() {
        CodexProtocol.readReply("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n".reader().buffered())
    }
    @Test(expected = IOException::class) fun incompleteResponseIsNotSuccess() {
        CodexProtocol.readReply("data: {\"type\":\"response.incomplete\"}\n\n".reader().buffered())
    }
    @Test fun refusalIsVisible() {
        assertEquals("Cannot help", CodexProtocol.readReply("""data: {"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"refusal","refusal":"Cannot help"}]}]}}

""".reader().buffered()))
    }
}
