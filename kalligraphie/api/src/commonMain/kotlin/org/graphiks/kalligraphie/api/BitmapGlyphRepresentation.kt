package org.graphiks.kalligraphie.api

/** Color space assigned to decoded glyph pixels. */
public enum class GlyphColorSpace {
    /** Standard red, green, blue display color space. */
    SRGB,
}

/** Pixel layout used by a decoded bitmap glyph. */
public enum class BitmapPixelFormat(
    /** Number of bytes occupied by each decoded pixel. */
    public val bytesPerPixel: Int,
) {
    /** One eight-bit alpha sample per pixel. */
    ALPHA_8(1),
}

/**
 * Exact raster strike selected from a bitmap font.
 *
 * Values are pixels per em in each font-axis direction. A strike is part of a bitmap
 * representation identity and must not be silently substituted for another strike.
 */
public data class BitmapStrike(
    /** Horizontal pixels per em. */
    public val pixelsPerEmX: Int,
    /** Vertical pixels per em. */
    public val pixelsPerEmY: Int,
) {
    init {
        require(pixelsPerEmX > 0) { "pixelsPerEmX must be positive." }
        require(pixelsPerEmY > 0) { "pixelsPerEmY must be positive." }
    }
}

/** Resource limits enforced before decoding one embedded bitmap strike. */
public data class BitmapLimits(
    /** Maximum strikes inspected while selecting the exact requested strike. */
    public val maxStrikes: Int,
    /** Maximum decoded bitmap width in pixels. */
    public val maxWidth: Int,
    /** Maximum decoded bitmap height in pixels. */
    public val maxHeight: Int,
    /** Maximum decoded pixels in one glyph. */
    public val maxPixels: Int,
    /** Maximum compressed source bytes read for one bitmap glyph. */
    public val maxCompressedBytes: Int,
    /** Maximum decoded pixel bytes retained for one bitmap glyph. */
    public val maxDecodedBytes: Int,
) {
    init {
        require(maxStrikes > 0) { "maxStrikes must be positive." }
        require(maxWidth > 0) { "maxWidth must be positive." }
        require(maxHeight > 0) { "maxHeight must be positive." }
        require(maxPixels > 0) { "maxPixels must be positive." }
        require(maxCompressedBytes > 0) { "maxCompressedBytes must be positive." }
        require(maxDecodedBytes > 0) { "maxDecodedBytes must be positive." }
    }
}

/**
 * Exact bitmap-strike and pixel capabilities accepted by a portable consumer.
 *
 * A provider may select only [strike], never a nearby strike chosen implicitly by device scale or
 * timing. It must reject a table, codec, or bitmap that exceeds [limits] before it returns a
 * certificate. The profile is immutable and contains no renderer, texture, or native object.
 */
public class BitmapProfile(
    /** Exact bitmap strike requested by the consumer. */
    public val strike: BitmapStrike,
    acceptedPixelFormats: List<BitmapPixelFormat>,
    acceptedColorSpaces: List<GlyphColorSpace>,
    /** Resource bounds applied to selected bitmap records. */
    public val limits: BitmapLimits,
    /** Version of the bitmap representation schema accepted by the consumer. */
    override val schemaVersion: Int = 1,
) : GlyphRepresentationProfile {
    /** Immutable pixel formats accepted by the consumer. */
    public val acceptedPixelFormats: List<BitmapPixelFormat> = acceptedPixelFormats.immutableListSnapshot()
    /** Immutable color spaces accepted by the consumer. */
    public val acceptedColorSpaces: List<GlyphColorSpace> = acceptedColorSpaces.immutableListSnapshot()

    init {
        require(schemaVersion > 0) { "schemaVersion must be positive." }
        require(this.acceptedPixelFormats.isNotEmpty()) { "At least one bitmap pixel format must be accepted." }
        require(this.acceptedColorSpaces.isNotEmpty()) { "At least one bitmap color space must be accepted." }
        require(this.acceptedPixelFormats.distinct().size == this.acceptedPixelFormats.size) { "Bitmap pixel formats must not repeat." }
        require(this.acceptedColorSpaces.distinct().size == this.acceptedColorSpaces.size) { "Bitmap color spaces must not repeat." }
    }

    override fun equals(other: Any?): Boolean =
        other is BitmapProfile &&
            strike == other.strike &&
            acceptedPixelFormats == other.acceptedPixelFormats &&
            acceptedColorSpaces == other.acceptedColorSpaces &&
            limits == other.limits &&
            schemaVersion == other.schemaVersion

    override fun hashCode(): Int {
        var result = strike.hashCode()
        result = 31 * result + acceptedPixelFormats.hashCode()
        result = 31 * result + acceptedColorSpaces.hashCode()
        result = 31 * result + limits.hashCode()
        return 31 * result + schemaVersion
    }
}

