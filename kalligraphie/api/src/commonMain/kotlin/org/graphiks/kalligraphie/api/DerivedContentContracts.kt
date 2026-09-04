package org.graphiks.kalligraphie.api

/**
 * Typed role explaining why a final glyph exists when it is not the direct
 * cursor-shaped output of the source scalars.
 *
 * Roles are durable domain vocabulary: they never name a milestone, plan, or
 * internal scheduling artifact. A role classifies the typographic transform
 * that produced the glyph so a consumer can understand and audit why the
 * glyph exists without ever turning derived or synthetic content into
 * document characters.
 */
public enum class GlyphProvenanceRole {
    /**
     * The glyph is the visible hyphen-in-minus replacing an invisible
     * `U+00AD SOFT HYPHEN` at a line boundary selected by the layouter.
     */
    SOFT_HYPHEN,

    /**
     * The glyph is the explicit hyphen inserted by an automatic, versioned
     * hyphenation service at a boundary inside a word.
     */
    AUTOMATIC_HYPHEN,

    /**
     * The glyph is a justification-extended kashida (tatweel) inserted to
     * stretch an Arabic-script line between words or letters.
     */
    KASHIDA,

    /**
     * The glyph is the repeated fill content published inside a tab stop
     * before an aligned field, such as the dots of a dotted leader.
     */
    TAB_LEADER,

    /**
     * The glyph is the ellipsis marker published by a truncation policy.
     */
    ELLIPSIS,

    /**
     * The glyph is an original glyph whose advance was extended by
     * inter-word or inter-character justification spacing.
     */
    JUSTIFICATION_SPACING,
}

/**
 * Explicit hyphenation behavior requested for one paragraph.
 *
 * [NONE] keeps every hyphen scalar and word handled exactly as shaped by the
 * selected fonts without any derived hyphen; [MANUAL] enables soft hyphens;
 * [AUTO] additionally consults an immutable [HyphenationService] when one is
 * supplied, and produces a structured diagnostic deterministically when the
 * mode is [AUTO] but no service is available.
 */
public enum class HyphenationMode {
    /** Hyphenation is disabled; soft hyphens are not reinterpreted. */
    NONE,

    /** Soft hyphens participate and can publish a visible hyphen. */
    MANUAL,

    /** Soft hyphens plus an automatic versioned hyphenation service. */
    AUTO,
}

/**
 * Versioned identity of one immutable, deterministic hyphenation service.
 *
 * The identity participates in replay identity: two services with different
 * [dataRevision] identifiers may produce different break sets, while equal
 * identities must produce equal break sets for equal input words and equal
 * [HyphenationService.hyphenmins].
 */
public class HyphenationServiceIdentity(
    /** Stable provider identifier. */
    public val providerId: String,
    /** Version of the patterns or dictionary data. */
    public val dataRevision: String,
    /** Immutable BCP 47 language tags this service serves, in declared order. */
    public val languages: List<String>,
) {
    /** Immutable defensive language snapshot. */
    public val languagesSnapshot: List<String> = languages.immutableListSnapshot()

    init {
        require(providerId.isNotBlank()) { "Hyphenation provider identifiers must not be blank." }
        require(dataRevision.isNotBlank()) { "Hyphenation data revisions must not be blank." }
        require(this.languagesSnapshot.isNotEmpty()) { "A hyphenation service must declare at least one language." }
        require(this.languagesSnapshot.all { tag -> tag.isNotBlank() }) { "Hyphenation language tags must not be blank." }
    }
}

/**
 * Immutable, deterministic, versioned service computing word hyphenation breaks.
 *
 * A service is pure: [hyphenation]([word], [language], [hyphenmins]) must
 * return the same break offsets for the same inputs on every call and every
 * platform. Services own no resource, never mutate the word, and are safe to
 * share between threads. Offsets are positions between scalars; the first
 * [HyphenationMinimums.left] and the last [HyphenationMinimums.right] scalars
 * of a word are never breakable.
 */
public interface HyphenationService {
    /** Versioned identity of this immutable service. */
    public val identity: HyphenationServiceIdentity

    /**
     * Returns the half-open internal break offsets of [word] in increasing order.
     *
     * [word] contains Unicode scalar values, never surrogate pairs or malformed
     * subsequences. [language] is the BCP 47 tag used for this word; services
     * without support for [language] return a deterministic empty result
     * rather than an exception or approximate data. [hyphenmins] overrides the
     * service defaults for this call.
     */
    public fun hyphenation(
        word: List<Int>,
        language: String,
        hyphenmins: HyphenationMinimums = HyphenationMinimums.default,
    ): List<Int>
}

