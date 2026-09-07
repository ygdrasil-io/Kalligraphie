package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SvgPaintGraphProfileTest {
    @Test
    fun requiresTheConsumerToDeclareTheExactGradientKindSpreadAndTransformNodes() {
        val paint = GlyphPaintIR(
            schemaVersion = 1,
            rootNode = 1,
            nodes = listOf(
                GlyphPaintNode.Path(
                    path = GlyphPaintPath(
                        listOf(
                            GlyphPaintPathCommand.MoveTo(0.0, 0.0),
                            GlyphPaintPathCommand.LineTo(10.0, 0.0),
                            GlyphPaintPathCommand.LineTo(10.0, 10.0),
                            GlyphPaintPathCommand.Close,
                        ),
                    ),
                    brush = GlyphPaintBrush.RadialGradient(
                        centerX = 5.0,
                        centerY = 5.0,
                        radius = 5.0,
                        stops = listOf(
                            GlyphPaintGradientStop(0.0, GlyphColor(255, 215, 0)),
                            GlyphPaintGradientStop(1.0, GlyphColor(255, 0, 0)),
                        ),
                    ),
                ),
                GlyphPaintNode.Transform(0, GlyphPaintTransform(120.0, 0.0, 0.0, 120.0, 37.5, -950.0)),
            ),
        )

        assertFalse(
            profile(
                nodeKinds = listOf(GlyphPaintNodeKind.PATH, GlyphPaintNodeKind.TRANSFORM),
                gradientKinds = emptyList(),
                gradientSpreads = emptyList(),
            ).accepts(paint),
        )
        assertFalse(
            profile(
                nodeKinds = listOf(GlyphPaintNodeKind.PATH),
                gradientKinds = listOf(GlyphPaintGradientKind.RADIAL),
                gradientSpreads = listOf(GlyphPaintGradientSpread.PAD),
            ).accepts(paint),
        )
        assertTrue(
            profile(
                nodeKinds = listOf(GlyphPaintNodeKind.PATH, GlyphPaintNodeKind.TRANSFORM),
                gradientKinds = listOf(GlyphPaintGradientKind.RADIAL),
                gradientSpreads = listOf(GlyphPaintGradientSpread.PAD),
            ).accepts(paint),
        )
    }

    private fun profile(
        nodeKinds: List<GlyphPaintNodeKind>,
        gradientKinds: List<GlyphPaintGradientKind>,
        gradientSpreads: List<GlyphPaintGradientSpread>,
    ): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = nodeKinds,
        acceptedCompositionModes = emptyList(),
        limits = PaintGraphLimits(
            maxNodes = 2,
            maxReferences = 1,
            maxDepth = 2,
            maxPaths = 1,
            maxGradients = 1,
        ),
        outlineProfile = OutlineProfile(
            maxBytes = 1024,
            maxContours = 8,
            maxPoints = 32,
            maxCompositeDepth = 2,
            maxCompositeComponents = 2,
        ),
        acceptedGradientKinds = gradientKinds,
        acceptedGradientSpreads = gradientSpreads,
    )
}
