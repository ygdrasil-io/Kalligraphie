# Advanced typography and derived content

This guide documents the consumer journey for advanced typographic behaviors
published by `org.graphiks:kalligraphie`. Every behavior listed here is a real
pipeline behavior: it shapes real text with real fonts and publishes observable
geometry through `JvmEditableParagraphFacade` (or the portable
`ParagraphLayouter` contract).

All derived and synthetic content shares one rule: **it never creates a
document position**. Carets, selection, hit testing, and copy still consult
the `TextSnapshot`; synthetic glyphs anchor at real snapshot boundaries and
carry a `GlyphProvenance` explaining why they exist.

## Provenance (origine des glyphes)

`PositionedGlyph.provenance` classifies every final glyph:

- `GlyphProvenance.Direct(sourceRange)`: the glyph is shaped directly from
  the scalars of `sourceRange` (default for ordinary shaping);
- `GlyphProvenance.Derived(sourceRange, role)`: the glyph derives from a real
  source range through one transform (soft hyphen, automatic hyphen,
  justification spacing);
- `GlyphProvenance.Synthetic(anchor, role)`: the glyph is synthetic and
  anchored at the real boundary `anchor` (tab leader, ellipsis marker,
  kashida).

Roles (`GlyphProvenanceRole`) cover soft hyphen, automatic hyphen, kashida,
tab leader, ellipsis, and justification spacing. Provenance is a value type
with structural equality, so layouts remain comparable.

## Hyphenation (césure)

`HyphenationMode` on the paragraph request distinguishes `NONE`, `MANUAL`
(soft hyphens), and `AUTO` (soft hyphens + versioned `HyphenationService`).

- A soft hyphen (`U+00AD`) is invisible when the line does not break there:
  its glyph carries zero advance and keeps its real caret boundaries.
- When the line breaks exactly at the soft hyphen boundary, a visible hyphen
  is published with `GlyphProvenance.Derived` role `SOFT_HYPHEN`, derived
  from the soft hyphen source scalar.
- In `AUTO` mode an immutable, deterministic service computes word breaks.
  The reference service is `JvmPatternHyphenationService.english()`: Liang
  pattern matching over digest-verified `hyph-en-us.pat.txt` (see the
  resource `PROVENANCE.md`).
- In `AUTO` mode without a service, the layout remains valid without
  automatic hyphenation and emits the structured diagnostic
  `layout.hyphenation-service-absent`.

## Justification and kashida

`ParagraphPositioningPolicy(alignment = JUSTIFY, justificationMode = ...)`
distributes the remaining inline extent over final glyphs:

- `INTER_WORD` extends space glyphs (`GlyphProvenance.Derived`,
  `JUSTIFICATION_SPACING`);
- `INTER_CHARACTER` extends character gaps;
- `KASHIDA` inserts actual tatweel glyphs from the font into Arabic-script
  gaps, each synthetic with role `KASHIDA`; absence of a usable tatweel
  degrades deterministically to spacing with diagnostic
  `layout.kashida-unavailable`;
- `AUTO` selects from the script context (words, CJK characters, kashida).

Justification never changes the source text, cluster boundaries, or editorial
positions.

## Tab stops and leaders (taquets et conduites)

`TabStop(position, alignment, alignmentCharacter, leader)` supports `START`,
`END`, `CENTER`, and `DECIMAL` alignments on the inline axis. A `DECIMAL`
stop centers the field's decimal character on the stop; a field without the
character is right-aligned. `leader` repeats a scalar as synthetic content
(`TAB_LEADER` provenance) between the preceding content and the stop. The tab
scalar `U+0009` remains a real character with exactly two caret positions.

## Ellipsis (troncature)

`OverflowPolicy.Ellipsis(side, marker)` truncates content that cannot fit:

- `INLINE_END` keeps the largest prefix that fits with the marker;
- `INLINE_START` keeps the largest suffix;
- `MIDDLE` keeps a prefix and a suffix around the marker.

The published line keeps the complete source range: hidden scalars publish
zero-advance suppressed glyphs, the marker is a single synthetic glyph
(`ELLIPSIS` role) anchored at the truncation boundary, and the result carries
`ParagraphTruncation(hiddenRange, anchor, side)` describing the exact hidden
content. BiDi joins keep their normal duplicate-candidate semantics; the
marker never adds a caret.

## Inline objects

`InlineObjectSnapshot` maps each `U+FFFC` scalar to a consumer-owned
`InlineObjectDefinition` (opaque identity, width, height, baseline offset,
alignment). The engine advances the pen by the object width, publishes a
`PositionedInlineObject` with its physical rectangle, and leaves rendering to
the consumer. Caret boundaries exist exactly around the scalar; hit testing at
the object center returns the nearest boundary candidate; selection across the
object includes its rectangle; copying still reads the `U+FFFC` from the
`TextSnapshot`.

## Recertification (recertification des glyphes finaux)

In renderable mode, `GlyphMaterializationCertificate` is produced **after**
every transform — kashida, hyphen substitution, tab leader, ellipsis marker —
against the final glyph identifier. Every published glyph carries its
certificate and render asset key, including synthetic glyphs.

## Incremental equality

`incremental == full` holds across these behaviors: an edit that changes
hyphenation, justification, or inline objects produces identical observable
lines through the incremental session and the full facade (same ranges, glyphs
with provenance, origins, carets, and object rects).

## Known limitations

- Vertical writing modes (`vertical-rl` / `vertical-lr`, UTR #50 orientation,
  `vhea`/`vmtx` metrics) are **not** implemented in this release. Horizontal
  composition remains the only supported writing mode; the ticket documents
  this as the sole remaining advanced-typography capability.
- Flow regions, exclusions, and pagination belong to a later ticket.