/** Per-side minimum scalars kept on a line around an automatic hyphen break. */
public class HyphenationMinimums(
    /** Minimum scalars kept before the break (including the hyphen position). */
    public val left: Int,
    /** Minimum scalars kept after the break. */
    public val right: Int,
) {
    init {
        require(left >= 1) { "Hyphenation minimum on the left must be at least one scalar." }
        require(right >= 1) { "Hyphenation minimum on the right must be at least one scalar." }
    }

    /** Default per-side minimum of two scalars on the left and three on the right. */
    public companion object {
        /** Standard `left=2, right=3` minimums. */
        public val default: HyphenationMinimums = HyphenationMinimums(2, 3)
    }
}

/**
 * Horizontal paragraph positioning policy: alignment, justification, and tabs.
 *
 * Values are immutable, own no resource, and are safe to share between
 * threads. [alignment] applies to every line except the last, which uses
 * [lastLineAlignment] when supplied. [JustificationMode.AUTO] derives
 * inter-word, inter-character, or kashida spacing from the script context.
 */
public class ParagraphPositioningPolicy(
    /** Alignment of non-final lines in the physical inline axis. */
    public val alignment: ParagraphAlignment = ParagraphAlignment.START,
    /** Justification mode applied to fully justified lines. */
    public val justificationMode: JustificationMode = JustificationMode.AUTO,
    /** Alignment of the final paragraph line, or `null` to reuse [alignment]. */
    public val lastLineAlignment: ParagraphAlignment? = null,
    tabStops: List<TabStop> = emptyList(),
    /** Default distance between implicit tab stops when no explicit stop applies. */
    public val defaultTabInterval: LayoutUnit = LayoutUnit(1000f / 8f),
) {
    /** Immutable explicit tab stops in increasing position order. */
    public val tabStops: List<TabStop> = tabStops.immutableListSnapshot()

    init {
        require(defaultTabInterval.value > 0f) { "Default tab interval must be strictly positive." }
        require(this.tabStops.zipWithNext().all { (left, right) -> left.position.value < right.position.value }) {
            "Tab stops must be strictly ordered by position."
        }
    }
}

/** Physical alignment of line content within one line box. */
public enum class ParagraphAlignment {
    /** Content begins at the logical start of the inline axis. */
    START,

    /** Content ends at the logical end of the inline axis. */
    END,

    /** Content is centered in the available inline extent. */
    CENTER,

    /** Content spans the complete available inline extent. */
    JUSTIFY,
}

/** Justification distribution policy for fully justified lines. */
public enum class JustificationMode {
    /** Select spacing from the script context: words, CJK characters, or kashida. */
    AUTO,

    /** Distribute extra advance across space glyphs only. */
    INTER_WORD,

    /** Forced distribution across every eligible character gap. */
    INTER_CHARACTER,

    /** Insert explicit kashida glyphs on every eligible Arabic-script gap. */
    KASHIDA,
}

/** Alignment of a field bounded by tab stops. */
public enum class TabAlignment {
    /** Field content begins at the tab stop position. */
    START,

    /** Field content ends at the tab stop position. */
    END,

    /** Field content is centered on the tab stop position. */
    CENTER,

    /** The field's decimal alignment character is centered on the tab stop position. */
    DECIMAL,
}

/**
 * One explicit tab stop.
 *
 * [position] is the physical inline offset of the stop from the line start
 * (the paragraph region's logical inline origin). [alignmentCharacter] is the
 * scalar used by [TabAlignment.DECIMAL]; if a field contains none, the field
 * is right-aligned. [leader] is the scalar repeated as synthetic fill between
 * the previous content and the stop, or `null` for no leader.
 */
