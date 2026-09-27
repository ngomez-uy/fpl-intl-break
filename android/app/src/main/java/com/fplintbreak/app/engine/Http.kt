package com.fplintbreak.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpException(val status: Int, url: String) : IOException("GET $url -> $status")

/** Fetches public JSON pages. Port of backend/src/http.ts. */
class Http {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Keep request bursts small: these are free, unofficial endpoints.
    private val permits = Semaphore(MAX_CONCURRENT)

    suspend fun getText(url: String): String = permits.withPermit { fetchWithRetry(url) }

    private suspend fun fetchWithRetry(url: String): String {
        var attempt = 1
        while (true) {
            val (code, body) = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "application/json").build()
                client.newCall(request).execute().use { res ->
                    res.code to if (res.isSuccessful) res.body?.string().orEmpty() else null
                }
            }
            if (body != null) return body
            if (attempt >= 3 || (code < 500 && code != 429)) throw HttpException(code, url)
            delay(500L * attempt)
            attempt++
        }
    }

    private companion object {
        const val MAX_CONCURRENT = 4
        // Same desktop UA the web backend uses, which is what these endpoints were tested with.
        const val UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36"
    }
}
