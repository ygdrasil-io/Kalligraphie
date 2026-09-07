package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ColrCpalReaderTest {
    @Test
    fun decodesVersionZeroLayersAndTheExactlySelectedPalette() {
        val result = ColrCpalReader.read(colrVersionZeroTable(), cpalVersionZeroTable())

        val data = assertIs<FontOperationResult.Success<ColrCpalV0Data>>(result).value
        assertEquals(
            listOf(GlyphColor(0, 0, 255), GlyphColor(255, 255, 0)),
            data.palette(1),
        )
        assertEquals(
            listOf(
                ColrV0Layer(GlyphId(4), paletteIndex = 0),
                ColrV0Layer(GlyphId(5), paletteIndex = 1),
            ),
            data.layersFor(GlyphId(7)),
        )
    }

    @Test
    fun rejectsEveryLayerThatReferencesAnUnavailablePaletteEntryBeforeSelection() {
        val colr = colrVersionZeroTable().also { bytes -> bytes.writeUInt16(26, 2) }

        val result = ColrCpalReader.read(colr, cpalVersionZeroTable())

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.colr.invalid-palette-index", failure.error.code)
    }
}

private fun cpalVersionZeroTable(): ByteArray = ByteArray(32).also { bytes ->
    bytes.writeUInt16(0, 0)
    bytes.writeUInt16(2, 2)
    bytes.writeUInt16(4, 2)
    bytes.writeUInt16(6, 4)
    bytes.writeUInt32(8, 16)
    bytes.writeUInt16(12, 0)
    bytes.writeUInt16(14, 2)

    bytes.writeBgra(16, red = 255, green = 0, blue = 0)
    bytes.writeBgra(20, red = 0, green = 255, blue = 0)
    bytes.writeBgra(24, red = 0, green = 0, blue = 255)
    bytes.writeBgra(28, red = 255, green = 255, blue = 0)
}

private fun colrVersionZeroTable(): ByteArray = ByteArray(28).also { bytes ->
    bytes.writeUInt16(0, 0)
    bytes.writeUInt16(2, 1)
    bytes.writeUInt32(4, 14)
    bytes.writeUInt32(8, 20)
    bytes.writeUInt16(12, 2)

    bytes.writeUInt16(14, 7)
    bytes.writeUInt16(16, 0)
    bytes.writeUInt16(18, 2)

    bytes.writeUInt16(20, 4)
    bytes.writeUInt16(22, 0)
    bytes.writeUInt16(24, 5)
    bytes.writeUInt16(26, 1)
}

private fun ByteArray.writeBgra(offset: Int, red: Int, green: Int, blue: Int, alpha: Int = 255) {
    this[offset] = blue.toByte()
    this[offset + 1] = green.toByte()
    this[offset + 2] = red.toByte()
    this[offset + 3] = alpha.toByte()
}

private fun ByteArray.writeUInt16(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

private fun ByteArray.writeUInt32(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}
