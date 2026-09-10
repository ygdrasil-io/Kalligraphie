package org.graphiks.kalligraphie.api

import kotlin.jvm.JvmInline

/** Opaque identity of one immutable typography configuration revision. */
public class TypographyVersion private constructor() {
    /** Factories for typography revision identities. */
    public companion object {
        /** Creates a fresh typography revision identity. */
        public fun create(): TypographyVersion = TypographyVersion()
    }
}

/**
 * Immutable typography inputs used to lay out one text revision.
 *
 * The feature list is defensively captured, and the snapshot retains no resolver, renderer, or
 * other live resource.
 *
 * @param features deterministic feature overrides captured in caller order.
 */
public class TypographySnapshot(
    /** Identity of this exact typography revision. */
    public val version: TypographyVersion,
    /** Immutable catalogue generation used for font lookup. */
    public val fontCatalog: FontCatalogSnapshot,
    /** Immutable fallback order compatible with [fontCatalog]. */
    public val resolutionPolicy: FontResolutionPolicySnapshot,
    /** Geometry applied to resolved faces. */
    public val fontInstanceDescriptor: FontInstanceDescriptor,
    features: List<OpenTypeFeature> = emptyList(),
    /** Stable identity of every shaping option not represented by [features]. */
    public val shapingConfigurationIdentity: String = "default",
) {
    /** Deterministic feature overrides in caller order. */
    public val features: List<OpenTypeFeature> = features.immutableListSnapshot()

    init {
        require(fontCatalog.generation == resolutionPolicy.generation) {
            "Typography font catalog and resolution policy must use the same generation."
        }
        require(this.features.map(OpenTypeFeature::tag).distinct().size == this.features.size) {
            "Typography features must not repeat a tag."
        }
        require(shapingConfigurationIdentity.isNotBlank()) {
            "Typography shaping configuration identity must not be blank."
        }
    }
}

/** One replacement from a source snapshot range to a range in the decoded target snapshot. */
public class TextChange(
    /** Half-open range removed from the source revision. */
    public val sourceRange: TextRange,
    /** Half-open range inserted from the target revision. */
    public val insertedTargetRange: TextRange,
) {
    /** Number of decoded Unicode scalars inserted by this replacement. */
    public val insertedScalarCount: Int =
        insertedTargetRange.endExclusive.ordinal - insertedTargetRange.start.ordinal

    /** Compares the source and target ranges. */
    override fun equals(other: Any?): Boolean =
        other is TextChange &&
            sourceRange == other.sourceRange &&
            insertedTargetRange == other.insertedTargetRange

    /** Returns a stable hash of the source and target ranges. */
    override fun hashCode(): Int = 31 * sourceRange.hashCode() + insertedTargetRange.hashCode()

    /** Returns a diagnostic form containing only opaque ranges and the derived scalar count. */
    override fun toString(): String =
        "TextChange(sourceRange=$sourceRange, insertedTargetRange=$insertedTargetRange, " +
            "insertedScalarCount=$insertedScalarCount)"
}

/**
 * Authoritative, normalized edit sequence between two immutable text revisions.
 *
 * Records retain target ranges and derived scalar counts, never copied inserted text. The change
 * list is a defensive immutable snapshot ordered in logical source and target order.
 */
public class TextChangeSet private constructor(
    /** Source text revision consumed by [changes]. */
    public val sourceVersion: TextVersion,
    /** Target text revision produced by [changes]. */
    public val targetVersion: TextVersion,
    changes: List<TextChange>,
) {
    /** Normalized replacements in logical order. */
    public val changes: List<TextChange> = changes.immutableListSnapshot()

    /** Validated construction of versioned text changes. */
    public companion object {
        /**
         * Validates source and target spaces, complete target accounting, and non-overlap before
         * normalizing adjacent replacements.
         */
        public fun create(
            source: TextSnapshot,
            target: TextSnapshot,
            changes: List<TextChange>,
        ): LayoutContractResult<TextChangeSet> = createTextChangeSet(source, target, changes)

        internal fun validated(
            source: TextSnapshot,
            target: TextSnapshot,
            changes: List<TextChange>,
        ): TextChangeSet = TextChangeSet(source.version, target.version, changes)
    }

    /**
     * Maps one source boundary into [target] without exposing scalar ordinals.
     *
     * At a zero-width insertion boundary, [afterInsertion] selects the following target side;
     * replacement boundaries map to the corresponding outer target boundary. A boundary inside
     * removed source is not mappable and returns `null`.
     */
    public fun mapSourceBoundaryToTarget(
        sourceBoundary: TextIndex,
        target: TextSnapshot,
        afterInsertion: Boolean,
    ): TextIndex? {
        if (target.version != targetVersion || !sourceBoundary.usesVersion(sourceVersion)) return null
        var shift = 0
        changes.forEach { change ->
            val sourceStart = change.sourceRange.start.ordinal
            val sourceEnd = change.sourceRange.endExclusive.ordinal
            val targetStart = change.insertedTargetRange.start.ordinal
            val targetEnd = change.insertedTargetRange.endExclusive.ordinal
            val boundary = sourceBoundary.ordinal
            if (sourceStart == sourceEnd && boundary == sourceStart) {
                return target.textIndexAtScalarBoundary(if (afterInsertion) targetEnd else targetStart)
            }
            if (boundary == sourceStart) return target.textIndexAtScalarBoundary(targetStart)
            if (boundary == sourceEnd) return target.textIndexAtScalarBoundary(targetEnd)
            if (boundary in (sourceStart + 1) until sourceEnd) return null
            if (boundary > sourceEnd || (sourceStart == sourceEnd && boundary > sourceStart)) {
                shift += (targetEnd - targetStart) - (sourceEnd - sourceStart)
            }
        }
        return target.textIndexAtScalarBoundary(sourceBoundary.ordinal + shift)
    }

    /**
     * Maps an unchanged source [range] into [target], returning `null` when any edit intersects
     * its interior. Insertions exactly at an edge are assigned outside the mapped range.
     */
    public fun mapUnchangedSourceRangeToTarget(range: TextRange, target: TextSnapshot): TextRange? {
        if (!range.start.usesVersion(sourceVersion) || target.version != targetVersion) return null
        val intersectsChange = changes.any { change ->
            if (change.sourceRange.start == change.sourceRange.endExclusive) {
                change.sourceRange.start > range.start && change.sourceRange.start < range.endExclusive
            } else {
                range.start < change.sourceRange.endExclusive && change.sourceRange.start < range.endExclusive
            }
        }
        if (intersectsChange) return null
        val start = mapSourceBoundaryToTarget(range.start, target, afterInsertion = true) ?: return null
        val end = mapSourceBoundaryToTarget(range.endExclusive, target, afterInsertion = false) ?: return null
        return TextRange(start, end)
    }
}

