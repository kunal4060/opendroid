package com.opendroid.ai.core.rag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RagIndexStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun document(id: String, name: String, hash: String) = RagDocument(
        id = id,
        sourceUri = "content://docs/$id",
        displayName = name,
        mimeType = "text/plain",
        sizeBytes = 42L,
        contentHash = hash,
        status = RagDocumentStatus.READY,
        chunkCount = 1,
        indexedAt = 1234L
    )

    private fun chunk(docId: String) = RagChunk(
        id = "$docId:0",
        documentId = docId,
        documentName = "Facts.txt",
        chunkIndex = 0,
        pageRef = 2,
        text = "The Zorblaxian embassy keeps its ceremonial spoons in vault K-9.",
        tokenCount = 12,
        embedding = VectorMath.encode(floatArrayOf(0.25f, 0.75f)),
        embeddingModelId = "OpenAI|text-embedding-3-small"
    )

    @Test
    fun `index survives a reload from disk`() = runBlocking {
        val dir = tempFolder.newFolder("rag")
        val store = RagIndexStore(dir)
        store.mutate {
            RagIndex(
                documents = listOf(document("d1", "Facts.txt", "abc")),
                chunks = listOf(chunk("d1"))
            )
        }

        val reloaded = RagIndexStore(dir)
        reloaded.ensureLoaded()
        val index = reloaded.index.value
        assertEquals(1, index.documents.size)
        assertEquals("abc", index.documents[0].contentHash)
        assertEquals(1, index.chunks.size)
        assertEquals(2, index.chunks[0].pageRef)
        assertEquals(
            floatArrayOf(0.25f, 0.75f).toList(),
            VectorMath.decode(index.chunks[0].embedding!!).toList()
        )
    }

    @Test
    fun `deleting a document removes its chunks from the persisted index`() = runBlocking {
        val dir = tempFolder.newFolder("rag")
        val store = RagIndexStore(dir)
        store.mutate {
            RagIndex(
                documents = listOf(document("d1", "A.txt", "a"), document("d2", "B.txt", "b")),
                chunks = listOf(chunk("d1"), chunk("d2"))
            )
        }
        store.mutate { index ->
            index.copy(
                documents = index.documents.filterNot { it.id == "d1" },
                chunks = index.chunks.filterNot { it.documentId == "d1" }
            )
        }
        val reloaded = RagIndexStore(dir)
        reloaded.ensureLoaded()
        assertEquals(listOf("d2"), reloaded.index.value.documents.map { it.id })
        assertEquals(listOf("d2"), reloaded.index.value.chunks.map { it.documentId })
    }

    @Test
    fun `clearAll empties the store`() = runBlocking {
        val dir = tempFolder.newFolder("rag")
        val store = RagIndexStore(dir)
        store.mutate { RagIndex(documents = listOf(document("d1", "A.txt", "a"))) }
        store.clearAll()
        assertTrue(store.index.value.documents.isEmpty())
        val reloaded = RagIndexStore(dir)
        reloaded.ensureLoaded()
        assertTrue(reloaded.index.value.documents.isEmpty())
    }

    @Test
    fun `corrupt index file degrades to an empty index`() = runBlocking {
        val dir = tempFolder.newFolder("rag")
        dir.resolve("index.json").writeText("{ this is not json")
        val store = RagIndexStore(dir)
        store.ensureLoaded()
        assertTrue(store.index.value.documents.isEmpty())
    }
}
