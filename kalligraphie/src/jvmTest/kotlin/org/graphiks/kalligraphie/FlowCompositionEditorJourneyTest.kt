package org.graphiks.kalligraphie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.BidiRun
import org.graphiks.kalligraphie.api.CaretAffinity
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditorOperationLimitKind
import org.graphiks.kalligraphie.api.EditorOperationProfile
import org.graphiks.kalligraphie.api.FlowChain
import org.graphiks.kalligraphie.api.FlowCompositionError
import org.graphiks.kalligraphie.api.FlowCompositionResult
import org.graphiks.kalligraphie.api.FlowLayout
import org.graphiks.kalligraphie.api.FlowLayoutState
import org.graphiks.kalligraphie.api.FlowRegion
import org.graphiks.kalligraphie.api.FlowRegionIdentity
import org.graphiks.kalligraphie.api.FlowRegionResult
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FragmentationConstraintKind
import org.graphiks.kalligraphie.api.FragmentationConstraints
import org.graphiks.kalligraphie.api.HyphenationMode
import org.graphiks.kalligraphie.api.HyphenationMinimums
import org.graphiks.kalligraphie.api.HyphenationService
import org.graphiks.kalligraphie.api.HyphenationServiceIdentity
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.InlineInterval
import org.graphiks.kalligraphie.api.InlineObjectAlignment
import org.graphiks.kalligraphie.api.InlineObjectDefinition
import org.graphiks.kalligraphie.api.InlineObjectEntry
import org.graphiks.kalligraphie.api.InlineObjectId
import org.graphiks.kalligraphie.api.InlineObjectSnapshot
import org.graphiks.kalligraphie.api.LayoutDelta
import org.graphiks.kalligraphie.api.LayoutInput
import org.graphiks.kalligraphie.api.LayoutPoint
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutTailState
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineBand
import org.graphiks.kalligraphie.api.LineBreakAnalysis
import org.graphiks.kalligraphie.api.LineLayout
import org.graphiks.kalligraphie.api.LineOverscan
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.ParagraphConstraints
import org.graphiks.kalligraphie.api.ParagraphFragment
import org.graphiks.kalligraphie.api.ParagraphLayoutRequest
import org.graphiks.kalligraphie.api.RangeChange
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.TextChange
import org.graphiks.kalligraphie.api.TextChangeSet
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TypographyDelta
import org.graphiks.kalligraphie.api.UnicodeAnalysis
import org.graphiks.kalligraphie.api.WritingMode
import org.graphiks.kalligraphie.api.createIncrementalFlowLayoutRequest
import org.graphiks.kalligraphie.layout.IncrementalFlowLayoutEngine
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend

class FlowCompositionEditorJourneyTest {
    @Test
    fun operationLimitPublishesNoFlowAndExactRetryPublishesTheRealClusterPrefix() {
        val fixture = incrementalRealFontFixture(
            "aaaaaaaaaa",
            fonts = listOf(IncrementalFontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans")),
        )
        val chain = horizontalChain(count = 1, inlineExtent = 3_800f)
        val constraints = incrementalTestConstraints(width = 3_800f, top = 100f, height = 1_200f)

        val limited = assertIs<FlowCompositionResult.Failure>(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    chain,
                    requestedRange = fixture.snapshot.incrementalRange(0, 1),
                    constraints = constraints,
                    operationProfile = EditorOperationProfile(maxTotalGlyphs = 0),
                ),
            ),
        )
        val exceeded = assertIs<FlowCompositionError.OperationLimitExceeded>(limited.error).limit
        assertEquals(EditorOperationLimitKind.TOTAL_GLYPHS, exceeded.kind)
        assertEquals(0L, exceeded.maximum)

