package org.graphiks.kalligraphie.api

/** Portable paint-node category accepted by a paint-graph consumer. */
public enum class GlyphPaintNodeKind {
    /** A solid fill of one complete outline. */
    SOLID_OUTLINE,

    /** A vector path with a portable solid or gradient brush. */
    PATH,

    /** An affine transform over one complete child subgraph. */
    TRANSFORM,

    /** An ordered compositing group. */
    GROUP,
}

/** Gradient category a paint-graph consumer explicitly accepts. */
public enum class GlyphPaintGradientKind {
    /** A gradient interpolated along one line. */
    LINEAR,

    /** A gradient interpolated outward from one circle. */
    RADIAL,
}

/** Resource limits enforced while validating one portable paint graph. */
public data class PaintGraphLimits(
    /** Maximum graph nodes. */
    public val maxNodes: Int,
    /** Maximum node-to-node references. */
    public val maxReferences: Int,
    /** Maximum root-to-leaf reference depth. */
    public val maxDepth: Int,
    /** Maximum source bytes accepted for one paint-table route. */
    public val maxSourceBytes: Int = 1_048_576,
    /** Maximum path-bearing paint nodes accepted by this profile. */
    public val maxPaths: Int = maxNodes,
    /** Maximum gradient-bearing paint nodes accepted by this profile. */
    public val maxGradients: Int = 0,
    /** Maximum CPAL palettes decoded for this route. */
    public val maxPalettes: Int = 256,
    /** Maximum entries in every decoded CPAL palette. */
    public val maxPaletteEntries: Int = 4_096,
    /** Maximum CPAL color records decoded for this route. */
    public val maxColorRecords: Int = 65_536,
    /** Maximum COLR base-glyph records decoded before selecting one glyph. */
    public val maxBaseGlyphRecords: Int = 65_536,
    /** Maximum COLR layer records decoded before selecting one glyph. */
    public val maxLayerRecords: Int = 65_536,
    /** Maximum compressed SVG bytes read from one OpenType SVG document. */
    public val maxCompressedSvgBytes: Int = maxSourceBytes,
    /** Maximum decoded SVG bytes normalized from one OpenType SVG document. */
    public val maxDecompressedSvgBytes: Int = maxSourceBytes,
    /** Maximum nested SVG elements admitted while parsing one document. */
    public val maxSvgDepth: Int = 64,
    /** Maximum path commands normalized from one SVG glyph. */
    public val maxSvgPathCommands: Int = maxNodes * 16,
    /** Maximum gradient stops normalized from one SVG glyph. */
    public val maxSvgGradientStops: Int = 256,
) {
    init {
        require(maxNodes > 0) { "maxNodes must be positive." }
        require(maxReferences >= 0) { "maxReferences must be non-negative." }
        require(maxDepth > 0) { "maxDepth must be positive." }
        require(maxSourceBytes > 0) { "maxSourceBytes must be positive." }
        require(maxPaths >= 0) { "maxPaths must be non-negative." }
        require(maxGradients >= 0) { "maxGradients must be non-negative." }
        require(maxPalettes > 0) { "maxPalettes must be positive." }
        require(maxPaletteEntries > 0) { "maxPaletteEntries must be positive." }
        require(maxColorRecords > 0) { "maxColorRecords must be positive." }
        require(maxBaseGlyphRecords > 0) { "maxBaseGlyphRecords must be positive." }
        require(maxLayerRecords > 0) { "maxLayerRecords must be positive." }
        require(maxCompressedSvgBytes > 0) { "maxCompressedSvgBytes must be positive." }
        require(maxDecompressedSvgBytes > 0) { "maxDecompressedSvgBytes must be positive." }
        require(maxSvgDepth > 0) { "maxSvgDepth must be positive." }
        require(maxSvgPathCommands > 0) { "maxSvgPathCommands must be positive." }
        require(maxSvgGradientStops > 0) { "maxSvgGradientStops must be positive." }
    }
}

/**
 * Consumer capabilities and resource bounds for one portable paint graph.
 *
 * [accepts] is deterministic and rejects an unsupported node, composition operation, or resource
 * bound before a provider can certify the graph. The profile owns immutable snapshots of all
 * caller-supplied collections and is safe to share between concurrent operations.
 */
