package org.graphiks.kalligraphie.api

/** An eight-bit, non-premultiplied color used by a portable paint graph. */
public data class GlyphColor(
    /** Red component in the inclusive range `0..255`. */
    public val red: Int,
    /** Green component in the inclusive range `0..255`. */
    public val green: Int,
    /** Blue component in the inclusive range `0..255`. */
    public val blue: Int,
    /** Alpha component in the inclusive range `0..255`. */
    public val alpha: Int = 255,
) {
    init {
        require(red in 0..255) { "red must be between 0 and 255." }
        require(green in 0..255) { "green must be between 0 and 255." }
        require(blue in 0..255) { "blue must be between 0 and 255." }
        require(alpha in 0..255) { "alpha must be between 0 and 255." }
    }
}

/** Composition operation declared by a portable paint graph. */
public enum class GlyphPaintCompositionMode {
    /** Paint source over the current destination. */
    SOURCE_OVER,
}

/**
 * One immutable node in a portable glyph paint graph.
 *
 * References are zero-based indexes in [GlyphPaintIR.nodes]. Constructors retain only immutable
 * values; [GlyphPaintIR] validates every reference and cycle before exposing the graph.
 */
public sealed interface GlyphPaintNode {
    /** Child node indexes in deterministic paint order. */
    public val children: List<Int>

    /** Paints one complete portable outline with a solid color. */
    public data class SolidOutline(
        /** Outline to paint. */
        public val outline: GlyphOutlineIR,
        /** Color applied to the outline. */
        public val color: GlyphColor,
    ) : GlyphPaintNode {
        override val children: List<Int> = emptyList()
    }

    /** Paints one portable vector path with an explicitly normalized brush. */
    public data class Path(
        /** Geometry to fill. */
        public val path: GlyphPaintPath,
        /** Brush applied to [path]. */
        public val brush: GlyphPaintBrush,
    ) : GlyphPaintNode {
        override val children: List<Int> = emptyList()
    }

    /** Applies one affine transform to a complete child subgraph. */
    public data class Transform(
        /** Only child node affected by the transform. */
        public val child: Int,
        /** Transform applied before the child is painted. */
        public val transform: GlyphPaintTransform,
    ) : GlyphPaintNode {
        override val children: List<Int> = listOf(child)
    }

    /** Groups child nodes in source order with one explicitly declared composition operation. */
    public class Group(
        children: List<Int>,
        /** Composition operation applied while painting the group. */
        public val compositionMode: GlyphPaintCompositionMode = GlyphPaintCompositionMode.SOURCE_OVER,
    ) : GlyphPaintNode {
        override val children: List<Int> = children.immutableListSnapshot()

        init {
            require(this.children.isNotEmpty()) { "A paint group must contain at least one child." }
        }

        override fun equals(other: Any?): Boolean =
            other is Group && children == other.children && compositionMode == other.compositionMode

        override fun hashCode(): Int = 31 * children.hashCode() + compositionMode.hashCode()

        override fun toString(): String = "Group(children=$children, compositionMode=$compositionMode)"
    }
}

/**
 * Complete immutable portable paint graph for one glyph.
 *
 * The graph contains no SVG source, external reference, native object, or renderer state. It
 * validates node indexes and rejects reference cycles during construction, so consumers cannot
 * discover an unsupported cyclic subgraph after a provider certifies this representation.
 */
public class GlyphPaintIR(
    /** Version of the graph schema. */
    public val schemaVersion: Int,
    /** Root node index in [nodes]. */
    public val rootNode: Int,
    nodes: List<GlyphPaintNode>,
) {
    /** Immutable graph nodes in stable index order. */
    public val nodes: List<GlyphPaintNode> = nodes.immutableListSnapshot()

    init {
        require(schemaVersion > 0) { "schemaVersion must be positive." }
        require(this.nodes.isNotEmpty()) { "A paint graph must contain at least one node." }
        require(rootNode in this.nodes.indices) { "rootNode must identify a graph node." }
        this.nodes.forEachIndexed { index, node ->
            require(node.children.all { child -> child in this.nodes.indices }) {
                "Paint node $index references a node outside the graph."
            }
        }
        validateAcyclic()
    }

    private fun validateAcyclic() {
        val visiting = BooleanArray(nodes.size)
        val visited = BooleanArray(nodes.size)

        fun visit(index: Int) {
            require(!visiting[index]) { "Paint graph contains a reference cycle." }
            if (visited[index]) return
            visiting[index] = true
            nodes[index].children.forEach(::visit)
            visiting[index] = false
            visited[index] = true
        }

        nodes.indices.forEach(::visit)
    }

    override fun equals(other: Any?): Boolean =
        other is GlyphPaintIR && schemaVersion == other.schemaVersion && rootNode == other.rootNode && nodes == other.nodes

    override fun hashCode(): Int = 31 * (31 * schemaVersion + rootNode) + nodes.hashCode()

    override fun toString(): String = "GlyphPaintIR(schemaVersion=$schemaVersion, rootNode=$rootNode, nodes=$nodes)"
}