        val retry = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    chain,
                    requestedRange = fixture.snapshot.incrementalRange(0, 1),
                    constraints = constraints,
                    operationProfile = EditorOperationProfile(maxTotalGlyphs = 64),
                ),
            ),
        )
        assertEquals(fixture.snapshot.incrementalRange(0, 6), retry.fragments.single().laidOutRange)
        assertEquals(
            listOf(68, 68, 68, 68, 68, 68),
            retry.lines.single().positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.shapedGlyph.glyphId.value } },
        )
    }

    @Test
    fun boundedFlowPublishesTheLargestActuallyPlaceableClusterPrefixBetweenExponentialProbes() {
        val fixture = incrementalRealFontFixture(
            "aaaaaaaaaa",
            fonts = listOf(IncrementalFontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans")),
        )

        val result = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    horizontalChain(count = 1, inlineExtent = 3_800f),
                    requestedRange = fixture.snapshot.incrementalRange(0, 1),
                    constraints = incrementalTestConstraints(width = 3_800f, top = 100f, height = 1_200f),
                ),
            ),
        )

        assertEquals(fixture.snapshot.incrementalRange(0, 6), result.fragments.single().laidOutRange)
        assertEquals(
            fixture.snapshot.incrementalRange(6, 10),
            assertNotNull(result.unmaterializedTail).remainingSourceRange,
        )
    }

    @Test
    fun changedPreparedAnalysesCannotFastPathACompleteLayoutFromTheSameInputVersions() {
        val fixture = incrementalRealFontFixture(
            "ab cd",
            fonts = listOf(IncrementalFontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans")),
        )
        val queries = MutableList(2) { 0 }
        val chain = horizontalChain(count = 2, queries = queries, inlineExtent = 1_600f)
        val initialRequest = request(fixture, chain)
        val backend = assertIs<FontOperationResult.Success<ShapingBackend>>(
            JvmHarfBuzzShapingBackend.open(),
        ).value
        try {
            val prepared = prepareParagraph(initialRequest, backend)
            val initial = success(
                IncrementalFlowLayoutEngine.layout(
                    initialRequest.request,
                    prepared,
                    EditableLineMaterialization.LayoutOnly,
                ),
            )
            assertNull(initial.unmaterializedTail)
            val split = fixture.snapshot.incrementalRange(0, 3)
            val suffix = fixture.snapshot.incrementalRange(3, 5)
            val changedUnicode = UnicodeAnalysis(
                range = prepared.unicodeAnalysis.range,
                unicodeData = prepared.unicodeAnalysis.unicodeData,
                graphemeClusters = prepared.unicodeAnalysis.graphemeClusters,
                scriptLanguageRuns = prepared.unicodeAnalysis.scriptLanguageRuns,
                logicalBidiRuns = listOf(BidiRun(split, 0), BidiRun(suffix, 2)),
                visualBidiRuns = listOf(BidiRun(split, 0), BidiRun(suffix, 2)),
            )
            val changedBreaks = LineBreakAnalysis(
                range = prepared.lineBreakAnalysis.range,
                unicodeData = changedUnicode.unicodeData,
                graphemeClusters = changedUnicode.graphemeClusters,
                opportunities = emptyList(),
            )
            val changedParagraph = prepared.withAnalyses(changedUnicode, changedBreaks)
            val resumedRequest = request(fixture, chain, previousState = initial.state)
            queries.indices.forEach { queries[it] = 0 }

            val resumed = success(
                IncrementalFlowLayoutEngine.layout(
                    resumedRequest.request,
                    changedParagraph,
                    EditableLineMaterialization.LayoutOnly,
                ),
            )

            assertTrue(
                queries.sum() > 0,
                "Different prepared analyses must recompose instead of republishing the complete retained state.",
            )
            val independent = success(
                IncrementalFlowLayoutEngine.layout(
                    initialRequest.request,
                    changedParagraph,
                    EditableLineMaterialization.LayoutOnly,
                ),
            )
            assertEquals(independent.fragments.map { it.laidOutRange }, resumed.fragments.map { it.laidOutRange })
            assertEquals(independent.lines.map(LineLayout::glyphIds), resumed.lines.map(LineLayout::glyphIds))
            assertEquals(independent.lines.map(LineLayout::lineBox), resumed.lines.map(LineLayout::lineBox))
            assertEquals(independent.coverage.tailState, resumed.coverage.tailState)
        } finally {
            backend.close()
        }
    }

    @Test
    fun pureRtlPackingPublishesALogicalPrefixWhenEveryClusterFitsIndividually() {
        val fixture = incrementalRealFontFixture(
            "\u05D0\u05D1\u05D2",
            fonts = listOf(IncrementalFontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans")),
        )
        val bounds = LayoutRect(LayoutUnit(100f), LayoutUnit(100f), LayoutUnit(2_200f), LayoutUnit(2_500f))
        val chain = FlowChain(
            listOf(
                FixedFlowRegion(
                    bounds,
                    listOf(InlineInterval(0f, 900f), InlineInterval(1_200f, 2_100f)),
                ),
            ),
        )

        val first = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    chain,
                    requestedRange = fixture.snapshot.incrementalRange(0, 1),
                    constraints = incrementalTestConstraints(width = 2_100f, top = 100f, height = 2_400f),
                    baseDirection = BaseDirection.RIGHT_TO_LEFT,
                ),
            ),
        )

        assertEquals(fixture.snapshot.incrementalRange(0, 2), first.lines.single().range)
        assertEquals(
            fixture.snapshot.incrementalRange(2, 3),
            assertNotNull(first.unmaterializedTail).remainingSourceRange,
        )
    }

    @Test
    fun oneLineCoverageNeverShapesTheUntouchedDocumentTail() {
        val fixture = incrementalRealFontFixture(
            List(128) { "office" }.joinToString(" "),
            fonts = listOf(IncrementalFontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans")),
        )
        val chain = horizontalChain(count = 1, inlineExtent = 4_000f)
        val backend = assertIs<FontOperationResult.Success<ShapingBackend>>(
            JvmHarfBuzzShapingBackend.open(),
        ).value
        val recording = RecordingShapingBackend(backend)
        try {
            val result = success(
                JvmFlowCompositionFacade.layoutBorrowing(
                    request(
                        fixture,
                        chain,
                        requestedRange = fixture.snapshot.incrementalRange(0, 1),
                        constraints = incrementalTestConstraints(width = 4_000f, top = 100f, height = 1_200f),
                    ),
                    recording,
                ),
            )

            assertNotNull(result.unmaterializedTail)
            assertTrue(recording.requests.isNotEmpty())
            assertTrue(
                recording.requests.all { shaped -> shaped.itemRange.endExclusive < fixture.snapshot.range.endExclusive },
                "A one-line request must not submit the untouched document tail to shaping.",
            )
        } finally {
            backend.close()
        }
    }

    @Test
    fun oneLineAutomaticHyphenationDoesNotInspectEveryUntouchedWord() {
        val wordCount = 128
        val fixture = incrementalRealFontFixture(
            List(wordCount) { "abcdefgh" }.joinToString(" "),
            fonts = listOf(IncrementalFontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans")),
        )
        val inspectedWords = mutableListOf<List<Int>>()
        val service = object : HyphenationService {
            override val identity = HyphenationServiceIdentity(
                providerId = "flow-bounded-hyphenation",
                dataRevision = "1",
                languages = listOf("en"),
            )

            override fun hyphenation(
                word: List<Int>,
                language: String,
                hyphenmins: HyphenationMinimums,
            ): List<Int> {
                inspectedWords += word
                return listOf(4)
            }
        }

        val result = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    horizontalChain(count = 1, inlineExtent = 4_000f),
                    requestedRange = fixture.snapshot.incrementalRange(0, 1),
                    constraints = incrementalTestConstraints(width = 4_000f, top = 100f, height = 1_200f),
                    hyphenationMode = HyphenationMode.AUTO,
                    hyphenationService = service,
                ),
            ),
        )

        assertNotNull(result.unmaterializedTail)
        assertTrue(inspectedWords.isNotEmpty())
        assertTrue(
            inspectedWords.size < wordCount,
            "A one-line request must not ask the hyphenator to inspect the untouched suffix.",
        )
    }

    @Test
    fun repeatedExtensionRetainsBoundedCheckpointsAndAnEarlierEditMatchesFullLayout() {
        val lineCount = 24
        val sourceText = List(lineCount) { "a" }.joinToString("\n")
        val source = incrementalRealFontFixture(
            sourceText,
            fonts = listOf(IncrementalFontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans")),
        )
        val chain = horizontalChain(count = lineCount, inlineExtent = 4_000f)
        val constraints = incrementalTestConstraints(width = 4_000f, top = 100f, height = 1_200f)
        var extended = success(
            JvmFlowCompositionFacade.layout(
                request(
                    source,
                    chain,
                    requestedRange = source.snapshot.incrementalRange(0, 1),
                    constraints = constraints,
                ),
            ),
        )
        for (line in 1 until lineCount) {
            val lineStart = line * 2
            extended = success(
                JvmFlowCompositionFacade.layout(
                    request(
                        source,
                        chain,
                        requestedRange = source.snapshot.incrementalRange(lineStart, lineStart + 1),
                        constraints = constraints,
                        previousState = extended.state,
                    ),
                ),
            )
        }

        assertTrue(extended.state.checkpoints.size <= 12)
        val oldestRetained = extended.state.checkpoints.first().laidOutRange.start
        assertTrue(oldestRetained > source.snapshot.range.start)

        val target = source.withText("b${sourceText.drop(1)}")
        val change = assertIs<org.graphiks.kalligraphie.api.LayoutContractResult.Success<TextChangeSet>>(
            TextChangeSet.create(
                source.snapshot,
                target.snapshot,
                listOf(
                    TextChange(
                        source.snapshot.incrementalRange(0, 1),
                        target.snapshot.incrementalRange(0, 1),
                    ),
                ),
            ),
        ).value
        val edited = success(
            JvmFlowCompositionFacade.layout(
                request(
                    target,
                    chain,
                    constraints = constraints,
                    previousState = extended.state,
                    delta = LayoutDelta(text = change),
                ),
            ),
        )
        val full = success(JvmFlowCompositionFacade.layout(request(target, chain, constraints = constraints)))

        assertEquals(target.snapshot.range.start, edited.diagnostics.reflowStart)
        assertEquals(full.fragments.map(ParagraphFragment::laidOutRange), edited.fragments.map(ParagraphFragment::laidOutRange))
        assertEquals(full.lines.map(LineLayout::lineBox), edited.lines.map(LineLayout::lineBox))
        assertEquals(
            full.lines.map { line ->
                line.allCaretCandidates.map { caret -> caret.position to caret.geometry }
            },
            edited.lines.map { line ->
                line.allCaretCandidates.map { caret -> caret.position to caret.geometry }
            },
        )
    }

    @Test
    fun fragmentedPackingPublishesTheLargestSafeClusterPrefixWithoutDroppingTheTail() {
        val fixture = incrementalRealFontFixture(
            "abc",
            fonts = listOf(IncrementalFontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans")),
        )
        val bounds = LayoutRect(LayoutUnit(100f), LayoutUnit(100f), LayoutUnit(2_200f), LayoutUnit(2_500f))
        val chain = FlowChain(
            listOf(
                FixedFlowRegion(
                    bounds,
                    listOf(InlineInterval(0f, 900f), InlineInterval(1_200f, 2_100f)),
                ),
            ),
        )
        val constraints = incrementalTestConstraints(width = 2_100f, top = 100f, height = 2_400f)

        val first = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    chain,
                    requestedRange = fixture.snapshot.incrementalRange(0, 1),
                    constraints = constraints,
                ),
            ),
        )

        assertEquals(fixture.snapshot.incrementalRange(0, 2), first.lines.single().range)
        assertEquals(
            fixture.snapshot.incrementalRange(2, 3),
            assertNotNull(first.unmaterializedTail).remainingSourceRange,
        )

        val completed = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    chain,
                    constraints = constraints,
                    previousState = first.state,
                ),
            ),
        )

        assertEquals(
            listOf(fixture.snapshot.incrementalRange(0, 2), fixture.snapshot.incrementalRange(2, 3)),
            completed.lines.map(LineLayout::range),
        )
        assertNull(completed.unmaterializedTail)
    }

    @Test
    fun foreignTypographyProofRangesAreRejectedBeforeFlowLayout() {
        val source = incrementalRealFontFixture("fi fi")
        val target = source.withTypography()
        val chain = horizontalChain(2)
        val initial = success(JvmFlowCompositionFacade.layout(request(source, chain)))
        val foreignSource = incrementalSnapshot("ab")
        val foreignTarget = incrementalSnapshot("ac")
        val foreignChange = assertIs<org.graphiks.kalligraphie.api.LayoutContractResult.Success<TextChangeSet>>(
            TextChangeSet.create(
                foreignSource,
                foreignTarget,
                listOf(
                    TextChange(
                        foreignSource.incrementalRange(1, 2),
                        foreignTarget.incrementalRange(1, 2),
                    ),
                ),
            ),
        ).value

        val rejected = assertIs<FlowCompositionResult.Failure>(
            createIncrementalFlowLayoutRequest(
                input = LayoutInput(target.snapshot, target.typography),
                requestedRange = target.snapshot.range,
                constraints = incrementalTestConstraints(width = 1_600f, top = 100f, height = 1_200f),
                flowChain = chain,
                overscan = LineOverscan(0),
                previousState = initial.state,
                delta = LayoutDelta(
                    typography = TypographyDelta(
                        sourceVersion = source.typography.version,
                        targetVersion = target.typography.version,
                        rangeChange = RangeChange.from(foreignChange),
                    ),
                ),
            ),
        )

        assertIs<FlowCompositionError.IncompatibleState>(rejected.error)

        val foreignTargetOnly = incrementalSnapshot("fi xi")
        val foreignTargetChange = assertIs<org.graphiks.kalligraphie.api.LayoutContractResult.Success<TextChangeSet>>(
            TextChangeSet.create(
                source.snapshot,
                foreignTargetOnly,
                listOf(
                    TextChange(
                        source.snapshot.incrementalRange(3, 4),
                        foreignTargetOnly.incrementalRange(3, 4),
                    ),
                ),
            ),
        ).value
        val targetRejected = assertIs<FlowCompositionResult.Failure>(
            createIncrementalFlowLayoutRequest(
                input = LayoutInput(target.snapshot, target.typography),
                requestedRange = target.snapshot.range,
                constraints = incrementalTestConstraints(width = 1_600f, top = 100f, height = 1_200f),
                flowChain = chain,
                overscan = LineOverscan(0),
                previousState = initial.state,
                delta = LayoutDelta(
                    typography = TypographyDelta(
                        sourceVersion = source.typography.version,
                        targetVersion = target.typography.version,
                        rangeChange = RangeChange.from(foreignTargetChange),
                    ),
                ),
            ),
        )

        assertIs<FlowCompositionError.IncompatibleState>(targetRejected.error)
    }

    @Test
    fun exclusionGapPublishesTheSameLogicalCaretAtBothGeometricEdges() {
        val fixture = incrementalRealFontFixture("abcd")
        val bounds = LayoutRect(LayoutUnit(100f), LayoutUnit(100f), LayoutUnit(4_100f), LayoutUnit(1_300f))
        val intervals = listOf(InlineInterval(0f, 1_300f), InlineInterval(2_200f, 4_000f))
        val chain = FlowChain(listOf(FixedFlowRegion(bounds, intervals)))

        val line = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    chain,
                    constraints = incrementalTestConstraints(width = 4_000f, top = 100f, height = 1_200f),
                ),
            ),
        ).lines.single()

        assertEquals(2, line.fragments.size)
        val preceding = line.fragments.first().caretCandidates
        val following = line.fragments.last().caretCandidates
        val sharedIndex = assertNotNull(
            preceding.map { it.position.index }.toSet()
                .intersect(following.map { it.position.index }.toSet())
                .singleOrNull(),
            "The logical interval boundary must be represented on both sides of the exclusion.",
        )
        val precedingEdge = assertNotNull(
            preceding.singleOrNull {
                it.position.index == sharedIndex && it.position.affinity == CaretAffinity.UPSTREAM
            },
        )
        val followingEdge = assertNotNull(
            following.singleOrNull {
                it.position.index == sharedIndex && it.position.affinity == CaretAffinity.DOWNSTREAM
            },
        )

        assertEquals(
            precedingEdge.position,
            line.hitTest(
                LayoutPoint(LayoutUnit(precedingEdge.geometry.start.x.value + 1f), line.baseline.y),
            ).position,
        )
        assertEquals(
            followingEdge.position,
            line.hitTest(
                LayoutPoint(LayoutUnit(followingEdge.geometry.start.x.value - 1f), line.baseline.y),
            ).position,
        )
        assertTrue(precedingEdge.geometry.start.x < followingEdge.geometry.start.x)
    }

    @Test
    fun refinedExclusionMayShortenTheLineWhileKeepingTheEstablishedTallBand() {
        val fixture = incrementalRealFontFixture(
            "a \uFFFC",
            fonts = listOf(IncrementalFontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans")),
        )
        val objectDefinition = InlineObjectDefinition(
            id = InlineObjectId.create("flow-refinement-tall"),
            width = LayoutUnit(900f),
            height = LayoutUnit(1_400f),
            baselineOffset = LayoutUnit(1_200f),
            alignment = InlineObjectAlignment.BASELINE,
        )
        val inlineObjects = InlineObjectSnapshot(
            listOf(InlineObjectEntry(fixture.snapshot.textIndexAtScalarBoundary(2), objectDefinition)),
        )
        val bounds = LayoutRect(LayoutUnit(100f), LayoutUnit(100f), LayoutUnit(2_300f), LayoutUnit(3_100f))
        val queriedBands = mutableListOf<LineBand>()
        val region = object : FlowRegion {
            override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
            override val bounds: LayoutRect = bounds

            override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult {
                queriedBands += lineBand
                return FlowRegionResult.AvailableIntervals(
                    listOf(
                        InlineInterval(
                            0f,
                            if (lineBand.blockExtent <= 1_200f) 2_200f else 1_100f,
                        ),
                    ),
                )
            }
        }

        val composed = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture = fixture,
                    chain = FlowChain(listOf(region)),
                    requestedRange = fixture.snapshot.incrementalRange(0, 1),
                    constraints = incrementalTestConstraints(width = 2_200f, top = 100f, height = 3_000f),
                    inlineObjects = inlineObjects,
                ),
            ),
        )

        val line = composed.lines.single()
        assertEquals(fixture.snapshot.incrementalRange(0, 2), line.range)
        assertEquals(1_500f, line.lineBox.bottom.value - line.lineBox.top.value)
        assertTrue(line.positionedInlineObjects.isEmpty())
        assertTrue(
            queriedBands.zipWithNext().all { (previous, current) ->
                previous.blockStart != current.blockStart || current.blockExtent >= previous.blockExtent
            },
            "Refinement must never issue a smaller line band at the same block origin.",
        )
        assertEquals(
            fixture.snapshot.incrementalRange(2, 3),
            assertNotNull(composed.unmaterializedTail).remainingSourceRange,
        )
    }

    @Test
    fun earlyEditMatchesIndependentFullFlowCompositionForMaterializedCoverage() {
        val source = incrementalRealFontFixture("fi fi fi")
        val target = source.withText("ii fi fi")
        val chain = horizontalChain(4)
        val initial = success(
            JvmFlowCompositionFacade.layout(request(source, chain)),
        )
        val change = assertIs<org.graphiks.kalligraphie.api.LayoutContractResult.Success<TextChangeSet>>(
            TextChangeSet.create(
                source.snapshot,
                target.snapshot,
                listOf(
                    TextChange(
                        source.snapshot.incrementalRange(0, 1),
                        target.snapshot.incrementalRange(0, 1),
                    ),
                ),
            ),
        ).value

        val edited = success(
            JvmFlowCompositionFacade.layout(
                request(
                    target,
                    chain,
                    previousState = initial.state,
                    delta = LayoutDelta(text = change),
                ),
            ),
        )
        val full = success(JvmFlowCompositionFacade.layout(request(target, chain)))

        assertEquals(
            listOf(
                target.snapshot.incrementalRange(0, 3),
                target.snapshot.incrementalRange(3, 6),
                target.snapshot.incrementalRange(6, 8),
            ),
            edited.fragments.map { it.laidOutRange },
        )
        assertEquals(target.snapshot.range, edited.coverage.range)
        assertEquals(LayoutTailState.MaterializedThroughDocumentEnd, edited.coverage.tailState)
        assertNull(edited.unmaterializedTail)
        assertEquals(full.fragments.map { it.laidOutRange }, edited.fragments.map { it.laidOutRange })
        assertEquals(full.lines.map(LineLayout::glyphIds), edited.lines.map(LineLayout::glyphIds))
        assertEquals(full.lines.map(LineLayout::glyphAdvances), edited.lines.map(LineLayout::glyphAdvances))
        assertEquals(full.lines.map(LineLayout::lineBox), edited.lines.map(LineLayout::lineBox))
        assertEquals(
            full.lines.map { line -> line.allCaretCandidates.map { it.position.index } },
            edited.lines.map { line -> line.allCaretCandidates.map { it.position.index } },
        )
        assertEquals(
            listOf(100f, 2_100f, 4_100f),
            edited.lines.map { it.lineBox.top.value },
        )
        assertEquals(
            listOf(listOf(0, 1, 2, 2, 3), listOf(3, 4, 5, 5, 6), listOf(6, 7, 8)),
            edited.lines.map { line ->
                line.allCaretCandidates.map { caret ->
                    (0..8).single { ordinal ->
                        caret.position.index == target.snapshot.textIndexAtScalarBoundary(ordinal)
                    }
                }
            },
        )
    }

    @Test
    fun boundedCoveragePublishesExactTailAndDoesNotTouchLaterRegions() {
        val fixture = incrementalRealFontFixture("fi fi fi")
        val queries = MutableList(4) { 0 }
        val chain = horizontalChain(4, queries)

        val partial = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    chain,
                    requestedRange = fixture.snapshot.incrementalRange(0, 1),
                ),
            ),
        )

        assertEquals(listOf(fixture.snapshot.incrementalRange(0, 3)), partial.fragments.map { it.laidOutRange })
        assertEquals(fixture.snapshot.incrementalRange(0, 3), partial.coverage.range)
        assertEquals(
            LayoutTailState.Invalidated(fixture.snapshot.incrementalRange(3, 8)),
            partial.coverage.tailState,
        )
        val tail = assertNotNull(partial.unmaterializedTail)
        assertEquals(fixture.snapshot.incrementalRange(3, 8), tail.remainingSourceRange)
        assertEquals(0, tail.regionIndex)
        assertEquals(chain.regions[0].identity, tail.regionIdentity)
        assertEquals(1_200f, tail.nextBlockOffset)
        assertEquals(listOf(true, false, false, false), queries.map { it > 0 })
        assertEquals(1, partial.state.checkpoints.size)
        assertEquals(0, partial.state.checkpoints.single().resumeRegionOrdinal)
        assertEquals(chain.regions[0].identity, partial.state.checkpoints.single().resumeRegionIdentity)
        assertEquals(1_200f, partial.state.checkpoints.single().blockCursor)
    }

    @Test
    fun laterCoverageResumesFromTheStructuredTailAndHonorsLineOverscan() {
        val fixture = incrementalRealFontFixture("fi fi fi fi")
        val queries = MutableList(4) { 0 }
        val chain = horizontalChain(4, queries)
        val first = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    chain,
                    requestedRange = fixture.snapshot.incrementalRange(0, 1),
                ),
            ),
        )
        val firstRegionQueries = queries[0]

        val extended = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    chain,
                    requestedRange = fixture.snapshot.incrementalRange(3, 4),
                    overscan = 1,
                    previousState = first.state,
                ),
            ),
        )

        assertEquals(
            listOf(
                fixture.snapshot.incrementalRange(0, 3),
                fixture.snapshot.incrementalRange(3, 6),
                fixture.snapshot.incrementalRange(6, 9),
            ),
            extended.fragments.map { it.laidOutRange },
        )
        assertEquals(firstRegionQueries, queries[0])
        assertTrue(queries[1] > 0)
        assertTrue(queries[2] > 0)
        assertEquals(0, queries[3])
        assertEquals(fixture.snapshot.textIndexAtScalarBoundary(3), extended.diagnostics.reflowStart)
        assertEquals(false, extended.diagnostics.usedConservativeInvalidation)
        assertNotNull(extended.unmaterializedTail)

        val independent = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    horizontalChain(4),
                    requestedRange = fixture.snapshot.incrementalRange(3, 4),
                    overscan = 1,
                ),
            ),
        )
        assertEquals(independent.fragments.map { it.laidOutRange }, extended.fragments.map { it.laidOutRange })
        assertEquals(independent.lines.map(LineLayout::glyphIds), extended.lines.map(LineLayout::glyphIds))
        assertEquals(independent.lines.map(LineLayout::lineBox), extended.lines.map(LineLayout::lineBox))
    }

    @Test
    fun stateFromAnIncompatibleChainIsRejectedBeforeEitherChainIsQueried() {
        val fixture = incrementalRealFontFixture("fi fi fi")
        val originalQueries = MutableList(4) { 0 }
        val originalChain = horizontalChain(4, originalQueries)
        val partial = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    originalChain,
                    requestedRange = fixture.snapshot.incrementalRange(0, 1),
                ),
            ),
        )
        val replacementQueries = MutableList(4) { 0 }
        val replacementChain = horizontalChain(4, replacementQueries)
        val originalCount = originalQueries.sum()

        val rejected = assertIs<FlowCompositionResult.Failure>(
            createIncrementalFlowLayoutRequest(
                input = LayoutInput(fixture.snapshot, fixture.typography),
                requestedRange = fixture.snapshot.incrementalRange(3, 4),
                constraints = incrementalTestConstraints(width = 1_600f, top = 100f, height = 1_200f),
                flowChain = replacementChain,
                overscan = LineOverscan(0),
                previousState = partial.state,
            ),
        )

        assertIs<FlowCompositionError.IncompatibleState>(rejected.error)
        assertEquals(originalCount, originalQueries.sum())
        assertTrue(replacementQueries.all { it == 0 })
    }

    @Test
    fun middleRegionEditRestartsAfterPreservedEarlierRegions() {
        val source = incrementalRealFontFixture("fi fi fi fi fi")
        val target = source.withText("fi fi fx fi fi")
        val queries = MutableList(5) { 0 }
        val chain = horizontalChain(5, queries)
        val initial = success(JvmFlowCompositionFacade.layout(request(source, chain)))
        val change = assertIs<org.graphiks.kalligraphie.api.LayoutContractResult.Success<TextChangeSet>>(
            TextChangeSet.create(
                source.snapshot,
                target.snapshot,
                listOf(
                    TextChange(
                        source.snapshot.incrementalRange(7, 8),
                        target.snapshot.incrementalRange(7, 8),
                    ),
                ),
            ),
        ).value
        val beforeEdit = queries.toList()

        val edited = success(
            JvmFlowCompositionFacade.layout(
                request(
                    target,
                    chain,
                    requestedRange = target.snapshot.incrementalRange(9, 10),
                    previousState = initial.state,
                    delta = LayoutDelta(text = change),
                ),
            ),
        )

        assertEquals(beforeEdit[0], queries[0])
        assertEquals(beforeEdit[1], queries[1])
        assertTrue(queries[2] > beforeEdit[2])
        assertTrue(queries[3] > beforeEdit[3])
        assertEquals(beforeEdit[4], queries[4])
        assertEquals(target.snapshot.textIndexAtScalarBoundary(6), edited.diagnostics.reflowStart)
        assertEquals(target.snapshot.textIndexAtScalarBoundary(12), edited.diagnostics.stabilizedAt)
        assertEquals(
            listOf(target.snapshot.incrementalRange(6, 9), target.snapshot.incrementalRange(9, 12)),
            edited.fragments.map { it.laidOutRange },
        )

        val full = success(
            JvmFlowCompositionFacade.layout(
                request(
                    target,
                    horizontalChain(5),
                    requestedRange = target.snapshot.incrementalRange(9, 10),
                ),
            ),
        )
        val matchingFullLines = full.lines.filter { line -> line.range.start >= edited.coverage.range.start }
        assertEquals(matchingFullLines.map(LineLayout::range), edited.lines.map(LineLayout::range))
        assertEquals(matchingFullLines.map(LineLayout::glyphIds), edited.lines.map(LineLayout::glyphIds))
        assertEquals(matchingFullLines.map(LineLayout::lineBox), edited.lines.map(LineLayout::lineBox))
        assertEquals(
            matchingFullLines.map { line -> line.allCaretCandidates.map { it.position.index } },
            edited.lines.map { line -> line.allCaretCandidates.map { it.position.index } },
        )
    }

    @Test
    fun boundedPublicationDoesNotRelaxSatisfiableKeepTogetherOrMinimumLines() {
        val fixture = incrementalRealFontFixture("fi fi")
        val firstBounds = LayoutRect(LayoutUnit(100f), LayoutUnit(100f), LayoutUnit(1_700f), LayoutUnit(1_300f))
        val secondBounds = LayoutRect(LayoutUnit(100f), LayoutUnit(2_100f), LayoutUnit(1_700f), LayoutUnit(4_500f))
        val chain = FlowChain(
            listOf(
                FixedFlowRegion(firstBounds, listOf(InlineInterval(0f, 1_600f))),
                FixedFlowRegion(secondBounds, listOf(InlineInterval(0f, 1_600f))),
            ),
            FragmentationConstraints(
                minLinesAtStart = 2,
                minLinesAtEnd = 2,
                keepTogether = true,
            ),
        )

        val result = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    chain,
                    requestedRange = fixture.snapshot.incrementalRange(0, 1),
                ),
            ),
        )

        assertEquals(secondBounds.top, result.lines.single().lineBox.top)
        assertEquals(emptyList(), result.fragments.flatMap { fragment -> fragment.diagnostics })
        val tail = assertNotNull(result.unmaterializedTail)
        assertEquals(1, tail.regionIndex)
        assertEquals(1_200f, tail.nextBlockOffset)
    }

    @Test
    fun boundedFragmentationCommitmentResumesWithoutSpuriousRelaxation() {
        val fixture = incrementalRealFontFixture("fi fi")
        val firstBounds = LayoutRect(LayoutUnit(100f), LayoutUnit(100f), LayoutUnit(1_700f), LayoutUnit(1_300f))
        val secondBounds = LayoutRect(LayoutUnit(100f), LayoutUnit(2_100f), LayoutUnit(1_700f), LayoutUnit(4_500f))
        val chain = FlowChain(
            listOf(
                FixedFlowRegion(firstBounds, listOf(InlineInterval(0f, 1_600f))),
                FixedFlowRegion(secondBounds, listOf(InlineInterval(0f, 1_600f))),
            ),
            FragmentationConstraints(minLinesAtStart = 2, minLinesAtEnd = 2),
        )
        val partial = success(
            JvmFlowCompositionFacade.layout(
                request(fixture, chain, requestedRange = fixture.snapshot.incrementalRange(0, 1)),
            ),
        )
        val tail = assertNotNull(partial.unmaterializedTail)
        assertEquals(1, assertNotNull(tail.fragmentationCommitment).remainingLineCount)

        val resumed = success(
            JvmFlowCompositionFacade.layout(
                request(fixture, chain, requestedRange = tail.remainingSourceRange, previousState = partial.state),
            ),
        )
        val full = success(JvmFlowCompositionFacade.layout(request(fixture, chain)))

        assertNull(resumed.unmaterializedTail)
        assertEquals(full.fragments.map { it.laidOutRange }, resumed.fragments.map { it.laidOutRange })
        assertEquals(
            full.fragments.map { fragment -> fragment.continuation?.regionIdentity },
            resumed.fragments.map { fragment -> fragment.continuation?.regionIdentity },
        )
        assertEquals(full.lines.map(LineLayout::lineBox), resumed.lines.map(LineLayout::lineBox))
        assertEquals(
            full.fragments.flatMap { fragment -> fragment.diagnostics },
            resumed.fragments.flatMap { fragment -> fragment.diagnostics },
        )
        assertEquals(emptyList(), resumed.fragments.flatMap { fragment -> fragment.diagnostics })
    }

    @Test
    fun localizedTypographyChangeReevaluatesCommittedMinimumLinesFromParagraphStart() {
        val source = incrementalRealFontFixture("fi fi")
        val target = source.withTypography()
        val firstBounds = LayoutRect(LayoutUnit(100f), LayoutUnit(100f), LayoutUnit(1_700f), LayoutUnit(1_300f))
        val secondBounds = LayoutRect(LayoutUnit(100f), LayoutUnit(2_100f), LayoutUnit(1_700f), LayoutUnit(4_500f))
        val chain = FlowChain(
            listOf(
                FixedFlowRegion(firstBounds, listOf(InlineInterval(0f, 1_600f))),
                FixedFlowRegion(secondBounds, listOf(InlineInterval(0f, 1_600f))),
            ),
            FragmentationConstraints(minLinesAtStart = 2, minLinesAtEnd = 2),
        )
        val initial = success(
            JvmFlowCompositionFacade.layout(
                request(source, chain, requestedRange = source.snapshot.incrementalRange(0, 1)),
            ),
        )
        assertNotNull(assertNotNull(initial.unmaterializedTail).fragmentationCommitment)
        val typographyRangeProof = assertIs<org.graphiks.kalligraphie.api.LayoutContractResult.Success<TextChangeSet>>(
            TextChangeSet.create(
                source.snapshot,
                source.snapshot,
                listOf(
                    TextChange(
                        source.snapshot.incrementalRange(3, 5),
                        source.snapshot.incrementalRange(3, 5),
                    ),
                ),
            ),
        ).value

        val edited = success(
            JvmFlowCompositionFacade.layout(
                request(
                    target,
                    chain,
                    previousState = initial.state,
                    delta = LayoutDelta(
                        typography = TypographyDelta(
                            sourceVersion = source.typography.version,
                            targetVersion = target.typography.version,
                            rangeChange = RangeChange.from(typographyRangeProof),
                        ),
                    ),
                ),
            ),
        )
        val full = success(JvmFlowCompositionFacade.layout(request(target, chain)))

        assertEquals(target.snapshot.range.start, edited.diagnostics.reflowStart)
        assertEquals(full.fragments.map { it.laidOutRange }, edited.fragments.map { it.laidOutRange })
        assertEquals(full.lines.map(LineLayout::lineBox), edited.lines.map(LineLayout::lineBox))
        assertEquals(
            full.fragments.flatMap { it.diagnostics },
            edited.fragments.flatMap { it.diagnostics },
        )
    }

    @Test
    fun editedContentReevaluatesARelaxedKeepTogetherDecisionAgainstFullLayout() {
        val source = incrementalRealFontFixture(
            "a\nx.",
            fonts = listOf(IncrementalFontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans")),
        )
        val target = source.withText("a x.")
        val lineMetrics = LineVerticalMetrics(LayoutUnit(200f), LayoutUnit(100f))
        val firstBounds = LayoutRect(LayoutUnit(100f), LayoutUnit(100f), LayoutUnit(4_100f), LayoutUnit(1_300f))
        val secondBounds = LayoutRect(LayoutUnit(100f), LayoutUnit(2_100f), LayoutUnit(4_100f), LayoutUnit(3_300f))
        val constraints = HorizontalParagraphConstraints(firstBounds, lineMetrics)
        val chain = FlowChain(
            listOf(
                FixedFlowRegion(firstBounds, listOf(InlineInterval(0f, 4_000f))),
                FixedFlowRegion(secondBounds, listOf(InlineInterval(0f, 4_000f))),
            ),
            FragmentationConstraints(keepTogether = true),
        )
        val initial = success(
            JvmFlowCompositionFacade.layout(
                request(
                    source,
                    chain,
                    requestedRange = source.snapshot.incrementalRange(0, 1),
                    constraints = constraints,
                ),
            ),
        )
        assertEquals(
            listOf(FragmentationConstraintKind.KEEP_TOGETHER),
            assertNotNull(initial.unmaterializedTail).relaxedConstraints,
        )
        val change = assertIs<org.graphiks.kalligraphie.api.LayoutContractResult.Success<TextChangeSet>>(
            TextChangeSet.create(
                source.snapshot,
                target.snapshot,
                listOf(
                    TextChange(
                        source.snapshot.incrementalRange(1, 2),
                        target.snapshot.incrementalRange(1, 2),
                    ),
                ),
            ),
        ).value

        val edited = success(
            JvmFlowCompositionFacade.layout(
                request(
                    target,
                    chain,
                    constraints = constraints,
                    previousState = initial.state,
                    delta = LayoutDelta(text = change),
                ),
            ),
        )
        val full = success(JvmFlowCompositionFacade.layout(request(target, chain, constraints = constraints)))

        assertEquals(full.fragments.map { it.laidOutRange }, edited.fragments.map { it.laidOutRange })
        assertEquals(full.lines.map(LineLayout::lineBox), edited.lines.map(LineLayout::lineBox))
        assertEquals(
            full.fragments.flatMap { it.diagnostics },
            edited.fragments.flatMap { it.diagnostics },
        )
        assertEquals(emptyList(), edited.fragments.flatMap { it.diagnostics })
    }

    @Test
    fun automaticHyphenationEditReflowsFromTheAffectedWordStart() {
        val source = incrementalRealFontFixture("abcdefghij")
        val target = source.withText("abcdefghiX")
        val service = object : HyphenationService {
            override val identity = HyphenationServiceIdentity(
                providerId = "flow-journey-deterministic",
                dataRevision = "1",
                languages = listOf("en"),
            )

            override fun hyphenation(
                word: List<Int>,
                language: String,
                hyphenmins: HyphenationMinimums,
            ): List<Int> = if (word.lastOrNull() == 'j'.code) listOf(3, 6) else listOf(2, 5, 7)
        }
        val chain = horizontalChain(6, inlineExtent = 2_600f)
        val initial = success(
            JvmFlowCompositionFacade.layout(
                request(
                    source,
                    chain,
                    constraints = incrementalTestConstraints(width = 2_600f, top = 100f, height = 1_200f),
                    hyphenationMode = HyphenationMode.AUTO,
                    hyphenationService = service,
                ),
            ),
        )
        val change = assertIs<org.graphiks.kalligraphie.api.LayoutContractResult.Success<TextChangeSet>>(
            TextChangeSet.create(
                source.snapshot,
                target.snapshot,
                listOf(
                    TextChange(
                        source.snapshot.incrementalRange(9, 10),
                        target.snapshot.incrementalRange(9, 10),
                    ),
                ),
            ),
        ).value

        val edited = success(
            JvmFlowCompositionFacade.layout(
                request(
                    target,
                    chain,
                    previousState = initial.state,
                    delta = LayoutDelta(text = change),
                    constraints = incrementalTestConstraints(width = 2_600f, top = 100f, height = 1_200f),
                    hyphenationMode = HyphenationMode.AUTO,
                    hyphenationService = service,
                ),
            ),
        )
        val full = success(
            JvmFlowCompositionFacade.layout(
                request(
                    target,
                    horizontalChain(6, inlineExtent = 2_600f),
                    constraints = incrementalTestConstraints(width = 2_600f, top = 100f, height = 1_200f),
                    hyphenationMode = HyphenationMode.AUTO,
                    hyphenationService = service,
                ),
            ),
        )

        assertEquals(target.snapshot.range.start, edited.diagnostics.reflowStart)
        assertTrue(edited.diagnostics.usedConservativeInvalidation)
        assertTrue(initial.fragments.map { it.laidOutRange } != full.fragments.map { it.laidOutRange })
        assertEquals(full.fragments.map { it.laidOutRange }, edited.fragments.map { it.laidOutRange })
        assertEquals(full.lines.map(LineLayout::glyphIds), edited.lines.map(LineLayout::glyphIds))
        assertEquals(full.lines.map(LineLayout::glyphAdvances), edited.lines.map(LineLayout::glyphAdvances))
        assertEquals(full.lines.map(LineLayout::lineBox), edited.lines.map(LineLayout::lineBox))
        assertEquals(
            full.lines.map { line -> line.allCaretCandidates.map { it.position.index } },
            edited.lines.map { line -> line.allCaretCandidates.map { it.position.index } },
        )
    }

    @Test
    fun bidiEditAtCheckpointBoundaryInvalidatesToAPrerequisiteIndependentBoundary() {
        val source = incrementalRealFontFixture("fi fi fi")
        val target = source.withText("fi \u200Fi fi")
        val queries = MutableList(4) { 0 }
        val chain = horizontalChain(4, queries)
        val initial = success(JvmFlowCompositionFacade.layout(request(source, chain)))
        val change = assertIs<org.graphiks.kalligraphie.api.LayoutContractResult.Success<TextChangeSet>>(
            TextChangeSet.create(
                source.snapshot,
                target.snapshot,
                listOf(
                    TextChange(
                        source.snapshot.incrementalRange(3, 4),
                        target.snapshot.incrementalRange(3, 4),
                    ),
                ),
            ),
        ).value
        val beforeEdit = queries.toList()

        val edited = success(
            JvmFlowCompositionFacade.layout(
                request(target, chain, previousState = initial.state, delta = LayoutDelta(text = change)),
            ),
        )
        val full = success(JvmFlowCompositionFacade.layout(request(target, horizontalChain(4))))

        assertEquals(target.snapshot.range.start, edited.diagnostics.reflowStart)
        assertTrue(edited.diagnostics.usedConservativeInvalidation)
        assertTrue(queries[0] > beforeEdit[0])
        assertEquals(full.fragments.map { it.laidOutRange }, edited.fragments.map { it.laidOutRange })
        assertEquals(full.lines.map(LineLayout::glyphIds), edited.lines.map(LineLayout::glyphIds))
        assertEquals(full.lines.map(LineLayout::lineBox), edited.lines.map(LineLayout::lineBox))
        assertEquals(
            full.lines.map { line -> line.allCaretCandidates.map { it.position.index } },
            edited.lines.map { line -> line.allCaretCandidates.map { it.position.index } },
        )
    }

    @Test
    fun earlierCoverageCanBeRequestedAfterPublishingAnEditedMiddleSuffix() {
        val source = incrementalRealFontFixture("fi fi fi fi fi")
        val target = source.withText("fi fi fx fi fi")
        val chain = horizontalChain(5)
        val initial = success(JvmFlowCompositionFacade.layout(request(source, chain)))
        val change = assertIs<org.graphiks.kalligraphie.api.LayoutContractResult.Success<TextChangeSet>>(
            TextChangeSet.create(
                source.snapshot,
                target.snapshot,
                listOf(
                    TextChange(
                        source.snapshot.incrementalRange(7, 8),
                        target.snapshot.incrementalRange(7, 8),
                    ),
                ),
            ),
        ).value
        val middle = success(
            JvmFlowCompositionFacade.layout(
                request(
                    target,
                    chain,
                    requestedRange = target.snapshot.incrementalRange(7, 8),
                    previousState = initial.state,
                    delta = LayoutDelta(text = change),
                ),
            ),
        )

        val earlier = success(
            JvmFlowCompositionFacade.layout(
                request(
                    target,
                    chain,
                    requestedRange = target.snapshot.incrementalRange(0, 1),
                    previousState = middle.state,
                ),
            ),
        )
        val full = success(
            JvmFlowCompositionFacade.layout(
                request(target, horizontalChain(5), requestedRange = target.snapshot.incrementalRange(0, 1)),
            ),
        )

        assertEquals(target.snapshot.range.start, earlier.diagnostics.reflowStart)
        assertEquals(full.fragments.map { it.laidOutRange }, earlier.fragments.map { it.laidOutRange })
        assertEquals(full.lines.map(LineLayout::glyphIds), earlier.lines.map(LineLayout::glyphIds))
        assertEquals(full.lines.map(LineLayout::lineBox), earlier.lines.map(LineLayout::lineBox))
    }

    @Test
    fun lateEditFromCompleteSourceStateCanDirectlyMaterializeEarlierCoverage() {
        val source = incrementalRealFontFixture("fi fi fi fi fi")
        val target = source.withText("fi fi fi fi fx")
        val chain = horizontalChain(5)
        val completeSource = success(JvmFlowCompositionFacade.layout(request(source, chain)))
        val change = assertIs<org.graphiks.kalligraphie.api.LayoutContractResult.Success<TextChangeSet>>(
            TextChangeSet.create(
                source.snapshot,
                target.snapshot,
                listOf(
                    TextChange(
                        source.snapshot.incrementalRange(13, 14),
                        target.snapshot.incrementalRange(13, 14),
                    ),
                ),
            ),
        ).value

        val edited = success(
            JvmFlowCompositionFacade.layout(
                request(
                    target,
                    chain,
                    requestedRange = target.snapshot.incrementalRange(0, 1),
                    previousState = completeSource.state,
                    delta = LayoutDelta(text = change),
                ),
            ),
        )
        val full = success(
            JvmFlowCompositionFacade.layout(
                request(target, horizontalChain(5), requestedRange = target.snapshot.incrementalRange(0, 1)),
            ),
        )

        assertEquals(target.snapshot.range.start, edited.diagnostics.reflowStart)
        assertEquals(full.fragments.map(ParagraphFragment::laidOutRange), edited.fragments.map(ParagraphFragment::laidOutRange))
        assertEquals(full.lines.map(LineLayout::glyphIds), edited.lines.map(LineLayout::glyphIds))
        assertEquals(full.lines.map(LineLayout::lineBox), edited.lines.map(LineLayout::lineBox))
        assertEquals(
            full.lines.map { line -> line.allCaretCandidates.map { caret -> caret.position to caret.geometry } },
            edited.lines.map { line -> line.allCaretCandidates.map { caret -> caret.position to caret.geometry } },
        )
    }

    @Test
    fun overscanPastPhysicalEndStillPublishesCompleteSingleLineDocument() {
        val fixture = incrementalRealFontFixture("fi")
        val chain = horizontalChain(1)

        val result = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    chain,
                    overscan = 1,
                ),
            ),
        )

        assertEquals(listOf(fixture.snapshot.range), result.fragments.map { it.laidOutRange })
        assertEquals(LayoutTailState.MaterializedThroughDocumentEnd, result.coverage.tailState)
        assertNull(result.unmaterializedTail)
    }

    @Test
    fun oneRequestedLineInTallRegionStopsInsideRegionWithExactContinuation() {
        val fixture = incrementalRealFontFixture("fi fi fi")
        var queries = 0
        val bounds = LayoutRect(LayoutUnit(100f), LayoutUnit(100f), LayoutUnit(1_700f), LayoutUnit(6_100f))
        val chain = FlowChain(
            listOf(
                FixedFlowRegion(bounds, listOf(InlineInterval(0f, 1_600f))) { queries += 1 },
            ),
        )

        val result = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    chain,
                    requestedRange = fixture.snapshot.incrementalRange(0, 1),
                    constraints = ParagraphConstraints(
                        bounds,
                        LineVerticalMetrics(LayoutUnit(900f), LayoutUnit(300f)),
                        WritingMode.HORIZONTAL_TB,
                    ),
                ),
            ),
        )

        assertEquals(listOf(fixture.snapshot.incrementalRange(0, 3)), result.fragments.map { it.laidOutRange })
        val tail = assertNotNull(result.unmaterializedTail)
        assertEquals(0, tail.regionIndex)
        assertEquals(1_200f, tail.nextBlockOffset)
        assertEquals(fixture.snapshot.incrementalRange(3, 8), tail.remainingSourceRange)
        assertTrue(queries > 0)
    }

    @Test
    fun compatibleCompleteStateSatisfiesSecondRequestWithoutRegionQuery() {
        val fixture = incrementalRealFontFixture("fi")
        val queries = mutableListOf(0)
        val chain = horizontalChain(1, queries)
        val first = success(JvmFlowCompositionFacade.layout(request(fixture, chain)))
        val beforeSecond = queries.single()

        val second = success(
            JvmFlowCompositionFacade.layout(
                request(fixture, chain, previousState = first.state),
            ),
        )

        assertEquals(beforeSecond, queries.single())
        assertEquals(first.fragments.map { it.laidOutRange }, second.fragments.map { it.laidOutRange })
        assertEquals(first.lines.map(LineLayout::glyphIds), second.lines.map(LineLayout::glyphIds))
    }

    @Test
    fun invalidFlowLayoutAggregateIsRejectedAsAPublicBusinessError() {
        val fixture = incrementalRealFontFixture("fi fi")
        val legitimate = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    horizontalChain(1),
                    requestedRange = fixture.snapshot.incrementalRange(0, 1),
                ),
            ),
        )

        val rejected = assertIs<FlowCompositionResult.Failure>(
            FlowLayout.create(
                inputIdentity = legitimate.inputIdentity,
                requestedRange = legitimate.requestedRange,
                fragments = emptyList(),
                coverage = legitimate.coverage,
                unmaterializedTail = legitimate.unmaterializedTail,
                state = legitimate.state,
                diagnostics = legitimate.diagnostics,
            ),
        )

        assertIs<FlowCompositionError.InvalidState>(rejected.error)

        val uncovered = assertIs<FlowCompositionResult.Failure>(
            FlowLayout.create(
                inputIdentity = legitimate.inputIdentity,
                requestedRange = assertNotNull(legitimate.unmaterializedTail).remainingSourceRange,
                fragments = legitimate.fragments,
                coverage = legitimate.coverage,
                unmaterializedTail = legitimate.unmaterializedTail,
                state = legitimate.state,
                diagnostics = legitimate.diagnostics,
            ),
        )

        assertIs<FlowCompositionError.InvalidState>(uncovered.error)
    }

    @Test
    fun contradictoryFlowStateIsRejectedAsAPublicBusinessError() {
        val fixture = incrementalRealFontFixture("fi fi")
        val chain = horizontalChain(2)
        val partial = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    chain,
                    requestedRange = fixture.snapshot.incrementalRange(0, 1),
                ),
            ),
        )

        val rejected = assertIs<FlowCompositionResult.Failure>(
            FlowLayoutState.create(
                inputIdentity = partial.state.inputIdentity,
                flowCompositionIdentity = partial.state.flowCompositionIdentity,
                coverage = partial.coverage,
                configuration = partial.state.configuration,
                materializedFragments = partial.fragments,
                checkpoints = partial.state.checkpoints,
                continuation = null,
            ),
        )

        assertIs<FlowCompositionError.InvalidState>(rejected.error)
    }

    @Test
    fun flowStateRejectsForeignFragmentGeometryEvenWithALegitimateEnd() {
        val fixture = incrementalRealFontFixture("fi fi")
        val legitimate = success(JvmFlowCompositionFacade.layout(request(fixture, horizontalChain(2))))
        val foreign = success(JvmFlowCompositionFacade.layout(request(fixture, horizontalChain(2))))
        val mixed = listOf(foreign.fragments.first(), legitimate.fragments.last())

        val rejected = assertIs<FlowCompositionResult.Failure>(
            FlowLayoutState.create(
                inputIdentity = legitimate.state.inputIdentity,
                flowCompositionIdentity = legitimate.state.flowCompositionIdentity,
                coverage = legitimate.coverage,
                configuration = legitimate.state.configuration,
                materializedFragments = mixed,
                checkpoints = legitimate.state.checkpoints,
                continuation = null,
            ),
        )

        assertIs<FlowCompositionError.InvalidState>(rejected.error)
    }

    @Test
    fun flowStateRejectsASingleFinalFragmentFromAnotherChain() {
        val fixture = incrementalRealFontFixture("fi")
        val legitimate = success(JvmFlowCompositionFacade.layout(request(fixture, horizontalChain(1))))
        val foreign = success(JvmFlowCompositionFacade.layout(request(fixture, horizontalChain(1))))
        assertNull(foreign.fragments.single().continuation)

        val rejected = assertIs<FlowCompositionResult.Failure>(
            FlowLayoutState.create(
                inputIdentity = legitimate.state.inputIdentity,
                flowCompositionIdentity = legitimate.state.flowCompositionIdentity,
                coverage = legitimate.coverage,
                configuration = legitimate.state.configuration,
                materializedFragments = foreign.fragments,
                checkpoints = emptyList(),
                continuation = null,
            ),
        )

        assertIs<FlowCompositionError.InvalidState>(rejected.error)
    }

    @Test
    fun flowStateRejectsARegionRetrogradeFragmentTransition() {
        val fixture = incrementalRealFontFixture("fi fi")
        val chain = horizontalChain(2)
        val legitimate = success(JvmFlowCompositionFacade.layout(request(fixture, chain)))
        val first = legitimate.fragments.first()
        val last = legitimate.fragments.last()
        val retrograde = listOf(
            ParagraphFragment(
                paragraphRange = first.paragraphRange,
                laidOutRange = first.laidOutRange,
                isFirstFragment = first.isFirstFragment,
                isLastFragment = first.isLastFragment,
                lines = first.lines,
                continuation = first.continuation,
                diagnostics = first.diagnostics,
                flowProvenance = assertNotNull(first.flowProvenance).copy(
                    regionIndex = 1,
                    regionIdentity = chain.regions[1].identity,
                ),
            ),
            ParagraphFragment(
                paragraphRange = last.paragraphRange,
                laidOutRange = last.laidOutRange,
                isFirstFragment = last.isFirstFragment,
                isLastFragment = last.isLastFragment,
                lines = last.lines,
                continuation = last.continuation,
                diagnostics = last.diagnostics,
                flowProvenance = assertNotNull(last.flowProvenance).copy(
                    regionIndex = 0,
                    regionIdentity = chain.regions[0].identity,
                ),
            ),
        )

        val rejected = assertIs<FlowCompositionResult.Failure>(
            FlowLayoutState.create(
                inputIdentity = legitimate.state.inputIdentity,
                flowCompositionIdentity = legitimate.state.flowCompositionIdentity,
                coverage = legitimate.coverage,
                configuration = legitimate.state.configuration,
                materializedFragments = retrograde,
                checkpoints = legitimate.state.checkpoints,
                continuation = null,
            ),
        )

        assertIs<FlowCompositionError.InvalidState>(rejected.error)
    }

    @Test
    fun verticalRegionUsesTheSameFacadeWithoutOwningAPageOrRenderer() {
        val fixture = incrementalRealFontFixture("f")
        val bounds = LayoutRect(LayoutUnit(400f), LayoutUnit(200f), LayoutUnit(3_400f), LayoutUnit(4_200f))
        val region = FixedFlowRegion(bounds, listOf(InlineInterval(0f, 4_000f)))
        val constraints = ParagraphConstraints(
            region = bounds,
            lineMetrics = LineVerticalMetrics(LayoutUnit(900f), LayoutUnit(300f)),
            writingMode = WritingMode.VERTICAL_RL,
        )

        val composed = success(
            JvmFlowCompositionFacade.layout(
                request(fixture, FlowChain(listOf(region)), constraints = constraints),
            ),
        )

        assertEquals(fixture.snapshot.range, composed.coverage.range)
        assertEquals(bounds.right, composed.lines.single().lineBox.right)
        assertTrue(composed.lines.single().allCaretCandidates.isNotEmpty())
    }

    private fun request(
        fixture: IncrementalRealFontFixture,
        chain: FlowChain,
        requestedRange: TextRange = fixture.snapshot.range,
        constraints: ParagraphConstraints = incrementalTestConstraints(width = 1_600f, top = 100f, height = 1_200f),
        overscan: Int = 0,
        previousState: FlowLayoutState? = null,
        delta: LayoutDelta? = null,
        hyphenationMode: HyphenationMode = HyphenationMode.MANUAL,
        hyphenationService: HyphenationService? = null,
        inlineObjects: InlineObjectSnapshot? = null,
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        operationProfile: EditorOperationProfile = EditorOperationProfile.unbounded,
    ): JvmFlowCompositionRequest {
        val portable = assertIs<FlowCompositionResult.Success<org.graphiks.kalligraphie.api.IncrementalFlowLayoutRequest>>(
            createIncrementalFlowLayoutRequest(
                input = LayoutInput(fixture.snapshot, fixture.typography),
                requestedRange = requestedRange,
                constraints = constraints,
                flowChain = chain,
                overscan = LineOverscan(overscan),
                previousState = previousState,
                delta = delta,
                operationProfile = operationProfile,
            ),
        ).value
        return JvmFlowCompositionRequest(
            request = portable,
            baseDirection = baseDirection,
            language = "en",
            hyphenationMode = hyphenationMode,
            hyphenationService = hyphenationService,
            inlineObjects = inlineObjects,
        )
    }

    private fun prepareParagraph(
        request: JvmFlowCompositionRequest,
        backend: ShapingBackend,
    ): ParagraphLayoutRequest = checkNotNull(
        JvmEditableParagraphFacade.prepareParagraphRequestBorrowing(
            JvmEditableParagraphFacadeRequest(
                snapshot = request.request.input.text,
                sourceRange = request.request.input.text.range,
                constraints = request.request.constraints,
                baseDirection = request.baseDirection,
                language = request.language,
                fontCatalog = request.request.input.typography.fontCatalog,
                resolutionPolicy = request.request.input.typography.resolutionPolicy,
                fontInstanceDescriptor = request.request.input.typography.fontInstanceDescriptor,
                features = request.features,
                materialization = request.materialization,
                overflowPolicy = request.overflowPolicy,
                positioning = request.positioning,
                hyphenationMode = request.hyphenationMode,
                hyphenationService = request.hyphenationService,
                inlineObjects = request.inlineObjects,
                textOrientation = request.textOrientation,
                verticalMetricsPolicy = request.verticalMetricsPolicy,
                cancellationToken = request.request.cancellationToken,
            ),
            backend,
        ),
    )

    private fun ParagraphLayoutRequest.withAnalyses(
        unicodeAnalysis: UnicodeAnalysis,
        lineBreakAnalysis: LineBreakAnalysis,
    ): ParagraphLayoutRequest = ParagraphLayoutRequest(
        snapshot = snapshot,
        sourceRange = sourceRange,
        unicodeAnalysis = unicodeAnalysis,
        lineBreakAnalysis = lineBreakAnalysis,
        constraints = constraints,
        baseDirection = baseDirection,
        language = language,
        featurePolicy = featurePolicy,
        features = features,
        fontCatalog = fontCatalog,
        resolutionPolicy = resolutionPolicy,
        fontInstanceDescriptor = fontInstanceDescriptor,
        shapingBackend = shapingBackend,
        materializationIdentity = materializationIdentity,
        overflowPolicy = overflowPolicy,
        continuation = continuation,
        positioning = positioning,
        hyphenationMode = hyphenationMode,
        hyphenationService = hyphenationService,
        inlineObjects = inlineObjects,
        textOrientation = textOrientation,
        verticalMetricsPolicy = verticalMetricsPolicy,
        cancellationToken = cancellationToken,
    )

    private fun horizontalChain(
        count: Int,
        queries: MutableList<Int>? = null,
        inlineExtent: Float = 1_600f,
    ): FlowChain = FlowChain(
        List(count) { index ->
            FixedFlowRegion(
                bounds = LayoutRect(
                    LayoutUnit(100f),
                    LayoutUnit(100f + index * 2_000f),
                    LayoutUnit(100f + inlineExtent),
                    LayoutUnit(1_300f + index * 2_000f),
                ),
                intervals = listOf(InlineInterval(0f, inlineExtent)),
                onQuery = { queries?.let { it[index] += 1 } },
            )
        },
    )

    private fun success(
        result: FlowCompositionResult<FlowLayout>,
    ): FlowLayout = assertIs<FlowCompositionResult.Success<FlowLayout>>(
        result,
        "Expected flow success, got ${(result as? FlowCompositionResult.Failure)?.error}",
    ).value

    private class FixedFlowRegion(
        override val bounds: LayoutRect,
        private val intervals: List<InlineInterval>,
        private val onQuery: () -> Unit = {},
    ) : FlowRegion {
        override val identity: FlowRegionIdentity = FlowRegionIdentity.create()

        override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult {
            onQuery()
            return FlowRegionResult.AvailableIntervals(intervals)
        }
    }

    private class RecordingShapingBackend(
        private val delegate: ShapingBackend,
    ) : ShapingBackend {
        override val identity = delegate.identity
        val requests = mutableListOf<ShapingRequest>()

        override fun shape(request: ShapingRequest): FontOperationResult<ShapedGlyphRun> {
            requests += request
            return delegate.shape(request)
        }

        override fun close(): FontOperationResult<Unit> = delegate.close()
    }
}
