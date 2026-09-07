package org.graphiks.kalligraphie.api

/** Portable paint-node category accepted by a paint-graph consumer. */
public enum class GlyphPaintNodeKind {
    /** A solid fill of one complete outline. */
    SOLID_OUTLINE,

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
) {
    init {
        require(maxNodes > 0) { "maxNodes must be positive." }
        require(maxReferences >= 0) { "maxReferences must be non-negative." }
        require(maxDepth > 0) { "maxDepth must be positive." }
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
            limits == other.limits &&
            schemaVersion == other.schemaVersion

    override fun hashCode(): Int = 31 * (31 * (31 * acceptedNodeKinds.hashCode() + acceptedCompositionModes.hashCode()) + limits.hashCode()) + schemaVersion
}

private fun GlyphPaintNode.kind(): GlyphPaintNodeKind = when (this) {
    is GlyphPaintNode.SolidOutline -> GlyphPaintNodeKind.SOLID_OUTLINE
    is GlyphPaintNode.Group -> GlyphPaintNodeKind.GROUP
}

private fun GlyphPaintIR.depthFromRoot(): Int {
    fun depth(index: Int): Int = 1 + (nodes[index].children.maxOfOrNull(::depth) ?: 0)
    return depth(rootNode)
}
