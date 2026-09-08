package org.graphiks.kalligraphie.api

/** Portable paint-node category accepted by a paint-graph consumer. */
public enum class GlyphPaintNodeKind {
    /** A solid fill of one complete outline. */
    SOLID_OUTLINE,

    /** A solid fill of one portable path that can contain cubic Bézier segments. */
    PATH,

    /** An ordered compositing group. */
    GROUP,
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
    /** Maximum bytes retained by expanded CPAL palettes after shared source records are resolved. */
    public val maxDecodedPaletteBytes: Int = maxColorRecords.coerceAtMost(Int.MAX_VALUE / 4) * 4,
    /** Maximum COLR base-glyph records decoded before selecting one glyph. */
    public val maxBaseGlyphRecords: Int = 65_536,
    /** Maximum COLR layer records decoded before selecting one glyph. */
    public val maxLayerRecords: Int = 65_536,
    /** Maximum SVG-in-OpenType document records decoded for one paint route. */
    public val maxSvgDocuments: Int = 4_096,
    /** Maximum SVG transform operations normalized for one paint route. */
    public val maxSvgTransformOperations: Int = 4_096,
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
        require(maxDecodedPaletteBytes > 0) { "maxDecodedPaletteBytes must be positive." }
        require(maxBaseGlyphRecords > 0) { "maxBaseGlyphRecords must be positive." }
        require(maxLayerRecords > 0) { "maxLayerRecords must be positive." }
        require(maxSvgDocuments > 0) { "maxSvgDocuments must be positive." }
        require(maxSvgTransformOperations > 0) { "maxSvgTransformOperations must be positive." }
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
) : GlyphRepresentationProfile {
    /** Immutable node categories accepted by this consumer. */
    public val acceptedNodeKinds: List<GlyphPaintNodeKind> = acceptedNodeKinds.immutableListSnapshot()
    /** Immutable composition operations accepted by this consumer. */
    public val acceptedCompositionModes: List<GlyphPaintCompositionMode> = acceptedCompositionModes.immutableListSnapshot()

    init {
        require(schemaVersion > 0) { "schemaVersion must be positive." }
        require(this.acceptedNodeKinds.isNotEmpty()) { "At least one paint node kind must be accepted." }
        require(this.acceptedNodeKinds.distinct().size == this.acceptedNodeKinds.size) { "Paint node kinds must not repeat." }
        require(this.acceptedCompositionModes.distinct().size == this.acceptedCompositionModes.size) {
            "Paint composition modes must not repeat."
        }
    }

    /** Returns whether [paint] is completely supported within this profile's declared bounds. */
    public fun accepts(paint: GlyphPaintIR): Boolean {
        if (paint.schemaVersion != schemaVersion || paint.nodes.size > limits.maxNodes) return false
        val references = paint.nodes.sumOf { node -> node.children.size }
        if (references > limits.maxReferences) return false
        val paths = paint.nodes.count { node -> node is GlyphPaintNode.SolidOutline || node is GlyphPaintNode.Path }
        if (paths > limits.maxPaths) return false
        if (paint.nodes.filterIsInstance<GlyphPaintNode.SolidOutline>().any { node -> !outlineProfile.acceptsOutline(node.outline) }) {
            return false
        }
        if (paint.nodes.filterIsInstance<GlyphPaintNode.Path>().any { node -> !outlineProfile.acceptsPath(node.path) }) {
            return false
        }
        if (paint.nodes.any { node -> node.kind() !in acceptedNodeKinds }) return false
        if (paint.nodes.filterIsInstance<GlyphPaintNode.Group>().any { group -> group.compositionMode !in acceptedCompositionModes }) {
            return false
        }
        return !paint.exceedsDepth(limits.maxDepth)
    }

    override fun equals(other: Any?): Boolean =
        other is PaintGraphProfile &&
            acceptedNodeKinds == other.acceptedNodeKinds &&
            acceptedCompositionModes == other.acceptedCompositionModes &&
            limits == other.limits &&
            outlineProfile == other.outlineProfile &&
            schemaVersion == other.schemaVersion

    override fun hashCode(): Int {
        var result = acceptedNodeKinds.hashCode()
        result = 31 * result + acceptedCompositionModes.hashCode()
        result = 31 * result + limits.hashCode()
        result = 31 * result + outlineProfile.hashCode()
        return 31 * result + schemaVersion
    }
}

private fun GlyphPaintNode.kind(): GlyphPaintNodeKind = when (this) {
    is GlyphPaintNode.SolidOutline -> GlyphPaintNodeKind.SOLID_OUTLINE
    is GlyphPaintNode.Path -> GlyphPaintNodeKind.PATH
    is GlyphPaintNode.Group -> GlyphPaintNodeKind.GROUP
}

internal fun OutlineProfile.acceptsOutline(outline: GlyphOutlineIR): Boolean =
    outline.contours.size <= maxContours &&
        outline.pointCount <= maxPoints &&
        outline.components.size <= maxCompositeComponents &&
        outline.limits.maxBytes <= maxBytes &&
        outline.limits.maxContours <= maxContours &&
        outline.limits.maxPoints <= maxPoints &&
        outline.limits.maxCompositeDepth <= maxCompositeDepth &&
        outline.limits.maxCompositeComponents <= maxCompositeComponents

internal fun OutlineProfile.acceptsPath(path: GlyphPaintPath): Boolean =
    path.contourCount <= maxContours &&
        path.pointCount <= maxPoints &&
        path.estimatedByteSize <= maxBytes

private fun GlyphPaintIR.exceedsDepth(maximum: Int): Boolean {
    val greatestVisitedDepth = IntArray(nodes.size)
    val pendingNodes = ArrayDeque<Int>()
    val pendingDepths = ArrayDeque<Int>()
    pendingNodes.addLast(rootNode)
    pendingDepths.addLast(1)

    while (pendingNodes.isNotEmpty()) {
        val node = pendingNodes.removeLast()
        val depth = pendingDepths.removeLast()
        if (depth > maximum) return true
        if (depth <= greatestVisitedDepth[node]) continue
        greatestVisitedDepth[node] = depth
        nodes[node].children.forEach { child ->
            pendingNodes.addLast(child)
            pendingDepths.addLast(depth + 1)
        }
    }
    return false
}