/** Result of constructing an incremental layout contract from caller-controlled values. */
public sealed interface LayoutContractResult<out Value> {
    /** Successfully validated immutable contract value. */
    public data class Success<Value>(
        /** Validated value. */
        public val value: Value,
    ) : LayoutContractResult<Value>

    /** Typed rejection of invalid or incompatible contract values. */
    public data class Failure(
        /** Reason validation failed. */
        public val error: IncrementalLayoutError,
    ) : LayoutContractResult<Nothing>
}

/**
 * Validates and normalizes an edit sequence between [source] and [target].
 *
 * Same-boundary inserts merge only when their target ranges are contiguous. Replacements merge
 * only when both their non-empty source ranges and target ranges are adjacent.
 */
public fun createTextChangeSet(
    source: TextSnapshot,
    target: TextSnapshot,
    changes: List<TextChange>,
): LayoutContractResult<TextChangeSet> {
    val capturedChanges = changes.toList()
    capturedChanges.forEach { change ->
        val sourceError = validateRangeDomain(source, change.sourceRange, "Text change source range")
        if (sourceError != null) return LayoutContractResult.Failure(sourceError)
        val targetError = validateRangeDomain(target, change.insertedTargetRange, "Text change target range")
        if (targetError != null) return LayoutContractResult.Failure(targetError)
        if (change.sourceRange.isEmpty() && change.insertedTargetRange.isEmpty()) {
            return LayoutContractResult.Failure(
                IncrementalLayoutError.InvalidTextChange("A text change must remove or insert at least one scalar."),
            )
        }
    }

    val ordered = capturedChanges.sortedWith(
        compareBy<TextChange> { it.sourceRange.start.ordinal }
            .thenBy { it.insertedTargetRange.start.ordinal },
    )
    val overlap = ordered.zipWithNext().firstOrNull { (previous, current) ->
        current.sourceRange.start.ordinal < previous.sourceRange.endExclusive.ordinal
    }
    if (overlap != null) {
        return LayoutContractResult.Failure(
            IncrementalLayoutError.OverlappingRanges(overlap.first.sourceRange, overlap.second.sourceRange),
        )
    }

    var sourceCursor = 0
    var targetCursor = 0
    ordered.forEach { change ->
        val unchangedSourceCount = change.sourceRange.start.ordinal - sourceCursor
        val unchangedTargetCount = change.insertedTargetRange.start.ordinal - targetCursor
        if (unchangedSourceCount != unchangedTargetCount || unchangedSourceCount < 0) {
            return LayoutContractResult.Failure(
                IncrementalLayoutError.InvalidTextChange(
                    "Target ranges must account exactly for unchanged text between source edits.",
                ),
            )
        }
        if (!unchangedScalarsMatch(source, target, sourceCursor, targetCursor, unchangedSourceCount)) {
            return LayoutContractResult.Failure(
                IncrementalLayoutError.InvalidTextChange(
                    "Text outside target insertion ranges must equal the unchanged source text.",
                ),
            )
        }
        sourceCursor = change.sourceRange.endExclusive.ordinal
        targetCursor = change.insertedTargetRange.endExclusive.ordinal
    }
    if (source.scalars.size - sourceCursor != target.scalars.size - targetCursor) {
        return LayoutContractResult.Failure(
            IncrementalLayoutError.InvalidTextChange(
                "Target ranges must account exactly for unchanged text after the final source edit.",
            ),
        )
    }
    val trailingScalarCount = source.scalars.size - sourceCursor
    if (!unchangedScalarsMatch(source, target, sourceCursor, targetCursor, trailingScalarCount)) {
        return LayoutContractResult.Failure(
            IncrementalLayoutError.InvalidTextChange(
                "Text after the final target insertion range must equal the unchanged source text.",
            ),
        )
    }

    val normalized = mutableListOf<TextChange>()
    ordered.forEach { change ->
        val previous = normalized.lastOrNull()
        if (previous != null && previous.canMergeWith(change)) {
            normalized[normalized.lastIndex] = TextChange(
                sourceRange = TextRange(previous.sourceRange.start, change.sourceRange.endExclusive),
                insertedTargetRange = TextRange(
                    previous.insertedTargetRange.start,
                    change.insertedTargetRange.endExclusive,
                ),
            )
        } else {
            normalized += change
        }
    }
    return LayoutContractResult.Success(TextChangeSet.validated(source, target, normalized))
}

/** Describes source and target ranges invalidated by an input delta. */
public sealed interface RangeChange {
    /** Proven ranges derived from one authoritative [TextChangeSet]. */
    public class Proven internal constructor(
        sourceRanges: List<TextRange>,
        targetRanges: List<TextRange>,
    ) : RangeChange {
        /** Changed ranges in the source text space. */
        public val sourceRanges: List<TextRange> = sourceRanges.immutableListSnapshot()

        /** Changed ranges in the target text space. */
        public val targetRanges: List<TextRange> = targetRanges.immutableListSnapshot()
    }

    /** Conservative invalidation used when no affected ranges have been proven. */
    public data object FullInvalidation : RangeChange

    /** Factories for range invalidation contracts. */
    public companion object {
        /** Derives affected source and target ranges from the authoritative text delta. */
        public fun from(changeSet: TextChangeSet): Proven = Proven(
            sourceRanges = changeSet.changes.map(TextChange::sourceRange),
            targetRanges = changeSet.changes.map(TextChange::insertedTargetRange),
        )
    }
}

/**
 * Immutable font-policy transition and its conservatively affected ranges.
 *
 * @param provenRanges optional ranges derived from an authoritative change set.
 */
public class FontResolutionPolicyDelta(
    /** Policy used by the source typography revision. */
    public val source: FontResolutionPolicySnapshot,
    /** Policy used by the target typography revision. */
    public val target: FontResolutionPolicySnapshot,
    provenRanges: RangeChange.Proven? = null,
) {
    /** Proven invalidation ranges, or full invalidation when none were supplied. */
    public val rangeChange: RangeChange = provenRanges ?: RangeChange.FullInvalidation
}

/** Versioned typography transition with explicit invalidation scope. */
public class TypographyDelta(
    /** Source typography revision. */
    public val sourceVersion: TypographyVersion,
    /** Target typography revision. */
    public val targetVersion: TypographyVersion,
    /** Affected ranges, conservatively full when the change is not localized. */
    public val rangeChange: RangeChange = RangeChange.FullInvalidation,
    /** Optional policy-specific details. */
    public val fontResolutionPolicy: FontResolutionPolicyDelta? = null,
)

