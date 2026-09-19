package org.graphiks.kalligraphie.conformance

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.SourceEncoding
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PortableDecodingConformanceTest {
    private val version = TextVersion.create()

    @Test
    fun decodesUtf8ScalarsAndSourceRanges() {
        val snapshot = Kalligraphie.decodeUtf8(
            version,
            listOf(TextSlice.Utf8("A\u00E9\uD83D\uDE00".encodeToByteArray())),
        ).snapshot
        assertEquals(listOf(0x41, 0xE9, 0x1F600), snapshot.scalars)
        assertEquals(listOf(1, 2, 4), snapshot.sourceRanges.map { it.endExclusive.value - it.start.value })
    }

    @Test
    fun decodesUtf16SurrogatePairs() {
        val snapshot = Kalligraphie.decodeUtf16(
            version,
            listOf(TextSlice.Utf16("A\uD83D\uDE00".toCharArray())),
        ).snapshot
        assertEquals(SourceEncoding.UTF16, snapshot.sourceEncoding)
        assertEquals(listOf(0x41, 0x1F600), snapshot.scalars)
    }

    @Test
    fun reportsMalformedUtf8AsReplacementWithADiagnostic() {
        val result = Kalligraphie.decodeUtf8(
            version,
            listOf(TextSlice.Utf8(byteArrayOf(0xC3.toByte(), 0x28))),
        )
        assertEquals(listOf(0xFFFD, 0x28), result.snapshot.scalars)
        assertEquals(listOf("text.malformed-utf8"), result.diagnostics.map { it.code })
    }

    @Test
    fun rejectsASliceSeamThatSplitsOneScalar() {
        val bytes = "\u00E9".encodeToByteArray()
        assertFailsWith<IllegalArgumentException> {
            Kalligraphie.decodeUtf8(
                version,
                listOf(TextSlice.Utf8(byteArrayOf(bytes[0])), TextSlice.Utf8(byteArrayOf(bytes[1]))),
            )
        }
    }
}
