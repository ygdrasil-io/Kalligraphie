package org.graphiks.kalligraphie.api

/**
 * Opaque identity of one inline object definition.
 *
 * Identities are supplied by the consumer and are never interpreted by the
 * engine: the consumer owns rendering and the mapping from identity to its
 * own resource. Equality is the documented comparison.
 */
public class InlineObjectId internal constructor(
    /** Non-empty opaque identity token. */
    public val value: String,
) {
    /** Compares the opaque identity value. */
    override fun equals(other: Any?): Boolean = other is InlineObjectId && value == other.value

    /** Returns a stable hash of the opaque identity value. */
    override fun hashCode(): Int = value.hashCode()

    /** Returns a diagnostic form containing only the opaque identity value. */
    override fun toString(): String = "InlineObjectId()"

    /** Factory for opaque inline-object identities from non-empty tokens. */
    public companion object {
        /** Creates an opaque identity from a non-empty token. */
        public fun create(value: String): InlineObjectId {
            require(value.isNotBlank()) { "Inline object identity tokens must not be blank." }
            return InlineObjectId(value)
        }
    }
}

/** How one inline object is attached to its line box. */
public enum class InlineObjectAlignment {
    /** The object top is aligned with the line box top (line ascent origin). */
    TOP,

    /** The object baseline is placed below its top by [InlineObjectDefinition.baselineOffset]. */
    BASELINE,

    /** The object bottom is aligned with the line box bottom (line descent origin). */
    BOTTOM,

    /** The object vertical center is aligned with the line box center. */
    CENTER,
}

/**
 * Consumer-owned size, baseline, and alignment of one inline object.
 *
 * Width participates in line advance and line breaking; height and baseline
 * participate only in line geometry. Rendering stays entirely with the
 * consumer: the engine never instantiates a resource for the identity.
 */
public class InlineObjectDefinition(
    /** Opaque consumer identity of this object. */
    public val id: InlineObjectId,
    /** Positive inline extent in layout units. */
    public val width: LayoutUnit,
    /** Positive block extent in layout units. */
    public val height: LayoutUnit,
    /** Distance from the object top to the text baseline when alignment is [InlineObjectAlignment.BASELINE]. */
    public val baselineOffset: LayoutUnit = LayoutUnit(0f),
    /** Attachment of the object to its line box. */
    public val alignment: InlineObjectAlignment = InlineObjectAlignment.BASELINE,
) {
    init {
        require(width.value > 0f) { "Inline object width must be strictly positive." }
        require(height.value > 0f) { "Inline object height must be strictly positive." }
        require(baselineOffset.value >= 0f) { "Inline object baseline offsets must be non-negative." }
    }
}

/**
 * Immutable association of one `U+FFFC` scalar boundary with its object.
 */
public class InlineObjectEntry(
    /** Scalar boundary carrying the `U+FFFC` object replacement character. */
    public val index: TextIndex,
    /** Definition of the object placed at [index]. */
    public val definition: InlineObjectDefinition,
)

/**
 * Immutable snapshot mapping `U+FFFC` object replacement scalars to definitions.
 *
 * Entries are strictly ordered by scalar boundary. The engine validates that
 * every entry boundary actually carries an `U+FFFC` scalar before composing;
 * this value owns no resource and is safe to share between threads.
 */
public class InlineObjectSnapshot(
    /** Entries in strictly increasing boundary order. */
    entries: List<InlineObjectEntry>,
) {
    /** Immutable entry snapshot in strictly increasing boundary order. */
    public val entries: List<InlineObjectEntry> = entries.immutableListSnapshot()

    init {
        require(this.entries.zipWithNext().all { (left, right) -> left.index < right.index }) {
            "Inline object entries must be strictly ordered by scalar boundary."
        }
    }

    /** Returns the definition bound to [index], or `null` when none is bound. */
    public fun definition(index: TextIndex): InlineObjectDefinition? =
        this.entries.firstOrNull { entry -> entry.index == index }?.definition
}

/**
 * One inline object placed into a final line.
 *
 * [rect] is expressed in line-local physical coordinates: the line baseline
 * is `(0, 0)` in the same coordinate system as positioned glyphs. The value
 * owns no renderer resource: consumers draw the object identified by
 * [definition] themselves.
 */
public class PositionedInlineObject(
    /** Snapshot-bound range occupied by the object replacement scalar. */
    public val sourceRange: TextRange,
    /** Definition that placed this object. */
    public val definition: InlineObjectDefinition,
    /** Physical line-local rectangle occupied by the object. */
    public val rect: LayoutRect,
)