/** Complete immutable text and typography inputs for one layout revision. */
public data class LayoutInput(
    /** Canonical decoded text revision. */
    public val text: TextSnapshot,
    /** Typography revision applied to [text]. */
    public val typography: TypographySnapshot,
)

/** Versioned changes from a previous layout input to a target [LayoutInput]. */
public class LayoutDelta(
    /** Optional authoritative text transition. */
    public val text: TextChangeSet? = null,
    /** Optional typography transition. */
    public val typography: TypographyDelta? = null,
)

/** Non-negative number of complete lines retained beyond the requested range. */
@JvmInline
public value class LineOverscan(
    /** Number of extra complete lines on each applicable boundary. */
    public val lineCount: Int,
) {
    init {
        require(lineCount >= 0) { "Line overscan must be non-negative." }
    }
}

/** Explicit state of source text following complete published coverage. */
public sealed interface LayoutTailState {
    /** All source through the document end has been materialized. */
    public data object MaterializedThroughDocumentEnd : LayoutTailState

    /** An exact unmaterialized suffix is semantically proven unchanged. */
    public data class Stable(
        /** Exact stable suffix beginning at the covered-range end. */
        public val range: TextRange,
    ) : LayoutTailState

    /** An exact unmaterialized suffix remains invalid and requires later materialization. */
    public data class Invalidated(
        /** Exact invalid suffix beginning at the covered-range end. */
        public val range: TextRange,
    ) : LayoutTailState
}

/**
 * Immutable coverage metadata for a laid-out text revision.
 *
 * Public callers construct coverage through [create], which proves that [range] carries
 * [textVersion]. The internal constructor supports trusted layout implementations while request
 * validation still rejects an inconsistent internal value at the public boundary.
 */
public class LayoutCoverage internal constructor(
    /** Text revision to which [range] belongs. */
    public val textVersion: TextVersion,
    /** Contiguous half-open range covered by complete layout output. */
    public val range: TextRange,
    /** Whether [range] completely covers the corresponding request. */
    public val isComplete: Boolean,
    /** Explicit state of the exact source suffix following [range]. */
    public val tailState: LayoutTailState = LayoutTailState.MaterializedThroughDocumentEnd,
) {
    /** Exact unmaterialized suffix that remains invalid, or `null` otherwise. */
    public val invalidatedSuffix: TextRange?
        get() = (tailState as? LayoutTailState.Invalidated)?.range

    init {
        val tailRange = tailState.rangeOrNull()
        require(tailRange == null || tailRange.start.sharesVersionWith(range.start)) {
            "An unmaterialized tail must use the coverage text version."
        }
        require(tailRange == null || tailRange.start == range.endExclusive) {
            "An unmaterialized tail must begin exactly where complete published coverage ends."
        }
    }

    /** Validated construction of immutable coverage metadata. */
    public companion object {
        /** Creates coverage only when [range] belongs to [textVersion]. */
        public fun create(
            textVersion: TextVersion,
            range: TextRange,
            isComplete: Boolean,
            invalidatedSuffix: TextRange? = null,
        ): LayoutContractResult<LayoutCoverage> = create(
            textVersion,
            range,
            isComplete,
            invalidatedSuffix?.let { LayoutTailState.Invalidated(it) }
                ?: LayoutTailState.MaterializedThroughDocumentEnd,
        )

        /** Creates coverage with an explicit stable, invalidated, or fully materialized tail. */
        public fun create(
            textVersion: TextVersion,
            range: TextRange,
            isComplete: Boolean,
            tailState: LayoutTailState,
        ): LayoutContractResult<LayoutCoverage> {
            val tailRange = tailState.rangeOrNull()
            if (!range.usesVersion(textVersion) || (tailRange != null && !tailRange.usesVersion(textVersion))) {
                return LayoutContractResult.Failure(
                    IncrementalLayoutError.VersionMismatch(
                        "Layout coverage and its tail must use the declared text version.",
                    ),
                )
            }
            if (tailRange != null && tailRange.start != range.endExclusive) {
                return LayoutContractResult.Failure(
                    IncrementalLayoutError.InvalidRange(
                        "An unmaterialized tail must begin exactly where complete published coverage ends.",
                    ),
                )
            }
            return LayoutContractResult.Success(LayoutCoverage(textVersion, range, isComplete, tailState))
        }
    }
}

/** Immutable version checkpoint represented by a retained layout state. */
public data class LayoutCheckpoint(
    /** Text revision captured by the state. */
    public val textVersion: TextVersion,
    /** Typography revision captured by the state. */
    public val typographyVersion: TypographyVersion,
)

/**
 * Resource-free continuation signature after one complete line.
 *
 * [semanticValue] must encode every producer-specific state component that can change subsequent
 * line breaking or geometry. Absolute boundaries are compared separately after text-delta mapping.
 */
public data class LayoutContinuationSignature(
    /** Source boundary immediately following the complete line. */
    public val boundary: TextIndex,
    /** Stable complete semantic serialization of continuation state. */
    public val semanticValue: String,
) {
    init {
        require(semanticValue.isNotBlank()) { "A continuation semantic value must not be blank." }
    }

    /** Compares continuation semantics independently of snapshot-bound absolute position. */
    public fun hasSameSemantics(other: LayoutContinuationSignature): Boolean = semanticValue == other.semanticValue
}

/** Exact resource-free signature of one complete observable line. */
public class LineCheckpointSignature private constructor(
    /** Source range and break boundary represented by this complete line. */
    public val range: TextRange,
    /** Complete continuation state immediately after [range]. */
    public val continuation: LayoutContinuationSignature,
    private val observable: ObservableLineSignature,
) {
    /** Compares every observable except the snapshot-bound absolute source range. */
    public fun hasSameObservableLayout(other: LineCheckpointSignature): Boolean = observable == other.observable

    /** Compares the source range and every captured observable. */
    override fun equals(other: Any?): Boolean =
        other is LineCheckpointSignature &&
            range == other.range &&
            continuation == other.continuation &&
            observable == other.observable

    /** Returns a stable hash of the source range and captured observables. */
    override fun hashCode(): Int = 31 * (31 * range.hashCode() + continuation.hashCode()) + observable.hashCode()

    /** Factories for complete line signatures. */
    public companion object {
        /** Captures range-relative glyph, cluster, font, shaping, geometry, caret, and continuation facts. */
        public fun from(
            line: LineLayout,
            continuation: LayoutContinuationSignature,
        ): LineCheckpointSignature = LineCheckpointSignature(
            range = line.range,
            continuation = continuation,
            observable = line.toObservableSignature(),
        ).also {
            require(continuation.boundary == line.range.endExclusive) {
                "A line checkpoint continuation must begin at the line end boundary."
            }
        }
    }
}

