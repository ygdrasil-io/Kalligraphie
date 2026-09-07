# Font Management

Kalligraphie exposes an embedded TrueType path through
`org.graphiks:kalligraphie` on the JVM reference target only. The public
contracts stay portable, but this executable route is JVM-only. A
consumer supplies captured SFNT bytes to `Kalligraphie.embedded(...)`,
selects a stable face record, creates a font instance, and uses a render asset handle to
materialize a portable glyph representation.

The supported functional scope is intentionally narrow:

- JVM reference target only;
- static SFNT TrueType only: `0x00010000` and `true`;
- embedded OpenType sources with face index `0` for each source;
- `LAYOUT_ONLY` for cmap and metrics;
- `RENDERABLE` with schema version `1` `OutlineProfile`, `PaintGraphProfile`,
  or `BitmapProfile` when the selected face advertises the matching route;
- `glyf` outlines in design units, with separately scaled `LayoutUnit`
  metrics;
- COLR version 0 and CPAL version 0 paint graphs made from solid outlines,
  ordered groups, exact CPAL palette selection, and an explicit foreground
  color;
- SVG-in-OpenType table version 0 with raw UTF-8 documents only: `svg`, `g`,
  and self-closing `path` elements; `translate` and `scale`; `M`, `L`, `H`,
  `V`, `C`, `S`, and `Z` path commands; opaque `#RRGGBB` fills; and `fill="none"`
  for explicitly inkless paths. Scripts,
  external resources, entities, animation, compression, gradients, clips,
  masks, strokes, and unlisted attributes are rejected before an asset is
  published;
- EBLC version 2 / EBDT version 2 bitmap strikes using index subtable format 1
  and image format 1 only: byte-aligned one-bit alpha decoded to `ALPHA_8` in
  sRGB, with an exact requested strike;
- detached render assets that keep resolving after the owning resolver or
  attached handle is closed.

```kotlin
val catalogResult = Kalligraphie.embedded(bytes, provenance)
val faceId = catalog.faces.single().id
val size = FontInstanceDescriptor(LayoutUnit(2048f))
val requirements = FontAccessRequirementsSnapshot.renderable(outlineProfile)
```

Renderable glyph access requires an explicit representation profile. Closing a
resolver or render asset is idempotent. New acquisitions after closure return
`font.resource-closed`; a detached asset owns the immutable data required for
`resolveGlyph(...)`.

### Bounded representation retention

`FontMaterializationCachePolicy` optionally retains complete immutable portable outline,
paint-graph, and decoded-bitmap results for one captured face. The policy is disabled by default and can be passed to
`Kalligraphie.embedded(...)` or `MacosSystemFontCatalogOptions`. Its byte budget is a cost policy
only: it neither changes route selection nor any representation key, certificate, diagnostic, or
glyph result. Entries are scoped to one provider generation and face, weighted by retained
normalized contour and paint data plus decoded bitmap pixels, and evicted least-recently-used first. Cancellation and operational
errors are never retained; a result larger than the budget is returned normally without being
retained. No cache entry holds a resolver, render asset, catalog, or native resource.

```kotlin
val cachePolicy = FontMaterializationCachePolicy(maxEvictableBytesPerFace = 4L * 1024L * 1024L)
val catalogResult = Kalligraphie.embedded(bytes, provenance, cachePolicy)
```

The cache is released after the last resolver or render asset using that face closes. Detached
assets keep their ordinary resource lease, so detaching does not change an already-admitted
operation or expose a closed cache entry.

On macOS, the JVM artifact also exposes `MacosSystemFontCatalog.open()`. It
captures bounded, regular `.ttf` files into a portable snapshot and uses the
same routes as embedded fonts. It does not expose CoreText handles, nor claim
support for `.otf` or `.ttc` files.

## Exact editable Unicode lines

The JVM reference target also provides one complete headless route for a
single non-wrapped editable line. `Kalligraphie.decodeUtf8(...)` or
`Kalligraphie.decodeUtf16(...)` creates an immutable `TextSnapshot`. The
JVM-only `JvmEditableLineFacade` then analyzes Unicode, resolves script and
BiDi runs, shapes each run with its embedded HarfBuzz backend, and positions
the final line.

```kotlin
val decoded = Kalligraphie.decodeUtf8(
    version = TextVersion.create(),
    slices = listOf(TextSlice.Utf8(editorBytes)),
)
val result = JvmEditableLineFacade.layout(
    JvmEditableLineFacadeRequest(
        snapshot = decoded.snapshot,
        font = instance,
        baseDirection = BaseDirection.LEFT_TO_RIGHT,
        language = "en",
        featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
        features = emptyList(),
        verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
        materialization = EditableLineMaterialization.LayoutOnly,
    ),
)
```

Direction, language, feature policy, feature overrides, line metrics, and
publication mode are required inputs. Script and resolved run direction are
produced by the pinned Unicode analysis and passed explicitly to every shaping
request. The result is `EditableLineResult`: on success it contains shaped and
positioned glyphs, text-to-cluster-to-glyph mappings, logical and visual caret
navigation, selection geometry, and deterministic hit testing.

For `RENDERABLE` output, replace `LayoutOnly` with
`EditableLineMaterialization.Renderable` and provide an open resolver, a
variant, and an `OutlineProfile`. Every published final glyph then carries an
outline-route certificate tied to its exact `FontRenderAssetKey`. The resolver
remains caller-owned; the facade borrows it only during the synchronous call.

The embedded HarfBuzz 14.3.0 backend is the JVM reference implementation. Its
Linux and macOS x64/arm64 resources are pinned, hash-verified, and never found
through a system-library search. Public contracts contain no JNI or native
types. Android and Apple do not yet provide executable shaping adapters, so
this route must not be treated as conformant on those platforms.

## Deterministic multi-font fallback

`EmbeddedFontCatalog` can capture several audited OpenType sources in one
`FontCatalogGeneration`. `FontResolutionPolicySnapshot` binds a complete,
versioned candidate order and an explicit final last-resort face to that exact
generation. `ExactEditableLineLayouter.layout(MultiFontEditableLineRequest)`
derives fallback units from Unicode grapheme analysis, assigns every unit to
one face, and shapes the affected contiguous context.

In `LAYOUT_ONLY`, a candidate must map and shape the complete unit. In
`RENDERABLE`, it must additionally materialize every final shaped glyph with
the requested outline profile. Failed candidates are blacklisted for the
operation and never silently retried for the same unit and profile. The
published `PositionedGlyphRun` records its actual `FontInstanceKey`; every
renderable glyph carries a certificate tied to its exact generation-bound
asset key. A resolver may reopen such a key only in the captured generation;
a detached asset remains independently usable after its originating resolver
closes.

Out of scope for the editable-line API: hyphenation,
justification, vertical writing, rendering pixels, GPU APIs, TTC/OTC,
CFF/CFF2, variations, synthetic styles, and render routes other than the
outline route it explicitly requests. See
[Editable Paragraphs](editable-paragraphs.md) for the JVM multiline paragraph
route.