public class PaintGraphProfile(
    acceptedNodeKinds: List<GlyphPaintNodeKind>,
    acceptedCompositionModes: List<GlyphPaintCompositionMode>,
    /** Resource limits applied to every accepted graph. */
    public val limits: PaintGraphLimits,
    /** Bounds enforced while materializing every outline referenced by the graph. */
    public val outlineProfile: OutlineProfile,
    /** Version of the paint-graph schema accepted by the consumer. */
    override val schemaVersion: Int = 1,
    acceptedGradientKinds: List<GlyphPaintGradientKind> = emptyList(),
    acceptedGradientSpreads: List<GlyphPaintGradientSpread> = emptyList(),
) : GlyphRepresentationProfile {
    /** Immutable node categories accepted by this consumer. */
    public val acceptedNodeKinds: List<GlyphPaintNodeKind> = acceptedNodeKinds.immutableListSnapshot()
    /** Immutable composition operations accepted by this consumer. */
    public val acceptedCompositionModes: List<GlyphPaintCompositionMode> = acceptedCompositionModes.immutableListSnapshot()
    /** Immutable gradient categories accepted by this consumer. */
    public val acceptedGradientKinds: List<GlyphPaintGradientKind> = acceptedGradientKinds.immutableListSnapshot()
    /** Immutable repeat behaviors accepted for every gradient. */
    public val acceptedGradientSpreads: List<GlyphPaintGradientSpread> = acceptedGradientSpreads.immutableListSnapshot()

    init {
        require(schemaVersion > 0) { "schemaVersion must be positive." }
        require(this.acceptedNodeKinds.isNotEmpty()) { "At least one paint node kind must be accepted." }
        require(this.acceptedNodeKinds.distinct().size == this.acceptedNodeKinds.size) { "Paint node kinds must not repeat." }
        require(this.acceptedCompositionModes.distinct().size == this.acceptedCompositionModes.size) {
            "Paint composition modes must not repeat."
        }
        require(this.acceptedGradientKinds.distinct().size == this.acceptedGradientKinds.size) {
            "Paint gradient kinds must not repeat."
        }
        require(this.acceptedGradientSpreads.distinct().size == this.acceptedGradientSpreads.size) {
            "Paint gradient spreads must not repeat."
        }
    }

    /** Returns whether [paint] is completely supported within this profile's declared bounds. */
    public fun accepts(paint: GlyphPaintIR): Boolean {
        if (paint.schemaVersion != schemaVersion || paint.nodes.size > limits.maxNodes) return false
        val references = paint.nodes.sumOf { node -> node.children.size }
        if (references > limits.maxReferences) return false
        val paths = paint.nodes.count { node -> node is GlyphPaintNode.SolidOutline || node is GlyphPaintNode.Path }
        if (paths > limits.maxPaths) return false
        val gradients = paint.nodes.mapNotNull { node -> (node as? GlyphPaintNode.Path)?.brush }.filter { it !is GlyphPaintBrush.Solid }
        if (gradients.size > limits.maxGradients) return false
        if (gradients.any { gradient -> gradient.kind() !in acceptedGradientKinds || gradient.spread() !in acceptedGradientSpreads }) {
            return false
        }
        if (paint.nodes.any { node -> node.kind() !in acceptedNodeKinds }) return false
        if (paint.nodes.filterIsInstance<GlyphPaintNode.Group>().any { group -> group.compositionMode !in acceptedCompositionModes }) {
            return false
        }
        return paint.depthFromRoot() <= limits.maxDepth
    }

    override fun equals(other: Any?): Boolean =
        other is PaintGraphProfile &&
            acceptedNodeKinds == other.acceptedNodeKinds &&
            acceptedCompositionModes == other.acceptedCompositionModes &&
            acceptedGradientKinds == other.acceptedGradientKinds &&
            acceptedGradientSpreads == other.acceptedGradientSpreads &&
            limits == other.limits &&
            outlineProfile == other.outlineProfile &&
            schemaVersion == other.schemaVersion

    override fun hashCode(): Int {
        var result = acceptedNodeKinds.hashCode()
        result = 31 * result + acceptedCompositionModes.hashCode()
        result = 31 * result + acceptedGradientKinds.hashCode()
        result = 31 * result + acceptedGradientSpreads.hashCode()
        result = 31 * result + limits.hashCode()
        result = 31 * result + outlineProfile.hashCode()
        return 31 * result + schemaVersion
    }
}

private fun GlyphPaintNode.kind(): GlyphPaintNodeKind = when (this) {
    is GlyphPaintNode.SolidOutline -> GlyphPaintNodeKind.SOLID_OUTLINE
    is GlyphPaintNode.Path -> GlyphPaintNodeKind.PATH
    is GlyphPaintNode.Transform -> GlyphPaintNodeKind.TRANSFORM
    is GlyphPaintNode.Group -> GlyphPaintNodeKind.GROUP
}

private fun GlyphPaintBrush.kind(): GlyphPaintGradientKind = when (this) {
    is GlyphPaintBrush.LinearGradient -> GlyphPaintGradientKind.LINEAR
    is GlyphPaintBrush.RadialGradient -> GlyphPaintGradientKind.RADIAL
    is GlyphPaintBrush.Solid -> error("A solid brush has no gradient kind.")
}

private fun GlyphPaintBrush.spread(): GlyphPaintGradientSpread = when (this) {
    is GlyphPaintBrush.LinearGradient -> spread
    is GlyphPaintBrush.RadialGradient -> spread
    is GlyphPaintBrush.Solid -> error("A solid brush has no gradient spread.")
}

private fun GlyphPaintIR.depthFromRoot(): Int {
    fun depth(index: Int): Int = 1 + (nodes[index].children.maxOfOrNull(::depth) ?: 0)
    return depth(rootNode)
}
