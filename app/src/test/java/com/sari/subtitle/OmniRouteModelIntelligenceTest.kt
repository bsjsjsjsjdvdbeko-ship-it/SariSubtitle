package com.sari.subtitle

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OmniRouteModelIntelligenceTest {
    @Test fun explicitFreeMetadataIsRecognized() {
        val model = OmniRouteModelIntelligence.parse(
            JSONObject("""{"id":"provider/free-chat","owned_by":"provider","is_free":true,"capabilities":["chat"]}""")
        )!!
        assertEquals(true, model.isFree)
        assertTrue(model.capabilities.contains("chat"))
    }

    @Test fun unknownPriceIsNotGuessedFromModelName() {
        val model = OmniRouteModelIntelligence.parse(
            JSONObject("""{"id":"free-looking-model","owned_by":"provider"}""")
        )!!
        assertNull(model.isFree)
        assertEquals("السعر غير معروف", model.costLabel)
    }

    @Test fun zeroPricingMetadataIsRecognizedAsFree() {
        val model = OmniRouteModelIntelligence.parse(
            JSONObject("""{"id":"model-x","pricing":{"prompt":0,"completion":0}}""")
        )!!
        assertEquals(true, model.isFree)
    }

    @Test fun incompletePricingRemainsUnknown() {
        val model = OmniRouteModelIntelligence.parse(
            JSONObject("""{"id":"model-partial","pricing":{"prompt":0}}""")
        )!!
        assertNull(model.isFree)
    }

    @Test fun nonZeroPricingMetadataIsNotFree() {
        val model = OmniRouteModelIntelligence.parse(
            JSONObject("""{"id":"model-y","pricing":{"prompt":0.1,"completion":0.2}}""")
        )!!
        assertEquals(false, model.isFree)
    }
    @Test fun supportedEndpointsAreMappedToSeparateTaskCapabilities() {
        val model = OmniRouteModelIntelligence.parse(
            JSONObject("""{"id":"multi-task","supportedEndpoints":["/v1/chat/completions","/v1/audio/transcriptions","/v1/audio/speech"]}""")
        )!!
        assertTrue(model.supports("translation"))
        assertTrue(model.supports("stt"))
        assertTrue(model.supports("tts"))
    }

    @Test fun modelTypeCanIdentifyEmbeddingWithoutGuessingFromItsName() {
        val model = OmniRouteModelIntelligence.parse(
            JSONObject("""{"id":"model-xyz","type":"embedding"}""")
        )!!
        assertTrue(model.supports("embedding"))
        assertFalse(model.supports("translation"))
    }

    @Test fun capabilitiesCanIncludeChatAndAudioTogether() {
        val model = OmniRouteModelIntelligence.parse(
            JSONObject("""{"id":"combo-model","capabilities":["chat","audio/transcriptions"]}""")
        )!!
        assertTrue(model.supports("chat"))
        assertTrue(model.supports("stt"))
    }

}
