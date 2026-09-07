# Font Management

Kalligraphie exposes embedded and JVM system-font catalogs through
`org.graphiks:kalligraphie`. The common contracts stay portable, while the
executable reference route is JVM-only. A consumer opens an immutable catalog,
selects a stable face record, creates an instance, acquires a detachable render
asset, and resolves one certified glyph without parsing OpenType or retaining a
renderer resource.

The supported functional scope is intentionally narrow:

- JVM reference target only;
- static SFNT TrueType only: `0x00010000` and `true`;
- embedded OpenType sources with face index `0` for each source, or an immutable
  snapshot of `.ttf` files found in the supported JVM system-font directories;
- `LAYOUT_ONLY` for cmap and metrics;
- `RENDERABLE` with an explicitly ordered compatible profile:
  `OutlineProfile` schema version `1`, `PaintGraphProfile` schema version `1`,
  or `BitmapProfile` schema version `1`;
- TrueType outlines in design units, with separately scaled `LayoutUnit` metrics;
- COLR/CPAL version `0` paint graphs with solid outline layers and source-order
  grouping, including an explicitly selected CPAL palette and foreground color;
- uncompressed OpenType `SVG ` version `0` documents reduced to bounded paths,
  matrix transforms, solid fills, and linear or radial gradients; the source SVG
  document is never returned to the caller;
- EBLC version `2` index-subtable format `1` with EBDT version `2` image format
  `1` one-bit pixels, normalized as `Alpha8` sRGB bitmap glyphs;
- detached render assets that keep resolving after the owning resolver or
  attached handle is closed.

No native bridge is currently implemented. `NativeHandleProfile`, COLR version
`1`, compressed SVG, SVG scripts, links, animation, filters, masks, clips,
images and text, bitmap codecs other than EBDT image format `1`, CFF/CFF2,
collections, variations, synthetic styles, and GPU or rasterizer APIs are
rejected before a representation is certified. Profile limits bound source
bytes, graph nodes and references, SVG depth and geometry, bitmap table and
record bytes, dimensions, per-glyph and aggregate strike pixels, and decoded
bytes. A requested route is never silently replaced with a native or
less-faithful route.

```kotlin
fun <T> success(result: FontOperationResult<T>): T = when (result) {
    is FontOperationResult.Success -> result.value
    is FontOperationResult.Failure -> error(result.error.message)
    is FontOperationResult.Cancelled -> error("cancelled")
}

val catalog = success(Kalligraphie.embedded(bytes, provenance))
val resolver = success(catalog.openAssetResolver())
val requirements = FontAccessRequirementsSnapshot.renderable(outlineProfile)
val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f))))
val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, requirements))
val certified = success(asset.resolveGlyphCertified(FontGlyphRequest(GlyphId(36))))
val sameCertifiedRepresentation = success(asset.resolveCertifiedGlyph(certified.certificate))
val detached = success(asset.detach())
asset.close()
resolver.close()
val stillUsable = success(detached.resolveCertifiedGlyph(certified.certificate))
detached.close()
```

The snippet uses an outline profile; a paint or bitmap request follows the
identical lifecycle with its own explicit profile and limits. It deliberately
shows a certificate: `resolveCertifiedGlyph(...)` accepts only the exact asset
key, glyph ID, selected profile, schema version, route, render variant, and
catalog generation that produced it. Closing a resolver or render asset is
idempotent. An operation already admitted may finish, later acquisitions return
`font.resource-closed`, and a detached asset owns the immutable data required
for a later resolution. `JvmFontCatalogs.openSystemFontCatalog()` follows the
same sequence for an installed JVM font snapshot; every invocation creates a
new provider generation.

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

Out of scope for the editable-line API: hyphenation, justification, vertical
writing, rendering pixels, GPU APIs, TTC/OTC, CFF/CFF2, variations, synthetic
styles, and system-font fallback. Its renderable mode remains outline-only;
use the standalone catalog route above for the supported COLR/CPAL, SVG, and
bitmap representations. See [Editable Paragraphs](editable-paragraphs.md) for
the JVM multiline paragraph route.