/**
 * Structured resource-free checkpoint after one complete flow fragment.
 *
 * [continuation] contains the exact current replay identity, region ordinal and revision, block
 * cursor, fragmentation state, and paragraph inputs. Observable comparison uses captured line
 * geometry and shaping facts without a serialized string identity.
 */
public class FlowLayoutCheckpoint private constructor(
    /** Exact source range represented immediately before [continuation]. */
    public val laidOutRange: TextRange,
    /** Exact structured continuation after [laidOutRange]. */
    public val continuation: FlowContinuation,
    private val observable: FlowFragmentObservableSignature,
) {
    /** Zero-based region ordinal at which replay resumes. */
    public val resumeRegionOrdinal: Int = continuation.regionIndex

    /** Exact immutable region revision at [resumeRegionOrdinal]. */
    public val resumeRegionIdentity: FlowRegionIdentity = continuation.regionIdentity

    /** Exact logical block-axis cursor at which replay resumes. */
    public val blockCursor: Float = continuation.nextBlockOffset

    /**
     * Whether the captured UAX #9 structure prevents proving a local text-edit dependency closure.
     * Such edits must restart at paragraph start rather than reuse this checkpoint.
     */
    public val hasNonLocalBidiDependencies: Boolean =
        continuation.paragraphReplayIdentity?.hasNonLocalBidiDependencies ?: true

    /** Compares all captured fragment observables independently of absolute source versions. */
    public fun hasSameObservableLayout(other: FlowLayoutCheckpoint): Boolean = observable == other.observable

    /** Compares structured region, cursor, writing-mode, and fragmentation continuation state. */
    public fun hasSameFlowSemantics(other: FlowLayoutCheckpoint): Boolean =
        continuation.inputIdentity == other.continuation.inputIdentity &&
            continuation.paragraphRange == other.continuation.paragraphRange &&
            continuation.remainingSourceRange == other.continuation.remainingSourceRange &&
            continuation.compositionIdentity == other.continuation.compositionIdentity &&
            continuation.regionIndex == other.continuation.regionIndex &&
            continuation.regionIdentity == other.continuation.regionIdentity &&
            continuation.writingMode == other.continuation.writingMode &&
            continuation.nextBlockOffset == other.continuation.nextBlockOffset &&
            continuation.fragmentationConstraints == other.continuation.fragmentationConstraints &&
            continuation.relaxedConstraints == other.continuation.relaxedConstraints &&
            continuation.fragmentationCommitment == other.continuation.fragmentationCommitment &&
            continuation.paragraphReplayIdentity?.let { replay ->
                other.continuation.paragraphReplayIdentity?.let(replay::hasSameReplayIdentity)
            } == true

    /**
     * Rebinds this unchanged observable checkpoint to a proven target range and freshly captured
     * target continuation.
     */
    public fun remap(
        laidOutRange: TextRange,
        continuation: FlowContinuation,
    ): FlowLayoutCheckpoint {
        require(laidOutRange.endExclusive == continuation.remainingSourceRange.start) {
            "A remapped flow checkpoint must end where its continuation begins."
        }
        return FlowLayoutCheckpoint(laidOutRange, continuation, observable)
    }

    /** Factories for exact complete-fragment checkpoint capture. */
    public companion object {
        /** Captures every line observable and the fragment's exact structured continuation. */
        public fun capture(fragment: ParagraphFragment): FlowLayoutCheckpoint {
            val continuation = requireNotNull(fragment.continuation) {
                "Only a non-final flow fragment can create a replay checkpoint."
            }
            return FlowLayoutCheckpoint(
                fragment.laidOutRange,
                continuation,
                FlowFragmentObservableSignature(
                    isFirstFragment = fragment.isFirstFragment,
                    lines = fragment.lines.map(LineLayout::toObservableSignature),
                    diagnostics = fragment.diagnostics,
                ),
            )
        }
    }
}

private data class FlowFragmentObservableSignature(
    val isFirstFragment: Boolean,
    val lines: List<ObservableLineSignature>,
    val diagnostics: List<FlowCompositionDiagnostic>,
)

/** Semantically complete resource-free configuration used to validate checkpoint reuse. */
public class LayoutConfigurationSignature private constructor(
    private val value: LayoutConfigurationValue,
) {
    /** Compares all layout inputs that can affect line breaking or final geometry. */
    override fun equals(other: Any?): Boolean =
        other is LayoutConfigurationSignature && value == other.value

    /** Returns a stable hash of all captured layout inputs. */
    override fun hashCode(): Int = value.hashCode()

    /**
     * Checks that [target] differs only by the exact font-resolution policy transition in [delta].
     *
     * Both policy snapshots are compared by catalogue generation, identity, version, complete
     * candidate order, and last-resort face. Every non-policy configuration component must match.
     */
    public fun validatesFontResolutionPolicyTransition(
        target: LayoutConfigurationSignature,
        delta: FontResolutionPolicyDelta,
    ): Boolean {
        val sourcePolicy = delta.source.toConfigurationValue()
        val targetPolicy = delta.target.toConfigurationValue()
        return value.resolutionPolicy == sourcePolicy &&
            target.value.resolutionPolicy == targetPolicy &&
            value.copy(resolutionPolicy = targetPolicy) == target.value
    }

    /** Checks whether this configuration uses exactly [policy] for font resolution. */
    public fun matchesFontResolutionPolicy(policy: FontResolutionPolicySnapshot): Boolean =
        value.resolutionPolicy == policy.toConfigurationValue()

    /** Factories for request configuration signatures. */
    public companion object {
        /** Captures constraints, font catalogue, resolution policy, geometry, features, and shaping configuration. */
        public fun from(
            input: LayoutInput,
            constraints: ParagraphConstraints,
        ): LayoutConfigurationSignature = LayoutConfigurationSignature(
            LayoutConfigurationValue(
                constraints = constraints,
                resolutionPolicy = input.typography.resolutionPolicy.toConfigurationValue(),
                fontInstanceDescriptor = input.typography.fontInstanceDescriptor,
                features = input.typography.features,
                shapingConfigurationIdentity = input.typography.shapingConfigurationIdentity,
            ),
        )
    }
}

