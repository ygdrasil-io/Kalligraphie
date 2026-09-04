package org.graphiks.kalligraphie.api

/**
 * Opaque identity of one immutable source-text revision.
 *
 * Versions are equality-comparable but intentionally expose neither a numeric
 * representation nor a caller-controlled construction value.
 */
public class TextVersion private constructor() {
    /** Factory for opaque immutable text-version identities. */
    public companion object {
        /** Creates a fresh opaque text-version identity. */
        public fun create(): TextVersion = TextVersion()
    }
}

/** Distinguishes the source code-unit representation used by a text snapshot. */
public enum class SourceEncoding {
    /** Source offsets count UTF-8 bytes. */
    UTF8,

    /** Source offsets count UTF-16 code units. */
    UTF16,
}

/** Opaque scalar boundary belonging to one specific [TextVersion]. */
public class TextIndex internal constructor(
    private val version: TextVersion,
    internal val ordinal: Int,
) {
    /** Returns whether this index is compatible with the supplied snapshot version. */
    internal fun belongsTo(candidate: TextSnapshot): Boolean = version == candidate.version

    /**
     * Compares two opaque boundaries in logical scalar order.
     *
     * Both boundaries must belong to one [TextVersion]; comparing different versions throws
     * [IllegalArgumentException]. The result exposes ordering only, never the private scalar
     * ordinal or any source-encoding offset.
     */
    public operator fun compareTo(other: TextIndex): Int {
        require(version == other.version) { "Text indices must belong to the same version." }
        return ordinal.compareTo(other.ordinal)
    }

    /** Returns whether this boundary and [other] belong to the same text version. */
    public fun sharesVersionWith(other: TextIndex): Boolean = version == other.version

    /** Compares the opaque version and hidden scalar-boundary ordinal. */
    override fun equals(other: Any?): Boolean =
        other is TextIndex && version == other.version && ordinal == other.ordinal

    /** Returns a stable hash of the opaque version and hidden ordinal. */
    override fun hashCode(): Int = 31 * version.hashCode() + ordinal

    /** Returns a diagnostic form that intentionally does not disclose the hidden ordinal. */
    override fun toString(): String = "TextIndex()"
}

/** Half-open range of scalar boundaries belonging to one specific text version. */
public class TextRange(
    /** Inclusive start boundary. */
    public val start: TextIndex,
    /** Exclusive end boundary. */
    public val endExclusive: TextIndex,
) {
    init {
        require(start.sharesVersionWith(endExclusive)) { "Text range boundaries must belong to the same version." }
        require(start.compareTo(endExclusive) <= 0) { "Text range start must not follow its end." }
    }

    /** Compares both snapshot-bound scalar boundaries. */
    override fun equals(other: Any?): Boolean =
        other is TextRange && start == other.start && endExclusive == other.endExclusive

    /** Returns a stable hash of the two scalar boundaries. */
    override fun hashCode(): Int = 31 * start.hashCode() + endExclusive.hashCode()

    /** Returns a diagnostic form containing only the opaque boundary representations. */
    override fun toString(): String = "TextRange(start=$start, endExclusive=$endExclusive)"
}

/** Source code-unit boundary within one [TextVersion] and [SourceEncoding]. */
public data class SourceOffset(
    /** Version in which this source boundary is meaningful. */
    public val version: TextVersion,
    /** Encoding whose byte or code-unit positions this offset counts. */
    public val encoding: SourceEncoding,
    /** Zero-based source boundary. */
    public val value: Int,
) {
    init {
        require(value >= 0) { "Source offset must be non-negative." }
    }
}

/** Half-open source range whose offsets share one version and source encoding. */
public data class SourceRange(
    /** Inclusive source boundary. */
    public val start: SourceOffset,
    /** Exclusive source boundary. */
    public val endExclusive: SourceOffset,
) {
    init {
        require(start.version == endExclusive.version) { "Source range boundaries must use the same version." }
        require(start.encoding == endExclusive.encoding) { "Source range boundaries must use the same encoding." }
        require(start.value <= endExclusive.value) { "Source range start must not follow its end." }
    }
}

/** Chooses the preceding or following scalar boundary for an interior source offset. */
public enum class SourceBias {
    /** Resolve an interior source offset to the preceding scalar boundary. */
    BEFORE,

    /** Resolve an interior source offset to the following scalar boundary. */
    AFTER,
}

/** Validated result of mapping a source offset to a scalar boundary. */
public sealed interface SourceIndexResult {
    /** Scalar boundary selected by the mapping. */
    public val index: TextIndex

