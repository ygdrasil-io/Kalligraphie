package org.graphiks.kalligraphie

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.CoverageStatus
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditorOperationLimitKind
import org.graphiks.kalligraphie.api.EditorOperationProfile
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontFallbackStage
import org.graphiks.kalligraphie.api.FontFallbackReason
import org.graphiks.kalligraphie.api.FontFallbackLastResortState
import org.graphiks.kalligraphie.api.aggregateFallbackDiagnostics
import org.graphiks.kalligraphie.api.GlyphPaintNodeKind
import org.graphiks.kalligraphie.api.GlyphPaintCompositionMode
import org.graphiks.kalligraphie.api.PaintGraphLimits
import org.graphiks.kalligraphie.api.PaintGraphProfile
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphMaterializationRoute
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.LayoutPoint
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineLayout
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.LogicalNavigationDirection
import org.graphiks.kalligraphie.api.MaterializationResourceProfile
import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.ParagraphLayoutError
import org.graphiks.kalligraphie.api.ParagraphLayoutRequest
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.VisualNavigationDirection
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend

class JvmEditableParagraphFacadeTest {
    @Test
    fun publishesLocalizedFallbackMaterializationRejection() {
        val fixture = diagnosticFixture("AA", colorLastResort = true)
        val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(fixture.catalog.openAssetResolver()).value
        try {
            val profile = diagnosticPaintProfile()
            val result = assertIs<ParagraphLayoutResult.Success>(JvmEditableParagraphFacade.layout(request(
                fixture, constraints(10_000f, 0f, 2_000f), materialization = EditableLineMaterialization.Renderable(
                    resolver, org.graphiks.kalligraphie.api.FontRenderVariantSnapshot.default, FontAccessRequirementsSnapshot.renderable(listOf(profile)),
                ),
            )))
            val line = result.layout.lines.single()
            assertTrue(line.positionedGlyphRuns.all { it.fontInstanceKey.face == fixture.arabicFace })
            val diagnostics = line.diagnostics.mapNotNull { it.fallbackDiagnostic }
            val rejected = diagnostics.filter { it.faceId == fixture.latinFace }
            assertEquals(listOf(range(fixture.snapshot, 0, 1), range(fixture.snapshot, 1, 2)), rejected.map { it.range })
            rejected.forEach { diagnostic ->
                assertSame(fixture.snapshot.version, diagnostic.textVersion)
                assertEquals(diagnostic.range, diagnostic.unit.range)
                assertEquals(diagnostic.unit.fragments, diagnostic.contributingFragments)
                assertEquals(profile, diagnostic.representationProfile)
                assertEquals(0, diagnostic.candidateRank)
                assertEquals(0, diagnostic.profileRank)
                assertEquals(FontFallbackStage.Materialization, diagnostic.stage)
                assertEquals(FontFallbackReason.RepresentationUnavailable, diagnostic.reason)
                assertEquals(FontFallbackLastResortState.NotLastResort, diagnostic.lastResortState)
            }
            val selected = diagnostics.filter { it.lastResortState == FontFallbackLastResortState.Selected }
            assertEquals(2, selected.size)
            assertTrue(selected.all { it.faceId == fixture.arabicFace && it.candidateRank == 1 })
            val grouped = rejected.reversed().aggregateFallbackDiagnostics()
            assertEquals(1, grouped.size)
            assertEquals(rejected, grouped.single().members)
            assertEquals(2, listOf(rejected.first(), rejected.first().copy(candidateRank = 2)).aggregateFallbackDiagnostics().size)
            assertFailsWith<UnsupportedOperationException> { (grouped.single().members as MutableList<*>).clear() }
            assertFailsWith<UnsupportedOperationException> { (rejected.first().contributingFragments as MutableList<*>).clear() }
            val fragments = rejected.first().contributingFragments.toMutableList()
            val copied = rejected.first().copy(contributingFragments = fragments)
            fragments.clear()
            assertEquals(rejected.first().contributingFragments, copied.contributingFragments)
            assertFailsWith<IllegalArgumentException> { rejected.first().copy(textVersion = TextVersion.create()) }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun reportsEveryExhaustedFallbackCandidateInCanonicalOrder() {
        val fixture = diagnosticFixture("A", colorLastResort = false)
        val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(fixture.catalog.openAssetResolver()).value
        try {
            val result = assertIs<ParagraphLayoutResult.Failure>(JvmEditableParagraphFacade.layout(request(
                fixture, constraints(10_000f, 0f, 2_000f), materialization = EditableLineMaterialization.Renderable(
                    resolver, org.graphiks.kalligraphie.api.FontRenderVariantSnapshot.default, FontAccessRequirementsSnapshot.renderable(listOf(diagnosticPaintProfile())),
                ),
            )))
            val error = assertIs<FontError.UnrenderableFontResolution>(assertIs<ParagraphLayoutError.FontFailure>(result.error).fontError)
            assertEquals(listOf(0, 1), error.fallbackDiagnostics.map { it.candidateRank })
            assertEquals(listOf(fixture.latinFace, fixture.arabicFace), error.fallbackDiagnostics.map { it.faceId })
            assertEquals(listOf(FontFallbackLastResortState.NotLastResort, FontFallbackLastResortState.Rejected),
                error.fallbackDiagnostics.map { it.lastResortState })
            assertTrue(error.fallbackDiagnostics.all { it.range == fixture.snapshot.range && it.profileRank == 0 })
            assertEquals(error.fallbackDiagnostics, result.diagnostics.mapNotNull { it.fallbackDiagnostic })
            assertFailsWith<UnsupportedOperationException> { (error.fallbackDiagnostics as MutableList<*>).clear() }
            val supplied = error.fallbackDiagnostics.toMutableList()
            val independentlyCaptured = FontError.UnrenderableFontResolution(error.message, error.location, supplied)
            supplied.clear()
            assertEquals(error, independentlyCaptured)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun closedResolverRemainsTerminalWithoutInventingFallbackRejections() {
        val fixture = diagnosticFixture("A", colorLastResort = true)
        val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(fixture.catalog.openAssetResolver()).value
        resolver.close()
        val result = assertIs<ParagraphLayoutResult.Failure>(JvmEditableParagraphFacade.layout(request(
            fixture, constraints(10_000f, 0f, 2_000f), materialization = EditableLineMaterialization.Renderable(
                resolver, FontRenderVariantKey.default, diagnosticOutlineProfile(),
            ),
        )))
        assertIs<FontError.ResourceClosed>(assertIs<ParagraphLayoutError.FontFailure>(result.error).fontError)
        assertTrue(result.diagnostics.none { it.fallbackDiagnostic != null })
    }

    @Test
    fun genericShapingFailureDoesNotClaimAContextProjectionFailure() {
        val fixture = diagnosticFixture("A", colorLastResort = true)
        val result = assertIs<ParagraphLayoutResult.Failure>(JvmEditableParagraphFacade.layout(request(
            fixture, constraints(10_000f, 0f, 2_000f), features = listOf(OpenTypeFeature("rand", 1)),
        )))
        val error = assertIs<FontError.UnrenderableFontResolution>(assertIs<ParagraphLayoutError.FontFailure>(result.error).fontError)
        assertEquals(listOf(0, 1), error.fallbackDiagnostics.map { it.candidateRank })
        assertTrue(error.fallbackDiagnostics.all { it.stage == FontFallbackStage.Shaping })
        assertTrue(error.fallbackDiagnostics.all { it.reason == FontFallbackReason.ShapingFailed })
    }

    @Test
    fun controlOnlySkipsRepresentationRejectionsBeforeSelectingFace() {
        val fixture = diagnosticFixture("\n", colorLastResort = true)
        val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(fixture.catalog.openAssetResolver()).value
        try {
            val result = assertIs<ParagraphLayoutResult.Success>(JvmEditableParagraphFacade.layout(request(
                fixture, constraints(10_000f, 0f, 2_000f), materialization = EditableLineMaterialization.Renderable(
                    resolver, org.graphiks.kalligraphie.api.FontRenderVariantSnapshot.default,
                    FontAccessRequirementsSnapshot.renderable(listOf(diagnosticPaintProfile())),
                ),
            )))
            val decisions = result.layout.lines.flatMap { it.diagnostics }.mapNotNull { it.fallbackDiagnostic }
            assertTrue(decisions.none { it.reason == FontFallbackReason.RepresentationUnavailable })
            assertTrue(decisions.all { it.representationProfile == null && it.profileRank == null })
            assertEquals(listOf(fixture.latinFace), result.layout.lines.flatMap { it.positionedGlyphRuns }
                .map { it.fontInstanceKey.face }.distinct())
        } finally {
            resolver.close()
        }
    }

    @Test
    fun keepsRejectedProfileBeforeSuccessfulMonoFaceLastResortSelection() {
        val fixture = fontFixture("A\n", listOf(FontFixture("liberation/LiberationSans-Regular.ttf", "Liberation Sans")))
        val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(fixture.catalog.openAssetResolver()).value
        try {
            val result = assertIs<ParagraphLayoutResult.Success>(JvmEditableParagraphFacade.layout(request(
                fixture, constraints(10_000f, 0f, 2_000f), materialization = EditableLineMaterialization.Renderable(
                    resolver, org.graphiks.kalligraphie.api.FontRenderVariantSnapshot.default,
                    FontAccessRequirementsSnapshot.renderable(listOf(diagnosticPaintProfile(), diagnosticOutlineProfile())),
                ),
            )))
            val allDiagnostics = result.layout.lines.flatMap { it.diagnostics }.mapNotNull { it.fallbackDiagnostic }
            val diagnostics = allDiagnostics.filter { it.range == range(fixture.snapshot, 0, 1) }
            assertEquals(listOf(0, 1), diagnostics.map { it.profileRank })
            assertEquals(listOf(FontFallbackReason.RepresentationUnavailable, FontFallbackReason.LastResortSelected), diagnostics.map { it.reason })
            assertEquals(listOf(FontFallbackLastResortState.Rejected, FontFallbackLastResortState.Selected), diagnostics.map { it.lastResortState })
            assertTrue(diagnostics.all { it.candidateRank == 0 })
            val control = allDiagnostics.single { it.range == range(fixture.snapshot, 1, 2) }
            assertEquals(FontFallbackStage.Shaping, control.stage)
            assertNull(control.representationProfile)
            assertNull(control.profileRank)
        } finally {
            resolver.close()
        }
    }

    private fun diagnosticFixture(text: String, colorLastResort: Boolean): ParagraphFixture = fontFixture(text, listOf(
        FontFixture("liberation/LiberationSans-Regular.ttf", "Liberation Sans"),
        if (colorLastResort) FontFixture("bungee-color/BungeeColor-Regular.ttf", "Bungee Color")
        else FontFixture("amiri/Amiri-Regular.ttf", "Amiri"),
    ))

    @Test
    fun checkpointsDistinguishFallbackDecisionsWhenFinalGlyphsAreIdentical() {
        val fixture = fontFixture("A", listOf(
            FontFixture("liberation/LiberationSans-Regular.ttf", "Liberation Sans"),
            FontFixture("amiri/Amiri-Regular.ttf", "Amiri"),
            FontFixture("bungee-color/BungeeColor-Regular.ttf", "Bungee Color"),
        ))
        val candidates = fixture.policy.candidates
        val reordered = fixture.copy(policy = FontResolutionPolicySnapshot(
            fixture.catalog.generation, "reordered-rejections", "1",
            listOf(candidates[1], candidates[0], candidates[2]), candidates[2].faceId,
        ))
        val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(fixture.catalog.openAssetResolver()).value
        try {
            val materialization = EditableLineMaterialization.Renderable(
                resolver, org.graphiks.kalligraphie.api.FontRenderVariantSnapshot.default,
                FontAccessRequirementsSnapshot.renderable(listOf(diagnosticPaintProfile())),
            )
            val lines = listOf(fixture, reordered).map { input ->
                assertIs<ParagraphLayoutResult.Success>(JvmEditableParagraphFacade.layout(request(
                    input, constraints(10_000f, 0f, 2_000f), materialization = materialization,
                ))).layout.lines.single()
            }
            assertEquals(lineFingerprint(lines[0]), lineFingerprint(lines[1]))
            val continuation = org.graphiks.kalligraphie.api.LayoutContinuationSignature(fixture.snapshot.range.endExclusive, "same-end")
            val checkpoints = lines.map { org.graphiks.kalligraphie.api.LineCheckpointSignature.from(it, continuation) }
            assertFalse(checkpoints[0].hasSameObservableLayout(checkpoints[1]))
        } finally {
            resolver.close()
        }
    }

    private fun diagnosticPaintProfile(): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE, GlyphPaintNodeKind.GROUP),
        acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
        limits = PaintGraphLimits(maxNodes = 1_024, maxReferences = 1_024, maxDepth = 32),
        outlineProfile = diagnosticOutlineProfile(),
    )

    private fun diagnosticOutlineProfile(): OutlineProfile = OutlineProfile(
        maxBytes = 1_000_000, maxContours = 256, maxPoints = 16_384, maxCompositeDepth = 8, maxCompositeComponents = 256,
    )

    @Test
    fun materializationFailureForOneUnitKeepsNeighboringRealRunsExactly() {
        val fixture = fontFixture("A\u044BA", listOf(
            FontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans"),
            FontFixture("liberation/LiberationSans-Regular.ttf", "Liberation Sans"),
        ))
        val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(
            fixture.catalog.openAssetResolver(),
        ).value
        try {
            fun render(maxContours: Int): LineLayout {
                val materialization = EditableLineMaterialization.Renderable(
                    resolver = resolver,
                    variant = FontRenderVariantKey.default,
                    outlineProfile = diagnosticOutlineProfile().copy(maxContours = maxContours),
                )
                return assertIs<ParagraphLayoutResult.Success>(
                    JvmEditableParagraphFacade.layout(
                        request(
                            fixture,
                            constraints(width = 10_000f, top = 0f, height = 1_200f),
                            language = "en",
                            materialization = materialization,
                        ),
                    ),
                ).layout.lines.single()
            }

            val baseline = render(maxContours = 256)
            val fallback = render(maxContours = 3)
            fun glyphAt(line: LineLayout, scalar: Int) = line.positionedGlyphRuns
                .flatMap { run -> run.glyphs.map { glyph -> run.fontInstanceKey.face to glyph } }
                .single { (_, glyph) -> glyph.mappedSourceRange == range(fixture.snapshot, scalar, scalar + 1) }

            assertEquals(fixture.latinFace, glyphAt(baseline, 1).first)
            assertEquals(fixture.arabicFace, glyphAt(fallback, 1).first)
            listOf(0, 2).forEach { scalar ->
                val expected = glyphAt(baseline, scalar)
                val actual = glyphAt(fallback, scalar)
                assertEquals(expected.first, actual.first)
                assertEquals(expected.second.shapedGlyph.glyphId, actual.second.shapedGlyph.glyphId)
                assertEquals(expected.second.advance, actual.second.advance)
            }
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(resolver.close())
        }
    }

    @Test
    fun renderableParagraphRejectsBeforeExceedingItsLiveAssetBudget() {
        val fixture = fontFixture("A\u0633", listOf(
            FontFixture("liberation/LiberationSans-Regular.ttf", "Liberation Sans"),
            FontFixture("amiri/Amiri-Regular.ttf", "Amiri"),
        ))
        val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(
            fixture.catalog.openAssetResolver(),
        ).value
        val materialization = EditableLineMaterialization.Renderable(
            resolver = resolver,
            variant = FontRenderVariantKey.default,
            outlineProfile = diagnosticOutlineProfile(),
        )
        try {
            val limited = assertIs<ParagraphLayoutResult.Failure>(
                JvmEditableParagraphFacade.layout(
                    request(
                        fixture,
                        constraints(width = 10_000f, top = 0f, height = 1_200f),
                        materialization = materialization,
                        operationProfile = EditorOperationProfile(
                            materializationResourceProfile = MaterializationResourceProfile(
                                maxLiveAssets = 1,
                                maxEstimatedAssetBytes = Long.MAX_VALUE,
                            ),
                        ),
                    ),
                ),
            )
            val exceeded = assertIs<ParagraphLayoutError.OperationLimitExceeded>(limited.error).limit
            assertEquals(EditorOperationLimitKind.MATERIALIZATION_ASSETS, exceeded.kind)
            assertEquals(1L, exceeded.maximum)
            assertEquals(2L, exceeded.observed)

            val accepted = assertIs<ParagraphLayoutResult.Success>(
                JvmEditableParagraphFacade.layout(
                    request(
                        fixture,
                        constraints(width = 10_000f, top = 0f, height = 1_200f),
                        materialization = materialization,
                        operationProfile = EditorOperationProfile(
                            materializationResourceProfile = MaterializationResourceProfile(
                                maxLiveAssets = 2,
                                maxEstimatedAssetBytes = Long.MAX_VALUE,
                            ),
                        ),
                    ),
                ),
            )
            val visibleGlyphs = accepted.layout.lines.flatMap { line ->
                line.positionedGlyphRuns.flatMap { run -> run.glyphs }
            }
            assertTrue(visibleGlyphs.isNotEmpty())
            assertTrue(visibleGlyphs.all { glyph -> glyph.materializationCertificate != null })
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(resolver.close())
        }
    }

    @Test
    fun finiteAssetByteBudgetAllowsRealPaintToOutlineFallback() {
        val fixture = fontFixture(
            "A",
            listOf(FontFixture("liberation/LiberationSans-Regular.ttf", "Liberation Sans")),
        )
        val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(
            fixture.catalog.openAssetResolver(),
        ).value
        try {
            val outcome = JvmEditableParagraphFacade.layout(
                request(
                    fixture,
                    constraints(width = 10_000f, top = 0f, height = 1_200f),
                    materialization = EditableLineMaterialization.Renderable(
                        resolver = resolver,
                        renderVariant = org.graphiks.kalligraphie.api.FontRenderVariantSnapshot.default,
                        requirements = FontAccessRequirementsSnapshot.renderable(
                            listOf(diagnosticPaintProfile(), diagnosticOutlineProfile()),
                        ),
                    ),
                    operationProfile = EditorOperationProfile(
                        materializationResourceProfile = MaterializationResourceProfile(
                            maxLiveAssets = 2,
                            maxEstimatedAssetBytes = 10_000_000L,
                        ),
                    ),
                ),
            )
            val result = assertIs<ParagraphLayoutResult.Success>(
                outcome,
                (outcome as? ParagraphLayoutResult.Failure)?.error?.toString(),
            )
            val glyphs = result.layout.lines.flatMap { line ->
                line.positionedGlyphRuns.flatMap { run -> run.glyphs }
            }
            assertTrue(glyphs.isNotEmpty())
            assertTrue(glyphs.all { glyph -> glyph.materializationCertificate?.route == GlyphMaterializationRoute.OUTLINE })
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(resolver.close())
        }
    }

    @Test
    fun colorParagraphRejectsBeforeConservativeExpandedPaintAssetBudget() {
        val fixture = fontFixture(
            "A",
            listOf(FontFixture("bungee-color/BungeeColor-Regular.ttf", "Bungee Color")),
        )
        val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(
            fixture.catalog.openAssetResolver(),
        ).value
        try {
            val result = assertIs<ParagraphLayoutResult.Failure>(
                JvmEditableParagraphFacade.layout(
                    request(
                        fixture,
                        constraints(width = 10_000f, top = 0f, height = 1_200f),
                        materialization = EditableLineMaterialization.Renderable(
                            resolver = resolver,
                            renderVariant = org.graphiks.kalligraphie.api.FontRenderVariantSnapshot.default,
                            requirements = FontAccessRequirementsSnapshot.renderable(listOf(diagnosticPaintProfile())),
                        ),
                        operationProfile = EditorOperationProfile(
                            materializationResourceProfile = MaterializationResourceProfile(
                                maxLiveAssets = 1,
                                maxEstimatedAssetBytes = 3_000_000L,
                            ),
                        ),
                    ),
                ),
            )
            val exceeded = assertIs<ParagraphLayoutError.OperationLimitExceeded>(result.error).limit
            assertEquals(EditorOperationLimitKind.MATERIALIZATION_ASSET_BYTES, exceeded.kind)
            assertEquals(3_000_000L, exceeded.maximum)
            assertTrue(exceeded.observed > exceeded.maximum)
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(resolver.close())
        }
    }

    @Test
    fun publicFacadeCertifiesLatinHebrewAndArabicFallbackFromMainArtifact() {
        val latin = mainArtifactFontSource("gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")
        val hebrew = mainArtifactFontSource("liberation/LiberationSans-Regular.ttf", "Liberation Sans Regular")
        val arabic = mainArtifactFontSource("amiri/Amiri-Regular.ttf", "Amiri Regular")
        val sources = listOf(latin, hebrew, arabic)
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            Kalligraphie.embedded(sources),
        ).value
        val faces = catalog.faces.map { face -> face.id }
        val snapshot = Kalligraphie.decodeUtf16(
            TextVersion.create(),
            listOf(TextSlice.Utf16("Latin \u05D0\u05D1\u05D2 \u0633\u0644\u0627\u0645".toCharArray())),
        ).snapshot
        val fixture = ParagraphFixture(
            snapshot = snapshot,
            catalog = catalog,
            policy = FontResolutionPolicySnapshot(
                generation = catalog.generation,
                policyId = "public-three-script-renderable-fixture",
                version = "1",
                candidates = faces.map(::FontResolutionCandidate),
                lastResortFace = faces.last(),
            ),
            latinFace = faces.first(),
            arabicFace = faces.last(),
        )
        val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(
            catalog.openAssetResolver(),
        ).value

        try {
            val result = assertIs<ParagraphLayoutResult.Success>(
                JvmEditableParagraphFacade.layout(
                    request(
                        fixture = fixture,
                        constraints = constraints(width = 10_000f, top = 50f, height = 1_200f),
                        materialization = EditableLineMaterialization.Renderable(
                            resolver = resolver,
                            variant = FontRenderVariantKey.default,
                            outlineProfile = OutlineProfile(
                                maxBytes = 1_000_000,
                                maxContours = 2_048,
                                maxPoints = 16_384,
                                maxCompositeDepth = 8,
                                maxCompositeComponents = 256,
                            ),
                        ),
                    ),
                ),
            )
            val glyphRuns = result.layout.lines.flatMap(LineLayout::positionedGlyphRuns)
            val glyphs = glyphRuns.flatMap { run -> run.glyphs }

            assertEquals(listOf(latin.id, hebrew.id, arabic.id), catalog.faces.map { face -> face.id.source })
            assertEquals(faces.toSet(), glyphRuns.map { run -> run.fontInstanceKey.face }.toSet())
            assertTrue(glyphs.isNotEmpty())
            assertTrue(glyphs.all { glyph -> glyph.materializationCertificate != null })
            val expectedOutlineRanges = listOf(
                faces[0] to range(snapshot, 0, 5),
                faces[1] to range(snapshot, 6, 9),
                faces[2] to range(snapshot, 10, 14),
            )
            expectedOutlineRanges.forEach { (face, scriptRange) ->
                assertTrue(
                    glyphRuns
                        .filter { run -> run.fontInstanceKey.face == face }
                        .flatMap { run -> run.glyphs }
                        .any { glyph ->
                            val mappedRange = glyph.mappedSourceRange
                            glyph.materializationCertificate?.route == GlyphMaterializationRoute.OUTLINE &&
                                mappedRange.start >= scriptRange.start &&
                                mappedRange.endExclusive <= scriptRange.endExclusive
                        },
                    "Expected face $face to certify an OUTLINE glyph inside $scriptRange.",
                )
            }
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(resolver.close())
        }
    }

    @Test
    fun operationGlyphLimitRejectsWholeParagraphAndExactRetryPublishesRealFallbackLines() {
        val fixture = multiFaceFixture("fi \u0633\u0644\u0627\u0645")
        val geometry = constraints(width = 1_400f, top = 50f, height = 2_400f)

        val limited = assertIs<ParagraphLayoutResult.Failure>(
            JvmEditableParagraphFacade.layout(
                request(fixture, geometry, operationProfile = EditorOperationProfile(maxTotalGlyphs = 2)),
            ),
        )
        val exceeded = assertIs<ParagraphLayoutError.OperationLimitExceeded>(limited.error).limit
        assertEquals(EditorOperationLimitKind.TOTAL_GLYPHS, exceeded.kind)
        assertEquals(2L, exceeded.maximum)
        assertTrue(exceeded.observed > exceeded.maximum)

        val retry = assertIs<ParagraphLayoutResult.Success>(
            JvmEditableParagraphFacade.layout(
                request(fixture, geometry, operationProfile = EditorOperationProfile(maxTotalGlyphs = 64)),
            ),
        )
        assertEquals(listOf(range(fixture.snapshot, 0, 3), range(fixture.snapshot, 3, 7)), retry.layout.lines.map(LineLayout::range))
        assertEquals(listOf(listOf(3, 1), listOf(85, 3080, 3075, 1919)), retry.layout.lines.map { line ->
            line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { glyph -> glyph.shapedGlyph.glyphId.value } }
        })
    }

