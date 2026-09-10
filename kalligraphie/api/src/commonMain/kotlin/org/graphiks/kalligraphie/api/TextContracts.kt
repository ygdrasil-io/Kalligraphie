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

    internal fun belongsTo(candidate: TextVersion): Boolean = version == candidate

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

/**
 * Read-only UTF-8 code-unit storage that can be borrowed for one synchronous decode call.
 *
 * Implementations must keep [length] and indexed byte values immutable and safe to read from
 * any thread for the complete call that borrows them. Kalligraphie never retains the storage
 * after that call returns.
 */
public interface Utf8Storage {
    /** Number of addressable UTF-8 bytes. */
    public val length: Int

    /** Returns the byte at [index], which must lie in `0 until length`. */
    public operator fun get(index: Int): Byte
}

/**
 * Read-only UTF-16 code-unit storage that can be borrowed for one synchronous decode call.
 *
 * Implementations must keep [length] and indexed code units immutable and safe to read from
 * any thread for the complete call that borrows them. Kalligraphie never retains the storage
 * after that call returns.
 */
public interface Utf16Storage {
    /** Number of addressable UTF-16 code units. */
    public val length: Int

    /** Returns the UTF-16 code unit at [index], which must lie in `0 until length`. */
    public operator fun get(index: Int): Char
}

/** Immutable source fragment accepted by the canonical text decoders. */
public sealed interface TextSlice {
    /**
     * UTF-8 fragment that either owns a copied byte array or borrows a storage subrange.
     *
     * The array constructor captures [bytes] immediately. Use [borrow] when an immutable
     * application-owned storage can remain readable for the complete synchronous decode call.
     */
    public class Utf8 private constructor(
        private val storage: Utf8Storage,
        private val startIndex: Int,
        /** Number of UTF-8 code units in this immutable fragment. */
        public val size: Int,
    ) : TextSlice {
        /** Creates an owned fragment by copying [bytes]. */
        public constructor(bytes: ByteArray) : this(OwnedUtf8Storage(bytes), 0, bytes.size)

        /** Returns the byte at the fragment-relative [index]. */
        public operator fun get(index: Int): Byte {
            require(index in 0 until size) { "UTF-8 slice index lies outside the borrowed subrange." }
            return storage[startIndex + index]
        }

        /** Returns a defensive copy of this fragment's bytes. */
        public fun copyBytes(): ByteArray = ByteArray(size) { index -> get(index) }

        /** Factories for UTF-8 fragments. */
        public companion object {
            /**
             * Borrows `[startIndex, endExclusive)` from [storage] without copying.
             *
             * The range is validated immediately. The storage must remain immutable, readable,
             * and thread-safe until the synchronous decode call returns; it is not retained by a
             * completed or failed decode result.
             */
            public fun borrow(
                storage: Utf8Storage,
                startIndex: Int = 0,
                endExclusive: Int = storage.length,
            ): Utf8 {
                require(storage.length >= 0) { "UTF-8 storage length must be non-negative." }
                require(startIndex in 0..endExclusive) { "UTF-8 slice start must not follow its end." }
                require(endExclusive <= storage.length) { "UTF-8 slice range lies outside the storage." }
                return Utf8(storage, startIndex, endExclusive - startIndex)
            }
        }
    }

