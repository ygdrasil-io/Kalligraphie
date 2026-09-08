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
  resource `PROVENANCE.md`). Its replay identity includes the provider,
  pattern revision, supported languages, and left/right minimums.
- An automatic break preserves every direct source glyph. It additionally
  publishes exactly one synthetic hyphen (`AUTOMATIC_HYPHEN`) anchored at the
  selected source boundary; the marker creates neither text nor a caret.
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
  gaps that are valid Unicode joining opportunities, each synthetic with role
  `KASHIDA`. It never crosses whitespace, non-Arabic text, or a letter that
  cannot join the following letter; absence of a usable tatweel
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
Fields are selected in logical source order between adjacent tab scalars, then
measured across their complete visual shaping runs. A fallback, script, or
BiDi boundary therefore does not reset their alignment or stop selection.

## Ellipsis (troncature)

`OverflowPolicy.Ellipsis(side, marker)` truncates content that cannot fit:

- `INLINE_END` keeps the largest prefix that fits with the marker;
- `INLINE_START` keeps the largest suffix;
- `MIDDLE` keeps a prefix and a suffix around the marker.

If no source cluster can fit with the marker, all three sides publish the
marker alone and describe the complete source range as hidden.

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
`TextSnapshot`. An entry with another snapshot revision, a boundary outside
the requested source range, or a scalar other than `U+FFFC` is rejected as
invalid input rather than ignored.

## Recertification (recertification des glyphes finaux)

In renderable mode, `GlyphMaterializationCertificate` is produced **after**
every transform — kashida, hyphen substitution, tab leader, ellipsis marker —
against the final glyph identifier. Every published glyph carries its
certificate and render asset key, including synthetic glyphs.

Fallback validation keeps only an operation-local proof containing the asset
key, glyph identifier, and accepted route. When all three still match after
the transforms, certification reuses that proof instead of materializing the
same glyph again. The proof contains neither an IR nor an asset handle, is not
published in `EditableLine` or `TextLayout`, and is discarded when the layout
call returns.

## Vertical writing

`ParagraphConstraints.writingMode` selects physical vertical composition:

- `VERTICAL_RL` advances glyphs from top to bottom and columns from right to
  left;
- `VERTICAL_LR` advances glyphs from top to bottom and columns from left to
  right.

Kalligraphie shapes each vertical run top-to-bottom with the OpenType `vert`
and `vrt2` features. `TextOrientation.MIXED` applies the pinned Unicode 16.0
UTR #50 `Vertical_Orientation` data to each extended-grapheme base scalar:
ideographic content is upright, while ordinary Latin content receives the
published clockwise-quarter-turn `LayoutAffineTransform`. `UPRIGHT` and
`SIDEWAYS` override that default for all clusters.

The final `PositionedGlyph` has a physical `y` advance and a renderer-visible
transform; Kalligraphie still renders no pixels. Carets are horizontal across a
vertical column, and selection geometry and hit testing use the same physical
axes. Neither rotation nor OpenType substitution creates a `TextIndex`.

Every selected final glyph reads its `vhea`/`vmtx` vertical metric. With
`VerticalMetricsPolicy.REQUIRE_FONT_METRICS`, a font without usable tables
fails composition. `SYNTHESIZE_IF_UNAVAILABLE` uses one em for that font and
publishes `layout.vertical-metrics-synthesized`. The CJK business fixture
exercises real `vhea`/`vmtx` tables; the Latin fixture exercises this explicit
fallback.

## Incremental equality

`incremental == full` holds across these behaviors: an edit that changes
hyphenation, justification, or inline objects produces identical observable
lines through the incremental session and the full facade (same ranges, glyphs
with provenance, origins, carets, and object rects).

The same guarantee covers both vertical writing modes. A continuation and an
incremental checkpoint retain the physical block-axis cursor, rather than
assuming that the next line is reached by increasing `y`. Reuse is allowed
only when the full observable composition configuration is identical:
positioning and tabs, hyphenation-service identity, inline objects,
orientation, and vertical-metrics policy are part of that identity.

## Flow composition

The advanced typography inputs above also participate in the exact identity of
`FlowContinuation` and incremental `FlowLayoutState` values. Flow-region
composition rejects reuse when it cannot prove the complete text, typography,
region, fragmentation, and paragraph configuration. See
[Editable paragraphs](editable-paragraphs.md#compose-through-regions-and-exclusions)
for the consumer route and application ownership boundary.
