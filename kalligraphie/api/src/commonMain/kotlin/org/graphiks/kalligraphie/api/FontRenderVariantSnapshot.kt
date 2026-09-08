package org.graphiks.kalligraphie.api

/**
 * Immutable palette selected from a CPAL table.
 *
 * Colors are stored in CPAL entry order and copied into an immutable list, so a palette remains
 * stable after its catalog, resolver, or caller-owned source collection has gone away.
 */
public class CpalPaletteSnapshot(
    /** Zero-based palette index in the source CPAL table. */
    public val index: Int,
    colors: List<GlyphColor>,
) {
    /** Palette colors in their declared CPAL order. */
    public val colors: List<GlyphColor> = colors.immutableListSnapshot()

    init {
        require(index >= 0) { "CPAL palette index must be non-negative." }
        require(this.colors.isNotEmpty()) { "CPAL palette must contain at least one color." }
    }

    override fun equals(other: Any?): Boolean = other is CpalPaletteSnapshot && index == other.index && colors == other.colors

    override fun hashCode(): Int = 31 * index + colors.hashCode()

    override fun toString(): String = "CpalPaletteSnapshot(index=$index, colors=$colors)"
}

/**
 * Geometry-neutral visual selection used while materializing a font asset.
 *
 * The snapshot selects one CPAL palette and an exact foreground color without adding renderer
 * effects such as stroke, shadow, hinting, or device transforms. Its [key] participates in
 * glyph-representation and certificate identity but never in shaping or text geometry.
 */
public data class FontRenderVariantSnapshot(
    /** Zero-based CPAL palette index selected for a color glyph, or `null` for the font default. */
    public val cpalPaletteIndex: Int? = null,
    /** Exact foreground color used where an OpenType paint route requests it, or `null` when absent. */
    public val foregroundColor: GlyphColor? = null,
) {
    init {
        require(cpalPaletteIndex == null || cpalPaletteIndex >= 0) { "CPAL palette index must be non-negative." }
    }

    /** Canonical key for this geometry-neutral selection. */
    public val key: FontRenderVariantKey =
        if (cpalPaletteIndex == null && foregroundColor == null) {
            FontRenderVariantKey.default
        } else {
            FontRenderVariantKey(
                buildString {
                    append("cpal:")
                    append(cpalPaletteIndex ?: "default")
                    append(";foreground:")
                    foregroundColor?.let { color -> append("${color.red},${color.green},${color.blue},${color.alpha}") } ?: append("none")
                },
            )
        }

    /** Default visual selection with no explicit palette or foreground color. */
    public companion object {
        /** Standard geometry-neutral default variant. */
        public val default: FontRenderVariantSnapshot = FontRenderVariantSnapshot()
    }
}
