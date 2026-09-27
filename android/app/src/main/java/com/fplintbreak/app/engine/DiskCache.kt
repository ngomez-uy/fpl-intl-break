package com.fplintbreak.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

const val MINUTE = 60_000L
const val HOUR = 60 * MINUTE
const val DAY = 24 * HOUR

@Serializable
private data class Entry(val savedAt: Long, val ttlMs: Long?, val body: String) // ttlMs null = never expires

/**
 * Per report: the oldest cache entry each source ("fpl", "fotmob", …) served, so the report
 * can say how fresh its data is. Long-lived entries (finished matches, search results) don't
 * go stale in a way that matters, so only short-lived ones count.
 */
class DataAges {
    private val oldest = ConcurrentHashMap<String, Long>()

    fun note(key: String, savedAt: Long, ttlMs: Long?) {
        if (ttlMs == null || ttlMs > VOLATILE_TTL_MS) return
        oldest.merge(key.substringBefore('_'), savedAt, ::minOf)
    }

    operator fun get(source: String): Long? = oldest[source]

    private companion object {
        const val VOLATILE_TTL_MS = 3 * HOUR // covers the 3-hour Transfermarkt squads, not 6-hour FPL fixtures
    }
}

/** A JSON-text cache on the phone's storage. Port of backend/src/cache.ts. */
class DiskCache(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fileFor(key: String) = File(dir, key.replace(Regex("[^a-zA-Z0-9_-]"), "_") + ".json")

    suspend fun read(key: String, ages: DataAges? = null): String? = withContext(Dispatchers.IO) {
        try {
            val entry = json.decodeFromString<Entry>(fileFor(key).readText())
            if (entry.ttlMs != null && System.currentTimeMillis() - entry.savedAt > entry.ttlMs) return@withContext null
            ages?.note(key, entry.savedAt, entry.ttlMs)
            entry.body
        } catch (e: Exception) {
            null
        }
    }

    suspend fun write(key: String, body: String, ttlMs: Long?, ages: DataAges? = null) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val entry = Entry(System.currentTimeMillis(), ttlMs, body)
        fileFor(key).writeText(json.encodeToString(Entry.serializer(), entry))
        ages?.note(key, entry.savedAt, ttlMs)
    }

    /** Returns the cached body for `key`, or runs `load` and caches its result. */
    suspend fun cached(key: String, ttlMs: (String) -> Long?, ages: DataAges?, load: suspend () -> String): String {
        read(key, ages)?.let { return it }
        val body = load()
        write(key, body, ttlMs(body), ages)
        return body
    }
}