/**
 * Resource-free capability for reusing a prior layout state.
 *
 * It exposes immutable identity, version checkpoint, coverage, semantic configuration,
 * continuation, and complete-line checkpoints. All values are resource-free: no document,
 * resolver, renderer, font handle, or platform resource can be reached through this handle.
 */
public class LayoutStateHandle(
    /** Stable implementation-defined state identity. */
    public val identity: String,
    /** Exact input versions captured by the state. */
    public val checkpoint: LayoutCheckpoint,
    /** Complete-line text coverage available in the state. */
    public val coverage: LayoutCoverage,
    /** Complete semantic configuration required to reuse this state. */
    public val configuration: LayoutConfigurationSignature? = null,
    /** Complete continuation signature after the final covered line. */
    public val continuation: LayoutContinuationSignature? = null,
    lineCheckpoints: List<LineCheckpointSignature> = emptyList(),
) {
    /** Immutable complete-line signatures ordered in logical source order. */
    public val lineCheckpoints: List<LineCheckpointSignature> = lineCheckpoints.immutableListSnapshot()

    init {
        require(identity.isNotBlank()) { "Layout state identity must not be blank." }
        require(checkpoint.textVersion == coverage.textVersion) {
            "Layout state checkpoint and coverage must use the same text version."
        }
        require(this.lineCheckpoints.all { signature ->
            signature.range.start.sharesVersionWith(coverage.range.start)
        }) {
            "Layout state line checkpoints must use the coverage text version."
        }
        require(this.lineCheckpoints.all { signature ->
            signature.range.start >= coverage.range.start &&
                signature.range.endExclusive <= coverage.range.endExclusive
        }) {
            "Layout state line checkpoints must stay within complete published coverage."
        }
        require(this.lineCheckpoints.zipWithNext().all { (left, right) ->
            left.range.endExclusive <= right.range.start
        }) {
            "Layout state line checkpoints must be ordered and non-overlapping."
        }
        require(continuation == null || continuation.boundary == coverage.range.endExclusive) {
            "Layout state continuation must begin at the covered-range end."
        }
        require(this.lineCheckpoints.isEmpty() || continuation == this.lineCheckpoints.last().continuation) {
            "Layout state continuation must equal the final line checkpoint continuation."
        }
    }
}

/** Fully validated immutable request for incremental paragraph layout. */
public class IncrementalLayoutRequest internal constructor(
    /** Target text and typography inputs. */
    public val input: LayoutInput,
    /** Target text range requested by the caller. */
    public val requestedRange: TextRange,
    /** Physical paragraph constraints, including the logical writing mode. */
    public val constraints: ParagraphConstraints,
    /** Complete-line overscan retained outside the requested range. */
    public val overscan: LineOverscan,
    /** Optional resource-free prior state metadata. */
    public val previousState: LayoutStateHandle?,
    /** Optional versioned changes from [previousState] to [input]. */
    public val delta: LayoutDelta?,
    /** Cooperative cancellation signal observed by layout work. */
    public val cancellationToken: CancellationToken,
    /** Shared finite resource policy for this complete incremental operation. */
    public val operationProfile: EditorOperationProfile,
) {
    internal constructor(
        input: LayoutInput,
        requestedRange: TextRange,
        constraints: ParagraphConstraints,
        overscan: LineOverscan,
        previousState: LayoutStateHandle?,
        delta: LayoutDelta?,
        cancellationToken: CancellationToken,
    ) : this(
        input,
        requestedRange,
        constraints,
        overscan,
        previousState,
        delta,
        cancellationToken,
        EditorOperationProfile.unbounded,
    )
}

/**
 * Validates an incremental layout request without retaining mutable document or renderer state.
 */
public fun createIncrementalLayoutRequest(
    input: LayoutInput,
    requestedRange: TextRange,
    constraints: ParagraphConstraints,
    overscan: LineOverscan,
    previousState: LayoutStateHandle?,
    delta: LayoutDelta?,
    cancellationToken: CancellationToken,
): LayoutContractResult<IncrementalLayoutRequest> = createIncrementalLayoutRequest(
    input,
    requestedRange,
    constraints,
    overscan,
    previousState,
    delta,
    cancellationToken,
    EditorOperationProfile.unbounded,
)

/**
 * Validates an incremental request with one explicit complete-operation resource policy.
 *
 * The policy controls admission and work only; it is deliberately absent from semantic
 * configuration, continuation, and cache identity.
 */
public fun createIncrementalLayoutRequest(
    input: LayoutInput,
    requestedRange: TextRange,
    constraints: ParagraphConstraints,
    overscan: LineOverscan,
    previousState: LayoutStateHandle?,
    delta: LayoutDelta?,
    cancellationToken: CancellationToken,
    operationProfile: EditorOperationProfile,
): LayoutContractResult<IncrementalLayoutRequest> {
    val rangeError = validateRangeDomain(input.text, requestedRange, "Requested layout range")
    if (rangeError != null) return LayoutContractResult.Failure(rangeError)

    if (previousState != null && !previousState.coverage.range.usesVersion(previousState.coverage.textVersion)) {
        return LayoutContractResult.Failure(
            IncrementalLayoutError.VersionMismatch(
                "Previous layout coverage range does not match its declared text version.",
            ),
        )
    }
    if (
        previousState != null &&
        previousState.checkpoint.textVersion != input.text.version &&
        delta?.text == null
    ) {
        return LayoutContractResult.Failure(
            IncrementalLayoutError.VersionMismatch("A changed text checkpoint requires a versioned text delta."),
        )
    }
    if (
        previousState != null &&
        previousState.checkpoint.typographyVersion != input.typography.version &&
        delta?.typography == null
    ) {
        return LayoutContractResult.Failure(
            IncrementalLayoutError.VersionMismatch(
                "A changed typography checkpoint requires a versioned typography delta.",
            ),
        )
    }
    if (delta?.text != null && delta.text.targetVersion != input.text.version) {
        return LayoutContractResult.Failure(
            IncrementalLayoutError.VersionMismatch("Text delta target does not match the layout input revision."),
        )
    }
    if (delta?.typography != null && delta.typography.targetVersion != input.typography.version) {
        return LayoutContractResult.Failure(
            IncrementalLayoutError.VersionMismatch("Typography delta target does not match the layout input revision."),
        )
    }
    if (previousState != null && delta?.text != null && previousState.checkpoint.textVersion != delta.text.sourceVersion) {
        return LayoutContractResult.Failure(
            IncrementalLayoutError.VersionMismatch("Text delta source does not match the previous layout checkpoint."),
        )
    }
    if (
        previousState != null &&
        delta?.typography != null &&
        previousState.checkpoint.typographyVersion != delta.typography.sourceVersion
    ) {
        return LayoutContractResult.Failure(
            IncrementalLayoutError.VersionMismatch("Typography delta source does not match the previous layout checkpoint."),
        )
    }
    if (delta?.typography != null) {
        val sourceTextVersion = previousState?.checkpoint?.textVersion
            ?: delta.text?.sourceVersion
            ?: input.text.version
        val proofError = validateTypographyProofs(
            sourceTextVersion = sourceTextVersion,
            target = input.text,
            targetConfiguration = LayoutConfigurationSignature.from(input, constraints),
            typographyDelta = delta.typography,
        )
        if (proofError != null) return LayoutContractResult.Failure(proofError)
    }
    return LayoutContractResult.Success(
        IncrementalLayoutRequest(
            input,
            requestedRange,
            constraints,
            overscan,
            previousState,
            delta,
            cancellationToken,
            operationProfile,
        ),
    )
}

