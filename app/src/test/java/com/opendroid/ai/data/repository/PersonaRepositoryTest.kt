package com.opendroid.ai.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.opendroid.ai.data.models.PersonaKind
import com.opendroid.ai.data.models.PersonaStarters
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PersonaRepositoryTest {

    private fun newDataStore() = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        produceFile = {
            Files.createTempDirectory("opendroid-personas-test")
                .resolve("personas.preferences_pb")
                .toFile()
        }
    )

    private fun newRepository() = PersonaRepository(newDataStore())

    @Test
    fun `first read seeds the starter library`() = runBlocking {
        val repository = newRepository()

        val library = repository.personas.first()

        assertEquals(PersonaStarters.starters().map { it.id }, library.map { it.id })
        assertTrue(library.all { it.isBuiltIn })
    }

    @Test
    fun `create then read returns the new entry after the starters`() = runBlocking {
        val repository = newRepository()

        val created = repository.createPersona("Poet", "Reply in verse.", PersonaKind.PERSONA)
        val library = repository.personas.first()

        assertEquals(PersonaStarters.starters().size + 1, library.size)
        assertEquals(created, library.last())
    }

    @Test
    fun `update replaces name prompt and kind in place`() = runBlocking {
        val repository = newRepository()
        val created = repository.createPersona("Draft", "Old prompt.", PersonaKind.PERSONA)

        repository.updatePersona(created.id, "Final", "New prompt.", PersonaKind.QUICK_COMMAND)
        val updated = repository.personas.first().first { it.id == created.id }

        assertEquals("Final", updated.name)
        assertEquals("New prompt.", updated.prompt)
        assertEquals(PersonaKind.QUICK_COMMAND, updated.kind)
        assertEquals(created.createdAt, updated.createdAt)
    }

    @Test
    fun `duplicate adds a copy with a fresh id and copy suffix`() = runBlocking {
        val repository = newRepository()
        val created = repository.createPersona("Tutor", "Teach me.", PersonaKind.PERSONA)

        val copy = repository.duplicatePersona(created.id)
        val library = repository.personas.first()

        assertTrue(copy != null)
        assertNotEquals(created.id, copy!!.id)
        assertEquals("Tutor copy", copy.name)
        assertEquals("Teach me.", copy.prompt)
        assertTrue(library.any { it.id == copy.id })
    }

    @Test
    fun `deleting a starter persists - it does not resurrect on the next read`() = runBlocking {
        val repository = newRepository()
        val starterId = PersonaStarters.CONCISE_ID

        repository.deletePersona(starterId)
        val library = repository.personas.first()

        assertEquals(PersonaStarters.starters().size - 1, library.size)
        assertTrue(library.none { it.id == starterId })
    }

    @Test
    fun `binding a persona makes it resolvable for that session only`() = runBlocking {
        val repository = newRepository()
        val created = repository.createPersona("Poet", "Reply in verse.", PersonaKind.PERSONA)

        repository.setSessionPersona("session-1", created.id)

        assertEquals(created.id, repository.personaForSession("session-1")?.id)
        assertNull(repository.personaForSession("session-2"))
        assertEquals(mapOf("session-1" to created.id), repository.sessionBindings.first())
    }

    @Test
    fun `clearing a binding returns the session to the default`() = runBlocking {
        val repository = newRepository()
        val created = repository.createPersona("Poet", "Reply in verse.", PersonaKind.PERSONA)
        repository.setSessionPersona("session-1", created.id)

        repository.setSessionPersona("session-1", null)

        assertNull(repository.personaForSession("session-1"))
        assertTrue(repository.sessionBindings.first().isEmpty())
    }

    @Test
    fun `deleting a bound persona strips the binding so the chat falls back to default`() = runBlocking {
        val repository = newRepository()
        val created = repository.createPersona("Poet", "Reply in verse.", PersonaKind.PERSONA)
        repository.setSessionPersona("session-1", created.id)
        repository.setSessionPersona("session-2", created.id)

        repository.deletePersona(created.id)

        assertNull(repository.personaForSession("session-1"))
        assertNull(repository.personaForSession("session-2"))
        assertTrue(repository.sessionBindings.first().isEmpty())
    }

    @Test
    fun `a quick command can never be bound to a session`() = runBlocking {
        val repository = newRepository()
        val command = repository.createPersona("TL;DR", "Summarise:", PersonaKind.QUICK_COMMAND)

        repository.setSessionPersona("session-1", command.id)

        assertNull(repository.personaForSession("session-1"))
        assertTrue(repository.sessionBindings.first().isEmpty())
    }

    @Test
    fun `an unknown persona id is never persisted as a binding`() = runBlocking {
        val repository = newRepository()

        repository.setSessionPersona("session-1", "does-not-exist")

        assertNull(repository.personaForSession("session-1"))
        assertTrue(repository.sessionBindings.first().isEmpty())
    }

    @Test
    fun `create rejects a blank name and an overlong prompt`() = runBlocking {
        val repository = newRepository()

        assertThrowsIllegalArgument {
            repository.createPersona("  ", "Prompt.", PersonaKind.PERSONA)
        }
        assertThrowsIllegalArgument {
            repository.createPersona(
                "Name",
                "x".repeat(com.opendroid.ai.data.models.PersonaValidation.MAX_PROMPT_CHARS + 1),
                PersonaKind.PERSONA
            )
        }
    }

    @Test
    fun `export then import into a fresh library restores the entries`() = runBlocking {
        val source = newRepository()
        source.createPersona("Poet", "Reply in verse.", PersonaKind.PERSONA)
        val exported = source.exportJson()

        val target = newRepository()
        val added = target.importJson(exported)
        val targetLibrary = target.personas.first()

        // Every exported entry is added exactly once: id collisions with the
        // target's identical seeded starters get fresh ids, never overwrites.
        assertEquals(PersonaStarters.starters().size + 1, added)
        assertEquals(PersonaStarters.starters().size * 2 + 1, targetLibrary.size)
        assertTrue(targetLibrary.any { it.name == "Poet" && it.prompt == "Reply in verse." })
    }

    @Test
    fun `import of malformed text throws`() = runBlocking {
        val repository = newRepository()

        try {
            repository.importJson("this is not json")
            fail("Expected an exception for malformed import text")
        } catch (expected: Exception) {
            // expected
        }
        try {
            repository.importJson("""{"format":"something-else","personas":[]}""")
            fail("Expected an exception for a non-persona-library payload")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `clearAllPersonas wipes entries and bindings back to the seeded fresh state`() = runBlocking {
        val repository = newRepository()
        val created = repository.createPersona("Poet", "Reply in verse.", PersonaKind.PERSONA)
        repository.setSessionPersona("session-1", created.id)

        repository.clearAllPersonas()

        assertEquals(PersonaStarters.starters().map { it.id }, repository.personas.first().map { it.id })
        assertTrue(repository.sessionBindings.first().isEmpty())
    }

    private suspend fun assertThrowsIllegalArgument(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
