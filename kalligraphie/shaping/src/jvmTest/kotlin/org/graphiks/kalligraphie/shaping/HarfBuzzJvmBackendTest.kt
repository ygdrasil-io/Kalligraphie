package org.graphiks.kalligraphie.shaping

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.ShaperClusterToken
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShapingFeaturePolicy
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.ShapingResourceLimit
import org.graphiks.kalligraphie.api.ShapingResourceProfile
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.OpenTypeScript
import org.graphiks.kalligraphie.api.GdefLigatureCaretState
import org.graphiks.kalligraphie.api.ShaperCluster
import org.graphiks.kalligraphie.unicode.TextSnapshots
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HarfBuzzJvmBackendTest {
    private val backends = mutableListOf<ShapingBackend>()

    @Test
    fun shapesAnItemWithItsArabicParagraphContext() {
        val backend = backend()
        val prepared = text("ببب")
        val font = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")
        val item = range(prepared, 1, 2)
        fun shapeItem(itemRange: TextRange, contextRange: TextRange) = backend.shape(
            request(
                prepared, font, ShapingDirection.RIGHT_TO_LEFT, OpenTypeScript("Arab"), "ar", 1,
                itemRange = itemRange, contextRange = contextRange,
                graphemeRanges = prepared.scalarRanges().filter { it.start >= itemRange.start && it.endExclusive <= itemRange.endExclusive },
            ),
        ).successValue()

        val full = shapeItem(prepared.snapshot.range, prepared.snapshot.range)
        val isolated = shapeItem(item, item)
        val contextual = shapeItem(item, prepared.snapshot.range)
        val fullToken = full.clusters.single { it.sourceRange == item }.token
        val medial = full.glyphs.single { fullToken in it.clusterTokens }

        assertEquals(GlyphId(5260), medial.glyphId)
        assertEquals(medial.glyphId, contextual.glyphs.single().glyphId)
        assertTrue(isolated.glyphs.single().glyphId != contextual.glyphs.single().glyphId)
        assertTrue(medial.safetyFlags.unsafeToConcat)
        assertEquals(medial.safetyFlags.unsafeToConcat, contextual.glyphs.single().safetyFlags.unsafeToConcat)
        // HarfBuzz conservatively marks even the isolated joining letter unsafe to concatenate.
        assertEquals(isolated.glyphs.single().safetyFlags.unsafeToConcat, contextual.glyphs.single().safetyFlags.unsafeToConcat)
        assertEquals(item, contextual.range)
        assertEquals(listOf(item), contextual.clusters.map { it.sourceRange })
        assertEquals(listOf(item), contextual.clusters.single().scalarRanges)
        assertEquals(listOf(ShaperClusterToken(0)), contextual.glyphs.single().clusterTokens)
    }

    @Test
    fun aLigatureAtTheItemBoundaryNeverPublishesPartialProvenance() {
        val backend = backend()
        val prepared = text("fi")
        val font = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")
        val full = backend.shape(request(prepared, font, ShapingDirection.LEFT_TO_RIGHT, OpenTypeScript("Latn"), "en", 0)).successValue()
        assertEquals(listOf(GlyphId(5042)), full.glyphs.map { it.glyphId })
        assertEquals(listOf(prepared.snapshot.range), full.clusters.map { it.sourceRange })

        listOf(0 to GlyphId(73), 1 to GlyphId(76)).forEach { (offset, expectedGlyph) ->
            val item = range(prepared, offset, offset + 1)
            val shaped = backend.shape(
                request(
                    prepared, font, ShapingDirection.LEFT_TO_RIGHT, OpenTypeScript("Latn"), "en", 0,
                    itemRange = item, contextRange = prepared.snapshot.range, graphemeRanges = listOf(item),
                ),
            ).successValue()
            assertEquals(listOf(expectedGlyph), shaped.glyphs.map { it.glyphId })
            assertEquals(listOf(item), shaped.clusters.map { it.sourceRange })
            assertEquals(listOf(item), shaped.clusters.single().scalarRanges)
            assertEquals(listOf(ShaperClusterToken(0)), shaped.glyphs.single().clusterTokens)
        }
    }

    @Test
    fun mixedScriptLanguageAndBidiContextKeepsOnlyTheSelectedItem() {
        val backend = backend()
        val prepared = text("😀fi ببب a")
        val font = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")
        val item = range(prepared, 5, 6)
        val shaped = backend.shape(
            request(
                prepared, font, ShapingDirection.RIGHT_TO_LEFT, OpenTypeScript("Arab"), "ar", 1,
                itemRange = item, contextRange = prepared.snapshot.range, graphemeRanges = listOf(item),
            ),
        ).successValue()
        assertEquals(listOf(GlyphId(5260)), shaped.glyphs.map { it.glyphId })
        assertEquals("ar", shaped.language)
        assertEquals(OpenTypeScript("Arab"), shaped.script)
        assertEquals(1, shaped.bidiLevel)
        assertEquals(listOf(item), shaped.clusters.map { it.sourceRange })
        assertEquals(listOf(item), shaped.clusters.single().scalarRanges)
        assertEquals(listOf(ShaperClusterToken(0)), shaped.glyphs.single().clusterTokens)
        listOf(
            Triple(range(prepared, 1, 3), "en", GlyphId(5042)),
            Triple(range(prepared, 8, 9), "fr", GlyphId(68)),
        ).forEach { (latinItem, language, expectedGlyph) ->
            val latin = backend.shape(
                request(
                    prepared, font, ShapingDirection.LEFT_TO_RIGHT, OpenTypeScript("Latn"), language, 0,
                    itemRange = latinItem, contextRange = prepared.snapshot.range,
                    graphemeRanges = prepared.scalarRanges().filter { it.start >= latinItem.start && it.endExclusive <= latinItem.endExclusive },
                ),
            ).successValue()
            assertEquals(listOf(expectedGlyph), latin.glyphs.map { it.glyphId })
            assertEquals(language, latin.language)
            assertEquals(0, latin.bidiLevel)
            assertEquals(listOf(latinItem), latin.clusters.map { it.sourceRange })
            assertEquals(listOf(ShaperClusterToken(0)), latin.glyphs.single().clusterTokens)
        }
    }

    @Test
    fun anArabicItemCannotBypassTheScalarBudgetWithItsSurroundingContext() {
        val prepared = text("ببب")
        val item = range(prepared, 1, 2)
        val result = backend().shape(
            request(
                prepared, fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans"),
                ShapingDirection.RIGHT_TO_LEFT, OpenTypeScript("Arab"), "ar", 1,
                itemRange = item, contextRange = prepared.snapshot.range, graphemeRanges = listOf(item),
                resourceProfile = ShapingResourceProfile(maxScalars = 1),
            ),
        )
        val error = assertIs<FontError.ShapingResourceLimitExceeded>(assertIs<FontOperationResult.Failure>(result).error)
        assertEquals(ShapingResourceLimit.SCALARS, error.limit)
        assertEquals(3, error.observed)
    }

    @AfterTest
    fun closeOpenedBackends() {
        backends.asReversed().forEach { backend ->
            assertIs<FontOperationResult.Success<Unit>>(backend.close())
        }
    }

    @Test
    fun closingTheBackendReleasesPreparedFontsAndRejectsLaterShaping() {
        val backend = backend()
        val prepared = text("fi")
        val request = request(
            prepared = prepared,
            font = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans"),
            direction = ShapingDirection.LEFT_TO_RIGHT,
            script = OpenTypeScript("Latn"),
            language = "en",
            bidiLevel = 0,
        )

        assertIs<FontOperationResult.Success<*>>(backend.shape(request))
        assertIs<FontOperationResult.Success<*>>(backend.close())
        assertIs<FontOperationResult.Success<*>>(backend.close())

        val failure = assertIs<FontOperationResult.Failure>(backend.shape(request))
        assertIs<FontError.ResourceClosed>(failure.error)
    }

    @Test
    fun eachPreparedFontBudgetRejectsARealLigatureWithoutPublishingPartialGlyphs() {
        val policies = listOf(
            JvmPreparedFontCachePolicy.default.copy(maxEntries = 0),
            JvmPreparedFontCachePolicy.default.copy(maxSourceBytes = 1),
            JvmPreparedFontCachePolicy.default.copy(maxEstimatedNativeBytes = 1),
            JvmPreparedFontCachePolicy.default.copy(maxTotalBytes = 1),
        )
        val prepared = text("fi")
        val font = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")
        for (policy in policies) {
            val backend = JvmHarfBuzzShapingBackend.open(policy).successValue()
            try {
                val failure = assertIs<FontOperationResult.Failure>(backend.shape(
                    request(prepared, font, ShapingDirection.LEFT_TO_RIGHT, OpenTypeScript("Latn"), "en", 0),
                ))
                assertIs<FontError.ResourceLimitExceeded>(failure.error)
            } finally {
                assertIs<FontOperationResult.Success<*>>(backend.close())
                assertIs<FontOperationResult.Success<*>>(backend.close())
            }
        }
    }

    @Test
    fun aOneFontBudgetStillShapesAlternatingRealSizesWithCompleteGeometry() {
        val backend = JvmHarfBuzzShapingBackend.open(
            JvmPreparedFontCachePolicy.default.copy(maxEntries = 1),
        ).successValue()
        val large = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")
        val small = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans", LayoutUnit(1024f))
        try {
            for ((font, advance) in listOf(large to 1290f, small to 645f, large to 1290f)) {
                val shaped = shape(backend, "fi", font, ShapingDirection.LEFT_TO_RIGHT, OpenTypeScript("Latn"), "en", 0)
                assertEquals(listOf(GlyphId(5042)), shaped.glyphs.map { it.glyphId })
                assertEquals(listOf(advance), shaped.glyphs.map { it.xAdvance.value })
                assertEquals(1, shaped.clusters.size)
                assertEquals(2, shaped.clusters.single().scalarRanges.size)
            }
        } finally {
            backend.close()
        }
    }

    @Test
    fun explicitPinnedDefaultFeaturePolicyShapesTheAuditedDefaultLigature() {
        val backend = backend()
        val policy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy

        val shaped = shape(
            backend = backend,
            text = "fi",
            font = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans"),
            direction = ShapingDirection.LEFT_TO_RIGHT,
            script = OpenTypeScript("Latn"),
            language = "en",
            bidiLevel = 0,
            featurePolicy = policy,
        )

        assertEquals(policy, backend.identity.semantic.featurePolicy)
        assertEquals(policy, shaped.featurePolicy)
        assertEquals(emptyList(), shaped.features)
        assertEquals(listOf(GlyphId(5042)), shaped.glyphs.map { it.glyphId })
    }

    @Test
    fun targetDistributionKeepsPortableSemanticsAndAuditedLatinShaping() {
        val backend = backend()

        val shaped = shape(
            backend = backend,
            text = "fi",
            font = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans"),
            direction = ShapingDirection.LEFT_TO_RIGHT,
            script = OpenTypeScript("Latn"),
            language = "en",
            bidiLevel = 0,
            features = listOf(OpenTypeFeature("liga", 1)),
        )

        assertEquals("harfbuzz-jvm", shaped.backendIdentity.semantic.backendId)
        assertEquals("harfbuzz", shaped.backendIdentity.semantic.engineId)
        assertEquals("14.3.0", shaped.backendIdentity.semantic.engineVersion)
        assertEquals("ot", shaped.backendIdentity.semantic.shaperId)
        assertEquals(JvmHarfBuzzShapingBackend.pinnedFeaturePolicy, shaped.backendIdentity.semantic.featurePolicy)
        assertTrue(shaped.backendIdentity.semantic.configurationFingerprint.contains("monotone-characters"))
        assertEquals(expectedOperatingSystem(), shaped.backendIdentity.provenance.operatingSystem)
        assertEquals(expectedArchitecture(), shaped.backendIdentity.provenance.architecture)
        assertEquals(expectedNativeSourceRevision(), shaped.backendIdentity.provenance.sourceRevision)
        assertEquals(expectedNativeArtifactId(), shaped.backendIdentity.provenance.artifactId)
        assertEquals(expectedNativeArtifactSha256(), shaped.backendIdentity.provenance.artifactSha256)
        assertEquals("harfbuzz", shaped.backendIdentity.provenance.sourceProject)
        assertEquals(expectedBuildChainIdentity(), shaped.backendIdentity.provenance.buildChainIdentity)
        assertEquals(listOf(GlyphId(5042)), shaped.glyphs.map { it.glyphId })
        assertEquals(listOf(LayoutUnit(1290f)), shaped.glyphs.map { it.xAdvance })
        assertEquals(listOf(ShaperClusterToken(0)), shaped.glyphs.map { it.clusterToken })
    }

    @Test
    fun nativeIdentityBindsEachTargetToItsVerifiedArtifactAndOtShaper() {
        val identity = backend().identity

        assertEquals(expectedNativeSourceRevision(), identity.provenance.sourceRevision)
        assertEquals(expectedNativeArtifactId(), identity.provenance.artifactId)
        assertEquals(expectedNativeArtifactSha256(), identity.provenance.artifactSha256)
        assertEquals("ot", identity.semantic.shaperId)
    }

    @Test
    fun disabledStandardLigatureUsesTheFrozenSeparateGlyphsAndAdvances() {
        val policy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy
        val shaped = shape(
            backend = backend(),
            text = "fi",
            font = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans"),
            direction = ShapingDirection.LEFT_TO_RIGHT,
            script = OpenTypeScript("Latn"),
            language = "en",
            bidiLevel = 0,
            featurePolicy = policy,
            features = listOf(OpenTypeFeature("liga", 0)),
        )

        assertEquals(policy, shaped.featurePolicy)
        assertEquals(listOf(GlyphId(73), GlyphId(76)), shaped.glyphs.map { it.glyphId })
        assertEquals(listOf(LayoutUnit(721f), LayoutUnit(569f)), shaped.glyphs.map { it.xAdvance })
        assertEquals(listOf(ShaperClusterToken(0), ShaperClusterToken(1)), shaped.glyphs.map { it.clusterToken })
    }

    @Test
    fun nonDesignLayoutSizePreservesFrozenFractionalAdvance() {
        val shaped = shape(
            backend = backend(),
            text = "fi",
            font = fontInstance(
                "/fonts/dejavu/DejaVuSans.ttf",
                "DejaVu Sans",
                layoutSize = LayoutUnit(1000f),
            ),
            direction = ShapingDirection.LEFT_TO_RIGHT,
            script = OpenTypeScript("Latn"),
            language = "en",
            bidiLevel = 0,
            features = listOf(OpenTypeFeature("liga", 1)),
        )

        assertEquals(LayoutUnit(629.8828f), shaped.glyphs.single().xAdvance)
    }

    @Test
    fun ligaturePreservesBidirectionalTextClusterGlyphProjectionsAndAuditedGdefAbsence() {
        val backend = backend()
        val prepared = text("fi")
        val shaped = backend.shape(
            request(
                prepared = prepared,
                font = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans"),
                direction = ShapingDirection.LEFT_TO_RIGHT,
                script = OpenTypeScript("Latn"),
                language = "en",
                bidiLevel = 0,
                features = listOf(OpenTypeFeature("liga", 1)),
            ),
        ).successValue()

        val firstScalar = range(prepared, 0, 1)
        val secondScalar = range(prepared, 1, 2)
        val merged = range(prepared, 0, 2)
        val token = ShaperClusterToken(0)
        assertEquals(listOf(token), shaped.mappings.clustersForSource(firstScalar))
        assertEquals(listOf(token), shaped.mappings.clustersForSource(secondScalar))
        assertEquals(listOf(firstScalar, secondScalar), shaped.mappings.sourcesForCluster(token))
        assertEquals(listOf(0), shaped.mappings.glyphsForCluster(token))
        assertEquals(listOf(token), shaped.mappings.clustersForGlyph(0))
        assertEquals(merged, shaped.clusters.single().sourceRange)
        assertEquals(listOf(firstScalar, secondScalar), shaped.clusters.single().scalarRanges)
        assertEquals(
            listOf(index(prepared, 0), index(prepared, 1), index(prepared, 2)),
            shaped.clusters.single().admissibleGraphemeBoundaries,
        )
        assertEquals(GdefLigatureCaretState.ABSENT, shaped.ligatureCaretFacts.single().state)
    }

    @Test
    fun combiningMarkPreservesFrozenPositionAndSeparateSourceRelations() {
        val backend = backend()
        val prepared = text("x\u0301")
        val shaped = backend.shape(
            request(
                prepared = prepared,
                font = fontInstance("/fonts/liberation/LiberationSans-Regular.ttf", "Liberation Sans"),
                direction = ShapingDirection.LEFT_TO_RIGHT,
                script = OpenTypeScript("Latn"),
                language = "en",
                bidiLevel = 0,
                graphemeRanges = listOf(range(prepared, 0, 2)),
            ),
        ).successValue()

        assertEquals(listOf(GlyphId(91), GlyphId(707)), shaped.glyphs.map { it.glyphId })
        assertEquals(LayoutUnit(-249f), shaped.glyphs[1].xOffset)
        assertEquals(LayoutUnit(-340f), shaped.glyphs[1].yOffset)
        assertEquals(listOf(ShaperClusterToken(0)), shaped.mappings.clustersForSource(range(prepared, 0, 1)))
        assertEquals(listOf(ShaperClusterToken(1)), shaped.mappings.clustersForSource(range(prepared, 1, 2)))
        assertEquals(listOf(0), shaped.mappings.glyphsForCluster(ShaperClusterToken(0)))
        assertEquals(listOf(1), shaped.mappings.glyphsForCluster(ShaperClusterToken(1)))
        assertEquals(listOf(range(prepared, 1, 2)), shaped.clusters[1].scalarRanges)
        assertEquals(listOf(index(prepared, 2)), shaped.clusters[1].admissibleGraphemeBoundaries)
        assertTrue(index(prepared, 1) !in shaped.clusters[1].admissibleGraphemeBoundaries)
    }

    @Test
    fun explicitRtlRunRetainsFrozenGlyphOrderFlagsLevelAndDirection() {
        val backend = backend()
        val font = fontInstance("/fonts/liberation/LiberationSans-Regular.ttf", "Liberation Sans")
        val prepared = text("שלום")
        val shaped = backend.shape(
            request(
                prepared = prepared,
                font = font,
                direction = ShapingDirection.RIGHT_TO_LEFT,
                script = OpenTypeScript("Hebr"),
                language = "he",
                bidiLevel = 1,
            ),
        ).successValue()

        assertEquals(ShapingDirection.RIGHT_TO_LEFT, shaped.direction)
        assertEquals(1, shaped.bidiLevel)
        assertEquals(OpenTypeScript("Hebr"), shaped.script)
        assertEquals("he", shaped.language)
        assertTrue(shaped.bot)
        assertTrue(shaped.eot)
        assertEquals(font.key, shaped.fontInstanceKey)
        assertEquals(backend.identity, shaped.backendIdentity)
        assertEquals(listOf(1293, 1285, 1292, 1305), shaped.glyphs.map { it.glyphId.value })
        assertEquals(listOf(3, 2, 1, 0), shaped.glyphs.map { it.clusterToken.value })
        assertEquals(listOf(0, 2, 2, 2), shaped.glyphs.map { glyph -> glyph.safetyFlags.mask() })
        assertEquals(listOf(range(prepared, 0, 1)), shaped.mappings.sourcesForCluster(ShaperClusterToken(0)))
        assertEquals(listOf(range(prepared, 3, 4)), shaped.mappings.sourcesForCluster(ShaperClusterToken(3)))
        assertEquals(listOf(ShaperClusterToken(0)), shaped.mappings.clustersForSource(range(prepared, 0, 1)))
        assertEquals(listOf(ShaperClusterToken(3)), shaped.mappings.clustersForSource(range(prepared, 3, 4)))
    }

    @Test
    fun realBackendShapesConcurrentHebrewCallsToTheSameCompleteAuditedRun() {
        val backend = backend()
        val font = fontInstance("/fonts/liberation/LiberationSans-Regular.ttf", "Liberation Sans")
        val prepared = text("שלום")
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val observations = Collections.synchronizedList(mutableListOf<ConcurrentShapingObservation>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())

        val workers = List(8) {
            thread(name = "harfbuzz-real-shaping-$it") {
                try {
                    ready.countDown()
                    start.await()
                    val shaped = backend.shape(
                        request(
                            prepared = prepared,
                            font = font,
                            direction = ShapingDirection.RIGHT_TO_LEFT,
                            script = OpenTypeScript("Hebr"),
                            language = "he",
                            bidiLevel = 1,
                        ),
                    ).successValue()
                    observations += ConcurrentShapingObservation(
                        glyphIds = shaped.glyphs.map { glyph -> glyph.glyphId.value },
                        advances = shaped.glyphs.map { glyph -> glyph.xAdvance.value },
                        clusterTokens = shaped.glyphs.map { glyph -> glyph.clusterToken.value },
                    )
                } catch (error: Throwable) {
                    failures += error
                }
            }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        start.countDown()
        workers.forEach { worker ->
            worker.join(10_000)
            assertTrue(!worker.isAlive, "A real concurrent shaping call did not complete.")
        }

        assertEquals(emptyList(), failures)
        assertEquals(
            List(8) {
                ConcurrentShapingObservation(
                    glyphIds = listOf(1293, 1285, 1292, 1305),
                    advances = listOf(1389f, 532f, 1085f, 1495f),
                    clusterTokens = listOf(3, 2, 1, 0),
                )
            },
            observations,
        )
    }

    @Test
    fun gdefUsesOnlyInternalAdmissibleGraphemeBoundariesForItsExpectedCount() {
        val prepared = text("f\u0301i")
        val fact = LigatureCaretFactInterpreter.fromNativeResponse(
            glyphIndex = 0,
            direction = ShapingDirection.LEFT_TO_RIGHT,
            cluster = cluster(
                prepared = prepared,
                scalarRanges = listOf(range(prepared, 0, 1), range(prepared, 1, 2), range(prepared, 2, 3)),
                admissibleBoundaries = listOf(index(prepared, 0), index(prepared, 2), index(prepared, 3)),
            ),
            response = NativeLigatureCaretResponse(
                totalCount = 1,
                copiedCount = 1,
                positions = listOf(LayoutUnit(600f)),
            ),
        )

        assertEquals(GdefLigatureCaretState.AVAILABLE, fact.state)
        assertEquals(listOf(index(prepared, 2)), fact.logicalSourceBoundaries)
        assertEquals(listOf(LayoutUnit(600f)), fact.positions)
    }

    @Test
    fun realHarfBuzzFfmReadsFrozenAmiriGdefLigatureCarets() {
        val prepared = text("ffi")
        val shaped = backend().shape(
            request(
                prepared = prepared,
                font = fontInstance(
                    resource = "/fonts/amiri/Amiri-Regular.ttf",
                    declaredName = "Amiri Regular",
                    layoutSize = LayoutUnit(1000f),
                ),
                direction = ShapingDirection.LEFT_TO_RIGHT,
                script = OpenTypeScript("Latn"),
                language = "en",
                bidiLevel = 0,
                featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
            ),
        ).successValue()

        assertEquals(listOf(GlyphId(6631)), shaped.glyphs.map { it.glyphId })
        assertEquals(listOf(LayoutUnit(795f)), shaped.glyphs.map { it.xAdvance })
        val fact = shaped.ligatureCaretFacts.single()
        assertEquals(GdefLigatureCaretState.AVAILABLE, fact.state)
        assertEquals(listOf(index(prepared, 1), index(prepared, 2)), fact.logicalSourceBoundaries)
        assertEquals(listOf(LayoutUnit(269f), LayoutUnit(537f)), fact.positions)
    }

    @Test
    fun realHarfBuzzRejectsUnadjustedGdefCaretsForAKernedLigature() {
        val prepared = text("fiV")
        val shaped = backend().shape(
            request(
                prepared = prepared,
                font = fontInstance(
                    resource = "/fonts/gdef-kern/GdefKerningFixture.ttf",
                    declaredName = "Kalligraphie GDEF Kerning Fixture",
                    layoutSize = LayoutUnit(1000f),
                ),
                direction = ShapingDirection.LEFT_TO_RIGHT,
                script = OpenTypeScript("Latn"),
                language = "en",
                bidiLevel = 0,
            ),
        ).successValue()

        assertEquals(listOf(GlyphId(3), GlyphId(4)), shaped.glyphs.map { it.glyphId })
        assertEquals(listOf(LayoutUnit(800f), LayoutUnit(600f)), shaped.glyphs.map { it.xAdvance })
        val fact = shaped.ligatureCaretFacts.single()
        assertEquals(GdefLigatureCaretState.INCONSISTENT, fact.state)
        assertEquals(listOf(index(prepared, 1)), fact.logicalSourceBoundaries)
        assertEquals(emptyList(), fact.positions)
    }

    @Test
    fun gdefCaretsAreRejectedWhenTheShapedLigatureAdvanceContainsKerning() {
        val prepared = text("ffi")
        val fact = LigatureCaretFactInterpreter.fromNativeResponse(
            glyphIndex = 0,
            direction = ShapingDirection.LEFT_TO_RIGHT,
            cluster = cluster(
                prepared = prepared,
                scalarRanges = listOf(range(prepared, 0, 1), range(prepared, 1, 2), range(prepared, 2, 3)),
                admissibleBoundaries = listOf(index(prepared, 0), index(prepared, 1), index(prepared, 2), index(prepared, 3)),
            ),
            response = NativeLigatureCaretResponse(
                totalCount = 2,
                copiedCount = 2,
                finalAdvanceMatchesUnshapedAdvance = false,
                positions = listOf(LayoutUnit(269f), LayoutUnit(537f)),
            ),
        )

        assertEquals(GdefLigatureCaretState.INCONSISTENT, fact.state)
        assertEquals(emptyList(), fact.positions)
    }

    @Test
    fun excessiveNativeGdefTotalIsInconsistentEvenWhenItsBufferWasFilled() {
        val prepared = text("fi")
        val fact = LigatureCaretFactInterpreter.fromNativeResponse(
            glyphIndex = 0,
            direction = ShapingDirection.LEFT_TO_RIGHT,
            cluster = cluster(
                prepared = prepared,
                scalarRanges = listOf(range(prepared, 0, 1), range(prepared, 1, 2)),
                admissibleBoundaries = listOf(index(prepared, 0), index(prepared, 1), index(prepared, 2)),
            ),
            response = NativeLigatureCaretResponse(
                totalCount = 2,
                copiedCount = 1,
                positions = listOf(LayoutUnit(600f)),
            ),
        )

        assertEquals(GdefLigatureCaretState.INCONSISTENT, fact.state)
        assertEquals(emptyList(), fact.positions)
    }

    @Test
    fun rtlGdefPositionsAreAssociatedWithLogicalSourceBoundaries() {
        val prepared = text("אבג")
        val fact = LigatureCaretFactInterpreter.fromNativeResponse(
            glyphIndex = 0,
            direction = ShapingDirection.RIGHT_TO_LEFT,
            cluster = cluster(
                prepared = prepared,
                scalarRanges = listOf(range(prepared, 0, 1), range(prepared, 1, 2), range(prepared, 2, 3)),
                admissibleBoundaries = listOf(index(prepared, 0), index(prepared, 1), index(prepared, 2), index(prepared, 3)),
            ),
            response = NativeLigatureCaretResponse(
                totalCount = 2,
                copiedCount = 2,
                positions = listOf(LayoutUnit(100f), LayoutUnit(200f)),
            ),
        )

        assertEquals(GdefLigatureCaretState.AVAILABLE, fact.state)
        assertEquals(listOf(index(prepared, 1), index(prepared, 2)), fact.logicalSourceBoundaries)
        assertEquals(listOf(LayoutUnit(200f), LayoutUnit(100f)), fact.positions)
    }

    @Test
    fun randomFeatureIsRejectedBeforeNativeShaping() {
        val backend = backend()
        val result = backend.shape(
            request(
                prepared = text("fi"),
                font = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans"),
                direction = ShapingDirection.LEFT_TO_RIGHT,
                script = OpenTypeScript("Latn"),
                language = "en",
                bidiLevel = 0,
                features = listOf(OpenTypeFeature("rand", 1)),
            ),
        )

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.shaping-feature-not-deterministic", failure.error.code)
    }

    @Test
    fun j1FontInstanceTransfersAdefensiveOpenTypeCopyToTheBackend() {
        val instance = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")
        val extracted = instance.copyOpenTypeData().successValue()
        val changedCopy = extracted.copyBytes()
        changedCopy[0] = (changedCopy[0].toInt() xor 0x7F).toByte()

        val shaped = shape(
            backend = backend(),
            text = "fi",
            font = instance,
            direction = ShapingDirection.LEFT_TO_RIGHT,
            script = OpenTypeScript("Latn"),
            language = "en",
            bidiLevel = 0,
            features = listOf(OpenTypeFeature("liga", 1)),
        )

        assertEquals(GlyphId(5042), shaped.glyphs.single().glyphId)
    }

    @Test
    fun cancellationAfterNativeShapingDoesNotPublishAGlyphRun() {
        val prepared = text("fi")
        var observations = 0

        val result = backend().shape(
            request(
                prepared = prepared,
                font = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans"),
                direction = ShapingDirection.LEFT_TO_RIGHT,
                script = OpenTypeScript("Latn"),
                language = "en",
                bidiLevel = 0,
                resourceProfile = ShapingResourceProfile(maxGlyphs = 0),
                cancellationToken = CancellationToken { observations++ >= 3 },
            ),
        )

        assertIs<FontOperationResult.Cancelled>(result)
        assertEquals(4, observations)
    }

    @Test
    fun scalarBudgetRejectsTheShapingRequestBeforeNativeGlyphPublication() {
        val prepared = text("fi")

        val result = backend().shape(
            request(
                prepared = prepared,
                font = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans"),
                direction = ShapingDirection.LEFT_TO_RIGHT,
                script = OpenTypeScript("Latn"),
                language = "en",
                bidiLevel = 0,
                resourceProfile = ShapingResourceProfile(maxScalars = 1),
            ),
        )

        val error = assertIs<FontError.ShapingResourceLimitExceeded>(
            assertIs<FontOperationResult.Failure>(result).error,
        )
        assertEquals(ShapingResourceLimit.SCALARS, error.limit)
        assertEquals(2, error.observed)
    }

    @Test
    fun glyphBudgetRejectsNativeOutputBeforePortableRunPublication() {
        val prepared = text("fi")

        val result = backend().shape(
            request(
                prepared = prepared,
                font = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans"),
                direction = ShapingDirection.LEFT_TO_RIGHT,
                script = OpenTypeScript("Latn"),
                language = "en",
                bidiLevel = 0,
                resourceProfile = ShapingResourceProfile(maxGlyphs = 0),
            ),
        )

        val error = assertIs<FontError.ShapingResourceLimitExceeded>(
            assertIs<FontOperationResult.Failure>(result).error,
        )
        assertEquals(ShapingResourceLimit.GLYPHS, error.limit)
        assertEquals(1, error.observed)
    }

    @Test
    fun invalidRequestDoesNotInventALeftToRightDefault() {
        val prepared = text("a")

        assertFailsWith<IllegalArgumentException> {
            ShapingRequest(
                snapshot = prepared.snapshot,
                range = prepared.snapshot.range,
                font = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans"),
                direction = ShapingDirection.RIGHT_TO_LEFT,
                script = OpenTypeScript("Latn"),
                language = "en",
                bidiLevel = 0,
                bot = true,
                eot = true,
                featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
                features = emptyList(),
                graphemeClusters = listOf(prepared.snapshot.range),
            )
        }
    }

    @Test
    fun scriptTagsRejectNonAsciiLetters() {
        assertFailsWith<IllegalArgumentException> { OpenTypeScript("Łatn") }
    }

    @Test
    fun unsupportedPlatformReturnsATypedFailureWithoutNativeFallback() {
        val result = HarfBuzzNativeLoader.load(HarfBuzzPlatform(osName = "Plan 9", architecture = "mips64"))

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.shaping-native-platform-unsupported", failure.error.code)
    }

    private fun backend(): ShapingBackend = JvmHarfBuzzShapingBackend.open().successValue().also(backends::add)

    private fun shape(
        backend: ShapingBackend,
        text: String,
        font: FontInstance,
        direction: ShapingDirection,
        script: OpenTypeScript,
        language: String,
        bidiLevel: Int,
        featurePolicy: ShapingFeaturePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
        features: List<OpenTypeFeature> = emptyList(),
    ) = backend.shape(
        request(text(text), font, direction, script, language, bidiLevel, featurePolicy, features),
    ).successValue()

    private fun request(
        prepared: PreparedText,
        font: FontInstance,
        direction: ShapingDirection,
        script: OpenTypeScript,
        language: String,
        bidiLevel: Int,
        featurePolicy: ShapingFeaturePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
        features: List<OpenTypeFeature> = emptyList(),
        graphemeRanges: List<TextRange> = prepared.scalarRanges(),
        resourceProfile: ShapingResourceProfile = ShapingResourceProfile.unbounded,
        cancellationToken: CancellationToken = CancellationToken.none,
        itemRange: TextRange = prepared.snapshot.range,
        contextRange: TextRange = itemRange,
    ): ShapingRequest =
        ShapingRequest(
            snapshot = prepared.snapshot,
            itemRange = itemRange,
            contextRange = contextRange,
            font = font,
            direction = direction,
            script = script,
            language = language,
            bidiLevel = bidiLevel,
            bot = itemRange.start == contextRange.start,
            eot = itemRange.endExclusive == contextRange.endExclusive,
            featurePolicy = featurePolicy,
            features = features,
            graphemeClusters = graphemeRanges,
            resourceProfile = resourceProfile,
            cancellationToken = cancellationToken,
        )

    private fun text(value: String): PreparedText {
        val snapshot = TextSnapshots.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16(value.toCharArray())),
        ).snapshot
        return PreparedText(snapshot)
    }

    private fun fontInstance(
        resource: String,
        declaredName: String,
        layoutSize: LayoutUnit = LayoutUnit(2048f),
    ): FontInstance {
        val source = FontSource(fixtureBytes(resource), FontSourceProvenance(declaredName))
        val catalog = Kalligraphie.embedded(listOf(source)).successValue()
        val face = catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.layoutOnly()).successValue()
        return face.instantiate(FontInstanceDescriptor(layoutSize = layoutSize)).successValue()
    }

    private fun fixtureBytes(resource: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes() }

    private fun range(text: PreparedText, start: Int, endExclusive: Int): TextRange =
        TextRange(index(text, start), index(text, endExclusive))

    private fun index(text: PreparedText, ordinal: Int) = text.snapshot.textIndexAtScalarBoundary(ordinal)

    private fun cluster(
        prepared: PreparedText,
        scalarRanges: List<TextRange>,
        admissibleBoundaries: List<org.graphiks.kalligraphie.api.TextIndex>,
    ): ShaperCluster = ShaperCluster(
        token = ShaperClusterToken(0),
        sourceRange = range(prepared, 0, 3.coerceAtMost(prepared.snapshot.scalars.size)),
        scalarRanges = scalarRanges,
        admissibleGraphemeBoundaries = admissibleBoundaries,
    )

    private fun <T> FontOperationResult<T>.successValue(): T =
        assertIs<FontOperationResult.Success<T>>(this).value

    private fun expectedNativeArtifactId(): String = when (System.getProperty("os.name") to System.getProperty("os.arch")) {
        "Mac OS X" to "aarch64" -> "harfbuzz-source:14.3.0:4c2aa804671d7276e8a0eb95da07202ead05c843:macos-arm64/libharfbuzz.dylib"
        "Mac OS X" to "x86_64" -> "harfbuzz-source:14.3.0:4c2aa804671d7276e8a0eb95da07202ead05c843:macos-x64/libharfbuzz.dylib"
        "Linux" to "aarch64" -> "org.lwjgl:lwjgl-harfbuzz:3.4.3:natives-linux-arm64/libharfbuzz.so"
        "Linux" to "amd64" -> "org.lwjgl:lwjgl-harfbuzz:3.4.3:natives-linux/libharfbuzz.so"
        else -> error("Unexpected shaping test platform.")
    }

    private fun expectedOperatingSystem(): String = when (System.getProperty("os.name")) {
        "Mac OS X" -> "macos"
        "Linux" -> "linux"
        else -> error("Unexpected shaping test platform.")
    }

    private fun expectedArchitecture(): String = when (System.getProperty("os.arch")) {
        "aarch64" -> "arm64"
        "x86_64", "amd64" -> "x64"
        else -> error("Unexpected shaping test architecture.")
    }

    private fun expectedBuildChainIdentity(): String = when (System.getProperty("os.name")) {
        "Mac OS X" -> "cmake-4.4.3;appleclang-21.0.0;macos-sdk-26.5;deployment-target-11.0"
        "Linux" -> "lwjgl-harfbuzz-3.4.3"
        else -> error("Unexpected shaping test platform.")
    }

    private fun expectedNativeSourceRevision(): String = when (System.getProperty("os.name") to System.getProperty("os.arch")) {
        "Mac OS X" to "aarch64",
        "Mac OS X" to "x86_64",
        -> "4c2aa804671d7276e8a0eb95da07202ead05c843"

        "Linux" to "aarch64",
        "Linux" to "amd64",
        -> "9f2f03173b7fee860cc00d999857d09fa4a362e2"

        else -> error("Unexpected shaping test platform.")
    }

    private fun expectedNativeArtifactSha256(): String = when (System.getProperty("os.name") to System.getProperty("os.arch")) {
        "Mac OS X" to "aarch64" -> "504948a7301dc70b1bf9c2f8dc02171c7b7bf35b14d4d5590a8af2a813d73e22"
        "Mac OS X" to "x86_64" -> "9d1ee85a217d781f91c00627248c8f9611058796f49aaf146dc88c1a1439776c"
        "Linux" to "aarch64" -> "b1c7c67034297763e0ce46f3749c4da33a4bb4064929868446cb5a3d81dc26bc"
        "Linux" to "amd64" -> "9a5e3576912c2f8c8b2533d4a264fec1eac9667adfd64f7e71e80179ba118614"
        else -> error("Unexpected shaping test platform.")
    }

    private class PreparedText(val snapshot: org.graphiks.kalligraphie.api.TextSnapshot) {
        fun scalarRanges(): List<TextRange> = snapshot.scalars.indices.map { scalar ->
            TextRange(snapshot.textIndexAtScalarBoundary(scalar), snapshot.textIndexAtScalarBoundary(scalar + 1))
        }
    }

    private data class ConcurrentShapingObservation(
        val glyphIds: List<Int>,
        val advances: List<Float>,
        val clusterTokens: List<Int>,
    )

    private fun org.graphiks.kalligraphie.api.ShapingSafetyFlags.mask(): Int =
        (if (unsafeToBreak) 1 else 0) or (if (unsafeToConcat) 2 else 0)
}
