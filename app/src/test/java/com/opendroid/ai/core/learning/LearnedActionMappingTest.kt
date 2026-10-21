package com.opendroid.ai.core.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedActionMappingTest {

    @Test
    fun `key normalisation matches the auto mapper rules`() {
        assertEquals("SEND_WHATSAPP", normalizeLearnedActionKey("send whatsapp"))
        assertEquals("SEND_WHATSAPP", normalizeLearnedActionKey("SEND-WHATSAPP"))
        assertEquals("SEND_WHATSAPP", normalizeLearnedActionKey("  send-whatsapp  "))
        // '.' and '!' are stripped as invalid chars (NOT treated as word
        // separators) — this is the auto mapper's exact behaviour too.
        assertEquals("OPENAPP", normalizeLearnedActionKey("open.app!"))
        // The identity the production contract requires, checked directly:
        // the learned-key normaliser must agree with the auto mapper on
        // every input, because dispatch looks mappings up by the latter.
        val autoMapper = com.opendroid.ai.actions.ActionAutoMapper()
        for (raw in listOf("send whatsapp", "SEND-WHATSAPP", "open.app!", "  Play Music  ", "a.b-c d")) {
            assertEquals(
                "normalisers diverge on '$raw'",
                autoMapper.normalizeActionName(raw),
                normalizeLearnedActionKey(raw)
            )
        }
    }

    @Test
    fun `param template substitutes tokens from runtime params case-insensitively`() {
        val template = mapOf(
            "contact" to "{Contact}",
            "message" to "Hello {name}, see {link}"
        )
        val resolved = LearnedParamTemplate.apply(
            template,
            mapOf("contact" to "Mom", "name" to "Asha", "link" to "https://x")
        )
        assertEquals("Mom", resolved["contact"])
        assertEquals("Hello Asha, see https://x", resolved["message"])
    }

    @Test
    fun `param template drops entries with unresolved tokens`() {
        val template = mapOf(
            "contact" to "{missing}",
            "state" to "on"
        )
        val resolved = LearnedParamTemplate.apply(template, emptyMap())
        assertEquals(mapOf("state" to "on"), resolved)
    }

    @Test
    fun `param template keeps literal values untouched`() {
        val template = mapOf("appName" to "WhatsApp")
        assertEquals(template, LearnedParamTemplate.apply(template, mapOf("other" to "x")))
        assertTrue(LearnedParamTemplate.apply(emptyMap(), mapOf("a" to "b")).isEmpty())
    }

    @Test
    fun `resolve maps each kind to its dispatch behaviour`() {
        val dismissed = LearnedActionMapping("X", LearnedActionMapping.Kind.DISMISSED)
        assertEquals(LearnedResolution.Skip, dismissed.resolve(emptyMap()))

        val action = LearnedActionMapping(
            attemptedAction = "X",
            kind = LearnedActionMapping.Kind.ACTION,
            targetAction = "SEND_SMS",
            paramTemplate = mapOf("phone" to "{number}")
        )
        assertEquals(
            LearnedResolution.RunAction("SEND_SMS", mapOf("phone" to "123")),
            action.resolve(mapOf("number" to "123"))
        )

        val macro = LearnedActionMapping(
            attemptedAction = "X",
            kind = LearnedActionMapping.Kind.MACRO,
            macroName = "morning"
        )
        assertEquals(LearnedResolution.RunMacro("morning"), macro.resolve(emptyMap()))
    }

    @Test
    fun `resolve returns null for a mapping with no usable target`() {
        val blankAction = LearnedActionMapping(
            attemptedAction = "X",
            kind = LearnedActionMapping.Kind.ACTION,
            targetAction = " "
        )
        assertNull(blankAction.resolve(emptyMap()))

        val blankMacro = LearnedActionMapping(
            attemptedAction = "X",
            kind = LearnedActionMapping.Kind.MACRO,
            macroName = null
        )
        assertNull(blankMacro.resolve(emptyMap()))
    }

    @Test
    fun `review state derives from the mapping`() {
        val none: LearnedActionMapping? = null
        assertEquals(UnknownActionReviewState.PENDING, none.reviewState())
        assertEquals(
            UnknownActionReviewState.DISMISSED,
            LearnedActionMapping("X", LearnedActionMapping.Kind.DISMISSED).reviewState()
        )
        assertEquals(
            UnknownActionReviewState.CONVERTED,
            LearnedActionMapping("X", LearnedActionMapping.Kind.ACTION, targetAction = "CHAT").reviewState()
        )
        assertEquals(
            UnknownActionReviewState.CONVERTED,
            LearnedActionMapping("X", LearnedActionMapping.Kind.MACRO, macroName = "m").reviewState()
        )
    }

    @Test
    fun `codec round trips and tolerates corrupt data`() {
        val mappings = mapOf(
            "SEND_WHATSAPP" to LearnedActionMapping(
                attemptedAction = "SEND_WHATSAPP",
                kind = LearnedActionMapping.Kind.ACTION,
                targetAction = "SEND_SMS",
                paramTemplate = mapOf("phone" to "{number}"),
                updatedAt = 42L
            )
        )
        assertEquals(mappings, LearnedActionCodec.decode(LearnedActionCodec.encode(mappings)))
        assertTrue(LearnedActionCodec.decode(null).isEmpty())
        assertTrue(LearnedActionCodec.decode("").isEmpty())
        assertTrue(LearnedActionCodec.decode("{not json").isEmpty())
    }
}
