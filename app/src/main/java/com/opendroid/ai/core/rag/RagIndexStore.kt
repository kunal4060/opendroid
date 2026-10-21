package com.opendroid.ai.core.rag

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence for the RAG index (feature D4): one JSON document at
 * `filesDir/rag/index.json` holding every [RagDocument] and [RagChunk].
 *
 * A file (not Room) is deliberate: the feature ships without a schema
 * migration, and at personal scale the whole index parses in milliseconds.
 * Writes are atomic (temp file + rename) and serialised through a mutex;
 * the in-memory [StateFlow] is the read model the UI and retrieval share.
 * A corrupt file degrades to an empty index rather than crashing — the
 * next mutation rewrites it cleanly (same posture as the persona store).
 */
@Singleton
class RagIndexStore internal constructor(
    private val indexDir: File
) {
    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(File(context.filesDir, RAG_DIR))

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val _index = MutableStateFlow(RagIndex())
    val index: StateFlow<RagIndex> = _index.asStateFlow()

    @Volatile
    private var loaded = false

    private val indexFile: File get() = File(indexDir, INDEX_FILE)

    /** Loads the persisted index on first use; a no-op afterwards. */
    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            _index.value = readFromDisk()
            loaded = true
        }
    }

    /** Applies [transform] to the index and persists the result atomically. */
    suspend fun mutate(transform: (RagIndex) -> RagIndex): RagIndex {
        ensureLoaded()
        return mutex.withLock {
            val updated = transform(_index.value)
            writeToDisk(updated)
            _index.value = updated
            updated
        }
    }

    /** Erases the whole knowledge base (the A6 forget-everything wipe). */
    suspend fun clearAll() {
        ensureLoaded()
        mutex.withLock {
            writeToDisk(RagIndex())
            _index.value = RagIndex()
        }
    }

    private fun readFromDisk(): RagIndex {
        val file = indexFile
        if (!file.exists()) return RagIndex()
        return try {
            json.decodeFromString<RagIndex>(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            RagIndex()
        }
    }

    private suspend fun writeToDisk(value: RagIndex) = withContext(Dispatchers.IO) {
        indexDir.mkdirs()
        val tmp = File(indexDir, "$INDEX_FILE.tmp")
        tmp.writeText(json.encodeToString(value), Charsets.UTF_8)
        if (!tmp.renameTo(indexFile)) {
            // Rename can fail across filesystems; fall back to a direct write.
            indexFile.writeText(json.encodeToString(value), Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        const val RAG_DIR = "rag"
        const val INDEX_FILE = "index.json"
    }
}