    /**
     * UTF-16 fragment that either owns a copied code-unit array or borrows a storage subrange.
     *
     * The array constructor captures [codeUnits] immediately. Use [borrow] when an immutable
     * application-owned storage can remain readable for the complete synchronous decode call.
     */
    public class Utf16 private constructor(
        private val storage: Utf16Storage,
        private val startIndex: Int,
        /** Number of UTF-16 code units in this immutable fragment. */
        public val size: Int,
    ) : TextSlice {
        /** Creates an owned fragment by copying [codeUnits]. */
        public constructor(codeUnits: CharArray) : this(OwnedUtf16Storage(codeUnits), 0, codeUnits.size)

        /** Returns the UTF-16 code unit at the fragment-relative [index]. */
        public operator fun get(index: Int): Char {
            require(index in 0 until size) { "UTF-16 slice index lies outside the borrowed subrange." }
            return storage[startIndex + index]
        }

        /** Returns a defensive copy of this fragment's UTF-16 code units. */
        public fun copyCodeUnits(): CharArray = CharArray(size) { index -> get(index) }

        /** Factories for UTF-16 fragments. */
        public companion object {
            /**
             * Borrows `[startIndex, endExclusive)` from [storage] without copying.
             *
             * The range is validated immediately. The storage must remain immutable, readable,
             * and thread-safe until the synchronous decode call returns; it is not retained by a
             * completed or failed decode result.
             */
            public fun borrow(
                storage: Utf16Storage,
                startIndex: Int = 0,
                endExclusive: Int = storage.length,
            ): Utf16 {
                require(storage.length >= 0) { "UTF-16 storage length must be non-negative." }
                require(startIndex in 0..endExclusive) { "UTF-16 slice start must not follow its end." }
                require(endExclusive <= storage.length) { "UTF-16 slice range lies outside the storage." }
                return Utf16(storage, startIndex, endExclusive - startIndex)
            }
        }
    }
}

private class OwnedUtf8Storage(bytes: ByteArray) : Utf8Storage {
    private val bytes: ByteArray = bytes.copyOf()
    override val length: Int = this.bytes.size
    override fun get(index: Int): Byte = bytes[index]
}

private class OwnedUtf16Storage(codeUnits: CharArray) : Utf16Storage {
    private val codeUnits: CharArray = codeUnits.copyOf()
    override val length: Int = this.codeUnits.size
    override fun get(index: Int): Char = codeUnits[index]
}

/**
 * Immutable Unicode scalar snapshot with reversible source-boundary mapping.
 *
 * The snapshot owns compact primitive scalar and source-boundary tables. [scalars] and
 * [sourceRanges] remain immutable list-shaped compatibility views; values are produced lazily
 * from those tables and the snapshot retains no source storage borrowed during decoding.
 */
