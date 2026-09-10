package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditorOperationLimitKind
import org.graphiks.kalligraphie.api.EditorOperationProfile
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.EditableLineError
import org.graphiks.kalligraphie.api.FallbackShapingFragment
import org.graphiks.kalligraphie.api.FallbackUnit
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.MultiFontEditableLineRequest
import org.graphiks.kalligraphie.api.OpenTypeScript
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import org.graphiks.kalligraphie.unicode.JvmUnicodeAnalyzer
import org.graphiks.kalligraphie.unicode.TextSnapshots
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MultiFontEditableLineTest {
    @Test
    fun totalGlyphBudgetSpansRealFallbackRunsBeforeExactRetry() {
        val selective = source("/fonts/gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")
        val complete = source("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")
        val catalog = catalogOf(selective, complete)
        val selectiveFace = FontFaceId(selective.id, 0)
        val completeFace = FontFaceId(complete.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = catalog.generation,
            policyId = "real-rejected-attempt-budget",
            version = "1",
            candidates = listOf(FontResolutionCandidate(selectiveFace), FontResolutionCandidate(completeFace)),
            lastResortFace = completeFace,
        )
        val source = text("f\u0301 x")
        val analysis = analyze(source, "en")

        val limited = assertIs<EditableLineResult.Failure>(
            ExactEditableLineLayouter.layout(
                request(
                    source,
                    analysis,
                    catalog,
                    policy,
                    backend(),
                    EditableLineMaterialization.LayoutOnly,
                    operationProfile = EditorOperationProfile(maxTotalGlyphs = 3),
                ),
            ),
        )
        val exceeded = assertIs<EditableLineError.OperationLimitExceeded>(limited.error).limit
        assertEquals(EditorOperationLimitKind.TOTAL_GLYPHS, exceeded.kind)
        assertEquals(3L, exceeded.maximum)
        assertEquals(4L, exceeded.observed)

        val retry = assertIs<EditableLineResult.Success>(
            ExactEditableLineLayouter.layout(
                request(
                    source,
                    analysis,
                    catalog,
                    policy,
                    backend(),
                    EditableLineMaterialization.LayoutOnly,
                    operationProfile = EditorOperationProfile(maxTotalGlyphs = 16),
                ),
            ),
        ).line
        assertEquals(
            listOf(73, 5923, 3, 91),
            retry.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.shapedGlyph.glyphId.value } },
        )
    }

    private val backends = mutableListOf<ShapingBackend>()

    @AfterTest
    fun closeOpenedBackends() {
        backends.asReversed().forEach { backend ->
            assertIs<FontOperationResult.Success<Unit>>(backend.close())
        }
    }

    @Test
    fun renderableMultiscriptLineSelectsLatinAndArabicFacesWithCertifiedFinalGlyphs() {
        val latin = source("/fonts/gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")
        val arabic = source("/fonts/amiri/Amiri-Regular.ttf", "Amiri Regular")
        val catalog = catalogOf(latin, arabic)
        val generation = catalog.generation
        val latinFace = FontFaceId(latin.id, 0)
        val arabicFace = FontFaceId(arabic.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = generation,
            policyId = "audited-multiscript-fixture",
            version = "1",
            candidates = listOf(FontResolutionCandidate(latinFace), FontResolutionCandidate(arabicFace)),
            lastResortFace = arabicFace,
        )
        val text = TextSnapshots.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(org.graphiks.kalligraphie.api.TextSlice.Utf16("fiسلام".toCharArray())),
        ).snapshot
        val analysis = JvmUnicodeAnalyzer.create().analyze(
            text,
            org.graphiks.kalligraphie.api.UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, "ar"),
        )
        val resolver = catalog.openAssetResolver().successValue()
        try {
            val result = ExactEditableLineLayouter.layout(
                MultiFontEditableLineRequest(
                    snapshot = text,
                    unicodeAnalysis = analysis,
                    fontCatalog = catalog,
                    resolutionPolicy = policy,
                    fontInstanceDescriptor = FontInstanceDescriptor(layoutSize = LayoutUnit(1000f)),
                    shapingBackend = backend(),
                    baseDirection = BaseDirection.LEFT_TO_RIGHT,
                    verticalMetrics = LineVerticalMetrics(LayoutUnit(900f), LayoutUnit(300f)),
                    materialization = EditableLineMaterialization.Renderable(
                        resolver = resolver,
                        variant = FontRenderVariantKey.default,
                        outlineProfile = outlineProfile(),
                    ),
                ),
            )

            val line = assertIs<EditableLineResult.Success>(result).line
            assertEquals(listOf(latinFace, arabicFace), line.positionedGlyphRuns.map { it.fontInstanceKey.face })
            assertEquals(listOf(3), line.positionedGlyphRuns[0].glyphs.map { it.shapedGlyph.glyphId.value })
            assertEquals(listOf(900f), line.positionedGlyphRuns[0].glyphs.map { it.advance.x.value })
            assertEquals(listOf(85, 3080, 3075, 1919), line.positionedGlyphRuns[1].glyphs.map { it.shapedGlyph.glyphId.value })
            assertEquals(listOf(452f, 446f, 245f, 568f), line.positionedGlyphRuns[1].glyphs.map { it.advance.x.value })
            assertEquals(listOf(5, 4, 3, 2), line.positionedGlyphRuns[1].glyphs.map { glyph ->
                text.scalarRanges(text.range)
                    .indexOfFirst { range -> range == glyph.sourceClusters.single().sourceRange }
            })
            line.positionedGlyphRuns.forEach { run ->
                assertNotNull(run.renderAssetKey)
                run.glyphs.forEach { glyph -> assertNotNull(glyph.materializationCertificate) }
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun detachedAssetsRemainUsableAndReopenOnlyInTheirCapturedGeneration() {
        val latin = source("/fonts/gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")
        val catalog = catalogOf(latin)
        val requirements = FontAccessRequirementsSnapshot.renderable(outlineProfile())
        val face = catalog.resolveFace(catalog.faces.single().id, requirements).successValue()
        val instance = face.instantiate(FontInstanceDescriptor(LayoutUnit(1000f))).successValue()
        val originalResolver = catalog.openAssetResolver().successValue()
        val attached = instance.acquireRenderAsset(originalResolver, FontRenderVariantKey.default, requirements).successValue()
        val detached = attached.detach().successValue()

        try {
            originalResolver.close()
            attached.close()
            assertIs<GlyphRepresentation.Outline>(detached.resolveGlyph(FontGlyphRequest(GlyphId(3))).successValue())

            val sameGenerationResolver = catalog.openAssetResolver().successValue()
            try {
                val reopened = sameGenerationResolver.reopen(detached.key).successValue()
                try {
                    assertIs<GlyphRepresentation.Outline>(reopened.resolveGlyph(FontGlyphRequest(GlyphId(3))).successValue())
                } finally {
                    reopened.close()
                }
                val unavailable = assertIs<FontOperationResult.Failure>(
                    sameGenerationResolver.reopen(detached.key.copy(variant = FontRenderVariantKey("unsupported"))),
                )
                assertIs<FontError.AssetUnavailable>(unavailable.error)
            } finally {
                sameGenerationResolver.close()
            }

            val incompatibleCatalog = catalogOf(
                latin,
                source("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans"),
            )
            val incompatibleResolver = incompatibleCatalog.openAssetResolver().successValue()
            try {
                val mismatchedAcquisition = assertIs<FontOperationResult.Failure>(
                    instance.acquireRenderAsset(incompatibleResolver, FontRenderVariantKey.default, requirements),
                )
                assertIs<FontError.IncompatibleCatalogGeneration>(mismatchedAcquisition.error)
                val failure = assertIs<FontOperationResult.Failure>(incompatibleResolver.reopen(detached.key))
                assertIs<FontError.IncompatibleCatalogGeneration>(failure.error)
            } finally {
                incompatibleResolver.close()
            }
        } finally {
            detached.close()
        }
    }

    @Test
    fun renderableFallbackRejectsAShapedGlyphWhoseOutlineExceedsTheProfile() {
        val complex = source("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")
        val simple = source("/fonts/gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")
        val catalog = catalogOf(complex, simple)
        val generation = catalog.generation
        val complexFace = FontFaceId(complex.id, 0)
        val simpleFace = FontFaceId(simple.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = generation,
            policyId = "audited-outline-fallback",
            version = "1",
            candidates = listOf(FontResolutionCandidate(complexFace), FontResolutionCandidate(simpleFace)),
            lastResortFace = simpleFace,
        )
        val text = text("fi")
        val analysis = analyze(text, "en")
        val backend = backend()
        val profile = outlineProfile(maxContours = 1)

        val layoutOnly = ExactEditableLineLayouter.layout(
            request(text, analysis, catalog, policy, backend, EditableLineMaterialization.LayoutOnly),
        )
        val layoutOnlyLine = assertIs<EditableLineResult.Success>(layoutOnly).line
        assertEquals(listOf(complexFace), layoutOnlyLine.positionedGlyphRuns.map { it.fontInstanceKey.face })
        assertEquals(listOf(5042), layoutOnlyLine.positionedGlyphRuns.single().glyphs.map { it.shapedGlyph.glyphId.value })
        assertEquals(null, layoutOnlyLine.positionedGlyphRuns.single().renderAssetKey)

        val resolver = catalog.openAssetResolver().successValue()
        try {
            val renderable = ExactEditableLineLayouter.layout(
                request(
                    text,
                    analysis,
                    catalog,
                    policy,
                    backend,
                    EditableLineMaterialization.Renderable(resolver, FontRenderVariantKey.default, profile),
                ),
            )
            val renderableLine = assertIs<EditableLineResult.Success>(renderable).line
            assertEquals(listOf(simpleFace), renderableLine.positionedGlyphRuns.map { it.fontInstanceKey.face })
            assertEquals(listOf(3), renderableLine.positionedGlyphRuns.single().glyphs.map { it.shapedGlyph.glyphId.value })
            renderableLine.positionedGlyphRuns.single().let { run ->
                val asset = resolver.reopen(assertNotNull(run.renderAssetKey)).successValue()
                try {
                    run.glyphs.forEach { glyph ->
                        assertEquals(run.renderAssetKey, glyph.materializationCertificate?.assetKey)
                        assertIs<GlyphRepresentation.Outline>(asset.resolveGlyph(FontGlyphRequest(glyph.shapedGlyph.glyphId)).successValue())
                    }
                } finally {
                    asset.close()
                }
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun deepFallbackIsDeterministicAndDiagnosesTheExplicitLastResort() {
        val latin = source("/fonts/gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")
        val universal = source("/fonts/liberation/LiberationSans-Regular.ttf", "Liberation Sans Regular")
        val arabic = source("/fonts/amiri/Amiri-Regular.ttf", "Amiri Regular")
        val catalog = catalogOf(latin, universal, arabic)
        val generation = catalog.generation
        val latinFace = FontFaceId(latin.id, 0)
        val universalFace = FontFaceId(universal.id, 0)
        val arabicFace = FontFaceId(arabic.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = generation,
            policyId = "audited-deep-fallback",
            version = "1",
            candidates = listOf(
                FontResolutionCandidate(latinFace),
                FontResolutionCandidate(universalFace),
                FontResolutionCandidate(arabicFace),
            ),
            lastResortFace = arabicFace,
        )
        val text = text("سلام")
        val analysis = analyze(text, "ar")
        val backend = backend()
        val resolver = catalog.openAssetResolver().successValue()
        try {
            val results = List(6) {
                assertIs<EditableLineResult.Success>(
                    ExactEditableLineLayouter.layout(
                        request(
                            text,
                            analysis,
                            catalog,
                            policy,
                            backend,
                            EditableLineMaterialization.Renderable(resolver, FontRenderVariantKey.default, outlineProfile()),
                        ),
                    ),
                ).line
            }
            val first = results.first()
            assertEquals(listOf(arabicFace), first.positionedGlyphRuns.map { it.fontInstanceKey.face })
            assertEquals(listOf(85, 3080, 3075, 1919), first.positionedGlyphRuns.single().glyphs.map { it.shapedGlyph.glyphId.value })
            assertEquals(
                8,
                first.diagnostics.count { diagnostic -> diagnostic.code == "font.fallback-candidate-rejected" },
            )
            assertEquals(
                4,
                first.diagnostics.count { diagnostic -> diagnostic.code == "font.fallback-last-resort" },
            )
            assertTrue(results.drop(1).all { line ->
                line.positionedGlyphRuns.map { it.fontInstanceKey } == first.positionedGlyphRuns.map { it.fontInstanceKey } &&
                    line.diagnostics == first.diagnostics
            })
        } finally {
            resolver.close()
        }
    }

    @Test
    fun exhaustionReturnsATypedFailureWithoutPublishingAPartialLine() {
        val latin = source("/fonts/gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")
        val catalog = catalogOf(latin)
        val generation = catalog.generation
        val latinFace = FontFaceId(latin.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = generation,
            policyId = "audited-exhaustion",
            version = "1",
            candidates = listOf(FontResolutionCandidate(latinFace)),
            lastResortFace = latinFace,
        )
        val text = text("سلام")
        val result = ExactEditableLineLayouter.layout(
            request(text, analyze(text, "ar"), catalog, policy, backend(), EditableLineMaterialization.LayoutOnly),
        )

        val failure = assertIs<EditableLineResult.Failure>(result)
        assertIs<org.graphiks.kalligraphie.api.FontError.UnrenderableFontResolution>(
            assertIs<EditableLineError.FontResolutionFailure>(failure.error).fontError,
        )
        assertEquals(1, failure.diagnostics.count { diagnostic -> diagnostic.code == "font.fallback-candidate-rejected" })
    }

    @Test
    fun graphemeVariationAndEmojiZwJSequencesRemainAssignedToOneFace() {
        val selective = source("/fonts/gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")
        val complete = source("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")
        val catalog = catalogOf(selective, complete)
        val generation = catalog.generation
        val selectiveFace = FontFaceId(selective.id, 0)
        val completeFace = FontFaceId(complete.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = generation,
            policyId = "audited-unicode-units",
            version = "1",
            candidates = listOf(FontResolutionCandidate(selectiveFace), FontResolutionCandidate(completeFace)),
            lastResortFace = completeFace,
        )
        val backend = backend()

        listOf(
            "f\u0301" to completeFace,
            "\u2764\uFE0F" to selectiveFace,
            "\u2764\uFE0F\u200D\u2764\uFE0F" to selectiveFace,
        ).forEach { (value, expectedFace) ->
            val text = text(value)
            val analysis = analyze(text, "und")
            val line = assertIs<EditableLineResult.Success>(
                ExactEditableLineLayouter.layout(request(text, analysis, catalog, policy, backend, EditableLineMaterialization.LayoutOnly)),
            ).line
            assertEquals(listOf(expectedFace), line.positionedGlyphRuns.map { it.fontInstanceKey.face })
            assertEquals(text.range, line.positionedGlyphRuns.single().sourceRun.range)
            assertEquals(analysis.graphemeClusters, line.positionedGlyphRuns.single().sourceRun.graphemeClusters)
        }
    }

    @Test
    fun resolverPublishesTheAtomicEmojiZwjUnitWithItsCompleteFragments() {
        val selective = source("/fonts/gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")
        val complete = source("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")
        val catalog = catalogOf(selective, complete)
        val selectiveFace = FontFaceId(selective.id, 0)
        val completeFace = FontFaceId(complete.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = catalog.generation,
            policyId = "atomic-emoji-zwj-fragments",
            version = "1",
            candidates = listOf(FontResolutionCandidate(selectiveFace), FontResolutionCandidate(completeFace)),
            lastResortFace = completeFace,
        )
        val source = text("\u2764\uFE0F\u200D\u2764\uFE0F")
        val analysis = analyze(source, "und")

        val resolution = FontFallbackResolver.resolve(
            request(source, analysis, catalog, policy, backend(), EditableLineMaterialization.LayoutOnly),
        ).successValue()

        val unit = resolution.units.single()
        val expectedFragments = listOf(
            FallbackShapingFragment(range(source, 0, 1), OpenTypeScript("Zyyy"), "und", 0),
            FallbackShapingFragment(range(source, 1, 3), OpenTypeScript("Zinh"), "und", 0),
            FallbackShapingFragment(range(source, 3, 4), OpenTypeScript("Zyyy"), "und", 0),
            FallbackShapingFragment(range(source, 4, 5), OpenTypeScript("Zinh"), "und", 0),
        )
        assertEquals(source.range, unit.range)
        assertEquals(expectedFragments, unit.fragments)

        val suppliedFragments = unit.fragments.toMutableList()
        val detachedUnit = FallbackUnit(unit.range, suppliedFragments)

        suppliedFragments.clear()

        assertEquals(expectedFragments, detachedUnit.fragments)
        assertFailsWith<UnsupportedOperationException> {
            (detachedUnit.fragments as MutableList<FallbackShapingFragment>).clear()
        }
    }

    @Test
    fun initialArabicZwjReachesRealShapingWithoutIndependentCmapCoverage() {
        val arabic = source("/fonts/amiri/Amiri-Regular.ttf", "Amiri Regular")
        val catalog = catalogOf(arabic)
        val arabicFace = FontFaceId(arabic.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = catalog.generation,
            policyId = "initial-arabic-zwj-shaping",
            version = "1",
            candidates = listOf(FontResolutionCandidate(arabicFace)),
            lastResortFace = arabicFace,
        )
        val source = text("\u200D\u0628")
        val analysis = analyze(source, "ar", BaseDirection.RIGHT_TO_LEFT)

        val resolution = FontFallbackResolver.resolve(
            request(
                source,
                analysis,
                catalog,
                policy,
                backend(),
                EditableLineMaterialization.LayoutOnly,
                BaseDirection.RIGHT_TO_LEFT,
            ),
        ).successValue()

        val shapedRun = resolution.shapedRuns.single()
        assertEquals(source.range, shapedRun.range)
        assertEquals(listOf(1589, 1), shapedRun.glyphs.map { glyph -> glyph.glyphId.value })
        assertEquals(listOf(883f, 0f), shapedRun.glyphs.map { glyph -> glyph.xAdvance.value })
    }

    @Test
    fun variationSequenceFallsBackOnlyToFaceWithAnExplicitUvsMapping() {
        val baseOnly = source("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")
        val uvs = source("/fonts/gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")
        val catalog = catalogOf(baseOnly, uvs)
        val generation = catalog.generation
        val uvsFace = FontFaceId(uvs.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = generation,
            policyId = "audited-variation-sequence",
            version = "1",
            candidates = listOf(FontResolutionCandidate(FontFaceId(baseOnly.id, 0)), FontResolutionCandidate(uvsFace)),
            lastResortFace = uvsFace,
        )
        val source = text("\u2764\uFE0F")

        val line = assertIs<EditableLineResult.Success>(
            ExactEditableLineLayouter.layout(
                request(source, analyze(source, "und"), catalog, policy, backend(), EditableLineMaterialization.LayoutOnly),
            ),
        ).line

        assertEquals(listOf(uvsFace), line.positionedGlyphRuns.map { it.fontInstanceKey.face })
        assertEquals(listOf(6), line.positionedGlyphRuns.single().glyphs.map { it.shapedGlyph.glyphId.value })
    }

    @Test
    fun fallbackKeepsThePreferredFaceOnBothSidesOfAnUnsupportedSameScriptGrapheme() {
        val preferred = source("/fonts/gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")
        val fallback = source("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")
        val catalog = catalogOf(preferred, fallback)
        val generation = catalog.generation
        val preferredFace = FontFaceId(preferred.id, 0)
        val fallbackFace = FontFaceId(fallback.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = generation,
            policyId = "audited-intra-script-fallback",
            version = "1",
            candidates = listOf(FontResolutionCandidate(preferredFace), FontResolutionCandidate(fallbackFace)),
            lastResortFace = fallbackFace,
        )
        val source = text("fAf")

        val line = assertIs<EditableLineResult.Success>(
            ExactEditableLineLayouter.layout(
                request(source, analyze(source, "en"), catalog, policy, backend(), EditableLineMaterialization.LayoutOnly),
            ),
        ).line

        assertEquals(
            listOf(preferredFace, fallbackFace, preferredFace),
            line.positionedGlyphRuns.map { it.fontInstanceKey.face },
        )
        assertEquals(listOf(1, 36, 1), line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.shapedGlyph.glyphId.value } })
    }

    @Test
    fun fallbackDoesNotDiscardTheCyrillicFragmentInsideAGraphemeBeforeArabicFallback() {
        val latin = source("/fonts/liberation/LiberationSans-Regular.ttf", "Liberation Sans Regular")
        val arabic = source("/fonts/amiri/Amiri-Regular.ttf", "Amiri Regular")
        val catalog = catalogOf(latin, arabic)
        val generation = catalog.generation
        val latinFace = FontFaceId(latin.id, 0)
        val arabicFace = FontFaceId(arabic.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = generation,
            policyId = "audited-cross-itemization-fallback",
            version = "1",
            candidates = listOf(FontResolutionCandidate(latinFace), FontResolutionCandidate(arabicFace)),
            lastResortFace = arabicFace,
        )
        val source = text("f\u0483سلام")
        val analysis = analyze(source, "ar")

        assertEquals(listOf("Latn", "Cyrl", "Arab"), analysis.scriptLanguageRuns.map { it.script })
        assertTrue(analysis.graphemeClusters.any { grapheme ->
            analysis.scriptLanguageRuns[1].range.start >= grapheme.start &&
                analysis.scriptLanguageRuns[1].range.endExclusive <= grapheme.endExclusive &&
                analysis.scriptLanguageRuns[1].range != grapheme
        })

        val lines = List(2) {
            assertIs<EditableLineResult.Success>(
                ExactEditableLineLayouter.layout(
                    request(source, analysis, catalog, policy, backend(), EditableLineMaterialization.LayoutOnly),
                ),
            ).line
        }
        val line = lines.first()

        assertEquals(listOf(latinFace, latinFace, arabicFace), line.positionedGlyphRuns.map { it.fontInstanceKey.face })
        assertEquals(listOf(73, 1076, 85, 3080, 3075, 1919), line.positionedGlyphRuns.flatMap { run ->
            run.glyphs.map { it.shapedGlyph.glyphId.value }
        })
        assertEquals(listOf(277.83203f, 0f, 452f, 446f, 245f, 568f), line.positionedGlyphRuns.flatMap { run ->
            run.glyphs.map { it.advance.x.value }
        })
        val publishedScalars = line.positionedGlyphRuns.flatMap { run ->
            run.glyphs.flatMap { glyph -> glyph.sourceClusters.flatMap { cluster -> cluster.scalarRanges } }
        }
        assertEquals(source.scalarRanges(source.range).toSet(), publishedScalars.toSet())
        assertEquals(source.scalarRanges(source.range).size, publishedScalars.size)
        assertTrue(lines.drop(1).all { candidate ->
            candidate.positionedGlyphRuns.map { run -> run.fontInstanceKey.face } ==
                line.positionedGlyphRuns.map { run -> run.fontInstanceKey.face }
        })
    }

    @Test
    fun cancellationBetweenFragmentsInOneFallbackGroupStartsNoSecondShape() {
        val latin = source("/fonts/liberation/LiberationSans-Regular.ttf", "Liberation Sans Regular")
        val arabic = source("/fonts/amiri/Amiri-Regular.ttf", "Amiri Regular")
        val catalog = catalogOf(latin, arabic)
        val generation = catalog.generation
        val latinFace = FontFaceId(latin.id, 0)
        val arabicFace = FontFaceId(arabic.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = generation,
            policyId = "fragment-cancellation",
            version = "1",
            candidates = listOf(FontResolutionCandidate(latinFace), FontResolutionCandidate(arabicFace)),
            lastResortFace = arabicFace,
        )
        val source = text("f\u0483\u0633\u0644\u0627\u0645")
        val token = SwitchableCancellationToken()
        val backend = CancellingAfterFirstShapeBackend(backend(), token)

        val result = ExactEditableLineLayouter.layout(
            request(
                source,
                analyze(source, "und"),
                catalog,
                policy,
                backend,
                EditableLineMaterialization.LayoutOnly,
                cancellationToken = token,
            ),
        )

        assertIs<EditableLineResult.Cancelled>(result)
        assertEquals(1, backend.shapeCalls)
    }

    @Test
    fun emptyMultiFontLineUsesTheExplicitRightToLeftCaretDirectionWithoutResolvingAFace() {
        val source = text("")
        val fallback = source("/fonts/gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")
        val catalog = catalogOf(fallback)
        val generation = catalog.generation
        val fallbackFace = FontFaceId(fallback.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = generation,
            policyId = "audited-empty-multi-font",
            version = "1",
            candidates = listOf(FontResolutionCandidate(fallbackFace)),
            lastResortFace = fallbackFace,
        )

        val line = assertIs<EditableLineResult.Success>(
            ExactEditableLineLayouter.layout(
                request(
                    source,
                    analyze(source, "und", BaseDirection.RIGHT_TO_LEFT),
                    catalog,
                    policy,
                    backend(),
                    EditableLineMaterialization.LayoutOnly,
                    BaseDirection.RIGHT_TO_LEFT,
                ),
            ),
        ).line

        assertTrue(line.positionedGlyphRuns.isEmpty())
        assertEquals(2, line.allCaretCandidates.size)
        assertTrue(line.allCaretCandidates.all { it.direction == org.graphiks.kalligraphie.api.ShapingDirection.RIGHT_TO_LEFT })
        assertTrue(line.allCaretCandidates.all { it.bidiLevel == 1 })
    }

    private fun source(resource: String, declaredName: String): FontSource =
        FontSource(fixtureBytes(resource), FontSourceProvenance(declaredName))

    private fun fixtureBytes(resource: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes() }

    private fun catalogOf(vararg sources: FontSource): FontCatalogSnapshot =
        Kalligraphie.embedded(sources.toList()).successValue()

    private fun outlineProfile(maxContours: Int = 10_000): OutlineProfile = OutlineProfile(
        maxBytes = 1_000_000,
        maxContours = maxContours,
        maxPoints = 100_000,
        maxCompositeDepth = 32,
        maxCompositeComponents = 10_000,
    )

    private fun <T> FontOperationResult<T>.successValue(): T =
        assertIs<FontOperationResult.Success<T>>(this).value

    private fun backend(): ShapingBackend = JvmHarfBuzzShapingBackend.open().successValue().also(backends::add)

    private fun text(value: String) = TextSnapshots.decodeUtf16(
        version = TextVersion.create(),
        slices = listOf(org.graphiks.kalligraphie.api.TextSlice.Utf16(value.toCharArray())),
    ).snapshot

    private fun range(
        snapshot: org.graphiks.kalligraphie.api.TextSnapshot,
        start: Int,
        endExclusive: Int,
    ): TextRange = TextRange(snapshot.textIndexAtScalarBoundary(start), snapshot.textIndexAtScalarBoundary(endExclusive))

    private fun analyze(
        text: org.graphiks.kalligraphie.api.TextSnapshot,
        language: String,
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
    ) = JvmUnicodeAnalyzer.create().analyze(
        text,
        org.graphiks.kalligraphie.api.UnicodeAnalysisRequest(baseDirection, language),
    )

    private fun request(
        text: org.graphiks.kalligraphie.api.TextSnapshot,
        analysis: org.graphiks.kalligraphie.api.UnicodeAnalysis,
        catalog: FontCatalogSnapshot,
        policy: FontResolutionPolicySnapshot,
        backend: org.graphiks.kalligraphie.api.ShapingBackend,
        materialization: EditableLineMaterialization,
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        cancellationToken: CancellationToken = CancellationToken.none,
        operationProfile: EditorOperationProfile = EditorOperationProfile.unbounded,
    ): MultiFontEditableLineRequest = MultiFontEditableLineRequest(
        snapshot = text,
        unicodeAnalysis = analysis,
        fontCatalog = catalog,
        resolutionPolicy = policy,
        fontInstanceDescriptor = FontInstanceDescriptor(layoutSize = LayoutUnit(1000f)),
        shapingBackend = backend,
        baseDirection = baseDirection,
        verticalMetrics = LineVerticalMetrics(LayoutUnit(900f), LayoutUnit(300f)),
        materialization = materialization,
        cancellationToken = cancellationToken,
        operationProfile = operationProfile,
    )

    private class SwitchableCancellationToken : CancellationToken {
        private var cancelled: Boolean = false

        fun cancel() {
            cancelled = true
        }

        override fun isCancellationRequested(): Boolean = cancelled
    }

    private class CancellingAfterFirstShapeBackend(
        private val delegate: ShapingBackend,
        private val token: SwitchableCancellationToken,
    ) : ShapingBackend {
        override val identity = delegate.identity
        var shapeCalls: Int = 0
            private set

        override fun shape(request: ShapingRequest): FontOperationResult<ShapedGlyphRun> {
            shapeCalls += 1
            val result = delegate.shape(request)
            if (shapeCalls == 1) token.cancel()
            return result
        }

        override fun close(): FontOperationResult<Unit> = delegate.close()
    }
}
