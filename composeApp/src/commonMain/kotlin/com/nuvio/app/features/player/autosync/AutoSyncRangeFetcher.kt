package com.nuvio.app.features.player.autosync

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One HTTP GET for [EmbeddedSubtitleTimelineLoader]'s byte-range reads.
 *
 * The body is capped at the caller's byte limit however much the server sends: a server that
 * ignores `Range` answers with the whole file, and a video file is gigabytes. Implementations read
 * at most that many bytes and then drop the connection instead of downloading the rest.
 */
internal fun interface AutoSyncRangeFetcher {
    suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int,
        timeoutMs: Long,
    ): AutoSyncRangeReply?
}

internal class AutoSyncRangeReply(
    val status: Int,
    headers: Map<String, String>,
    val bytes: ByteArray,
) {
    private val headers = headers.mapKeys { (name, _) -> name.lowercase() }

    fun header(name: String): String? = headers[name.lowercase()]
}

/** The platform's range fetcher, or null where automatic subtitle sync does not run. */
internal expect val platformAutoSyncRangeFetcher: AutoSyncRangeFetcher?

/**
 * [AutoSyncRangeFetcher] over a Ktor client. The body is read straight into a buffer of at most
 * `maxBytes` and the response is released as soon as that is full, which drops the connection
 * rather than draining a body the server sent in full.
 */
internal class KtorAutoSyncRangeFetcher(
    private val client: HttpClient,
) : AutoSyncRangeFetcher {
    override suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int,
        timeoutMs: Long,
    ): AutoSyncRangeReply? = withTimeoutOrNull(timeoutMs) {
        client.prepareGet(url) {
            headers.forEach { (name, value) -> header(name, value) }
        }.execute { response ->
            AutoSyncRangeReply(
                status = response.status.value,
                headers = response.headers.entries().associate { (name, values) ->
                    name to values.joinToString(",")
                },
                bytes = readAtMost(response.bodyAsChannel(), maxBytes),
            )
        }
    }

    private suspend fun readAtMost(channel: ByteReadChannel, maxBytes: Int): ByteArray {
        val buffer = ByteArray(maxBytes.coerceAtLeast(0))
        var offset = 0
        while (offset < buffer.size) {
            val count = channel.readAvailable(buffer, offset, buffer.size - offset)
            if (count == -1) break
            offset += count
        }
        return if (offset == buffer.size) buffer else buffer.copyOf(offset)
    }
}