public class TabStop(
    /** Inline position of the stop from the line start. */
    public val position: LayoutUnit,
    /** Alignment applied to the field starting at this stop. */
    public val alignment: TabAlignment = TabAlignment.START,
    /** Alignment character used by [TabAlignment.DECIMAL]. */
    public val alignmentCharacter: Int = DECIMAL_ALIGNMENT_CHARACTER,
    /** Leader scalar, or `null` when the gap is left empty. */
    public val leader: Int? = null,
) {
    init {
        require(position.value >= 0f) { "Tab stop positions must be non-negative." }
        require(alignmentCharacter.isUnicodeScalarValue()) { "Tab alignment characters must be Unicode scalar values." }
        require(leader == null || leader.isUnicodeScalarValue()) { "Tab leader must be a Unicode scalar value." }
    }

    /** Compares position and the complete stop behavior. */
    override fun equals(other: Any?): Boolean =
        other is TabStop &&
            position == other.position &&
            alignment == other.alignment &&
            alignmentCharacter == other.alignmentCharacter &&
            leader == other.leader

    /** Returns a stable hash of the complete stop behavior. */
    override fun hashCode(): Int = 31 * (31 * (31 * position.hashCode() + alignment.hashCode()) + alignmentCharacter) +
        (leader ?: 0)

    /** Returns a diagnostic form containing the complete stop behavior. */
    override fun toString(): String =
        "TabStop(position=$position, alignment=$alignment, alignmentCharacter=$alignmentCharacter, leader=$leader)"
}

private const val DECIMAL_ALIGNMENT_CHARACTER: Int = 0x002E

private fun Int.isUnicodeScalarValue(): Boolean = this in 0..0x10FFFF && this !in 0xD800..0xDFFF

/**
 * Typed provenance of one final positioned glyph.
 *
 * Provenance never changes source text: [Synthetic] anchors to a real
 * snapshot boundary and [Derived] names a real source range, but neither
 * creates a new document scalar, [TextIndex], caret candidate, or editable
 * position. Consumers read it for audit, highlighting, and rendering
 * choices; copy and editing operations always consult the [TextSnapshot].
 */
public sealed interface GlyphProvenance {
    /**
     * The glyph is shaped directly from the source scalars named by [sourceRange].
     *
     * This is the default provenance of every glyph produced by OpenType
     * shaping without any derived-content transform.
     */
    public class Direct(
        /** Half-open source range whose source scalars produced this glyph. */
        public val sourceRange: TextRange,
    ) : GlyphProvenance {
        /** Compares the complete provenance value. */
        override fun equals(other: Any?): Boolean = other is Direct && sourceRange == other.sourceRange

        /** Returns a stable hash of the complete provenance value. */
        override fun hashCode(): Int = sourceRange.hashCode()

        /** Returns a diagnostic form containing the complete provenance value. */
        override fun toString(): String = "GlyphProvenance.Direct(sourceRange=$sourceRange)"
    }

    /**
     * The glyph derives from the source scalars named by [sourceRange] through
     * one explicit typographic transform ([role]).
     *
     * [sourceRange] never names synthetic characters: it always identifies
     * real source scalars, typically the range of a hidden soft hyphen, a
     * justified space, or a vertical-orientation alternate.
     */
    public class Derived(
        /** Exact half-open source range transformed by [role]. */
        public val sourceRange: TextRange,
        /** Transform that produced this glyph. */
        public val role: GlyphProvenanceRole,
    ) : GlyphProvenance {
        /** Compares the complete provenance value. */
        override fun equals(other: Any?): Boolean =
            other is Derived && sourceRange == other.sourceRange && role == other.role

        /** Returns a stable hash of the complete provenance value. */
        override fun hashCode(): Int = 31 * sourceRange.hashCode() + role.hashCode()

        /** Returns a diagnostic form containing the complete provenance value. */
        override fun toString(): String = "GlyphProvenance.Derived(sourceRange=$sourceRange, role=$role)"
    }

    /**
     * The glyph does not exist in the source and is synthesized to satisfy a
     * typographic behavior, anchored at the real snapshot boundary [anchor].
     *
     * [anchor] must be a boundary already present in the [TextSnapshot]:
     * synthesizing a glyph never creates a new document position. The glyph's
     * cluster relation points at the cluster adjacent to [anchor], but no
     * caret, selection boundary, or copyable text is introduced by it.
     */
    public class Synthetic(
        /** Real snapshot boundary at which this glyph was anchored. */
        public val anchor: TextIndex,
        /** Behavior that required this synthetic glyph. */
        public val role: GlyphProvenanceRole,
    ) : GlyphProvenance {
        /** Compares the complete provenance value. */
        override fun equals(other: Any?): Boolean =
            other is Synthetic && anchor == other.anchor && role == other.role

        /** Returns a stable hash of the complete provenance value. */
        override fun hashCode(): Int = 31 * anchor.hashCode() + role.hashCode()

        /** Returns a diagnostic form containing the complete provenance value. */
        override fun toString(): String = "GlyphProvenance.Synthetic(anchor=$anchor, role=$role)"
    }
}
