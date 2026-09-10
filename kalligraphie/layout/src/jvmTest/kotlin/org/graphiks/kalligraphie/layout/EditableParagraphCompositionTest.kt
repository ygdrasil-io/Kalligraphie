package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.Kalligraphie
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CoverageStatus
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontDiagnosticSeverity
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphMetrics
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.LayoutPoint
import org.graphiks.kalligraphie.api.LayoutBounds
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineControlKind
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.ParagraphLayoutError
import org.graphiks.kalligraphie.api.ParagraphLayoutRequest
import org.graphiks.kalligraphie.api.ParagraphMaterializationIdentity
import org.graphiks.kalligraphie.api.ParagraphConstraints
import org.graphiks.kalligraphie.api.ParagraphPositioningPolicy
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.ShapingBackendIdentity
import org.graphiks.kalligraphie.api.ShapingDistributionProvenance
import org.graphiks.kalligraphie.api.ShapingProvenanceSpan
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.TabStop
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.api.WritingMode
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import org.graphiks.kalligraphie.unicode.JvmLineBreakAnalyzer
import org.graphiks.kalligraphie.unicode.JvmUnicodeAnalyzer
import org.graphiks.kalligraphie.unicode.TextSnapshots

class EditableParagraphCompositionTest {
    private val openedBackends = mutableListOf<ShapingBackend>()

    @AfterTest
    fun closeOpenedBackends() {
        openedBackends.asReversed().forEach { backend ->
            assertIs<FontOperationResult.Success<Unit>>(backend.close())
        }
    }

    @Test
    fun latinSpacesChooseTheLastLegalBreakThatFits() {
        val fixture = fixture("one two three", width = 3_000f, height = 4_000f)

        val result = compose(fixture)

        assertEquals(
            listOf(range(fixture.snapshot, 0, 4), range(fixture.snapshot, 4, 8), range(fixture.snapshot, 8, 13)),
            result.lines.map { it.line.range },
        )
        assertEquals(listOf(850f, 1_850f, 2_850f), result.lines.map { it.baseline.y.value })
        assertEquals(3, result.lines.size)
        result.lines.forEach { placed -> assertCompleteClusterCoverage(fixture.snapshot, placed.line.range, placed.line) }
    }

    @Test
    fun mandatoryBreakEndsTheCurrentLineEvenWhenMoreTextFits() {
        val fixture = fixture("a\nb", width = 20_000f, height = 3_000f)

        val result = compose(fixture)

        assertEquals(
            listOf(range(fixture.snapshot, 0, 2), range(fixture.snapshot, 2, 3)),
            result.lines.map { it.line.range },
        )
        assertEquals(listOf(850f, 1_850f), result.lines.map { it.baseline.y.value })
        result.lines.forEach { placed -> assertCompleteClusterCoverage(fixture.snapshot, placed.line.range, placed.line) }
    }

    @Test
    fun anUnbreakableOverwideUnitIsPublishedWholeAndAdvances() {
        val fixture = fixture("Supercalifragilistic", width = 100f, height = 2_000f)

        val result = compose(fixture)

        assertEquals(listOf(fixture.snapshot.range), result.lines.map { it.line.range })
        assertEquals(1, result.lines.size)
        assertTrue(result.lines.single().inlineAdvance.value > fixture.request.constraints.width.value)
        assertCompleteClusterCoverage(fixture.snapshot, fixture.snapshot.range, result.lines.single().line)
    }

    @Test
    fun emptyParagraphPublishesOnePhysicalEmptyLine() {
        val fixture = fixture("", width = 2_000f, height = 1_000f)

        val result = compose(fixture)

        assertEquals(1, result.lines.size)
        assertEquals(fixture.snapshot.range, result.lines.single().line.range)
        assertTrue(result.lines.single().line.positionedGlyphRuns.isEmpty())
        assertEquals(LayoutPoint(LayoutUnit(100f), LayoutUnit(850f)), result.lines.single().baseline)
    }

    @Test
    fun mandatoryTerminationAtParagraphEndPublishesATrailingEmptyLine() {
        val fixture = fixture("a\n", width = 2_000f, height = 3_000f)
        val end = fixture.snapshot.range.endExclusive

        val result = compose(fixture)

        assertEquals(
            listOf(fixture.snapshot.range, TextRange(end, end)),
            result.lines.map { it.line.range },
        )
        assertEquals(listOf(850f, 1_850f), result.lines.map { it.baseline.y.value })
        assertTrue(result.lines.last().line.positionedGlyphRuns.isEmpty())
    }