    @Test
    fun mainArtifactSnapshotsAnOrderedMultiFaceCatalogFromRealFonts() {
        val latin = fontSource("gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")
        val arabic = fontSource("amiri/Amiri-Regular.ttf", "Amiri Regular")
        val mutableSources = mutableListOf(latin, arabic)

        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            Kalligraphie.embedded(mutableSources),
        ).value
        mutableSources.clear()

        assertEquals(listOf(latin.id, arabic.id), catalog.faces.map { face -> face.id.source })
        // Both artifacts are checked-in, licensed TrueType fonts. Their order is the public
        // fallback order input; no internal SFNT reader or embedded provider is used here.
    }

    @Test
    fun publicFacadeCanonicalizesBcp47LanguageForPopulatedSnapshot() {
        val populatedFixture = multiFaceFixture("fi")
        val populated = assertIs<ParagraphLayoutResult.Success>(
            JvmEditableParagraphFacade.layout(
                request(
                    fixture = populatedFixture,
                    constraints = constraints(width = 1_400f, top = 50f, height = 1_200f),
                    language = "EN-us",
                ),
            ),
        )
        assertEquals(
            setOf("en-US"),
            populated.layout.lines
                .flatMap(LineLayout::positionedGlyphRuns)
                .map { run -> run.sourceRun.language }
                .toSet(),
        )
    }

    @Test
    fun emptyFacadeSuppliesCanonicalLanguageToParagraphLayoutRequest() {
        val emptyFixture = multiFaceFixture("")
        val backend = assertIs<FontOperationResult.Success<ShapingBackend>>(
            JvmHarfBuzzShapingBackend.open(),
        ).value
        var suppliedRequest: ParagraphLayoutRequest? = null

        val result = JvmEditableParagraphFacade.layout(
            request = request(
                fixture = emptyFixture,
                constraints = constraints(width = 1_400f, top = 50f, height = 1_200f),
                language = "EN-us",
            ),
            backend = backend,
            paragraphLayout = { paragraphRequest, _ ->
                suppliedRequest = paragraphRequest
                ParagraphLayoutResult.Cancelled()
            },
        )

        assertIs<ParagraphLayoutResult.Cancelled>(result)
        assertEquals("en-US", assertNotNull(suppliedRequest).language)
    }

    @Test
    fun facadeRequestFeaturesAreAnImmutableDefensiveSnapshot() {
        val fixture = multiFaceFixture("fi")
        val supplied = mutableListOf(OpenTypeFeature("kern", 1), OpenTypeFeature("liga", 0))

        val facadeRequest = request(
            fixture = fixture,
            constraints = constraints(width = 1_400f, top = 50f, height = 1_200f),
            features = supplied,
        )
        supplied.clear()

        assertEquals(listOf(OpenTypeFeature("kern", 1), OpenTypeFeature("liga", 0)), facadeRequest.features)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (facadeRequest.features as MutableList<OpenTypeFeature>).clear()
        }
        assertEquals(listOf(OpenTypeFeature("kern", 1), OpenTypeFeature("liga", 0)), facadeRequest.features)
    }

    @Test
    fun publicFacadePublishesMixedScriptFallbackGeometryAndMultilineEditing() {
        val fixture = multiFaceFixture("fi \u0633\u0644\u0627\u0645")

        val result = layout(fixture, constraints(width = 1_400f, top = 50f, height = 2_400f))
        val paragraph = result.layout
        val first = paragraph.lines[0]
        val second = paragraph.lines[1]

        assertEquals(CoverageStatus.COMPLETE, result.coverageStatus)
        assertNull(result.continuation)
        assertEquals(
            listOf(range(fixture.snapshot, 0, 3), range(fixture.snapshot, 3, 7)),
            paragraph.lines.map(LineLayout::range),
        )
        assertEquals(
            listOf(fixture.latinFace, fixture.arabicFace),
            paragraph.lines.flatMap(LineLayout::positionedGlyphRuns)
                .map { run -> run.fontInstanceKey.face }
                .distinct(),
        )
        val latinRun = paragraph.lines.flatMap(LineLayout::positionedGlyphRuns)
            .single { run -> run.fontInstanceKey.face == fixture.latinFace }
        val arabicRun = paragraph.lines.flatMap(LineLayout::positionedGlyphRuns)
            .single { run -> run.fontInstanceKey.face == fixture.arabicFace && run.sourceRun.range == second.range }
        assertEquals(listOf(3), latinRun.glyphs.map { glyph -> glyph.shapedGlyph.glyphId.value })
        assertEquals(listOf(900f), latinRun.glyphs.map { glyph -> glyph.advance.x.value })
        assertEquals(listOf(85, 3080, 3075, 1919), arabicRun.glyphs.map { glyph -> glyph.shapedGlyph.glyphId.value })
        assertEquals(listOf(452f, 446f, 245f, 568f), arabicRun.glyphs.map { glyph -> glyph.advance.x.value })
        // Frozen external HarfBuzz 14.3/14.4 oracles are documented with both checked-in fonts.

        assertEquals(
            listOf(
                LayoutRect(LayoutUnit(100f), LayoutUnit(50f), LayoutUnit(1_500f), LayoutUnit(1_250f)),
                LayoutRect(LayoutUnit(100f), LayoutUnit(1_250f), LayoutUnit(1_500f), LayoutUnit(2_450f)),
            ),
            paragraph.lines.map(LineLayout::lineBox),
        )
        assertEquals(
            listOf(LayoutUnit(950f), LayoutUnit(2_150f)),
            paragraph.lines.map { line -> line.baseline.y },
        )
        assertTrue(paragraph.lines.all { line -> line.designInkBounds.minX < line.designInkBounds.maxX })
        assertTrue(paragraph.lines.all { line -> line.contentMetrics.inlineAdvance.value > 0f })
        assertTrue(paragraph.lines.any { line ->
            line.contentMetrics.ascent != line.verticalMetrics.ascent ||
                line.contentMetrics.descent != line.verticalMetrics.descent
        })
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (paragraph.lines as MutableList<LineLayout>).clear()
        }

        val firstEnd = first.allCaretCandidates.single { candidate -> candidate.position.index == first.range.endExclusive }
        assertEquals(
            fixture.snapshot.textIndexAtScalarBoundary(4),
            assertNotNull(paragraph.nextLogical(firstEnd.position, LogicalNavigationDirection.FORWARD)).index,
        )
        assertSame(second.allCaretCandidates.first(), paragraph.nextVisual(firstEnd, VisualNavigationDirection.FORWARD))

        val selection = paragraph.selectionGeometry(
            first.allCaretCandidates.single { candidate -> candidate.position.index == first.range.start }.position,
            second.allCaretCandidates.single { candidate -> candidate.position.index == second.range.endExclusive }.position,
        )
        assertTrue(selection.isNotEmpty())
        assertEquals(setOf(first.lineBox.top, second.lineBox.top), selection.map { rectangle -> rectangle.top }.toSet())
        assertTrue(selection.none { rectangle ->
            rectangle.top < first.lineBox.bottom && rectangle.bottom > second.lineBox.top
        })

        val firstStart = first.allCaretCandidates.first()
        val secondInterior = second.allCaretCandidates.single { candidate ->
            candidate.position.index == fixture.snapshot.textIndexAtScalarBoundary(5)
        }
        val secondMidlineY = LayoutUnit((second.lineBox.top.value + second.lineBox.bottom.value) / 2f)
        val secondEnd = second.allCaretCandidates.last()
        assertSame(firstStart, paragraph.hitTest(LayoutPoint(firstStart.geometry.start.x, LayoutUnit(-500f))))
        assertSame(
            firstStart,
            paragraph.hitTest(LayoutPoint(firstStart.geometry.start.x, first.lineBox.bottom)),
        )
        assertSame(
            secondInterior,
            paragraph.hitTest(LayoutPoint(secondInterior.geometry.start.x, secondMidlineY)),
        )
        assertSame(secondEnd, paragraph.hitTest(LayoutPoint(secondEnd.geometry.start.x, LayoutUnit(3_000f))))
    }

    @Test
    fun publicFacadeChoosesTheLastLegalBreakThatFits() {
        val fixture = fontFixture(
            value = "one two three",
            fonts = listOf(FontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans")),
        )

        val result = layout(
            fixture,
            constraints(width = 3_000f, top = 50f, height = 3_600f),
            language = "en",
        )

        assertEquals(CoverageStatus.COMPLETE, result.coverageStatus)
        assertEquals(
            listOf(range(fixture.snapshot, 0, 4), range(fixture.snapshot, 4, 8), range(fixture.snapshot, 8, 13)),
            result.layout.lines.map(LineLayout::range),
        )
    }

    @Test
    fun publicFacadePublishesAnOverwideIndivisibleUnitWhole() {
        val fixture = fontFixture(
            value = "Supercalifragilistic",
            fonts = listOf(FontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans")),
        )

        val result = layout(
            fixture,
            constraints(width = 100f, top = 50f, height = 1_200f),
            language = "en",
        )
        val line = result.layout.lines.single()

        assertEquals(CoverageStatus.COMPLETE, result.coverageStatus)
        assertEquals(fixture.snapshot.range, line.range)
        assertTrue(line.contentMetrics.inlineAdvance > LayoutUnit(100f))
        assertTrue(line.positionedGlyphRuns.flatMap { run -> run.sourceRun.clusters }.isNotEmpty())
    }

    @Test
    fun publicFacadeKeepsCombiningVariationAndEmojiZwJUnitsWholeOnNarrowLines() {
        val fixture = fontFixture(
            value = "f\u0301 \u2764\uFE0F\u200D\u2764\uFE0F x",
            fonts = listOf(
                FontFixture("gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture"),
                FontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans"),
            ),
        )

        val result = layout(
            fixture,
            constraints(width = 100f, top = 50f, height = 3_600f),
            language = "en",
        )

        assertEquals(
            listOf(range(fixture.snapshot, 0, 3), range(fixture.snapshot, 3, 9), range(fixture.snapshot, 9, 10)),
            result.layout.lines.map(LineLayout::range),
        )
        assertEquals(
            listOf(listOf(73, 5923, 3), listOf(6, 6, 3), listOf(91)),
            result.layout.lines.map { line -> line.glyphIds() },
        )
        assertEquals(
            listOf(
                listOf(352.05078f, 0f, 317.8711f),
                listOf(900f, 900f, 317.8711f),
                listOf(591.7969f),
            ),
            result.layout.lines.map { line -> line.glyphAdvances() },
        )
        val emojiRange = range(fixture.snapshot, 3, 8)
        val emojiLine = result.layout.lines[1]
        val anchor = emojiLine.allCaretCandidates.first { it.position.index == emojiRange.start }.position
        val focus = emojiLine.allCaretCandidates.first { it.position.index == emojiRange.endExclusive }.position
        assertTrue(result.layout.selectionGeometry(anchor, focus).isNotEmpty())
        assertFalse(emojiLine.allCaretCandidates.any { candidate ->
            candidate.position.index > emojiRange.start && candidate.position.index < emojiRange.endExclusive
        })
        // Frozen Unicode 16 UAX #14 and HarfBuzz oracle over the checked-in real GDEF/DejaVu
        // fixtures; only public paragraph lines are observed here.
    }

    @Test
    fun fallbackHandlesControlsVariationSelectorsSoftHyphenArabicAndDevanagari() {
        val nestedIsolates = fontFixture(
            value = "f\u2067f\u2066f\u2069f\u2069f",
            fonts = listOf(FontFixture("gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")),
        )
        val nestedResult = layout(
            nestedIsolates,
            constraints(width = 1_000f, top = 50f, height = 1_200f),
            language = "und",
        )
        assertEquals(5, nestedResult.layout.lines.single().positionedGlyphRuns.flatMap { run -> run.glyphs }.size)

        val control = fontFixture(
            value = "\u2067",
            fonts = listOf(FontFixture("gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")),
        )
        val controlResult = layout(control, constraints(width = 1_000f, top = 50f, height = 1_200f), language = "und")
        assertEquals(listOf(control.snapshot.range), controlResult.layout.lines.map(LineLayout::range))
        assertTrue(controlResult.layout.lines.single().positionedGlyphRuns.flatMap { run -> run.glyphs }.isEmpty())

        val fixture = fontFixture(
            value = "\u0915\u094D\u200D\u0937 \u0915\u094D\u200C\u0937 \u2764\uFE0F co\u00ADoperate \u0644\u0627 \u0915\u094D\u0937\u093F",
            fonts = listOf(
                FontFixture("gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture"),
                FontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans"),
                FontFixture("amiri/Amiri-Regular.ttf", "Amiri Regular"),
                FontFixture("noto-devanagari/NotoSansDevanagari-Regular.ttf", "Noto Sans Devanagari Regular"),
            ),
        )
        val result = layout(
            fixture,
            constraints(width = 20_000f, top = 50f, height = 1_200f),
            language = "und",
        )
        val line = result.layout.lines.single()

        assertTrue(line.positionedGlyphRuns.all { run -> run.glyphs.isNotEmpty() })
        val softHyphenRange = range(fixture.snapshot, 15, 16)
        assertEquals(0f, line.positionedGlyphRuns.flatMap { run -> run.glyphs }
            .single { glyph -> glyph.mappedSourceRange == softHyphenRange }.advance.x.value)
        listOf(
            "Devanagari ZWJ" to range(fixture.snapshot, 0, 4),
            "Devanagari ZWNJ" to range(fixture.snapshot, 5, 8),
            "variation sequence" to range(fixture.snapshot, 10, 12),
            "Devanagari conjunct" to range(fixture.snapshot, 27, 31),
        ).forEach { (label, grapheme) ->
            assertFalse(line.allCaretCandidates.any { candidate ->
                candidate.position.index > grapheme.start && candidate.position.index < grapheme.endExclusive
            }, "Unexpected caret inside $label.")
        }
    }

    @Test
    fun publicFacadeReshapesAnUnsafeBreakWithFinalBotAndEotGlyphs() {
        val fixture = fontFixture(
            value = "office-office",
            fonts = listOf(FontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans")),
        )

        val result = layout(
            fixture,
            constraints(width = 3_200f, top = 50f, height = 2_400f),
            language = "en",
        )

        assertEquals(
            listOf(range(fixture.snapshot, 0, 7), range(fixture.snapshot, 7, 13)),
            result.layout.lines.map(LineLayout::range),
        )
        assertEquals(
            listOf(listOf(82, 5044, 70, 72, 16), listOf(82, 5044, 70, 72)),
            result.layout.lines.map { line -> line.glyphIds() },
        )
        assertEquals(
            listOf(
                listOf(611.8164f, 966.7969f, 549.8047f, 615.2344f, 360.83984f),
                listOf(611.8164f, 966.7969f, 549.8047f, 615.2344f),
            ),
            result.layout.lines.map { line -> line.glyphAdvances() },
        )
        assertTrue(result.layout.lines.all { line ->
            line.positionedGlyphRuns.first().sourceRun.bot && line.positionedGlyphRuns.last().sourceRun.eot
        })
        // Frozen HarfBuzz 14.3.0 oracle for separate BOT/EOT shaping of the selected lines.
    }

    @Test
    fun publicFacadeBacktracksFromAFinalEotAdvanceThatWouldOverflow() {
        val fixture = fontFixture(
            value = "A-V-AV",
            fonts = listOf(FontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans")),
        )

        val result = layout(
            fixture,
            constraints(width = 1_940f, top = 50f, height = 3_600f),
            language = "en",
        )

        assertEquals(
            listOf(range(fixture.snapshot, 0, 2), range(fixture.snapshot, 2, 4), range(fixture.snapshot, 4, 6)),
            result.layout.lines.map(LineLayout::range),
        )
        assertEquals(
            listOf(1_022.9492f, 986.3281f, 1_304.1992f),
            result.layout.lines.map { line -> line.contentMetrics.inlineAdvance.value },
        )
        assertTrue(result.layout.lines.all { line -> line.contentMetrics.inlineAdvance <= LayoutUnit(1_940f) })
        // Frozen HarfBuzz 14.3.0 oracle: final EOT shaping makes `A-V-` too wide, so the
        // published first line must backtrack to the preceding legal boundary `A-`.
    }

    @Test
    fun publicFacadeAppliesTheExactPerLineBidiOracle() {
        val fixture = fontFixture(
            value = "abc \u05D0\u05D1\u05D2   \u05E9\u05DC\u05D5\u05DD",
            fonts = listOf(FontFixture("liberation/LiberationSans-Regular.ttf", "Liberation Sans Regular")),
        )

        val result = layout(
            fixture,
            constraints(width = 4_500f, top = 50f, height = 2_400f),
            language = "he",
        )
        val first = result.layout.lines.first()

        assertEquals(
            listOf(range(fixture.snapshot, 0, 10), range(fixture.snapshot, 10, 14)),
            result.layout.lines.map(LineLayout::range),
        )
        assertEquals(listOf(0, 1, 0), first.positionedGlyphRuns.map { run -> run.sourceRun.bidiLevel })
        assertEquals(
            listOf(range(fixture.snapshot, 0, 4), range(fixture.snapshot, 4, 7), range(fixture.snapshot, 7, 10)),
            first.positionedGlyphRuns.map { run -> run.sourceRun.range },
        )
        assertEquals(
            listOf(
                listOf(68, 69, 70, 3, 1282, 1281, 1280, 3, 3, 3),
                listOf(1293, 1285, 1292, 1305),
            ),
            result.layout.lines.map { line -> line.glyphIds() },
        )
        assertEquals(
            listOf(
                listOf(556.15234f, 556.15234f, 500f, 277.83203f, 422.85156f, 598.14453f, 627.9297f, 277.83203f, 277.83203f, 277.83203f),
                listOf(678.22266f, 259.76562f, 529.78516f, 729.98047f),
            ),
            result.layout.lines.map { line -> line.glyphAdvances() },
        )
        assertEquals(listOf(0, 1, 2), first.positionedGlyphRuns.map { run -> run.visualOrder })
        // Frozen UAX #9 L1/L2 and HarfBuzz 14.3.0 oracle, asserted only through public lines.
    }

    @Test
    fun publicFacadeContinuationReplaysAsTheSameTallComposition() {
        val fixture = multiFaceFixture("fi \u0633\u0644\u0627\u0645")
        val partial = layout(fixture, constraints(width = 1_400f, top = 50f, height = 1_200f))
        val continuation = assertNotNull(partial.continuation)
        val resumed = assertIs<ParagraphLayoutResult.Success>(
            JvmEditableParagraphFacade.layout(
                request(
                    fixture = fixture,
                    constraints = constraints(width = 1_400f, top = 1_250f, height = 1_200f),
                    sourceRange = continuation.remainingSourceRange,
                    continuation = continuation,
                ),
            ),
        )
        val full = layout(fixture, constraints(width = 1_400f, top = 50f, height = 2_400f))

        assertEquals(CoverageStatus.PARTIAL, partial.coverageStatus)
        assertEquals(range(fixture.snapshot, 3, 7), continuation.remainingSourceRange)
        assertEquals(CoverageStatus.COMPLETE, resumed.coverageStatus)
        assertEquals(
            full.layout.lines.map(::lineFingerprint),
            (partial.layout.lines + resumed.layout.lines).map(::lineFingerprint),
        )

        val incompatible = JvmEditableParagraphFacade.layout(
            request(
                fixture = fixture,
                constraints = constraints(width = 1_399f, top = 1_250f, height = 1_200f),
                sourceRange = continuation.remainingSourceRange,
                continuation = continuation,
            ),
        )
        assertIs<ParagraphLayoutError.InvalidInput>(
            assertIs<ParagraphLayoutResult.Failure>(incompatible).error,
        )

        val incompatibleLeft = JvmEditableParagraphFacade.layout(
            request(
                fixture = fixture,
                constraints = HorizontalParagraphConstraints(
                    region = LayoutRect(
                        LayoutUnit(101f),
                        LayoutUnit(1_250f),
                        LayoutUnit(1_501f),
                        LayoutUnit(2_450f),
                    ),
                    lineMetrics = LineVerticalMetrics(LayoutUnit(900f), LayoutUnit(300f)),
                ),
                sourceRange = continuation.remainingSourceRange,
                continuation = continuation,
            ),
        )
        assertIs<ParagraphLayoutError.InvalidInput>(
            assertIs<ParagraphLayoutResult.Failure>(incompatibleLeft).error,
        )

        val incompatibleTop = JvmEditableParagraphFacade.layout(
            request(
                fixture = fixture,
                constraints = constraints(width = 1_400f, top = 1_251f, height = 1_200f),
                sourceRange = continuation.remainingSourceRange,
                continuation = continuation,
            ),
        )
        assertIs<ParagraphLayoutError.InvalidInput>(
            assertIs<ParagraphLayoutResult.Failure>(incompatibleTop).error,
        )
    }

    @Test
    fun publicFacadeReturnsTypedCancellationAndInvalidClusterRange() {
        val fixture = multiFaceFixture("f\u0301")
        val cancelled = JvmEditableParagraphFacade.layout(
            request(
                fixture,
                constraints(width = 1_400f, top = 50f, height = 1_200f),
                cancellationToken = CancellationToken.cancelled,
            ),
        )
        assertIs<ParagraphLayoutResult.Cancelled>(cancelled)

        val splitCluster = JvmEditableParagraphFacade.layout(
            request(
                fixture,
                constraints(width = 1_400f, top = 50f, height = 1_200f),
                sourceRange = range(fixture.snapshot, 1, 2),
            ),
        )
        assertIs<ParagraphLayoutError.InvalidInput>(
            assertIs<ParagraphLayoutResult.Failure>(splitCluster).error,
        )
    }

    @Test
    fun publicFacadePublishesEmptyAndMandatoryTrailingEmptyLines() {
        val emptyFixture = multiFaceFixture("")
        val empty = layout(emptyFixture, constraints(width = 1_400f, top = 50f, height = 1_200f))
        assertEquals(listOf(emptyFixture.snapshot.range), empty.layout.lines.map(LineLayout::range))
        assertTrue(empty.layout.lines.single().positionedGlyphRuns.isEmpty())

        val terminatedFixture = multiFaceFixture("fi\n")
        val terminated = layout(terminatedFixture, constraints(width = 1_400f, top = 50f, height = 2_400f))
        val end = terminatedFixture.snapshot.range.endExclusive
        assertEquals(
            listOf(terminatedFixture.snapshot.range, TextRange(end, end)),
            terminated.layout.lines.map(LineLayout::range),
        )
        assertTrue(terminated.layout.lines.last().positionedGlyphRuns.isEmpty())
    }

    @Test
    fun ownedBackendCloseFailureCannotPublishAParagraphSuccess() {
        val fixture = multiFaceFixture("fi")
        val backend = assertIs<FontOperationResult.Success<ShapingBackend>>(
            JvmHarfBuzzShapingBackend.open(),
        ).value
        try {
            val result = JvmEditableParagraphFacade.layout(
                request(fixture, constraints(width = 1_400f, top = 50f, height = 1_200f)),
                CloseFailingBackend(backend),
            )

            val failure = assertIs<ParagraphLayoutResult.Failure>(result)
            val fontFailure = assertIs<ParagraphLayoutError.FontFailure>(failure.error)
            assertEquals("font.test-close-failure", fontFailure.fontError.code)
            assertEquals("font.test-close-failure", failure.diagnostics.single().code)
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(backend.close())
        }
    }

    @Test
    fun borrowedBackendIsNotClosedByTheFacadeSeam() {
        val fixture = multiFaceFixture("fi")
        val delegate = assertIs<FontOperationResult.Success<ShapingBackend>>(
            JvmHarfBuzzShapingBackend.open(),
        ).value
        val backend = CloseTrackingBackend(delegate)

        try {
            assertIs<ParagraphLayoutResult.Success>(
                JvmEditableParagraphFacade.layoutBorrowing(
                    request(fixture, constraints(width = 1_400f, top = 50f, height = 1_200f)),
                    backend,
                ),
            )
            assertEquals(0, backend.closeCalls)
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(backend.close())
        }

        assertEquals(1, backend.closeCalls)
    }

    private fun layout(
        fixture: ParagraphFixture,
        constraints: HorizontalParagraphConstraints,
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        language: String = "ar",
    ): ParagraphLayoutResult.Success = assertIs(
        JvmEditableParagraphFacade.layout(request(fixture, constraints, baseDirection = baseDirection, language = language)),
    )

    private fun request(
        fixture: ParagraphFixture,
        constraints: HorizontalParagraphConstraints,
        sourceRange: TextRange = fixture.snapshot.range,
        continuation: org.graphiks.kalligraphie.api.LayoutContinuation? = null,
        cancellationToken: CancellationToken = CancellationToken.none,
        language: String = "ar",
        features: List<OpenTypeFeature> = emptyList(),
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        operationProfile: EditorOperationProfile = EditorOperationProfile.unbounded,
        materialization: EditableLineMaterialization = EditableLineMaterialization.LayoutOnly,
    ): JvmEditableParagraphFacadeRequest = JvmEditableParagraphFacadeRequest(
        snapshot = fixture.snapshot,
        sourceRange = sourceRange,
        constraints = constraints,
        baseDirection = baseDirection,
        language = language,
        fontCatalog = fixture.catalog,
        resolutionPolicy = fixture.policy,
        fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
        features = features,
        materialization = materialization,
        continuation = continuation,
        cancellationToken = cancellationToken,
        operationProfile = operationProfile,
    )

    private fun multiFaceFixture(value: String): ParagraphFixture = fontFixture(
        value = value,
        fonts = listOf(
            FontFixture("gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture"),
            FontFixture("amiri/Amiri-Regular.ttf", "Amiri Regular"),
        ),
        policyId = "public-multiscript-fixture",
    )

    private fun fontFixture(
        value: String,
        fonts: List<FontFixture>,
        policyId: String = "public-paragraph-fixture",
    ): ParagraphFixture {
        val sources = fonts.map { font -> fontSource(font.relativePath, font.declaredName) }
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            Kalligraphie.embedded(sources),
        ).value
        val faces = sources.map { source -> FontFaceId(source.id, 0) }
        val policy = FontResolutionPolicySnapshot(
            generation = catalog.generation,
            policyId = policyId,
            version = "1",
            candidates = faces.map(::FontResolutionCandidate),
            lastResortFace = faces.last(),
        )
        val snapshot = Kalligraphie.decodeUtf16(
            TextVersion.create(),
            listOf(TextSlice.Utf16(value.toCharArray())),
        ).snapshot
        return ParagraphFixture(snapshot, catalog, policy, faces.first(), faces.last())
    }

    private fun constraints(
        width: Float,
        top: Float,
        height: Float,
    ): HorizontalParagraphConstraints = HorizontalParagraphConstraints(
        region = LayoutRect(LayoutUnit(100f), LayoutUnit(top), LayoutUnit(100f + width), LayoutUnit(top + height)),
        lineMetrics = LineVerticalMetrics(LayoutUnit(900f), LayoutUnit(300f)),
    )

    private fun range(snapshot: TextSnapshot, start: Int, endExclusive: Int): TextRange = TextRange(
        snapshot.textIndexAtScalarBoundary(start),
        snapshot.textIndexAtScalarBoundary(endExclusive),
    )

    private fun lineFingerprint(line: LineLayout): List<Any> = listOf(
        line.range,
        line.baseline,
        line.contentMetrics,
        line.lineBox,
        line.designInkBounds,
        line.positionedGlyphRuns.flatMap { run ->
            run.glyphs.map { glyph -> glyph.shapedGlyph.glyphId to glyph.origin }
        },
        line.allCaretCandidates.map { candidate -> candidate.position to candidate.geometry },
    )

    private fun LineLayout.glyphIds(): List<Int> = positionedGlyphRuns.flatMap { run ->
        run.glyphs.map { glyph -> glyph.shapedGlyph.glyphId.value }
    }

    private fun LineLayout.glyphAdvances(): List<Float> = positionedGlyphRuns.flatMap { run ->
        run.glyphs.map { glyph -> glyph.advance.x.value }
    }

    private fun fontSource(relativePath: String, declaredName: String): FontSource = FontSource(
        sourceBytes = fixtureBytes(relativePath),
        provenance = FontSourceProvenance(declaredName),
    )

    private fun mainArtifactFontSource(relativePath: String, declaredName: String): FontSource {
        val classpathPath = "/fonts/$relativePath"
        val sourceBytes = checkNotNull(javaClass.getResourceAsStream(classpathPath)) {
            "main artifact fixture font is missing: $relativePath"
        }.use { stream -> stream.readBytes() }
        return FontSource(sourceBytes, FontSourceProvenance(declaredName))
    }

    private fun fixtureBytes(relativePath: String): ByteArray {
        val classpathPath = "/fonts/$relativePath"
        javaClass.getResourceAsStream(classpathPath)?.use { stream -> return stream.readBytes() }
        val sourceCandidates = listOf(
            Path.of("shaping", "src", "jvmTest", "resources", "fonts", relativePath),
            Path.of("kalligraphie", "shaping", "src", "jvmTest", "resources", "fonts", relativePath),
        )
        val source = sourceCandidates.firstOrNull(Files::isRegularFile)
        return Files.readAllBytes(checkNotNull(source) { "fixture font is missing: $relativePath" })
    }

    private data class ParagraphFixture(
        val snapshot: TextSnapshot,
        val catalog: FontCatalogSnapshot,
        val policy: FontResolutionPolicySnapshot,
        val latinFace: FontFaceId,
        val arabicFace: FontFaceId,
    )

    private data class FontFixture(
        val relativePath: String,
        val declaredName: String,
    )

    private class CloseFailingBackend(
        private val delegate: ShapingBackend,
    ) : ShapingBackend {
        override val identity = delegate.identity

        override fun shape(request: ShapingRequest): FontOperationResult<ShapedGlyphRun> = delegate.shape(request)

        override fun close(): FontOperationResult<Unit> = FontOperationResult.Failure(
            FontError.FontDataFailure(
                code = "font.test-close-failure",
                message = "The test backend could not close.",
                location = FontDiagnosticLocation.Source,
            ),
        )
    }

    private class CloseTrackingBackend(
        private val delegate: ShapingBackend,
    ) : ShapingBackend {
        override val identity = delegate.identity
        var closeCalls: Int = 0
            private set

        override fun shape(request: ShapingRequest): FontOperationResult<ShapedGlyphRun> = delegate.shape(request)

        override fun close(): FontOperationResult<Unit> {
            closeCalls += 1
            return delegate.close()
        }
    }
}