internal fun validateTypographyProofs(
    sourceTextVersion: TextVersion,
    target: TextSnapshot,
    targetConfiguration: LayoutConfigurationSignature,
    typographyDelta: TypographyDelta,
): IncrementalLayoutError? {
    val typographyError = validateProvenRangeSpaces(
        label = "Typography delta",
        rangeChange = typographyDelta.rangeChange,
        sourceVersion = sourceTextVersion,
        target = target,
    )
    if (typographyError != null) return typographyError

    val policyDelta = typographyDelta.fontResolutionPolicy ?: return null
    if (!targetConfiguration.matchesFontResolutionPolicy(policyDelta.target)) {
        return IncrementalLayoutError.VersionMismatch(
            "Font resolution policy delta target does not match the target typography configuration.",
        )
    }
    val policyChange = policyDelta.rangeChange
    val policyError = validateProvenRangeSpaces(
        label = "Font resolution policy delta",
        rangeChange = policyChange,
        sourceVersion = sourceTextVersion,
        target = target,
    )
    if (policyError != null) return policyError
    if (!typographyDelta.rangeChange.hasSameRangesAs(policyChange)) {
        return IncrementalLayoutError.InvalidRange(
            "Typography and font resolution policy deltas must declare the same affected ranges.",
        )
    }
    return null
}

private fun validateProvenRangeSpaces(
    label: String,
    rangeChange: RangeChange,
    sourceVersion: TextVersion,
    target: TextSnapshot,
): IncrementalLayoutError? {
    val proven = rangeChange as? RangeChange.Proven ?: return null
    if (proven.sourceRanges.any { range -> !range.usesVersion(sourceVersion) }) {
        return IncrementalLayoutError.VersionMismatch(
            "$label source ranges must use the declared source text revision.",
        )
    }
    proven.targetRanges.forEach { range ->
        val error = validateRangeDomain(target, range, "$label target range")
        if (error != null) return error
    }
    return null
}

private fun RangeChange.hasSameRangesAs(other: RangeChange): Boolean = when {
    this === RangeChange.FullInvalidation && other === RangeChange.FullInvalidation -> true
    this is RangeChange.Proven && other is RangeChange.Proven ->
        sourceRanges == other.sourceRanges && targetRanges == other.targetRanges
    else -> false
}

/**
 * Resource-free identity of the text and typography revisions represented by a publication.
 *
 * This value deliberately retains neither a [TextSnapshot] nor any font catalogue, resolver,
 * backend, text slice, or document capability.
 */
public data class IncrementalLayoutInputIdentity(
    /** Immutable text revision represented by the publication. */
    public val textVersion: TextVersion,
    /** Immutable typography revision represented by the publication. */
    public val typographyVersion: TypographyVersion,
)

/** Immutable published incremental layout metadata. */
public interface IncrementalLayout {
    /** Resource-free input revision identity represented by this output. */
    public val inputIdentity: IncrementalLayoutInputIdentity

    /** Complete-line coverage published by this output. */
    public val coverage: LayoutCoverage

    /** Complete immutable lines published in logical and physical order. */
    public val lines: List<LineLayout>

    /** Exact range derived from the first and last published complete lines. */
    public val coveredRange: TextRange
        get() = coverage.range

    /** Resource-free state metadata available for a later request. */
    public val state: LayoutStateHandle
}

/** Observable decisions made while producing one incremental result. */
public data class IncrementalLayoutDiagnostics(
    /** Exact target boundary from which complete-line recomposition began. */
    public val reflowStart: TextIndex,
    /** Whether prior state could not prove a safe checkpoint. */
    public val usedConservativeInvalidation: Boolean,
    /** Boundary after a semantically matching line proved the remaining continuation stable. */
    public val stabilizedAt: TextIndex? = null,
)

/** Typed reason an incremental layout contract or operation could not produce output. */
public sealed interface IncrementalLayoutError {
    /** Stable machine-readable error code. */
    public val code: String

    /** Deterministic human-readable explanation. */
    public val message: String

    /** A range, delta, or state belongs to an incompatible text or typography revision. */
    public data class VersionMismatch(
        override val message: String,
    ) : IncrementalLayoutError {
        override val code: String = "layout.incremental-version-mismatch"
    }

    /** A supplied range lies outside the snapshot to which it claims to belong. */
    public data class InvalidRange(
        override val message: String,
    ) : IncrementalLayoutError {
        override val code: String = "layout.incremental-invalid-range"
    }

    /** Two source ranges overlap and therefore cannot describe a deterministic edit sequence. */
    public data class OverlappingRanges(
        /** Earlier overlapping source range. */
        public val first: TextRange,
        /** Later overlapping source range. */
        public val second: TextRange,
    ) : IncrementalLayoutError {
        override val code: String = "layout.incremental-overlapping-ranges"
        override val message: String = "Text change source ranges must not overlap."
    }

    /** Source and target ranges do not describe a complete deterministic text transition. */
    public data class InvalidTextChange(
        override val message: String,
    ) : IncrementalLayoutError {
        override val code: String = "layout.incremental-invalid-text-change"
    }

    /** Complete-operation resource limit that rejected the candidate publication. */
    public data class OperationLimitExceeded(
        /** Exact resource dimension, configured maximum, and rejecting observation. */
        public val limit: EditorOperationLimitExceeded,
    ) : IncrementalLayoutError {
        override val code: String = "layout.incremental-operation-limit-exceeded"
        override val message: String =
            "Editor operation ${limit.kind} limit ${limit.maximum} was exceeded by ${limit.observed}."
    }