    /** A source offset that already lies on an exact scalar boundary. */
    public class Exact internal constructor(
        /** Exact scalar boundary. */
        override val index: TextIndex,
    ) : SourceIndexResult

    /** A source offset inside a multi-unit scalar or malformed maximal subpart. */
    public class Biased internal constructor(
        /** Boundary selected according to the requested bias. */
        override val index: TextIndex,
        /** Scalar source range containing the requested offset. */
        public val containingRange: SourceRange,
    ) : SourceIndexResult
}

/** Owned source fragment accepted by the canonical text decoders. */
public sealed interface TextSlice {
    /** Immutable snapshot of one UTF-8 byte fragment. */
    public class Utf8(bytes: ByteArray) : TextSlice {
        private val capturedBytes: ByteArray = bytes.copyOf()

        /** Returns a defensive copy of this fragment's bytes. */
        public fun copyBytes(): ByteArray = capturedBytes.copyOf()
    }

    /** Immutable snapshot of one UTF-16 code-unit fragment. */
    public class Utf16(codeUnits: CharArray) : TextSlice {
        private val capturedCodeUnits: CharArray = codeUnits.copyOf()

        /** Returns a defensive copy of this fragment's UTF-16 code units. */
        public fun copyCodeUnits(): CharArray = capturedCodeUnits.copyOf()
    }
}

