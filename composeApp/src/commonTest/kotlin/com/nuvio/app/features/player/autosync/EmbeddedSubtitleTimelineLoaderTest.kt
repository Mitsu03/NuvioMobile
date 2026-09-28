package com.nuvio.app.features.player.autosync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Exercises [EmbeddedSubtitleTimelineLoader] end-to-end against a hand-built, minimal Matroska
 * byte stream served through an [AutoSyncRangeFetcher] that answers Range requests the way a
 * streaming server does (206 with Content-Range, or 200 with the whole body when there is none). There's no real MKV fixture file; the bytes are
 * synthesized directly from the EBML IDs the loader itself parses (Segment/Tracks/Cues), which
 * keeps this test self-contained and avoids depending on ffmpeg or a checked-in binary fixture.
 */
class EmbeddedSubtitleTimelineLoaderTest {

    @Test
    fun loadsSubtitleCueTimelineFromRangedMatroskaCues() = runBlocking {
        val cueCount = 12
        val cueSpacingMs = 4000L
        val mkvBytes = MatroskaFixture.build(
            trackNumber = 1,
            language = "eng",
            cueStartTimesMs = List(cueCount) { it * cueSpacingMs },
        )

        val timeline = EmbeddedSubtitleTimelineLoader.load(
            sourceUrl = "https://example.test/well-formed.mkv",
            rangeFetcher = InMemoryRangeFetcher(mkvBytes),
        )

        val loaded = assertNotNull(timeline, "loader returned null for a well-formed fixture")
        assertEquals("matroska-cues", loaded.source)
        assertEquals(1, loaded.tracks.size)

        val track = loaded.tracks.single()
        assertEquals("eng", track.language)
        assertEquals(cueCount, track.cues.size)
        assertEquals(0L, track.cues.first().startTimeMs)
        assertEquals((cueCount - 1) * cueSpacingMs, track.cues.last().startTimeMs)
    }

    @Test
    fun returnsNullWhenSubtitleTimelineIsTooShortToTrust() = runBlocking {
        // Below MIN_INDEXED_CUES (8): AutoSync must not treat a sparse index as a usable reference.
        val mkvBytes = MatroskaFixture.build(
            trackNumber = 1,
            language = "eng",
            cueStartTimesMs = listOf(0L, 4000L, 8000L),
        )

        val timeline = EmbeddedSubtitleTimelineLoader.load(
            sourceUrl = "https://example.test/too-short.mkv",
            rangeFetcher = InMemoryRangeFetcher(mkvBytes),
        )
        assertNull(timeline, "a 3-cue index should be rejected as unusably short")
    }

    @Test
    fun returnsNullForNonMatroskaBytes() = runBlocking {
        val timeline = EmbeddedSubtitleTimelineLoader.load(
            sourceUrl = "https://example.test/not-matroska.mkv",
            rangeFetcher = InMemoryRangeFetcher(ByteArray(4096) { 0x00 }),
        )
        assertNull(timeline, "arbitrary non-EBML bytes must not be mistaken for a container")
    }

    @Test
    fun neverAsksForMoreThanItRequestedWhenTheServerIgnoresRange() = runBlocking {
        val mkvBytes = MatroskaFixture.build(
            trackNumber = 1,
            language = "eng",
            cueStartTimesMs = List(12) { it * 4000L },
        )
        val fetcher = InMemoryRangeFetcher(mkvBytes, honorsRange = false)

        EmbeddedSubtitleTimelineLoader.load(
            sourceUrl = "https://example.test/ignores-range.mkv",
            rangeFetcher = fetcher,
        )

        assertTrue(fetcher.requestedCaps.isNotEmpty())
        fetcher.requestedCaps.forEach { cap -> assertTrue(cap <= 512 * 1024, "cap $cap exceeds the probe size") }
    }
}