    @Test
    fun unsafeBoundaryReshapingKeepsTheFrozenLigatureAndSeparateLineAdvances() {
        val fixture = fixture("office-office", width = 3_200f, height = 2_000f)

        val result = compose(fixture)

        assertEquals(
            listOf(range(fixture.snapshot, 0, 7), range(fixture.snapshot, 7, 13)),
            result.lines.map { it.line.range },
        )
        assertEquals(
            listOf(listOf(82, 5044, 70, 72, 16), listOf(82, 5044, 70, 72)),
            result.lines.map { placed -> placed.line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.shapedGlyph.glyphId.value } } },
        )
        assertEquals(
            listOf(
                listOf(611.8164f, 966.7969f, 549.8047f, 615.2344f, 360.83984f),
                listOf(611.8164f, 966.7969f, 549.8047f, 615.2344f),
            ),
            result.lines.map { placed -> placed.line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.advance.x.value } } },
        )
        assertTrue(result.lines.all { placed -> placed.line.positionedGlyphRuns.first().sourceRun.bot })
        assertTrue(result.lines.all { placed -> placed.line.positionedGlyphRuns.last().sourceRun.eot })
        // Frozen external oracle: whole-span HarfBuzz 14.3.0 marks the glyph following the
        // legal hyphen boundary unsafe-to-break/unsafe-to-concat. Separately shaping `office-`
        // and `office` with DejaVu Sans at 1000, monotone characters, BOT/EOT, and the OT shaper
        // produces the literal glyph IDs/advances above; the provisional cross-boundary advance
        // of the hyphen is deliberately not published.
    }

    @Test
    fun finalEotAdvanceBacktracksFromAProvisionallyFittingHyphenBreak() {
        val fixture = fixture("A-V-AV", width = 1_940f, height = 3_000f)

        val result = compose(fixture)

        assertEquals(
            listOf(range(fixture.snapshot, 0, 2), range(fixture.snapshot, 2, 4), range(fixture.snapshot, 4, 6)),
            result.lines.map { it.line.range },
        )
        assertEquals(
            listOf(1_022.9492f, 986.3281f, 1_304.1992f),
            result.lines.map { it.inlineAdvance.value },
        )
        assertTrue(result.lines.all { line -> line.inlineAdvance.value <= fixture.request.constraints.width.value })
        // Frozen HarfBuzz 14.3.0 oracle at size 1000: paragraph-wide `A-V-` is
        // 1928.7109, but final EOT shaping makes its hyphen 739 design units and
        // the line 1950.6836 wide. The preceding legal `A-` candidate is 1022.9492.
    }

    @Test
    fun unsafeFlagsExpandOnlyTheBoundedFinalContextAfterASafePrefix() {
        val fixture = fixture(
            "abc office-office",
            width = 5_300f,
            height = 2_000f,
            recordShapingRequests = true,
        )

        val result = compose(fixture)

        assertEquals(range(fixture.snapshot, 0, 11), result.lines.first().line.range)
        assertEquals(
            listOf(68, 69, 70, 3, 82, 5044, 70, 72, 16),
            result.lines.first().line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.shapedGlyph.glyphId.value } },
        )
        assertEquals(
            listOf(612.79297f, 634.7656f, 549.8047f, 317.8711f, 611.8164f, 966.7969f, 549.8047f, 615.2344f, 360.83984f),
            result.lines.first().line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.advance.x.value } },
        )
        val finalEnd = range(fixture.snapshot, 0, 11).endExclusive
        assertTrue(fixture.recordingBackend!!.requests.any { request ->
            request.itemRange == range(fixture.snapshot, 3, 11) && request.eot
        })
        assertTrue(fixture.recordingBackend.requests.none { request ->
            request.itemRange == TextRange(fixture.snapshot.range.start, finalEnd) && request.eot
        })
        val finalRun = result.lines.first().line.positionedGlyphRuns.single().sourceRun
        assertEquals(
            listOf(
                ShapingProvenanceSpan(
                    range(fixture.snapshot, 0, 11),
                    fixture.request.shapingBackend.identity.provenance,
                ),
            ),
            finalRun.provenanceSpans,
        )
        // The provisional real-font flags are safe on `abc`, while the space-to-ligature
        // suffix is unsafe-to-concat and the selected hyphen boundary is unsafe-to-break.
    }

    @Test
    fun paragraphCoalescenceRetainsEveryRealShapingDistributionInFirstContributionOrder() {
        val fixture = fixture("abc office-office", width = 5_300f, height = 2_000f)
        val primary = fixture.request.shapingBackend.identity.provenance
        val secondary = primary.copy(
            operatingSystem = "portable-test-os",
            architecture = "portable-test-architecture",
            artifactId = "portable-test-artifact",
            artifactSha256 = "1".repeat(64),
            buildChainIdentity = "portable-test-build-chain",
        )
        val secondaryStart = fixture.snapshot.textIndexAtScalarBoundary(3)
        val routedBackend = DistributionRoutingBackend(fixture.request.shapingBackend) { shapingRequest ->
            if (shapingRequest.itemRange.start == secondaryStart) secondary else primary
        }

        val result = layout(copyRequest(fixture.request, shapingBackend = routedBackend))
        val sourceRun = result.layout.lines.first().positionedGlyphRuns.single().sourceRun

        assertEquals(
            listOf(68, 69, 70, 3, 82, 5044, 70, 72, 16),
            sourceRun.glyphs.map { glyph -> glyph.glyphId.value },
        )
        assertEquals(
            listOf(612.79297f, 634.7656f, 549.8047f, 317.8711f, 611.8164f, 966.7969f, 549.8047f, 615.2344f, 360.83984f),
            sourceRun.glyphs.map { glyph -> glyph.xAdvance.value },
        )
        assertEquals(primary, sourceRun.backendIdentity.provenance)
        assertEquals(listOf(primary, secondary), sourceRun.distributionProvenances)
    }

    @Test
    fun paragraphCompositionRetainsEveryLegacyIdentityValuePublishedWithRealGlyphs() {
        val fixture = fixture("abc office-office", width = 5_300f, height = 2_000f)
        val legacy = ShapingBackendIdentity(
            backendId = "legacy-paragraph-backend",
            nativeVersion = "legacy-native-version",
            nativeSourceRevision = "legacy-source-revision",
            nativeArtifactId = "legacy-artifact-id",
            nativeArtifactSha256 = "2".repeat(64),
            featurePolicy = fixture.request.featurePolicy,
            configurationFingerprint = "legacy-configuration",
        )
        val backend = LegacyIdentityReportingBackend(fixture.request.shapingBackend, legacy)

        val result = layout(copyRequest(fixture.request, shapingBackend = backend))
        val sourceRun = result.layout.lines.first().positionedGlyphRuns.single().sourceRun
        val (
            backendId,
            nativeVersion,
            nativeSourceRevision,
            nativeArtifactId,
            nativeArtifactSha256,
            featurePolicy,
            configurationFingerprint,
        ) = sourceRun.backendIdentity

        assertEquals(
            listOf(68, 69, 70, 3, 82, 5044, 70, 72, 16),
            sourceRun.glyphs.map { glyph -> glyph.glyphId.value },
        )
        assertEquals(
            listOf(612.79297f, 634.7656f, 549.8047f, 317.8711f, 611.8164f, 966.7969f, 549.8047f, 615.2344f, 360.83984f),
            sourceRun.glyphs.map { glyph -> glyph.xAdvance.value },
        )
        assertSame(legacy, sourceRun.backendIdentity)
        assertEquals(
            listOf(
                "legacy-paragraph-backend",
                "legacy-native-version",
                "legacy-source-revision",
                "legacy-artifact-id",
                "2".repeat(64),
                fixture.request.featurePolicy,
                "legacy-configuration",
            ),
            listOf(
                backendId,
                nativeVersion,
                nativeSourceRevision,
                nativeArtifactId,
                nativeArtifactSha256,
                featurePolicy,
                configurationFingerprint,
            ),
        )
    }

    @Test
    fun combiningVariationAndEmojiZwJUnitsRemainWholeAcrossNarrowLines() {
        val fixture = fixture(
            "f\u0301 \u2764\uFE0F\u200D\u2764\uFE0F x",
            width = 100f,
            height = 4_000f,
            fontResources = listOf(
                FontFixture("/fonts/gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture"),
                FontFixture("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans"),
            ),
        )

        val result = compose(fixture)

        assertEquals(
            listOf(range(fixture.snapshot, 0, 3), range(fixture.snapshot, 3, 9), range(fixture.snapshot, 9, 10)),
            result.lines.map { it.line.range },
        )
        assertEquals(
            listOf(listOf(73, 5923, 3), listOf(6, 6, 3), listOf(91)),
            result.lines.map { placed -> placed.line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.shapedGlyph.glyphId.value } } },
        )
        assertEquals(
            listOf(
                listOf(352.05078f, 0f, 317.8711f),
                listOf(900f, 900f, 317.8711f),
                listOf(591.7969f),
            ),
            result.lines.map { placed -> placed.line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.advance.x.value } } },
        )
        result.lines.forEach { placed -> assertCompleteClusterCoverage(fixture.snapshot, placed.line.range, placed.line) }
        // Frozen external oracles: Unicode 16 LineBreakTest LB8a/LB9 and HarfBuzz 14.3.0
        // over the audited GDEF/DejaVu fixtures. No expectation is computed by the composer.
    }

    @Test
    fun multilineBidiResetsTrailingSpacesAndReordersEachSelectedLine() {
        val fixture = fixture(
            "abc \u05D0\u05D1\u05D2   \u05E9\u05DC\u05D5\u05DD",
            width = 4_500f,
            height = 3_000f,
            language = "he",
            fontResources = listOf(FontFixture("/fonts/liberation/LiberationSans-Regular.ttf", "Liberation Sans Regular")),
        )

        val result = compose(fixture)

        assertEquals(
            listOf(range(fixture.snapshot, 0, 10), range(fixture.snapshot, 10, 14)),
            result.lines.map { it.line.range },
        )
        assertEquals(
            listOf(0, 1, 0),
            result.lines.first().line.positionedGlyphRuns.map { it.sourceRun.bidiLevel },
        )
        assertEquals(
            listOf(range(fixture.snapshot, 0, 4), range(fixture.snapshot, 4, 7), range(fixture.snapshot, 7, 10)),
            result.lines.first().line.positionedGlyphRuns.map { it.sourceRun.range },
        )
        assertEquals(
            listOf(
                listOf(68, 69, 70, 3, 1282, 1281, 1280, 3, 3, 3),
                listOf(1293, 1285, 1292, 1305),
            ),
            result.lines.map { placed -> placed.line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.shapedGlyph.glyphId.value } } },
        )
        assertEquals(
            listOf(
                listOf(556.15234f, 556.15234f, 500f, 277.83203f, 422.85156f, 598.14453f, 627.9297f, 277.83203f, 277.83203f, 277.83203f),
                listOf(678.22266f, 259.76562f, 529.78516f, 729.98047f),
            ),
            result.lines.map { placed -> placed.line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.advance.x.value } } },
        )
        assertEquals(listOf(0, 1, 2), result.lines.first().line.positionedGlyphRuns.map { it.visualOrder })
        // Frozen external oracle: UAX #9 L1/L2 for the selected 0..<10 line, plus
        // HarfBuzz 14.3.0 Liberation Sans glyph order for Latn/Hebr runs.
    }

    @Test
    fun trailingBoundaryNeutralOnAnRtlLineIsResetByRetainedX9L1Handling() {
        val fixture = fixture(
            "abc\u00AD def",
            width = 1_800f,
            height = 3_000f,
            baseDirection = BaseDirection.RIGHT_TO_LEFT,
            language = "he",
        )

        val result = compose(fixture)

        assertEquals(range(fixture.snapshot, 0, 5), result.lines.first().line.range)
        val softHyphen = range(fixture.snapshot, 3, 4)
        val retainedBnRun = result.lines.first().line.positionedGlyphRuns.single { run ->
            run.sourceRun.clusters.any { cluster -> cluster.sourceRange == softHyphen }
        }
        assertEquals(1, retainedBnRun.sourceRun.bidiLevel)
        // UAX #9 16.0 L1 plus §5.2: retained BN/X9 controls participate in the
        // trailing reset sequence; U+00AD must therefore use the RTL base level 1.
    }

    @Test
    fun fallbackResolutionNeverScansUnrelatedSnapshotTextOutsideSourceRange() {
        val fixture = fixture(
            "\u4E00one two",
            width = 3_000f,
            height = 2_000f,
            sourceStartOrdinal = 1,
        )

        val result = compose(fixture)

        assertEquals(
            listOf(range(fixture.snapshot, 1, 5), range(fixture.snapshot, 5, 8)),
            result.lines.map { line -> line.line.range },
        )
        assertTrue(result.lines.all { line -> line.line.range.start >= fixture.request.sourceRange.start })
        // DejaVu Sans has no U+4E00 mapping. The CJK scalar is intentionally present only
        // outside sourceRange, so a range-local resolver must never reject this paragraph.
    }

    @Test
    fun finalRangeLocalFallbackPreservesCandidateRejectionDiagnostics() {
        val fixture = fixture(
            "f\u0301 x",
            width = 3_000f,
            height = 2_000f,
            fontResources = listOf(
                FontFixture("/fonts/gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture"),
                FontFixture("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans"),
            ),
        )

        val result = compose(fixture)

        assertTrue(result.lines.first().line.diagnostics.any { diagnostic ->
            diagnostic.code == "font.fallback-candidate-rejected"
        })
        assertEquals(
            listOf(73, 5923, 3, 91),
            result.lines.first().line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.shapedGlyph.glyphId.value } },
        )
    }

    @Test
    fun verticalCompositionStopsBeforeAPartialLineBox() {
        val fixture = fixture("one two three", width = 3_000f, height = 1_999f)

        val result = compose(fixture)

        assertEquals(listOf(range(fixture.snapshot, 0, 4)), result.lines.map { it.line.range })
        assertEquals(range(fixture.snapshot, 4, 13), result.remainingSourceRange)
        assertNull(result.lines.single().line.positionedGlyphRuns.firstOrNull { run -> run.sourceRun.range.start >= result.remainingSourceRange!!.start })
    }

    @Test
    fun compositionResultRejectsMutationThroughAJvmMutableCast() {
        val fixture = fixture("one two three", width = 3_000f, height = 4_000f)
        val result = compose(fixture)

        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (result.lines as MutableList<ComposedParagraphLine>).clear()
        }
        assertEquals(3, result.lines.size)
    }

    @Test
    fun publicParagraphLayouterRejectsAWrongGenerationRenderableResolverForAnEmptyParagraph() {
        val fixture = fixture("", width = 3_000f, height = 1_000f)
        val foreignFixture = fixture(
            "",
            width = 3_000f,
            height = 1_000f,
            fontResources = listOf(FontFixture("/fonts/liberation/LiberationSans-Regular.ttf", "Liberation Sans Regular")),
        )
        val profile = OutlineProfile(
            maxBytes = 1_024,
            maxContours = 16,
            maxPoints = 64,
            maxCompositeDepth = 4,
            maxCompositeComponents = 8,
        )
        val materialization = EditableLineMaterialization.Renderable(
            foreignFixture.request.fontCatalog.openAssetResolver().successValue(),
            FontRenderVariantKey.default,
            profile,
        )
        try {
            val result = ParagraphComposer.layout(
                copyRequest(
                    fixture.request,
                    materializationIdentity = ParagraphMaterializationIdentity.Renderable(
                        FontRenderVariantKey.default,
                        profile,
                    ),
                ),
                materialization,
            )

            assertIs<ParagraphLayoutError.InvalidInput>(
                assertIs<ParagraphLayoutResult.Failure>(result).error,
            )
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(materialization.resolver.close())
        }
    }

    @Test
    fun publicParagraphLayouterRejectsAWrongGenerationResolverWhenResumingATerminalEmptyLine() {
        val fixture = fixture("a\n", width = 3_000f, height = 1_000f)
        val foreignFixture = fixture(
            "",
            width = 3_000f,
            height = 1_000f,
            fontResources = listOf(FontFixture("/fonts/liberation/LiberationSans-Regular.ttf", "Liberation Sans Regular")),
        )
        val profile = OutlineProfile(
            maxBytes = 1_024,
            maxContours = 16,
            maxPoints = 64,
            maxCompositeDepth = 4,
            maxCompositeComponents = 8,
        )
        val materializationIdentity = ParagraphMaterializationIdentity.Renderable(
            FontRenderVariantKey.default,
            profile,
        )
        val initialRequest = copyRequest(
            fixture.request,
            materializationIdentity = materializationIdentity,
        )
        val initialMaterialization = EditableLineMaterialization.Renderable(
            fixture.request.fontCatalog.openAssetResolver().successValue(),
            FontRenderVariantKey.default,
            profile,
        )
        val continuation = try {
            val initial = assertIs<ParagraphLayoutResult.Success>(
                ParagraphComposer.layout(initialRequest, initialMaterialization),
            )
            assertEquals(CoverageStatus.PARTIAL, initial.coverageStatus)
            checkNotNull(initial.continuation)
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(initialMaterialization.resolver.close())
        }
        val mismatchedMaterialization = EditableLineMaterialization.Renderable(
            foreignFixture.request.fontCatalog.openAssetResolver().successValue(),
            FontRenderVariantKey.default,
            profile,
        )
        try {
            val result = ParagraphComposer.layout(
                copyRequest(
                    initialRequest,
                    sourceRange = continuation.remainingSourceRange,
                    constraints = constraints(width = 3_000f, top = 1_050f, height = 1_000f),
                    continuation = continuation,
                ),
                mismatchedMaterialization,
            )

            assertIs<ParagraphLayoutError.InvalidInput>(
                assertIs<ParagraphLayoutResult.Failure>(result).error,
            )
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(mismatchedMaterialization.resolver.close())
        }
    }

    @Test
    fun publishedLineUsesRealGlyphMetricsForDistinctContentBoxAndInkGeometry() {
        val fixture = fixture("Ag", width = 3_000f, height = 1_000f)

        val result = assertIs<ParagraphLayoutResult.Success>(
            ParagraphComposer.layout(fixture.request, EditableLineMaterialization.LayoutOnly),
        )
        val line = result.layout.lines.single()
        val expectedInk = trueInkBounds(fixture, line)

        assertEquals(expectedInk, line.designInkBounds)
        assertEquals(
            LayoutUnit(line.baseline.y.value - expectedInk.minY.value),
            line.contentMetrics.ascent,
        )
        assertEquals(
            LayoutUnit(expectedInk.maxY.value - line.baseline.y.value),
            line.contentMetrics.descent,
        )
        assertEquals(
            LayoutUnit(
                line.positionedGlyphRuns.sumOf { run ->
                    run.glyphs.sumOf { glyph -> glyph.advance.x.value.toDouble() }
                }.toFloat(),
            ),
            line.contentMetrics.inlineAdvance,
        )
        assertEquals(fixture.request.constraints.region, line.lineBox)
        assertTrue(line.designInkBounds != LayoutBounds.empty)
        assertTrue(line.contentMetrics.ascent != line.verticalMetrics.ascent)
        assertTrue(line.contentMetrics.descent != line.verticalMetrics.descent)
        // The oracle is the actual DejaVu TrueType instance used by shaping, not an empty
        // placeholder or a renderer-dependent raster bound.
    }

    @Test
    fun verticalMixedTabRetainsItsPhysicalControlAndInlineAdvance() {
        val fixture = fixture("A\tB", width = 1_000f, height = 4_000f, writingMode = WritingMode.VERTICAL_RL)

        val projection = runCatching { layout(fixture.request).layout.lines.single() }

        assertTrue(projection.isSuccess, "Vertical projection must retain the TAB cluster between neighboring glyphs.")
        val line = projection.getOrThrow()
        val control = line.positionedGlyphRuns.flatMap { run -> run.lineControls }.single()
        assertEquals(LineControlKind.HORIZONTAL_TAB, control.kind)
        assertEquals(range(fixture.snapshot, 1, 2), control.sourceRange)
        assertEquals(line.baseline.x, control.origin.x)
        assertTrue(control.origin.y > line.baseline.y)
        assertEquals(LayoutUnit(0f), control.advance.x)
        assertTrue(control.advance.y.value > 0f)
        assertEquals(
            fixture.snapshot.scalarRanges(fixture.snapshot.range),
            line.positionedGlyphRuns.flatMap { run -> run.sourceRun.clusters }.flatMap { cluster -> cluster.scalarRanges },
        )
        assertEquals(
            LayoutUnit(
                (line.positionedGlyphRuns.sumOf { run ->
                    run.glyphs.sumOf { glyph -> glyph.advance.y.value.toDouble() }
                } + control.advance.y.value.toDouble()).toFloat(),
            ),
            line.contentMetrics.inlineAdvance,
        )
    }

    @Test
    fun verticalTabOnlyLineRetainsItsPhysicalControlAndInlineAdvance() {
        val fixture = fixture("\t", width = 1_000f, height = 4_000f, writingMode = WritingMode.VERTICAL_RL)

        val projection = runCatching { layout(fixture.request).layout.lines.single() }

        assertTrue(projection.isSuccess, "Vertical projection must retain a TAB-only shaped run.")
        val line = projection.getOrThrow()
        val control = line.positionedGlyphRuns.flatMap { run -> run.lineControls }.single()
        assertEquals(LineControlKind.HORIZONTAL_TAB, control.kind)
        assertEquals(fixture.snapshot.range, control.sourceRange)
        assertEquals(line.baseline, control.origin)
        assertEquals(LayoutUnit(0f), control.advance.x)
        assertTrue(control.advance.y.value > 0f)
        assertEquals(
            fixture.snapshot.scalarRanges(fixture.snapshot.range),
            line.positionedGlyphRuns.flatMap { run -> run.sourceRun.clusters }.flatMap { cluster -> cluster.scalarRanges },
        )
        assertEquals(control.advance.y, line.contentMetrics.inlineAdvance)
    }

    @Test
    fun verticalTabLeaderUsesItsCoveredExtentForInlineAdvance() {
        val fixture = fixture(
            "\t",
            width = 1_000f,
            height = 3_000f,
            writingMode = WritingMode.VERTICAL_RL,
            positioning = ParagraphPositioningPolicy(
                tabStops = listOf(TabStop(LayoutUnit(3_000f), leader = '.'.code)),
            ),
        )

        val result = layout(fixture.request)
        val line = result.layout.lines.single()
        val control = line.positionedGlyphRuns.flatMap { run -> run.lineControls }.single()
        val leaders = line.positionedGlyphRuns.flatMap { run -> run.glyphs }

        assertTrue(leaders.isNotEmpty())
        assertTrue(leaders.all { glyph ->
            glyph.origin.y.value + glyph.advance.y.value <= control.origin.y.value + control.advance.y.value
        })
        assertEquals(LayoutUnit(3_000f), control.advance.y)
        assertEquals(LayoutUnit(3_000f), line.contentMetrics.inlineAdvance)
        assertEquals(CoverageStatus.COMPLETE, result.coverageStatus)
        assertNull(result.continuation)
    }

    @Test
    fun trailingSpacesDoNotExpandPublishedInkBounds() {
        val fixture = fixture("Ag   ", width = 5_000f, height = 1_000f)

        val line = layout(fixture.request).layout.lines.single()
        val expectedInk = trueInkBounds(fixture, line)

        assertEquals(expectedInk, line.designInkBounds)
        assertTrue(line.designInkBounds.maxX < LayoutUnit(line.baseline.x.value + line.contentMetrics.inlineAdvance.value))
        // The independent font oracle discards an empty glyph metric before any translation, so
        // a translated zero-area space cannot become a false paragraph-coordinate ink point.
    }

    @Test
    fun cancelledFinalMetricProjectionReturnsTheFontCancellationDiagnostic() {
        val fixture = fixture("A", width = 3_000f, height = 1_000f)
        val diagnostic = FontDiagnostic(
            code = "font.test-metric-projection-cancelled",
            severity = FontDiagnosticSeverity.WARNING,
            location = FontDiagnosticLocation.Source,
            message = "The test font instance cancelled metric projection.",
        )

        val result = ParagraphComposer.layout(
            copyRequest(
                fixture.request,
                fontCatalog = ProjectionMetricCatalog(fixture.request.fontCatalog) { _, _ ->
                    FontOperationResult.Cancelled(listOf(diagnostic))
                },
            ),
            EditableLineMaterialization.LayoutOnly,
        )

        assertEquals(
            listOf("font.test-metric-projection-cancelled"),
            assertIs<ParagraphLayoutResult.Cancelled>(result).diagnostics.map { it.code },
        )
    }

    @Test
    fun finalMetricProjectionChecksCancellationBetweenGlyphMetricQueries() {
        val fixture = fixture("Ag", width = 3_000f, height = 1_000f)
        var cancellationRequested = false

        val result = ParagraphComposer.layout(
            copyRequest(
                fixture.request,
                fontCatalog = ProjectionMetricCatalog(fixture.request.fontCatalog) { instance, glyphId ->
                    cancellationRequested = true
                    instance.metrics(glyphId)
                },
                cancellationToken = org.graphiks.kalligraphie.api.CancellationToken { cancellationRequested },
            ),
            EditableLineMaterialization.LayoutOnly,
        )

        assertIs<ParagraphLayoutResult.Cancelled>(result)
    }

    @Test
    fun allSpaceLinePublishesOnlyItsBaselineAsInkBounds() {
        val fixture = fixture("   ", width = 5_000f, height = 1_000f)

        val line = layout(fixture.request).layout.lines.single()

        assertTrue(line.positionedGlyphRuns.flatMap { it.glyphs }.isNotEmpty())
        assertEquals(emptyList(), trueNonEmptyGlyphBounds(fixture, line))
        assertEquals(
            LayoutBounds(line.baseline.x, line.baseline.y, line.baseline.x, line.baseline.y),
            line.designInkBounds,
        )
        assertEquals(LayoutUnit(0f), line.contentMetrics.ascent)
        assertEquals(LayoutUnit(0f), line.contentMetrics.descent)
    }

    @Test
    fun extremeFiniteProjectionGeometryReturnsTypedOverflow() {
        val fixture = fixture("A", width = 1_000f, height = 1_000f)
        val request = copyRequest(
            fixture.request,
            constraints = HorizontalParagraphConstraints(
                region = LayoutRect(
                    LayoutUnit(3.0e38f),
                    LayoutUnit(50f),
                    LayoutUnit(3.3e38f),
                    LayoutUnit(1_050f),
                ),
                lineMetrics = LineVerticalMetrics(LayoutUnit(800f), LayoutUnit(200f)),
            ),
            fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1.0e38f)),
        )

        assertIs<ParagraphCompositionResult.Success>(ParagraphComposer.compose(request, EditableLineMaterialization.LayoutOnly))
        val result = ParagraphComposer.layout(request, EditableLineMaterialization.LayoutOnly)

        val failure = assertIs<ParagraphLayoutResult.Failure>(result)
        assertIs<ParagraphLayoutError.GeometryOverflow>(failure.error)
    }

    @Test
    fun extremeFiniteCaretProjectionReturnsTypedOverflow() {
        val fixture = fixture(" ", width = 1_000f, height = 1_000f)
        val request = copyRequest(
            fixture.request,
            constraints = HorizontalParagraphConstraints(
                region = LayoutRect(
                    LayoutUnit(3.3e38f),
                    LayoutUnit(50f),
                    LayoutUnit(3.4e38f),
                    LayoutUnit(1_050f),
                ),
                lineMetrics = LineVerticalMetrics(LayoutUnit(800f), LayoutUnit(200f)),
            ),
            fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1.0e38f)),
        )

        assertIs<ParagraphCompositionResult.Success>(ParagraphComposer.compose(request, EditableLineMaterialization.LayoutOnly))
        val result = ParagraphComposer.layout(request, EditableLineMaterialization.LayoutOnly)

        val failure = assertIs<ParagraphLayoutResult.Failure>(result)
        assertIs<ParagraphLayoutError.GeometryOverflow>(failure.error)
    }

    @Test
    fun continuationPublishesCompleteLinesAndResumesAsTheSameTallComposition() {
        val fixture = fixture("one two three", width = 3_000f, height = 1_000f)

        val partial = layout(fixture.request)
        val continuation = checkNotNull(partial.continuation)
        val resumed = layout(
            copyRequest(
                fixture.request,
                sourceRange = continuation.remainingSourceRange,
                constraints = constraints(width = 3_000f, top = 1_050f, height = 2_000f),
                continuation = continuation,
            ),
        )
        val full = layout(
            copyRequest(
                fixture.request,
                constraints = constraints(width = 3_000f, top = 50f, height = 3_000f),
            ),
        )

        assertEquals(CoverageStatus.PARTIAL, partial.coverageStatus)
        assertEquals(listOf(range(fixture.snapshot, 0, 4)), partial.layout.lines.map { it.range })
        assertEquals(range(fixture.snapshot, 4, 13), continuation.remainingSourceRange)
        assertEquals(CoverageStatus.COMPLETE, resumed.coverageStatus)
        assertEquals(
            full.layout.lines.map(::lineFingerprint),
            (partial.layout.lines + resumed.layout.lines).map(::lineFingerprint),
        )
        assertFailsWith<IllegalArgumentException> {
            copyRequest(
                fixture.request,
                sourceRange = continuation.remainingSourceRange,
                constraints = constraints(width = 2_999f, top = 1_050f, height = 2_000f),
                continuation = continuation,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            copyRequest(
                fixture.request,
                sourceRange = continuation.remainingSourceRange,
                constraints = HorizontalParagraphConstraints(
                    region = LayoutRect(LayoutUnit(100f), LayoutUnit(1_050f), LayoutUnit(3_100f), LayoutUnit(3_050f)),
                    lineMetrics = LineVerticalMetrics(LayoutUnit(700f), LayoutUnit(300f)),
                ),
                continuation = continuation,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            copyRequest(
                fixture.request,
                sourceRange = continuation.remainingSourceRange,
                constraints = constraints(width = 3_000f, top = 1_050f, height = 2_000f),
                materializationIdentity = ParagraphMaterializationIdentity.Renderable(
                    FontRenderVariantKey.default,
                    OutlineProfile(
                        maxBytes = 1_024,
                        maxContours = 16,
                        maxPoints = 64,
                        maxCompositeDepth = 4,
                        maxCompositeComponents = 8,
                    ),
                ),
                continuation = continuation,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            val foreign = fixture("one two three", width = 3_000f, height = 2_000f)
            copyRequest(
                foreign.request,
                sourceRange = foreign.snapshot.range,
                continuation = continuation,
            )
        }
    }

    @Test
    fun mandatoryTerminalEmptyLineIsProjectedAndCanContinueWhenItsBoxDoesNotFit() {
        val completeFixture = fixture("a\n", width = 3_000f, height = 2_000f)
        val complete = layout(completeFixture.request)
        val end = completeFixture.snapshot.range.endExclusive

        assertEquals(
            listOf(completeFixture.snapshot.range, TextRange(end, end)),
            complete.layout.lines.map { it.range },
        )

        val partialFixture = fixture("a\n", width = 3_000f, height = 1_000f)
        val partial = layout(partialFixture.request)
        val continuation = checkNotNull(partial.continuation)
        val resumed = layout(
            copyRequest(
                partialFixture.request,
                sourceRange = continuation.remainingSourceRange,
                constraints = constraints(width = 3_000f, top = 1_050f, height = 1_000f),
                continuation = continuation,
            ),
        )

        assertEquals(CoverageStatus.PARTIAL, partial.coverageStatus)
        assertEquals(listOf(partialFixture.snapshot.range), partial.layout.lines.map { it.range })
        assertEquals(TextRange(partialFixture.snapshot.range.endExclusive, partialFixture.snapshot.range.endExclusive), continuation.remainingSourceRange)
        assertEquals(CoverageStatus.COMPLETE, resumed.coverageStatus)
        assertEquals(1, resumed.layout.lines.size)
        assertEquals(continuation.remainingSourceRange, resumed.layout.lines.single().range)
    }

    private fun compose(fixture: Fixture): ParagraphCompositionResult.Success =
        assertIs(
            ParagraphComposer.compose(fixture.request, EditableLineMaterialization.LayoutOnly),
        )

    private fun layout(request: ParagraphLayoutRequest): ParagraphLayoutResult.Success = assertIs(
        ParagraphComposer.layout(request, EditableLineMaterialization.LayoutOnly),
    )

    private fun copyRequest(
        request: ParagraphLayoutRequest,
        sourceRange: TextRange = request.sourceRange,
        constraints: ParagraphConstraints = request.constraints,
        fontInstanceDescriptor: FontInstanceDescriptor = request.fontInstanceDescriptor,
        fontCatalog: FontCatalogSnapshot = request.fontCatalog,
        cancellationToken: org.graphiks.kalligraphie.api.CancellationToken = request.cancellationToken,
        materializationIdentity: ParagraphMaterializationIdentity = request.materializationIdentity,
        continuation: org.graphiks.kalligraphie.api.LayoutContinuation? = null,
        shapingBackend: ShapingBackend = request.shapingBackend,
    ): ParagraphLayoutRequest = ParagraphLayoutRequest(
        snapshot = request.snapshot,
        sourceRange = sourceRange,
        unicodeAnalysis = request.unicodeAnalysis,
        lineBreakAnalysis = request.lineBreakAnalysis,
        constraints = constraints,
        baseDirection = request.baseDirection,
        language = request.language,
        featurePolicy = request.featurePolicy,
        features = request.features,
        fontCatalog = fontCatalog,
        resolutionPolicy = request.resolutionPolicy,
        fontInstanceDescriptor = fontInstanceDescriptor,
        shapingBackend = shapingBackend,
        materializationIdentity = materializationIdentity,
        overflowPolicy = request.overflowPolicy,
        continuation = continuation,
        cancellationToken = cancellationToken,
    )

    private fun constraints(width: Float, top: Float, height: Float): HorizontalParagraphConstraints =
        HorizontalParagraphConstraints(
            region = LayoutRect(LayoutUnit(100f), LayoutUnit(top), LayoutUnit(100f + width), LayoutUnit(top + height)),
            lineMetrics = LineVerticalMetrics(LayoutUnit(800f), LayoutUnit(200f)),
        )

    private fun lineFingerprint(line: org.graphiks.kalligraphie.api.LineLayout): List<Any> = listOf(
        line.range,
        line.baseline,
        line.contentMetrics,
        line.lineBox,
        line.designInkBounds,
        line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { glyph -> glyph.shapedGlyph.glyphId to glyph.origin } },
        line.allCaretCandidates.map { candidate -> candidate.position to candidate.geometry },
    )

    private fun fixture(
        value: String,
        width: Float,
        height: Float,
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        language: String = "en",
        writingMode: WritingMode = WritingMode.HORIZONTAL_TB,
        positioning: ParagraphPositioningPolicy = ParagraphPositioningPolicy(),
        fontResources: List<FontFixture> = listOf(FontFixture("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")),
        sourceStartOrdinal: Int = 0,
        recordShapingRequests: Boolean = false,
    ): Fixture {
        val snapshot = TextSnapshots.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16(value.toCharArray())),
        ).snapshot
        val unicodeAnalysis = JvmUnicodeAnalyzer.create().analyze(
            snapshot,
            UnicodeAnalysisRequest(baseDirection, language),
        )
        val lineBreakAnalysis = JvmLineBreakAnalyzer.create().analyze(snapshot, unicodeAnalysis)
        val sources = fontResources.map { font -> source(font.resource, font.declaredName) }
        val catalog = Kalligraphie.embedded(sources).successValue()
        val generation = catalog.generation
        val faces = sources.map { source -> FontFaceId(source.id, 0) }
        val policy = FontResolutionPolicySnapshot(
            generation = generation,
            policyId = "paragraph-composition-test-policy",
            version = "1",
            candidates = faces.map(::FontResolutionCandidate),
            lastResortFace = faces.last(),
        )
        val nativeBackend = JvmHarfBuzzShapingBackend.open().successValue()
        val recordingBackend = if (recordShapingRequests) RecordingShapingBackend(nativeBackend) else null
        val backend = (recordingBackend ?: nativeBackend).also(openedBackends::add)
        val metrics = LineVerticalMetrics(LayoutUnit(800f), LayoutUnit(200f))
        val request = ParagraphLayoutRequest(
            snapshot = snapshot,
            sourceRange = range(snapshot, sourceStartOrdinal, snapshot.scalarRanges(snapshot.range).size),
            unicodeAnalysis = unicodeAnalysis,
            lineBreakAnalysis = lineBreakAnalysis,
            constraints = if (writingMode == WritingMode.HORIZONTAL_TB) {
                HorizontalParagraphConstraints(
                    region = LayoutRect(LayoutUnit(100f), LayoutUnit(50f), LayoutUnit(100f + width), LayoutUnit(50f + height)),
                    lineMetrics = metrics,
                )
            } else {
                ParagraphConstraints(
                    region = LayoutRect(LayoutUnit(100f), LayoutUnit(50f), LayoutUnit(100f + width), LayoutUnit(50f + height)),
                    lineMetrics = metrics,
                    writingMode = writingMode,
                )
            },
            baseDirection = baseDirection,
            language = language,
            featurePolicy = backend.identity.semantic.featurePolicy,
            fontCatalog = catalog,
            resolutionPolicy = policy,
            fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
            shapingBackend = backend,
            materializationIdentity = ParagraphMaterializationIdentity.LayoutOnly,
            positioning = positioning,
        )
        return Fixture(snapshot, request, recordingBackend)
    }

    private fun assertCompleteClusterCoverage(
        snapshot: TextSnapshot,
        range: TextRange,
        line: org.graphiks.kalligraphie.api.EditableLine,
    ) {
        val published = line.positionedGlyphRuns
            .flatMap { run -> run.sourceRun.clusters }
            .flatMap { cluster -> cluster.scalarRanges }
        assertEquals(snapshot.scalarRanges(range), published)
    }

    private fun range(snapshot: TextSnapshot, start: Int, endExclusive: Int): TextRange =
        TextRange(snapshot.textIndexAtScalarBoundary(start), snapshot.textIndexAtScalarBoundary(endExclusive))

    private fun trueNonEmptyGlyphBounds(
        fixture: Fixture,
        line: org.graphiks.kalligraphie.api.LineLayout,
    ): List<LayoutBounds> {
        val face = fixture.request.fontCatalog.faces.single().id
        val instance = fixture.request.fontCatalog.resolveFace(face, FontAccessRequirementsSnapshot.layoutOnly())
            .successValue()
            .instantiate(fixture.request.fontInstanceDescriptor)
            .successValue()
        return line.positionedGlyphRuns.flatMap { run ->
            check(run.fontInstanceKey == instance.key)
            run.glyphs.mapNotNull { glyph ->
                val metrics = instance.metrics(glyph.shapedGlyph.glyphId).successValue()
                metrics.scaledBounds.takeUnless { it == LayoutBounds.empty }?.let { bounds -> LayoutBounds(
                    minX = LayoutUnit(glyph.origin.x.value + bounds.minX.value),
                    minY = LayoutUnit(glyph.origin.y.value - bounds.maxY.value),
                    maxX = LayoutUnit(glyph.origin.x.value + bounds.maxX.value),
                    maxY = LayoutUnit(glyph.origin.y.value - bounds.minY.value),
                ) }
            }
        }
    }

    private fun trueInkBounds(
        fixture: Fixture,
        line: org.graphiks.kalligraphie.api.LineLayout,
    ): LayoutBounds {
        val glyphBounds = trueNonEmptyGlyphBounds(fixture, line)
        return if (glyphBounds.isEmpty()) {
            LayoutBounds(line.baseline.x, line.baseline.y, line.baseline.x, line.baseline.y)
        } else {
            LayoutBounds(
                glyphBounds.minOf { it.minX },
                glyphBounds.minOf { it.minY },
                glyphBounds.maxOf { it.maxX },
                glyphBounds.maxOf { it.maxY },
            )
        }
    }

    private fun source(resource: String, declaredName: String): FontSource =
        FontSource(checkNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes() }, FontSourceProvenance(declaredName))

    private fun <T> FontOperationResult<T>.successValue(): T = assertIs<FontOperationResult.Success<T>>(this).value

    private class DistributionRoutingBackend(
        private val delegate: ShapingBackend,
        private val provenanceFor: (ShapingRequest) -> ShapingDistributionProvenance,
    ) : ShapingBackend {
        override val identity: ShapingBackendIdentity = delegate.identity

        override fun shape(request: ShapingRequest): FontOperationResult<ShapedGlyphRun> =
            when (val shaped = delegate.shape(request)) {
                is FontOperationResult.Success -> {
                    val source = shaped.value
                    FontOperationResult.Success(
                        ShapedGlyphRun(
                            range = source.range,
                            fontInstanceKey = source.fontInstanceKey,
                            backendIdentity = ShapingBackendIdentity(source.backendIdentity.semantic, provenanceFor(request)),
                            direction = source.direction,
                            script = source.script,
                            language = source.language,
                            bidiLevel = source.bidiLevel,
                            bot = source.bot,
                            eot = source.eot,
                            featurePolicy = source.featurePolicy,
                            features = source.features,
                            graphemeClusters = source.graphemeClusters,
                            glyphs = source.glyphs,
                            clusters = source.clusters,
                            ligatureCaretFacts = source.ligatureCaretFacts,
                        ),
                        shaped.diagnostics,
                    )
                }

                is FontOperationResult.Failure -> shaped
                is FontOperationResult.Cancelled -> shaped
            }

        override fun close(): FontOperationResult<Unit> = FontOperationResult.Success(Unit)
    }

    private class LegacyIdentityReportingBackend(
        private val delegate: ShapingBackend,
        override val identity: ShapingBackendIdentity,
    ) : ShapingBackend {
        override fun shape(request: ShapingRequest): FontOperationResult<ShapedGlyphRun> =
            when (val shaped = delegate.shape(request)) {
                is FontOperationResult.Success -> {
                    val source = shaped.value
                    FontOperationResult.Success(
                        ShapedGlyphRun(
                            range = source.range,
                            fontInstanceKey = source.fontInstanceKey,
                            backendIdentity = identity,
                            direction = source.direction,
                            script = source.script,
                            language = source.language,
                            bidiLevel = source.bidiLevel,
                            bot = source.bot,
                            eot = source.eot,
                            featurePolicy = source.featurePolicy,
                            features = source.features,
                            graphemeClusters = source.graphemeClusters,
                            glyphs = source.glyphs,
                            clusters = source.clusters,
                            ligatureCaretFacts = source.ligatureCaretFacts,
                        ),
                        shaped.diagnostics,
                    )
                }

                is FontOperationResult.Failure -> shaped
                is FontOperationResult.Cancelled -> shaped
            }

        override fun close(): FontOperationResult<Unit> = FontOperationResult.Success(Unit)
    }

    private data class Fixture(
        val snapshot: TextSnapshot,
        val request: ParagraphLayoutRequest,
        val recordingBackend: RecordingShapingBackend?,
    )

    private data class FontFixture(val resource: String, val declaredName: String)

    private class RecordingShapingBackend(
        private val delegate: ShapingBackend,
    ) : ShapingBackend {
        override val identity = delegate.identity
        val requests: MutableList<ShapingRequest> = mutableListOf()

        override fun shape(request: ShapingRequest): FontOperationResult<ShapedGlyphRun> {
            requests += request
            return delegate.shape(request)
        }

        override fun close(): FontOperationResult<Unit> = delegate.close()
    }

    private class ProjectionMetricCatalog(
        private val delegate: FontCatalogSnapshot,
        private val projectMetrics: (FontInstance, org.graphiks.kalligraphie.api.GlyphId) -> FontOperationResult<GlyphMetrics>,
    ) : FontCatalogSnapshot by delegate {
        override fun resolveFace(
            faceId: FontFaceId,
            requirements: FontAccessRequirementsSnapshot,
        ): FontOperationResult<FontFace> = when (val resolved = delegate.resolveFace(faceId, requirements)) {
            is FontOperationResult.Success -> FontOperationResult.Success(
                ProjectionMetricFace(resolved.value, projectMetrics),
                resolved.diagnostics,
            )

            is FontOperationResult.Failure -> resolved
            is FontOperationResult.Cancelled -> resolved
        }
    }

    private class ProjectionMetricFace(
        private val delegate: FontFace,
        private val projectMetrics: (FontInstance, org.graphiks.kalligraphie.api.GlyphId) -> FontOperationResult<GlyphMetrics>,
    ) : FontFace by delegate {
        override fun instantiate(descriptor: FontInstanceDescriptor): FontOperationResult<FontInstance> =
            when (val instantiated = delegate.instantiate(descriptor)) {
                is FontOperationResult.Success -> FontOperationResult.Success(
                    ProjectionMetricInstance(instantiated.value, projectMetrics),
                    instantiated.diagnostics,
                )

                is FontOperationResult.Failure -> instantiated
                is FontOperationResult.Cancelled -> instantiated
            }
    }

    private class ProjectionMetricInstance(
        private val delegate: FontInstance,
        private val projectMetrics: (FontInstance, org.graphiks.kalligraphie.api.GlyphId) -> FontOperationResult<GlyphMetrics>,
    ) : FontInstance by delegate {
        override fun metrics(glyphId: org.graphiks.kalligraphie.api.GlyphId): FontOperationResult<GlyphMetrics> =
            projectMetrics(delegate, glyphId)
    }
}