/** Immutable Unicode scalar snapshot with reversible source-boundary mapping. */
public class TextSnapshot(
    /** Version shared by every source offset associated with this snapshot. */
    public val version: TextVersion,
    /** Encoding used for every source offset and range associated with this snapshot. */
    public val sourceEncoding: SourceEncoding,
    scalars: List<Int>,
    sourceRanges: List<SourceRange>,
) {
    /** Unicode scalar values in logical order. */
    public val scalars: List<Int> = scalars.immutableListSnapshot()

    /** Source range consumed by each scalar at the corresponding scalar boundary. */
    public val sourceRanges: List<SourceRange> = sourceRanges.immutableListSnapshot()

    /** Complete half-open scalar range of this snapshot. */
    public val range: TextRange = TextRange(TextIndex(version, 0), TextIndex(version, this.scalars.size))

    private val sourceLength: Int = this.sourceRanges.lastOrNull()?.endExclusive?.value ?: 0

    init {
        require(this.scalars.size == this.sourceRanges.size) {
            "Each text scalar must have exactly one source range."
        }
        require(this.scalars.all(::isUnicodeScalar)) { "Text snapshots contain only Unicode scalar values." }
        var expectedStart = 0
        this.sourceRanges.forEach { sourceRange ->
            require(sourceRange.start.version == version) { "Source ranges must use the snapshot version." }
            require(sourceRange.start.encoding == sourceEncoding) { "Source ranges must use the snapshot encoding." }
            require(sourceRange.start.value == expectedStart) { "Source ranges must be contiguous and ordered." }
            require(sourceRange.endExclusive.value > sourceRange.start.value) { "Source ranges must not be empty." }
            expectedStart = sourceRange.endExclusive.value
        }
    }

    /**
     * Creates an opaque scalar boundary for this version after validating its ordinal.
     *
     * The ordinal is accepted only as an input; existing [TextIndex] values never
     * expose their hidden ordinal.
     */
    public fun textIndexAtScalarBoundary(ordinal: Int): TextIndex {
        require(ordinal in 0..scalars.size) { "Scalar boundary lies outside the snapshot." }
        return TextIndex(version, ordinal)
    }

    /** Maps a source offset to an exact or bias-selected scalar boundary. */
    public fun sourceToTextIndex(offset: SourceOffset, bias: SourceBias): SourceIndexResult {
        require(offset.version == version) { "Source offset must use the snapshot version." }
        require(offset.encoding == sourceEncoding) { "Source offset must use the snapshot encoding." }
        require(offset.value <= sourceLength) { "Source offset lies outside the snapshot." }
        if (offset.value == sourceLength) return SourceIndexResult.Exact(textIndexAtScalarBoundary(scalars.size))

        val scalarIndex = scalarIndexContaining(offset.value)
        val sourceRange = sourceRanges[scalarIndex]
        if (offset.value == sourceRange.start.value) {
            return SourceIndexResult.Exact(textIndexAtScalarBoundary(scalarIndex))
        }
        val boundary = if (bias == SourceBias.BEFORE) scalarIndex else scalarIndex + 1
        return SourceIndexResult.Biased(textIndexAtScalarBoundary(boundary), sourceRange)
    }

    /** Maps a scalar boundary from this snapshot to its exact source boundary. */
    public fun textIndexToSource(index: TextIndex): SourceOffset {
        require(index.belongsTo(this)) { "Text index must belong to the snapshot version." }
        require(index.ordinal <= scalars.size) { "Text index lies outside the snapshot." }
        return if (index.ordinal == scalars.size) {
            SourceOffset(version, sourceEncoding, sourceLength)
        } else {
            sourceRanges[index.ordinal].start
        }
    }

    /**
     * Returns the Unicode scalar value immediately preceding [boundary], or `null` at the
     * snapshot start.
     *
     * The returned scalar is the last Unicode scalar value of the snapshot in logical order
     * before the requested boundary. This convenience never exposes scalar ordinals and is
     * useful for policies that must inspect the character before a line break, such as soft
     * hyphen handling.
     */
    public fun scalarPreceding(boundary: TextIndex): Int? {
        require(boundary.belongsTo(this)) { "Text index must belong to the snapshot version." }
        return scalars.getOrNull(boundary.ordinal - 1)
    }

    /** Returns the source range consumed by the scalar beginning at [index]. */
    public fun sourceRange(index: TextIndex): SourceRange {
        require(index.belongsTo(this)) { "Text index must belong to the snapshot version." }
        require(index.ordinal < scalars.size) { "Text index does not identify a scalar in the snapshot." }
        return sourceRanges[index.ordinal]
    }

    /**
     * Returns immutable scalar values in [range] in logical order.
     *
     * The range must belong to this snapshot and is interpreted as half-open scalar
     * boundaries. The result owns an immutable collection snapshot and is safe to share
     * between threads; it does not reveal the private representation of [TextIndex].
     */
    public fun scalarValues(range: TextRange): List<Int> {
        require(contains(range)) { "Text range must belong to this snapshot." }
        return scalars.subList(range.start.ordinal, range.endExclusive.ordinal).immutableListSnapshot()
    }

    /**
     * Returns one half-open scalar range per scalar in [range], in logical order.
     *
     * The input must belong to this snapshot. Returned ranges are bound to this snapshot's
     * version, contain no encoding offsets, and form an immutable partition of [range].
     */
    public fun scalarRanges(range: TextRange): List<TextRange> {
        require(contains(range)) { "Text range must belong to this snapshot." }
        return (range.start.ordinal until range.endExclusive.ordinal)
            .map { ordinal -> TextRange(textIndexAtScalarBoundary(ordinal), textIndexAtScalarBoundary(ordinal + 1)) }
            .immutableListSnapshot()
    }

    /** Returns whether the supplied source range lies entirely within this snapshot. */
    internal fun contains(sourceRange: SourceRange): Boolean =
        sourceRange.start.version == version &&
            sourceRange.start.encoding == sourceEncoding &&
            sourceRange.endExclusive.value <= sourceLength

    /** Returns whether [range] is a scalar range bound to this snapshot. */
    internal fun contains(range: TextRange): Boolean =
        range.start.belongsTo(this) &&
            range.endExclusive.belongsTo(this) &&
            range.endExclusive.ordinal <= scalars.size

    private fun scalarIndexContaining(sourceOffset: Int): Int {
        var lower = 0
        var upper = sourceRanges.size
        while (lower < upper) {
            val middle = (lower + upper) ushr 1
            if (sourceRanges[middle].endExclusive.value <= sourceOffset) {
                lower = middle + 1
            } else {
                upper = middle
            }
        }
        return lower
    }
}

/** Recoverable source-decoding issue retaining the complete malformed source span. */
public data class TextDiagnostic(
    /** Stable machine-readable diagnostic code. */
    public val code: String,
    /** Malformed source range replaced in the scalar snapshot. */
    public val sourceRange: SourceRange,
    /** Human-readable description of the decoding issue. */
    public val message: String,
) {
    init {
        require(code.isNotBlank()) { "Text diagnostic code must not be blank." }
        require(message.isNotBlank()) { "Text diagnostic message must not be blank." }
    }
}

/** Canonical decoded snapshot and immutable diagnostics that belong to that snapshot. */
public class TextDecodingResult(
    /** Scalar snapshot produced from the complete source. */
    public val snapshot: TextSnapshot,
    diagnostics: List<TextDiagnostic> = emptyList(),
) {
    /** Diagnostics in source order, each constrained to this result's snapshot. */
    public val diagnostics: List<TextDiagnostic> = diagnostics.immutableListSnapshot()

    init {
        require(this.diagnostics.all { snapshot.contains(it.sourceRange) }) {
            "Text diagnostics must belong to the decoded snapshot."
        }
    }
}

private fun isUnicodeScalar(value: Int): Boolean = value in 0..0x10FFFF && value !in 0xD800..0xDFFF
