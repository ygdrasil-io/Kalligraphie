package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphPaintBrush
import org.graphiks.kalligraphie.api.GlyphPaintGradientKind
import org.graphiks.kalligraphie.api.GlyphPaintGradientSpread
import org.graphiks.kalligraphie.api.GlyphPaintGradientStop
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintNodeKind
import org.graphiks.kalligraphie.api.GlyphPaintPath
import org.graphiks.kalligraphie.api.GlyphPaintPathCommand
import org.graphiks.kalligraphie.api.GlyphPaintTransform
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.PaintGraphLimits
import org.graphiks.kalligraphie.api.PaintGraphProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SvgOpenTypeGlyphRepresentationTest {
    @Test
    fun rejectsAnImpossibleNonDefaultSvgAssetKeyBeforeReopeningIt() {
        val catalog = success(Kalligraphie.embedded(fixtureBytes(), FontSourceProvenance("SVG reopen route sample")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(svgProfile()))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val variant = FontRenderVariantSnapshot(cpalPaletteIndex = 0)

        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            val impossibleKey = try {
                asset.key.copy(variant = variant.key, variantSnapshot = variant)
            } finally {
                asset.close()
            }

            val failure = assertIs<FontOperationResult.Failure>(resolver.reopen(impossibleKey))

            assertIs<FontError.AssetUnavailable>(failure.error)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun preservesAnAncestorGroupTransformWhenNormalizingTheTargetGlyph() {
        val transformed = fixtureBytes().also { bytes ->
            rewriteLastSvgDocument(bytes) { document ->
                val wrapped = document
                    .replace(" xmlns=\"http://www.w3.org/2000/svg\"", "")
                    .replace(" version=\"1.1\"", "")
                    .replace(
                        "<g id=\"glyph27\"",
                        "<g transform=\"matrix(2 0 0 2 0 0)\"><g id=\"glyph27\"",
                    )
                    .replace("</g></svg>", "</g></g></svg>")
                val padding = document.length - wrapped.length
                require(padding >= 0) { "Test mutation must not enlarge the SVG document" }
                wrapped.replace("</svg>", "${" ".repeat(padding)}</svg>")
            }
        }
        val catalog = success(Kalligraphie.embedded(transformed, FontSourceProvenance("SVG inherited-transform sample")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(svgProfile()))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))

        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val paint = assertIs<GlyphRepresentation.Paint>(
                    success(asset.resolveGlyph(FontGlyphRequest(GlyphId(27)))),
                ).paint

                assertEquals(
                    GlyphPaintTransform(240.0, 0.0, 0.0, 240.0, 75.0, -1_900.0),
                    assertIs<GlyphPaintNode.Transform>(paint.nodes[1]).transform,
                )
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun normalizesTheOpenTypeSvgRadialGradientAndGlyphTransformWithoutExposingSvgSource() {
        val catalog = success(Kalligraphie.embedded(fixtureBytes(), FontSourceProvenance("Google Fonts color-fonts SVG sample")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(svgProfile()))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))

        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val paint = assertIs<GlyphRepresentation.Paint>(
                    success(asset.resolveGlyph(FontGlyphRequest(GlyphId(27)))),
                ).paint

                assertEquals(1, paint.schemaVersion)
                assertEquals(1, paint.rootNode)
                assertEquals(
                    listOf(
                        GlyphPaintNode.Path(
                            path = GlyphPaintPath(
                                listOf(
                                    GlyphPaintPathCommand.MoveTo(9.0, 5.0),
                                    GlyphPaintPathCommand.ArcTo(4.0, 4.0, 0.0, true, true, 1.0, 5.0),
                                    GlyphPaintPathCommand.ArcTo(4.0, 4.0, 0.0, true, true, 9.0, 5.0),
                                    GlyphPaintPathCommand.Close,
                                ),
                            ),
                            brush = GlyphPaintBrush.RadialGradient(
                                centerX = 5.0,
                                centerY = 5.0,
                                radius = 4.0,
                                stops = listOf(
                                    GlyphPaintGradientStop(0.1, GlyphColor(255, 215, 0)),
                                    GlyphPaintGradientStop(0.95, GlyphColor(255, 0, 0)),
                                ),
                            ),
                        ),
                        GlyphPaintNode.Transform(
                            child = 0,
                            transform = GlyphPaintTransform(120.0, 0.0, 0.0, 120.0, 37.5, -950.0),
                        ),
                    ),
                    paint.nodes,
                )
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun rejectsAForbiddenSvgUseElementAndExcessiveDocumentDepthBeforePublishingPaint() {
        val malicious = fixtureBytes().also { bytes -> replaceAscii(bytes, "<path", "<use ") }
        val catalog = success(Kalligraphie.embedded(malicious, FontSourceProvenance("malicious SVG sample")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(svgProfile()))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))

        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val failure = assertIs<FontOperationResult.Failure>(asset.resolveGlyph(FontGlyphRequest(GlyphId(27))))
                assertIs<FontError.UnsupportedRepresentationProfile>(failure.error)
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }

        val shallowRequirements = FontAccessRequirementsSnapshot.renderable(listOf(svgProfile(maxSvgDepth = 3)))
        val shallowCatalog = success(Kalligraphie.embedded(fixtureBytes(), FontSourceProvenance("depth-limited SVG sample")))
        val shallowResolver = success(shallowCatalog.openAssetResolver())
        val shallowFace = success(shallowCatalog.resolveFace(shallowCatalog.faces.single().id, shallowRequirements))
        val shallowInstance = success(shallowFace.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        try {
            val asset = success(shallowInstance.acquireRenderAsset(shallowResolver, FontRenderVariantKey.default, shallowRequirements))
            try {
                val failure = assertIs<FontOperationResult.Failure>(asset.resolveGlyph(FontGlyphRequest(GlyphId(27))))
                assertIs<FontError.ResourceLimitExceeded>(failure.error)
            } finally {
                asset.close()
            }
        } finally {
            shallowResolver.close()
        }
    }

    @Test
    fun rejectsAnSvgFillRuleThatThePortablePaintIrCannotRepresent() {
        val malicious = fixtureBytes().also { bytes ->
            rewriteLastSvgDocument(bytes) { document ->
                document
                    .replace(" version=\"1.1\"", "")
                    .replace(" fill=\"url(#g1)\"", " fill-rule=\"evenodd\"")
                    .replace("</svg>", "          </svg>")
            }
        }
        val catalog = success(Kalligraphie.embedded(malicious, FontSourceProvenance("unsupported SVG fill rule sample")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(svgProfile()))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))

        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val failure = assertIs<FontOperationResult.Failure>(asset.resolveGlyph(FontGlyphRequest(GlyphId(27))))
                assertIs<FontError.UnsupportedRepresentationProfile>(failure.error)
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    private fun svgProfile(maxSvgDepth: Int = 8): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = listOf(GlyphPaintNodeKind.PATH, GlyphPaintNodeKind.TRANSFORM),
        acceptedCompositionModes = emptyList(),
        limits = PaintGraphLimits(
            maxNodes = 2,
            maxReferences = 1,
            maxDepth = 2,
            maxSourceBytes = 8_192,
            maxPaths = 1,
            maxGradients = 1,
            maxCompressedSvgBytes = 1_024,
            maxDecompressedSvgBytes = 1_024,
            maxSvgDepth = maxSvgDepth,
            maxSvgPathCommands = 8,
            maxSvgGradientStops = 4,
        ),
        outlineProfile = OutlineProfile(
            maxBytes = 1_024,
            maxContours = 8,
            maxPoints = 64,
            maxCompositeDepth = 2,
            maxCompositeComponents = 2,
        ),
        acceptedGradientKinds = listOf(GlyphPaintGradientKind.RADIAL),
        acceptedGradientSpreads = listOf(GlyphPaintGradientSpread.PAD),
    )

    private fun fixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/color-fonts-samples-picosvg/samples-picosvg.ttf")) {
            "Google Fonts color-fonts SVG fixture is missing"
        }.use { input -> input.readBytes() }

    private fun replaceAscii(bytes: ByteArray, expected: String, replacement: String) {
        require(expected.length == replacement.length)
        val expectedBytes = expected.encodeToByteArray()
        val start = bytes.indices.filter { offset ->
            offset + expectedBytes.size <= bytes.size && expectedBytes.indices.all { index -> bytes[offset + index] == expectedBytes[index] }
        }.lastOrNull() ?: error("Test fixture does not contain $expected")
        replacement.encodeToByteArray().forEachIndexed { index, value -> bytes[start + index] = value }
    }

    private fun rewriteLastSvgDocument(bytes: ByteArray, transform: (String) -> String) {
        val start = lastAsciiIndex(bytes, "<svg") ?: error("Test fixture has no SVG document")
        val close = asciiIndex(bytes, "</svg>", start) ?: error("Test fixture has an unterminated SVG document")
        val end = close + "</svg>".length
        val document = bytes.copyOfRange(start, end).decodeToString()
        val rewritten = transform(document)
        require(rewritten.length == document.length) { "Test mutation must preserve the SVG document length" }
        rewritten.encodeToByteArray().copyInto(bytes, destinationOffset = start)
    }

    private fun lastAsciiIndex(bytes: ByteArray, expected: String): Int? {
        val expectedBytes = expected.encodeToByteArray()
        return bytes.indices.lastOrNull { offset ->
            offset + expectedBytes.size <= bytes.size && expectedBytes.indices.all { index -> bytes[offset + index] == expectedBytes[index] }
        }
    }

    private fun asciiIndex(bytes: ByteArray, expected: String, start: Int): Int? {
        val expectedBytes = expected.encodeToByteArray()
        return (start..bytes.size - expectedBytes.size).firstOrNull { offset ->
            expectedBytes.indices.all { index -> bytes[offset + index] == expectedBytes[index] }
        }
    }

    private fun <T> success(result: FontOperationResult<T>): T = when (result) {
        is FontOperationResult.Success -> result.value
        is FontOperationResult.Failure -> error("Unexpected font failure ${result.error.code}: ${result.error.message}")
        is FontOperationResult.Cancelled -> error("Unexpected font cancellation")
    }
}