    /** Typed paragraph failure that prevented a complete incremental line from being published. */
    public data class ParagraphFailure(
        /** Exact paragraph error returned by the complete JVM paragraph operation. */
        public val paragraphError: ParagraphLayoutError,
    ) : IncrementalLayoutError {
        override val code: String = "layout.incremental-paragraph-failure"
        override val message: String = paragraphError.message
    }
}

/** Typed outcome of incremental layout without partial output on failure or cancellation. */
public sealed interface IncrementalLayoutResult {
    /** Successfully published immutable incremental layout output. */
    public data class Success(
        /** Published layout. */
        public val layout: IncrementalLayout,
        /** Exact incremental orchestration diagnostics. */
        public val diagnostics: IncrementalLayoutDiagnostics,
    ) : IncrementalLayoutResult

    /** No layout was published because validation or layout failed. */
    public data class Failure(
        /** Typed failure. */
        public val error: IncrementalLayoutError,
    ) : IncrementalLayoutResult

    /** No layout was published because cooperative cancellation was observed. */
    public data object Cancelled : IncrementalLayoutResult

    /** A completed attempt was older than the session's latest accepted attempt and was discarded. */
    public data object Obsolete : IncrementalLayoutResult
}

private data class LayoutConfigurationValue(
    val constraints: ParagraphConstraints,
    val resolutionPolicy: ResolutionPolicyConfigurationValue,
    val fontInstanceDescriptor: FontInstanceDescriptor,
    val features: List<OpenTypeFeature>,
    val shapingConfigurationIdentity: String,
)

private data class ResolutionPolicyConfigurationValue(
    val generation: FontCatalogGeneration,
    val policyId: String,
    val version: String,
    val candidates: List<FontResolutionCandidate>,
    val lastResortFace: FontFaceId,
)

private fun FontResolutionPolicySnapshot.toConfigurationValue(): ResolutionPolicyConfigurationValue =
    ResolutionPolicyConfigurationValue(
        generation = generation,
        policyId = policyId,
        version = version,
        candidates = candidates,
        lastResortFace = lastResortFace,
    )

private data class RelativeRange(val start: Int, val endExclusive: Int)

private data class ShapedGlyphSignature(
    val glyphId: GlyphId,
    val xAdvance: LayoutUnit,
    val yAdvance: LayoutUnit,
    val xOffset: LayoutUnit,
    val yOffset: LayoutUnit,
    val safetyFlags: ShapingSafetyFlags,
    val clusterTokens: List<ShaperClusterToken>,
)

private data class ClusterSignature(
    val token: ShaperClusterToken,
    val sourceRange: RelativeRange,
    val scalarRanges: List<RelativeRange>,
    val admissibleGraphemeBoundaries: List<Int>,
)

private data class LigatureCaretSignature(
    val glyphIndex: Int,
    val state: GdefLigatureCaretState,
    val logicalSourceBoundaries: List<Int>,
    val positions: List<LayoutUnit>,
)

private data class ShapedRunSignature(
    val range: RelativeRange,
    val fontInstanceKey: FontInstanceKey,
    val shapingSemantics: ShapingSemanticIdentity,
    val direction: ShapingDirection,
    val script: OpenTypeScript,
    val language: String,
    val bidiLevel: Int,
    val bot: Boolean,
    val eot: Boolean,
    val featurePolicy: ShapingFeaturePolicy,
    val features: List<OpenTypeFeature>,
    val graphemeClusters: List<RelativeRange>,
    val glyphs: List<ShapedGlyphSignature>,
    val clusters: List<ClusterSignature>,
    val ligatureCarets: List<LigatureCaretSignature>,
)

private data class PositionedGlyphSignature(
    val shapedGlyph: ShapedGlyphSignature,
    val sourceClusters: List<ClusterSignature>,
    val origin: LayoutPoint,
    val advance: LayoutVector,
    val renderAssetKey: FontRenderAssetKey?,
    val materializationCertificate: GlyphMaterializationCertificate?,
)

private data class PositionedRunSignature(
    val sourceRun: ShapedRunSignature,
    val visualOrder: Int,
    val renderAssetKey: FontRenderAssetKey?,
    val glyphs: List<PositionedGlyphSignature>,
)

private data class CaretSignature(
    val boundary: Int,
    val affinity: CaretAffinity,
    val geometry: LayoutSegment,
    val visualOrder: Int,
    val visualRunOrder: Int,
    val bidiLevel: Int,
    val direction: ShapingDirection,
    val strength: CaretStrength,
    val edge: CaretBoundaryEdge,
)

private data class PositionedInlineObjectSignature(
    val sourceRange: RelativeRange,
    val definition: InlineObjectDefinition,
    val rect: LayoutRect,
)

private data class LineFragmentSignature(
    val availableInterval: InlineInterval,
    val positionedRuns: List<PositionedRunSignature>,
    val carets: List<CaretSignature>,
    val positionedInlineObjects: List<PositionedInlineObjectSignature>,
)

private data class DiagnosticSignature(
    val code: String,
    val severity: EditableLineDiagnosticSeverity,
    val message: String,
    val sourceRange: RelativeRange?,
    val glyphId: GlyphId?,
    val fallbackDiagnostic: FallbackDiagnosticSignature?,
)

private data class FallbackFragmentSignature(
    val range: RelativeRange,
    val script: OpenTypeScript,
    val language: String,
    val bidiLevel: Int,
)

private data class FallbackDiagnosticSignature(
    val range: RelativeRange,
    val unitRange: RelativeRange,
    val fragments: List<FallbackFragmentSignature>,
    val contributingFragments: List<FallbackFragmentSignature>,
    val faceId: FontFaceId,
    val representationProfile: GlyphRepresentationProfile?,
    val candidateRank: Int,
    val profileRank: Int?,
    val stage: FontFallbackStage,
    val reason: FontFallbackReason,
    val lastResortState: FontFallbackLastResortState,
)

private fun FallbackShapingFragment.toSignature(base: Int): FallbackFragmentSignature =
    FallbackFragmentSignature(range.relativeTo(base), script, language, bidiLevel)

private fun FontFallbackDiagnostic.toSignature(base: Int): FallbackDiagnosticSignature = FallbackDiagnosticSignature(
    range.relativeTo(base), unit.range.relativeTo(base), unit.fragments.map { it.toSignature(base) },
    contributingFragments.map { it.toSignature(base) }, faceId, representationProfile, candidateRank,
    profileRank, stage, reason, lastResortState,
)

