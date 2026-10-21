package com.opendroid.ai.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PersonaPromptComposerTest {

    @Test
    fun `null persona returns the base prompt untouched`() {
        val base = "You are OpenDroid."
        assertEquals(base, PersonaPromptComposer.compose(null, base))
    }

    @Test
    fun `blank persona returns the base prompt untouched`() {
        val base = "You are OpenDroid."
        assertEquals(base, PersonaPromptComposer.compose("   ", base))
    }

    @Test
    fun `persona is prepended before the base prompt with a blank line between`() {
        val composed = PersonaPromptComposer.compose("Answer in one line.", "Base rules.")
        assertEquals("Answer in one line.\n\nBase rules.", composed)
    }

    @Test
    fun `persona text is trimmed before composing`() {
        val composed = PersonaPromptComposer.compose("  Be terse.  ", "Base.")
        assertEquals("Be terse.\n\nBase.", composed)
    }
}

class PersonaValidationTest {

    @Test
    fun `name is trimmed and capped`() {
        assertEquals("Tutor", PersonaValidation.normalizedName("  Tutor  "))
        val longName = "x".repeat(PersonaValidation.MAX_NAME_CHARS + 20)
        assertEquals(PersonaValidation.MAX_NAME_CHARS, PersonaValidation.normalizedName(longName).length)
    }

    @Test
    fun `blank name is rejected`() {
        assertThrowsIllegalArgument { PersonaValidation.normalizedName("   ") }
    }

    @Test
    fun `blank prompt is rejected`() {
        assertThrowsIllegalArgument { PersonaValidation.normalizedPrompt("") }
    }

    @Test
    fun `prompt at the cap is accepted and one char over is rejected`() {
        val atCap = "x".repeat(PersonaValidation.MAX_PROMPT_CHARS)
        assertEquals(atCap, PersonaValidation.normalizedPrompt(atCap))
        assertThrowsIllegalArgument {
            PersonaValidation.normalizedPrompt("x".repeat(PersonaValidation.MAX_PROMPT_CHARS + 1))
        }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}

class MergeImportedPersonasTest {

    private fun persona(
        id: String,
        name: String = "Persona $id",
        prompt: String = "Prompt $id",
        kind: PersonaKind = PersonaKind.PERSONA
    ) = Persona(id = id, name = name, prompt = prompt, kind = kind)

    @Test
    fun `imported entries are appended after existing ones`() {
        val existing = listOf(persona("a"))
        val merged = mergeImportedPersonas(existing, listOf(persona("b"), persona("c")))
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
    }

    @Test
    fun `id collision gets a fresh id so nothing is overwritten`() {
        val existing = listOf(persona("a", name = "Mine"))
        var counter = 0
        val merged = mergeImportedPersonas(
            existing,
            listOf(persona("a", name = "Theirs")),
            idFactory = { "fresh-${counter++}" },
            now = 100L
        )
        assertEquals(2, merged.size)
        assertEquals("Mine", merged[0].name)
        assertEquals("fresh-0", merged[1].id)
        assertEquals("Theirs", merged[1].name)
    }

    @Test
    fun `two imported entries sharing an id do not collide with each other`() {
        var counter = 0
        val merged = mergeImportedPersonas(
            emptyList(),
            listOf(persona("dup"), persona("dup")),
            idFactory = { "fresh-${counter++}" },
            now = 100L
        )
        assertEquals(2, merged.size)
        assertEquals(setOf("dup", "fresh-0"), merged.map { it.id }.toSet())
    }

    @Test
    fun `entries failing validation are skipped`() {
        val merged = mergeImportedPersonas(
            emptyList(),
            listOf(
                persona("ok"),
                persona("blank-name", name = "   "),
                persona("blank-prompt", prompt = " "),
                persona("too-long", prompt = "x".repeat(PersonaValidation.MAX_PROMPT_CHARS + 1))
            )
        )
        assertEquals(listOf("ok"), merged.map { it.id })
    }

    @Test
    fun `imported entries are never marked built in and keep their kind`() {
        val imported = persona("x", kind = PersonaKind.QUICK_COMMAND).copy(isBuiltIn = true)
        val merged = mergeImportedPersonas(emptyList(), listOf(imported), now = 42L)
        assertFalse(merged.single().isBuiltIn)
        assertEquals(PersonaKind.QUICK_COMMAND, merged.single().kind)
        assertEquals(42L, merged.single().updatedAt)
    }

    @Test
    fun `starters seed has three personas and two quick commands with fixed ids`() {
        val starters = PersonaStarters.starters()
        assertEquals(5, starters.size)
        assertEquals(3, starters.count { it.kind == PersonaKind.PERSONA })
        assertEquals(2, starters.count { it.kind == PersonaKind.QUICK_COMMAND })
        assertTrue(starters.all { it.isBuiltIn })
        assertEquals(starters.size, starters.map { it.id }.toSet().size)
        assertTrue(starters.all { it.prompt.isNotBlank() && it.name.isNotBlank() })
    }
}