/**
 * Bitmap glyph placement metrics in the coordinate system declared by its source strike.
 *
 * These values are normalized by the decoder and contain no renderer-specific hinting,
 * subpixel placement, antialiasing, or atlas parameters.
 */
public data class BitmapGlyphMetrics(
    /** Horizontal advance in bitmap-strike pixels. */
    public val advanceX: Int,
    /** Vertical advance in bitmap-strike pixels. */
    public val advanceY: Int,
)

/**
 * Immutable, decoded bitmap representation of one glyph.
 *
 * The constructor takes ownership by copying [decodedPixels]. [copyDecodedPixels] gives callers
 * a fresh copy, so no mutable pixel buffer is shared across assets, renderers, or threads.
 */
public class BitmapGlyphIR(
    /** Glyph materialized by this bitmap. */
    public val glyphId: GlyphId,
    /** Exact source strike selected during materialization. */
    public val strike: BitmapStrike,
    /** Decoded image width in pixels. */
    public val width: Int,
    /** Decoded image height in pixels. */
    public val height: Int,
    /** Horizontal origin relative to the glyph origin, in pixels. */
    public val originX: Int,
    /** Vertical origin relative to the glyph origin, in pixels. */
    public val originY: Int,
    /** Normalized advance metrics of the selected strike. */
    public val metrics: BitmapGlyphMetrics,
    /** Decoded pixel format. */
    public val pixelFormat: BitmapPixelFormat,
    /** Color space of the decoded pixels. */
    public val colorSpace: GlyphColorSpace,
    decodedPixels: ByteArray,
) {
    private val capturedPixels: ByteArray = decodedPixels.copyOf()

    init {
        require(width >= 0) { "width must be non-negative." }
        require(height >= 0) { "height must be non-negative." }
        val expectedByteCount = width.toLong() * height.toLong() * pixelFormat.bytesPerPixel.toLong()
        require(expectedByteCount <= Int.MAX_VALUE && capturedPixels.size == expectedByteCount.toInt()) {
            "Decoded bitmap pixel count does not match its dimensions and format."
        }
    }

    /** Number of decoded pixel bytes retained by this immutable representation. */
    public val decodedByteCount: Int
        get() = capturedPixels.size

    /** Returns a caller-owned copy of the decoded pixels in [pixelFormat]. */
    public fun copyDecodedPixels(): ByteArray = capturedPixels.copyOf()

    override fun equals(other: Any?): Boolean =
        other is BitmapGlyphIR &&
            glyphId == other.glyphId &&
            strike == other.strike &&
            width == other.width &&
            height == other.height &&
            originX == other.originX &&
            originY == other.originY &&
            metrics == other.metrics &&
            pixelFormat == other.pixelFormat &&
            colorSpace == other.colorSpace &&
            capturedPixels.contentEquals(other.capturedPixels)

    override fun hashCode(): Int {
        var result = glyphId.hashCode()
        result = 31 * result + strike.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + originX
        result = 31 * result + originY
        result = 31 * result + metrics.hashCode()
        result = 31 * result + pixelFormat.hashCode()
        result = 31 * result + colorSpace.hashCode()
        result = 31 * result + capturedPixels.contentHashCode()
        return result
    }

    override fun toString(): String =
        "BitmapGlyphIR(glyphId=$glyphId, strike=$strike, width=$width, height=$height, " +
            "originX=$originX, originY=$originY, metrics=$metrics, pixelFormat=$pixelFormat, " +
            "colorSpace=$colorSpace, decodedByteCount=${capturedPixels.size})"
}