private data class ObservableLineSignature(
    val baseDirection: ShapingDirection,
    val verticalMetrics: LineVerticalMetrics,
    val fragments: List<LineFragmentSignature>,
    val diagnostics: List<DiagnosticSignature>,
    val baseline: LayoutPoint,
    val contentMetrics: LineContentMetrics,
    val lineBox: LayoutRect,
    val designInkBounds: LayoutBounds,
)

private fun LineLayout.toObservableSignature(): ObservableLineSignature {
    val base = range.start.ordinal
    return ObservableLineSignature(
        baseDirection = baseDirection,
        verticalMetrics = verticalMetrics,
        fragments = fragments.map { fragment ->
            LineFragmentSignature(
                availableInterval = fragment.availableInterval,
                positionedRuns = fragment.positionedGlyphRuns.map { run -> run.toSignature(base) },
                carets = fragment.caretCandidates.map { candidate -> candidate.toSignature(base) },
                positionedInlineObjects = fragment.positionedInlineObjects.map { item ->
                    PositionedInlineObjectSignature(
                        sourceRange = item.sourceRange.relativeTo(base),
                        definition = item.definition,
                        rect = item.rect,
                    )
                },
            )
        },
        diagnostics = diagnostics.map { diagnostic ->
            DiagnosticSignature(
                code = diagnostic.code,
                severity = diagnostic.severity,
                message = diagnostic.message,
                sourceRange = diagnostic.sourceRange?.relativeTo(base),
                glyphId = diagnostic.glyphId,
                fallbackDiagnostic = diagnostic.fallbackDiagnostic?.toSignature(base),
            )
        },
        baseline = baseline,
        contentMetrics = contentMetrics,
        lineBox = lineBox,
        designInkBounds = designInkBounds,
    )
}

private fun CaretCandidate.toSignature(base: Int): CaretSignature = CaretSignature(
    boundary = position.index.ordinal - base,
    affinity = position.affinity,
    geometry = geometry,
    visualOrder = visualOrder,
    visualRunOrder = visualRunOrder,
    bidiLevel = bidiLevel,
    direction = direction,
    strength = strength,
    edge = edge,
)

private fun PositionedGlyphRun.toSignature(base: Int): PositionedRunSignature = PositionedRunSignature(
    sourceRun = sourceRun.toSignature(base),
    visualOrder = visualOrder,
    renderAssetKey = renderAssetKey,
    glyphs = glyphs.map { glyph -> glyph.toSignature(base) },
)

private fun ShapedGlyphRun.toSignature(base: Int): ShapedRunSignature = ShapedRunSignature(
    range = range.relativeTo(base),
    fontInstanceKey = fontInstanceKey,
    shapingSemantics = backendIdentity.semantic,
    direction = direction,
    script = script,
    language = language,
    bidiLevel = bidiLevel,
    bot = bot,
    eot = eot,
    featurePolicy = featurePolicy,
    features = features,
    graphemeClusters = graphemeClusters.map { it.relativeTo(base) },
    glyphs = glyphs.map(ShapedGlyph::toSignature),
    clusters = clusters.map { it.toSignature(base) },
    ligatureCarets = ligatureCaretFacts.map { fact ->
        LigatureCaretSignature(
            glyphIndex = fact.glyphIndex,
            state = fact.state,
            logicalSourceBoundaries = fact.logicalSourceBoundaries.map { it.ordinal - base },
            positions = fact.positions,
        )
    },
)

private fun ShapedGlyph.toSignature(): ShapedGlyphSignature = ShapedGlyphSignature(
    glyphId = glyphId,
    xAdvance = xAdvance,
    yAdvance = yAdvance,
    xOffset = xOffset,
    yOffset = yOffset,
    safetyFlags = safetyFlags,
    clusterTokens = clusterTokens,
)

private fun ShaperCluster.toSignature(base: Int): ClusterSignature = ClusterSignature(
    token = token,
    sourceRange = sourceRange.relativeTo(base),
    scalarRanges = scalarRanges.map { it.relativeTo(base) },
    admissibleGraphemeBoundaries = admissibleGraphemeBoundaries.map { it.ordinal - base },
)

private fun PositionedGlyph.toSignature(base: Int): PositionedGlyphSignature = PositionedGlyphSignature(
    shapedGlyph = shapedGlyph.toSignature(),
    sourceClusters = sourceClusters.map { it.toSignature(base) },
    origin = origin,
    advance = advance,
    renderAssetKey = renderAssetKey,
    materializationCertificate = materializationCertificate,
)

private fun TextRange.relativeTo(base: Int): RelativeRange =
    RelativeRange(start.ordinal - base, endExclusive.ordinal - base)

private fun validateRangeDomain(
    snapshot: TextSnapshot,
    range: TextRange,
    label: String,
): IncrementalLayoutError? {
    if (!range.start.belongsTo(snapshot) || !range.endExclusive.belongsTo(snapshot)) {
        return IncrementalLayoutError.VersionMismatch("$label must use the supplied snapshot version.")
    }
    if (!snapshot.contains(range)) {
        return IncrementalLayoutError.InvalidRange("$label must lie within the supplied snapshot.")
    }
    return null
}

private fun TextRange.isEmpty(): Boolean = start == endExclusive

private fun TextRange.usesVersion(version: TextVersion): Boolean =
    start.sharesVersionWith(TextIndex(version, 0))

private fun TextIndex.usesVersion(version: TextVersion): Boolean =
    sharesVersionWith(TextIndex(version, 0))

private fun LayoutTailState.rangeOrNull(): TextRange? = when (this) {
    LayoutTailState.MaterializedThroughDocumentEnd -> null
    is LayoutTailState.Stable -> range
    is LayoutTailState.Invalidated -> range
}

private fun unchangedScalarsMatch(
    source: TextSnapshot,
    target: TextSnapshot,
    sourceStart: Int,
    targetStart: Int,
    scalarCount: Int,
): Boolean =
    source.scalars.subList(sourceStart, sourceStart + scalarCount) ==
        target.scalars.subList(targetStart, targetStart + scalarCount)

private fun TextChange.canMergeWith(other: TextChange): Boolean {
    val targetAdjacent = insertedTargetRange.endExclusive == other.insertedTargetRange.start
    val sameBoundaryInserts =
        sourceRange.isEmpty() &&
            other.sourceRange.isEmpty() &&
            sourceRange.start == other.sourceRange.start
    val adjacentReplacements =
        !sourceRange.isEmpty() &&
            !other.sourceRange.isEmpty() &&
            sourceRange.endExclusive == other.sourceRange.start
    return targetAdjacent && (sameBoundaryInserts || adjacentReplacements)
}