/** Serves [bytes] the way a streaming server answers byte-Range requests. */
internal class InMemoryRangeFetcher(
    private val bytes: ByteArray,
    private val honorsRange: Boolean = true,
) : AutoSyncRangeFetcher {
    val requestedCaps = mutableListOf<Int>()

    override suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int,
        timeoutMs: Long,
    ): AutoSyncRangeReply {
        requestedCaps += maxBytes
        val match = headers["Range"]?.takeIf { honorsRange }?.let { RANGE_PATTERN.find(it) }
        if (match == null) {
            return AutoSyncRangeReply(
                status = 200,
                headers = mapOf("Content-Length" to bytes.size.toString()),
                bytes = bytes.copyOf(minOf(maxBytes, bytes.size)),
            )
        }
        val start = match.groupValues[1].toInt().coerceIn(0, bytes.size)
        val end = match.groupValues[2].toInt().coerceIn(start, bytes.size - 1)
        val slice = bytes.copyOfRange(start, end + 1)
        return AutoSyncRangeReply(
            status = 206,
            headers = mapOf("Content-Range" to "bytes $start-$end/${bytes.size}"),
            bytes = slice.copyOf(minOf(maxBytes, slice.size)),
        )
    }

    private companion object {
        val RANGE_PATTERN = Regex("""bytes=(\d+)-(\d+)""")
    }
}

/** Hand-rolled minimal EBML/Matroska byte builder covering only what the loader reads. */
internal object MatroskaFixture {
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_CUES = 0x1C53BB6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_LANGUAGE = 0x22B59CL
    private const val ID_CUE_POINT = 0xBBL
    private const val ID_CUE_TIME = 0xB3L
    private const val ID_CUE_TRACK_POSITIONS = 0xB7L
    private const val ID_CUE_TRACK = 0xF7L
    private const val TRACK_TYPE_SUBTITLE = 17L

    fun build(trackNumber: Int, language: String, cueStartTimesMs: List<Long>): ByteArray {
        val tracks = element(
            ID_TRACKS,
            element(
                ID_TRACK_ENTRY,
                element(ID_TRACK_NUMBER, uint(trackNumber.toLong())) +
                    element(ID_TRACK_TYPE, uint(TRACK_TYPE_SUBTITLE)) +
                    element(ID_LANGUAGE, language.encodeToByteArray()),
            ),
        )

        val cues = element(
            ID_CUES,
            cueStartTimesMs.fold(ByteArray(0)) { out, startMs ->
                out + element(
                    ID_CUE_POINT,
                    element(ID_CUE_TIME, uint(startMs)) +
                        element(
                            ID_CUE_TRACK_POSITIONS,
                            element(ID_CUE_TRACK, uint(trackNumber.toLong())),
                        ),
                )
            },
        )

        return element(ID_SEGMENT, tracks + cues)
    }

    /** [id]'s own bit-length already encodes its canonical byte width (standard Matroska IDs). */
    private fun element(id: Long, payload: ByteArray): ByteArray =
        idBytes(id) + sizeVint(payload.size.toLong()) + payload

    private fun idBytes(id: Long): ByteArray {
        val length = when {
            id > 0xFFFFFFL -> 4
            id > 0xFFFFL -> 3
            id > 0xFFL -> 2
            else -> 1
        }
        return ByteArray(length) { index ->
            ((id shr (8 * (length - 1 - index))) and 0xFF).toByte()
        }
    }

    /** Mirrors [EmbeddedSubtitleTimelineLoader]'s VINT decode exactly, in reverse. */
    private fun sizeVint(value: Long): ByteArray {
        var length = 1
        while (length < 8 && value > (1L shl (7 * length)) - 2) length++
        val bytes = ByteArray(length)
        var remaining = value
        for (index in length - 1 downTo 1) {
            bytes[index] = (remaining and 0xFF).toByte()
            remaining = remaining ushr 8
        }
        val marker = 1 shl (8 - length)
        bytes[0] = ((remaining.toInt() and (marker - 1)) or marker).toByte()
        return bytes
    }

    /**
     * Fixed-width (4-byte) big-endian unsigned integer -- big enough for ~49 days of milliseconds,
     * so real-length movie timelines never silently wrap around a narrower fixed width.
     */
    private fun uint(value: Long): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )
}