public class TextSnapshot private constructor(
    /** Version shared by every source offset associated with this snapshot. */
    public val version: TextVersion,
    /** Encoding used for every source offset and range associated with this snapshot. */
    public val sourceEncoding: SourceEncoding,
    private val scalarData: IntArray,
    private val sourceBoundaryData: IntArray,
) {
    /**
     * Creates a snapshot from list-shaped compatibility inputs and captures their values.
     *
     * Each [sourceRanges] entry must describe the corresponding scalar with contiguous ranges
     * beginning at source offset zero.
     */
    public constructor(
        version: TextVersion,
        sourceEncoding: SourceEncoding,
        scalars: List<Int>,
        sourceRanges: List<SourceRange>,
    ) : this(
        version = version,
        sourceEncoding = sourceEncoding,
        scalarData = scalars.toIntArray(),
        sourceBoundaryData = sourceBoundaryTable(version, sourceEncoding, sourceRanges),
    )

    /** Unicode scalar values in logical order through an immutable lazy view. */
    public val scalars: List<Int> = ImmutableScalarList(scalarData)

    /** Source range consumed by each scalar through an immutable lazy view. */
    public val sourceRanges: List<SourceRange> = ImmutableSourceRangeList(
        version,
        sourceEncoding,
        sourceBoundaryData,
    )

    /** Complete half-open scalar range of this snapshot. */
    public val range: TextRange = TextRange(TextIndex(version, 0), TextIndex(version, this.scalars.size))

    private val sourceLength: Int = sourceBoundaryData.last()

    init {
        require(sourceBoundaryData.size == scalarData.size + 1) {
            "Each text scalar must have exactly two adjacent source boundaries."
        }
        require(sourceBoundaryData.first() == 0) { "Source ranges must begin at source offset zero." }
        scalarData.forEach(::requireUnicodeScalar)
        for (index in scalarData.indices) {
            require(sourceBoundaryData[index + 1] > sourceBoundaryData[index]) {
                "Source ranges must be contiguous, ordered, and non-empty."
            }
        }
    }

    /** Implementation factories for compact decoder output. */
    public companion object {
        /**
         * @suppress
         *
         * Captures primitive scalar values and their `scalarCount + 1` contiguous source
         * boundaries into an owning snapshot without requiring per-scalar [SourceRange] values.
         */
        @KalligraphieInternalApi
        public fun fromPrimitiveTables(
            version: TextVersion,
            sourceEncoding: SourceEncoding,
            scalars: IntArray,
            sourceBoundaries: IntArray,
        ): TextSnapshot = TextSnapshot(
            version,
            sourceEncoding,
            scalars.copyOf(),
            sourceBoundaries.copyOf(),
        )
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
        val scalarStart = sourceBoundaryData[scalarIndex]
        val scalarEnd = sourceBoundaryData[scalarIndex + 1]
        if (offset.value == scalarStart) {
            return SourceIndexResult.Exact(textIndexAtScalarBoundary(scalarIndex))
        }
        val boundary = if (bias == SourceBias.BEFORE) scalarIndex else scalarIndex + 1
        return SourceIndexResult.Biased(
            textIndexAtScalarBoundary(boundary),
            SourceRange(
                SourceOffset(version, sourceEncoding, scalarStart),
                SourceOffset(version, sourceEncoding, scalarEnd),
            ),
        )
    }

    /** Maps a scalar boundary from this snapshot to its exact source boundary. */
    public fun textIndexToSource(index: TextIndex): SourceOffset {
        require(index.belongsTo(this)) { "Text index must belong to the snapshot version." }
        require(index.ordinal <= scalars.size) { "Text index lies outside the snapshot." }
        return if (index.ordinal == scalars.size) {
            SourceOffset(version, sourceEncoding, sourceLength)
        } else {
            SourceOffset(version, sourceEncoding, sourceBoundaryData[index.ordinal])
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
        return SourceRange(
            SourceOffset(version, sourceEncoding, sourceBoundaryData[index.ordinal]),
            SourceOffset(version, sourceEncoding, sourceBoundaryData[index.ordinal + 1]),
        )
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
     * @suppress
     *
     * Returns the scalar count of [range] without materializing scalar values or ranges.
     * This implementation detail exists for bounded cross-module text processing.
     */
    @KalligraphieInternalApi
    public fun scalarCount(range: TextRange): Int {
        require(contains(range)) { "Text range must belong to this snapshot." }
        return range.endExclusive.ordinal - range.start.ordinal
    }

    /**
     * @suppress
     *
     * Visits each scalar and its one-scalar range lazily in logical order. This implementation
     * detail lets bounded consumers observe cancellation before retaining every input range.
     */
    @KalligraphieInternalApi
    public fun forEachScalar(range: TextRange, action: (value: Int, scalarRange: TextRange) -> Unit) {
        require(contains(range)) { "Text range must belong to this snapshot." }
        for (ordinal in range.start.ordinal until range.endExclusive.ordinal) {
            action(
                scalars[ordinal],
                TextRange(textIndexAtScalarBoundary(ordinal), textIndexAtScalarBoundary(ordinal + 1)),
            )
        }
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
            if (sourceBoundaryData[middle + 1] <= sourceOffset) {
                lower = middle + 1
            } else {
                upper = middle
            }
        }
        return lower
    }
}

private fun sourceBoundaryTable(
    version: TextVersion,
    encoding: SourceEncoding,
    ranges: List<SourceRange>,
): IntArray {
    val boundaries = IntArray(ranges.size + 1)
    ranges.forEachIndexed { index, range ->
        require(range.start.version == version) { "Source ranges must use the snapshot version." }
        require(range.start.encoding == encoding) { "Source ranges must use the snapshot encoding." }
        require(range.start.value == boundaries[index]) { "Source ranges must be contiguous and ordered." }
        require(range.endExclusive.value > range.start.value) { "Source ranges must not be empty." }
        boundaries[index + 1] = range.endExclusive.value
    }
    return boundaries
}

private fun requireUnicodeScalar(value: Int) {
    require(isUnicodeScalar(value)) { "Text snapshots contain only Unicode scalar values." }
}

private class ImmutableScalarList(private val values: IntArray) : AbstractMutableList<Int>() {
    override val size: Int
        get() = values.size

    override fun get(index: Int): Int = values[index]
    override fun add(index: Int, element: Int): Unit = immutableTextViewMutation()
    override fun removeAt(index: Int): Int = immutableTextViewMutation()
    override fun set(index: Int, element: Int): Int = immutableTextViewMutation()
}

private class ImmutableSourceRangeList(
    private val version: TextVersion,
    private val encoding: SourceEncoding,
    private val boundaries: IntArray,
) : AbstractMutableList<SourceRange>() {
    override val size: Int
        get() = boundaries.size - 1

    override fun get(index: Int): SourceRange = SourceRange(
        SourceOffset(version, encoding, boundaries[index]),
        SourceOffset(version, encoding, boundaries[index + 1]),
    )

    override fun add(index: Int, element: SourceRange): Unit = immutableTextViewMutation()
    override fun removeAt(index: Int): SourceRange = immutableTextViewMutation()
    override fun set(index: Int, element: SourceRange): SourceRange = immutableTextViewMutation()
}

private fun <Value> immutableTextViewMutation(): Value =
    throw UnsupportedOperationException("Immutable text snapshot view.")

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

/** Resource dimension enforced while decoding one complete source snapshot. */
public enum class TextDecodingLimit {
    /** Total UTF-8 bytes or UTF-16 code units accepted from all source slices. */
    SOURCE_UNITS,

    /** Unicode scalars that would be published in the canonical snapshot. */
    SCALARS,
}

/** Typed source-contract failure that prevents publication of a decoded snapshot. */
public enum class TextDecodingFailure {
    /** A slice seam split one complete valid or malformed Unicode decoding unit. */
    INVALID_SLICE_BOUNDARY,
}

/**
 * Immutable resource profile for one canonical text-decoding operation.
 *
 * The limits protect memory and work without changing Unicode replacement or source-mapping
 * semantics for a successful result. Reaching either limit or observing cancellation never
 * publishes a partial [TextSnapshot]. [cancellationCheckInterval] bounds only the number of
 * decoded scalars between cooperative cancellation observations; it does not change the decoded
 * content.
 */
public class TextDecodingProfile(
    /** Maximum accepted UTF-8 bytes or UTF-16 code units across all slices. */
    public val maxSourceUnits: Int = Int.MAX_VALUE,
    /** Maximum Unicode scalars in a successfully decoded snapshot. */
    public val maxScalars: Int = Int.MAX_VALUE,
    /** Positive number of decoded scalars between cooperative cancellation checks. */
    public val cancellationCheckInterval: Int = 256,
) {
    init {
        require(maxSourceUnits >= 0) { "Text decoding source-unit budget must be non-negative." }
        require(maxScalars >= 0) { "Text decoding scalar budget must be non-negative." }
        require(cancellationCheckInterval > 0) { "Text decoding cancellation interval must be positive." }
    }

    /** Standard profile with no practical source or scalar limit. */
    public companion object {
        public val unbounded: TextDecodingProfile = TextDecodingProfile()
    }
}

/** Complete, atomic outcome of decoding one source revision under a [TextDecodingProfile]. */
public sealed interface TextDecodingOutcome {
    /** Complete immutable snapshot and its diagnostics. */
    public class Success(
        /** Decoded immutable text snapshot and source diagnostics. */
        public val value: TextDecodingResult,
    ) : TextDecodingOutcome

    /** A resource limit was reached before a complete snapshot could be published. */
    public class LimitExceeded(
        /** Resource dimension that rejected this operation. */
        public val limit: TextDecodingLimit,
        /** First source-unit or scalar count exceeding the configured limit. */
        public val observed: Long,
    ) : TextDecodingOutcome

    /** Source slices could not form one valid sequence of complete decoding units. */
    public class Failure(
        /** Typed reason that rejected the complete operation. */
        public val reason: TextDecodingFailure,
    ) : TextDecodingOutcome

    /** Cooperative cancellation was observed before a complete snapshot could be published. */
    public data object Cancelled : TextDecodingOutcome
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
