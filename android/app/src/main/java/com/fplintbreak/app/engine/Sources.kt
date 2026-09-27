package com.fplintbreak.app.engine

import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** Parses the date formats FPL and FotMob use, with or without a zone (no zone = UTC). */
internal fun parseInstant(s: String): Instant =
    try {
        OffsetDateTime.parse(s).toInstant()
    } catch (e: Exception) {
        LocalDateTime.parse(s).toInstant(ZoneOffset.UTC)
    }

internal fun parseMillis(s: String) = parseInstant(s).toEpochMilli()

/** Shared per-report plumbing for the source clients. */
internal class Fetcher(val http: Http, val cache: DiskCache, val ages: DataAges) {
    suspend fun cached(key: String, ttlMs: Long?, url: String) = cache.cached(key, { ttlMs }, ages) { http.getText(url) }

    suspend fun cached(key: String, ttlMs: (String) -> Long?, url: String) = cache.cached(key, ttlMs, ages) { http.getText(url) }
}
