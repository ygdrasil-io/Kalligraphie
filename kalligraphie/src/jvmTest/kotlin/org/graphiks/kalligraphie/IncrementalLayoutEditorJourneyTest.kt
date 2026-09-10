package org.graphiks.kalligraphie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontContentDigest
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicyDelta
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSourceId
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.IncrementalLayoutRequest
import org.graphiks.kalligraphie.api.IncrementalLayoutError
import org.graphiks.kalligraphie.api.IncrementalLayoutResult
import org.graphiks.kalligraphie.api.LayoutContractResult
import org.graphiks.kalligraphie.api.LayoutDelta
import org.graphiks.kalligraphie.api.LayoutInput
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutStateHandle
import org.graphiks.kalligraphie.api.LayoutTailState
import org.graphiks.kalligraphie.api.LineLayout
import org.graphiks.kalligraphie.api.LineOverscan
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.TextChange
import org.graphiks.kalligraphie.api.TextChangeSet
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TypographyDelta
import org.graphiks.kalligraphie.api.createIncrementalLayoutRequest

class IncrementalLayoutEditorJourneyTest {
    @Test
    fun insertionDeletionAndReplacementInsideOneParagraphMatchIndependentFullLayouts() {
        val initial = incrementalRealFontFixture("fi fi")
        val inserted = initial.withText("fi fi fi")
        val deleted = inserted.withText("fi fi")
        val replaced = deleted.withText("fi \u0633\u0644\u0627\u0645")
        val constraints = incrementalTestConstraints(width = 10_000f)

        openSession().use { session ->
            val first = success(session.layout(request(initial, constraints = constraints)))
            assertEquivalentToFull(first, fullLayout(initial, constraints))

            val second = success(
                session.layout(
                    request(
                        inserted,
                        constraints = constraints,
                        previousState = first.layout.state,
                        delta = LayoutDelta(text = change(initial, inserted, 5, 5, 5, 8)),
                    ),
                ),
            )
            assertEquivalentToFull(second, fullLayout(inserted, constraints))

            val third = success(
                session.layout(
                    request(
                        deleted,
                        constraints = constraints,
                        previousState = second.layout.state,
                        delta = LayoutDelta(text = change(inserted, deleted, 5, 8, 5, 5)),
                    ),
                ),
            )
            assertEquivalentToFull(third, fullLayout(deleted, constraints))

            val fourth = success(
                session.layout(
                    request(
                        replaced,
                        constraints = constraints,
                        previousState = third.layout.state,
                        delta = LayoutDelta(text = change(deleted, replaced, 3, 5, 3, 7)),
                    ),
                ),
            )
            assertEquivalentToFull(fourth, fullLayout(replaced, constraints))
            assertEquals(listOf(replaced.snapshot.incrementalRange(0, 7)), fourth.layout.lines.map(LineLayout::range))
            assertEquals(listOf(3, 1, 85, 3080, 3075, 1919), fourth.layout.lines.single().glyphIds())
            assertEquals(
                listOf(900f, 292f, 452f, 446f, 245f, 568f),
                fourth.layout.lines.single().glyphAdvances(),
            )
            assertEquals(replaced.snapshot.range, fourth.layout.coveredRange)
        }
    }

    @Test
    fun editAtBeginningOfFixedVisibleRangePreservesAuditedGeometry() {
        assertFixedVisibleEdit(
            targetText = "ii\nfi\nfi",
            sourceStart = 0,
            sourceEnd = 1,
            targetStart = 0,
            targetEnd = 1,
            editedLineIndex = 0,
            expectedTop = 50f,
            expectedCaretOrdinals = listOf(0, 1, 2, 3),
        )
    }

    @Test
    fun editAtMiddleOfFixedVisibleRangePreservesAuditedGeometry() {
        assertFixedVisibleEdit(
            targetText = "fi\nff\nfi",
            sourceStart = 4,
            sourceEnd = 5,
            targetStart = 4,
            targetEnd = 5,
            editedLineIndex = 1,
            expectedTop = 1_250f,
            expectedCaretOrdinals = listOf(3, 4, 5, 6),
        )
    }

    @Test
    fun editAtEndOfFixedVisibleRangePreservesAuditedGeometry() {
        assertFixedVisibleEdit(
            targetText = "fi\nfi\nff",
            sourceStart = 7,
            sourceEnd = 8,
            targetStart = 7,
            targetEnd = 8,
            editedLineIndex = 2,
            expectedTop = 2_450f,
            expectedCaretOrdinals = listOf(6, 7, 8),
        )
    }

    @Test
    fun multipleSoftWrappedLinesReflowBeforeAnAuditedStableSuffixRejoins() {
        val fonts = listOf(IncrementalFontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans"))
        val initial = incrementalRealFontFixture("one two three four\nstable tail\nsuffix", fonts)
        val target = initial.withText("one twenty x three\nstable tail\nsuffix")
        val constraints = incrementalTestConstraints(width = 3_000f, height = 12_000f)

        openSession().use { session ->
            val first = success(session.layout(request(initial, constraints = constraints, language = "en")))
            assertEquals(
                listOf(
                    initial.snapshot.incrementalRange(0, 4),
                    initial.snapshot.incrementalRange(4, 8),
                    initial.snapshot.incrementalRange(8, 14),
                    initial.snapshot.incrementalRange(14, 19),
                    initial.snapshot.incrementalRange(19, 26),
                    initial.snapshot.incrementalRange(26, 31),
                    initial.snapshot.incrementalRange(31, 37),
                ),
                first.layout.lines.map(LineLayout::range),
            )
            val edited = success(
                session.layout(
                    request(
                        target,
                        constraints = constraints,
                        requestedRange = target.snapshot.incrementalRange(4, 20),
                        previousState = first.layout.state,
                        delta = LayoutDelta(text = change(initial, target, 4, 18, 4, 18)),
                        language = "en",
                    ),
                ),
            )
            assertEquivalentToFull(edited, fullLayout(target, constraints, language = "en"))
            assertEquals(target.snapshot.textIndexAtScalarBoundary(4), edited.diagnostics.reflowStart)
            assertEquals(false, edited.diagnostics.usedConservativeInvalidation)
            assertEquals(
                listOf(
                    target.snapshot.incrementalRange(4, 11),
                    target.snapshot.incrementalRange(11, 13),
                    target.snapshot.incrementalRange(13, 19),
                    target.snapshot.incrementalRange(19, 26),
                ),
                edited.layout.lines.map(LineLayout::range),
            )
            assertEquals(target.snapshot.incrementalRange(4, 26), edited.layout.coveredRange)
            assertEquals(target.snapshot.textIndexAtScalarBoundary(26), edited.diagnostics.stabilizedAt)
            assertEquals(
                target.snapshot.incrementalRange(26, 37),
                assertIs<LayoutTailState.Stable>(edited.layout.coverage.tailState).range,
            )
            assertEquals(
                listOf(LayoutUnit(1_250f), LayoutUnit(2_450f), LayoutUnit(3_650f), LayoutUnit(4_850f)),
                edited.layout.lines.map { it.lineBox.top },
            )
        }
    }

    @Test
    fun paragraphBoundaryInsertionPublishesWholeParagraphLines() {
        val initial = incrementalRealFontFixture("fi fi")
        val target = initial.withText("fi\nfi")
        val constraints = incrementalTestConstraints(width = 10_000f)

        openSession().use { session ->
            val first = success(session.layout(request(initial, constraints = constraints)))
            val edited = success(
                session.layout(
                    request(
                        target,
                        constraints = constraints,
                        previousState = first.layout.state,
                        delta = LayoutDelta(text = change(initial, target, 2, 3, 2, 3)),
                    ),
                ),
            )
            assertEquivalentToFull(edited, fullLayout(target, constraints))
            assertEquals(
                listOf(target.snapshot.incrementalRange(0, 3), target.snapshot.incrementalRange(3, 5)),
                edited.layout.lines.map(LineLayout::range),
            )
            assertEquals(listOf(listOf(3), listOf(3)), edited.layout.lines.map { it.glyphIds() })
            assertEquals(target.snapshot.range, edited.layout.coveredRange)
        }
    }

    @Test
    fun ligatureToCombiningMarkEditKeepsClustersCaretsAndGlyphsAuditable() {
        val fonts = listOf(
            IncrementalFontFixture("gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture"),
            IncrementalFontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans"),
        )
        val initial = incrementalRealFontFixture("fi", fonts)
        val target = initial.withText("f\u0301")
        val constraints = incrementalTestConstraints(width = 10_000f)

        openSession().use { session ->
            val first = success(session.layout(request(initial, constraints = constraints, language = "en")))
            assertEquals(listOf(3), first.layout.lines.single().glyphIds())
            val edited = success(
                session.layout(
                    request(
                        target,
                        constraints = constraints,
                        previousState = first.layout.state,
                        delta = LayoutDelta(text = change(initial, target, 1, 2, 1, 2)),
                        language = "en",
                    ),
                ),
            )
            assertEquivalentToFull(edited, fullLayout(target, constraints, language = "en"))
            assertEquals(listOf(73, 5923), edited.layout.lines.single().glyphIds())
            assertEquals(listOf(352.05078f, 0f), edited.layout.lines.single().glyphAdvances())
            assertEquals(
                listOf(target.snapshot.incrementalRange(0, 1), target.snapshot.incrementalRange(1, 2)),
                edited.layout.lines.single().positionedGlyphRuns.flatMap { it.sourceRun.clusters }.map { it.sourceRange }.distinct(),
            )
            assertEquals(2, edited.layout.lines.single().allCaretCandidates.size)
        }
    }

    @Test
    fun bidiDirectionalContextEditReusesPriorCheckpointAndMatchesFullVisualRuns() {
        val fonts = listOf(IncrementalFontFixture("liberation/LiberationSans-Regular.ttf", "Liberation Sans Regular"))
        val initial = incrementalRealFontFixture("abc\nabc \u05D0\u05D1\u05D2", fonts)
        val target = initial.withText("abc\na\u05D0c \u05D0\u05D1\u05D2")
        val constraints = incrementalTestConstraints(width = 10_000f, height = 3_600f)

        openSession().use { session ->
            val first = success(
                session.layout(
                    request(
                        initial,
                        constraints = constraints,
                        baseDirection = BaseDirection.RIGHT_TO_LEFT,
                        language = "he",
                    ),
                ),
            )
            val edited = success(
                session.layout(
                    request(
                        target,
                        constraints = constraints,
                        requestedRange = target.snapshot.incrementalRange(4, 11),
                        previousState = first.layout.state,
                        delta = LayoutDelta(text = change(initial, target, 5, 6, 5, 6)),
                        baseDirection = BaseDirection.RIGHT_TO_LEFT,
                        language = "he",
                    ),
                ),
            )
            assertEquivalentToFull(edited, fullLayout(target, constraints, BaseDirection.RIGHT_TO_LEFT, "he"))
            assertEquals(target.snapshot.textIndexAtScalarBoundary(4), edited.diagnostics.reflowStart)
            assertEquals(false, edited.diagnostics.usedConservativeInvalidation)
            assertEquals(target.snapshot.incrementalRange(4, 11), edited.layout.coveredRange)
            assertEquals(org.graphiks.kalligraphie.api.ShapingDirection.RIGHT_TO_LEFT, edited.layout.lines.single().baseDirection)
            assertEquals(
                LineVerticalMetrics(LayoutUnit(900f), LayoutUnit(300f)),
                edited.layout.lines.single().verticalMetrics,
            )
            assertEquals(
                List(7) { "font.fallback-last-resort" },
                edited.layout.lines.single().diagnostics.filter { it.fallbackDiagnostic == null }.map { it.code },
            )
            assertTrue(edited.layout.lines.single().diagnostics.filter { it.fallbackDiagnostic == null }
                .all { it.sourceRange == null && it.glyphId == null })
            val fallbackDiagnostics = edited.layout.lines.single().diagnostics.mapNotNull { it.fallbackDiagnostic }
            assertEquals((4 until 11).map { target.snapshot.incrementalRange(it, it + 1) }, fallbackDiagnostics.map { it.range })
            assertTrue(fallbackDiagnostics.all { it.textVersion == target.snapshot.version &&
                it.reason == org.graphiks.kalligraphie.api.FontFallbackReason.LastResortSelected &&
                it.lastResortState == org.graphiks.kalligraphie.api.FontFallbackLastResortState.Selected })
            assertEquals(
                listOf(1, 1, 2, 1, 2),
                edited.layout.lines.single().positionedGlyphRuns.map { it.sourceRun.bidiLevel },
            )
            assertEquals(
                listOf(0, 1, 2, 3, 4),
                edited.layout.lines.single().positionedGlyphRuns.map { it.visualOrder },
            )
            assertEquals(
                listOf(
                    target.snapshot.incrementalRange(8, 11),
                    target.snapshot.incrementalRange(7, 8),
                    target.snapshot.incrementalRange(6, 7),
                    target.snapshot.incrementalRange(5, 6),
                    target.snapshot.incrementalRange(4, 5),
                ),
                edited.layout.lines.single().positionedGlyphRuns.map { it.sourceRun.range },
            )
            assertEquals(
                listOf(1282, 1281, 1280, 3, 70, 1280, 68),
                edited.layout.lines.single().glyphIds(),
            )
        }
    }

    @Test
    fun fallbackPolicyAndTypographyChangeForceDocumentStartReflow() {
        val initial = incrementalRealFontFixture("fi \u0633\u0644\u0627\u0645")
        val gdefFace = auditedFace("c9f28286059cf869a80340af0edd035cfb83d10da586f67c728234f2d63b90a8")
        val amiriFace = auditedFace("ab391c4147d054c48976e98322ad0eefe1427aa0e0502a12a4c75d80a70cfcd7")
        val reversedPolicy = FontResolutionPolicySnapshot(
            generation = initial.catalog.generation,
            policyId = "incremental-session-fixture-reversed",
            version = "2",
            candidates = listOf(amiriFace, gdefFace).map(::FontResolutionCandidate),
            lastResortFace = gdefFace,
        )
        val target = initial.withTypography(
            policy = reversedPolicy,
            fontInstanceDescriptor = org.graphiks.kalligraphie.api.FontInstanceDescriptor(LayoutUnit(1_200f)),
            features = listOf(OpenTypeFeature("liga", 0)),
        )
        val typographyDelta = TypographyDelta(
            sourceVersion = initial.typography.version,
            targetVersion = target.typography.version,
            fontResolutionPolicy = FontResolutionPolicyDelta(initial.policy, reversedPolicy),
        )
        val constraints = incrementalTestConstraints(width = 10_000f)

        openSession().use { session ->
            val first = success(session.layout(request(initial, constraints = constraints)))
            assertEquivalentToFull(first, fullLayout(initial, constraints))
            assertEquals(listOf(initial.snapshot.incrementalRange(0, 7)), first.layout.lines.map(LineLayout::range))
            assertEquals(
                listOf(gdefFace, amiriFace, amiriFace),
                first.layout.lines.single().positionedGlyphRuns.map { it.fontInstanceKey.face },
            )
            assertEquals(listOf(3, 1, 85, 3080, 3075, 1919), first.layout.lines.single().glyphIds())
            val edited = success(
                session.layout(
                    request(
                        target,
                        constraints = constraints,
                        previousState = first.layout.state,
                        delta = LayoutDelta(typography = typographyDelta),
                    ),
                ),
            )
            assertEquivalentToFull(edited, fullLayout(target, constraints))
            assertEquals(target.snapshot.range.start, edited.diagnostics.reflowStart)
            assertTrue(edited.diagnostics.usedConservativeInvalidation)
            assertEquals(target.snapshot.range, edited.layout.coveredRange)
            assertEquals(
                setOf(amiriFace),
                edited.layout.lines.flatMap { it.positionedGlyphRuns }.map { it.fontInstanceKey.face }.toSet(),
            )
            assertEquals(
                listOf(target.snapshot.incrementalRange(0, 3), target.snapshot.incrementalRange(3, 7)),
                edited.layout.lines.single().positionedGlyphRuns.map { it.sourceRun.range },
            )
            assertEquals(
                listOf(6261, 6264, 1, 85, 3080, 3075, 1919),
                edited.layout.lines.single().glyphIds(),
            )
            assertEquals(
                listOf(360f, 315.6f, 350.4f, 542.4f, 535.2f, 294f, 681.6f),
                edited.layout.lines.single().glyphAdvances(),
            )
            assertEquals(listOf(target.snapshot.incrementalRange(0, 7)), edited.layout.lines.map(LineLayout::range))
            assertEquals(
                setOf(LayoutUnit(1_200f)),
                edited.layout.lines.flatMap { it.positionedGlyphRuns }.map { it.fontInstanceKey.layoutSize }.toSet(),
            )
            assertEquals(
                setOf(OpenTypeFeature("liga", 0)),
                edited.layout.lines.flatMap { it.positionedGlyphRuns }.flatMap { it.sourceRun.features }.toSet(),
            )
        }
    }

    @Test
    fun visibleRangeWithOneLineOverscanPublishesExactWholeLineCoverage() {
        val fixture = incrementalRealFontFixture("fi\nfi\nfi\nfi")
        val requested = fixture.snapshot.incrementalRange(3, 5)

        openSession().use { session ->
            val result = success(session.layout(request(fixture, requestedRange = requested, overscan = 1)))
            assertEquivalentToFull(result, fullLayout(fixture, incrementalTestConstraints()))
            assertEquals(
                listOf(
                    fixture.snapshot.incrementalRange(0, 3),
                    fixture.snapshot.incrementalRange(3, 6),
                    fixture.snapshot.incrementalRange(6, 9),
                ),
                result.layout.lines.map(LineLayout::range),
            )
            assertEquals(fixture.snapshot.incrementalRange(0, 9), result.layout.coveredRange)
            assertEquals(
                fixture.snapshot.incrementalRange(9, 11),
                assertIs<LayoutTailState.Invalidated>(result.layout.coverage.tailState).range,
            )
        }
    }

    @Test
    fun malformedVersionsOverlapsAndCrossSpaceRangesAreRejectedWithTypedErrors() {
        val source = incrementalRealFontFixture("abcd")
        val target = source.withText("aXd")
        val foreign = source.withText("aYd")
        val overlap = TextChangeSet.create(
            source.snapshot,
            target.snapshot,
            listOf(
                TextChange(source.snapshot.incrementalRange(0, 2), target.snapshot.incrementalRange(0, 1)),
                TextChange(source.snapshot.incrementalRange(1, 3), target.snapshot.incrementalRange(1, 2)),
            ),
        )
        assertIs<IncrementalLayoutError.OverlappingRanges>(assertIs<LayoutContractResult.Failure>(overlap).error)

        val crossSpace = TextChangeSet.create(
            source.snapshot,
            target.snapshot,
            listOf(TextChange(foreign.snapshot.incrementalRange(1, 3), target.snapshot.incrementalRange(1, 2))),
        )
        assertIs<IncrementalLayoutError.VersionMismatch>(assertIs<LayoutContractResult.Failure>(crossSpace).error)

        openSession().use { session ->
            val previous = success(session.layout(request(source)))
            val wrongTargetDelta = change(source, foreign, 1, 3, 1, 2)
            val requestResult = createIncrementalLayoutRequest(
                LayoutInput(target.snapshot, target.typography),
                target.snapshot.range,
                incrementalTestConstraints(),
                LineOverscan(0),
                previous.layout.state,
                LayoutDelta(text = wrongTargetDelta),
                CancellationToken.none,
            )
            assertIs<IncrementalLayoutError.VersionMismatch>(assertIs<LayoutContractResult.Failure>(requestResult).error)
            assertEquals(source.snapshot.version, session.currentLayout()?.layout?.inputIdentity?.textVersion)
        }
    }

    @Test
    fun incompatibleConstraintsUseConservativeFullReflowAndMatchFullLayout() {
        val fixture = incrementalRealFontFixture("fi \u0633\u0644\u0627\u0645")
        val changedConstraints = incrementalTestConstraints(width = 10_000f, top = 500f)

        openSession().use { session ->
            val previous = success(session.layout(request(fixture)))
            val result = success(session.layout(request(fixture, constraints = changedConstraints, previousState = previous.layout.state)))
            assertEquivalentToFull(result, fullLayout(fixture, changedConstraints))
            assertEquals(fixture.snapshot.range.start, result.diagnostics.reflowStart)
            assertTrue(result.diagnostics.usedConservativeInvalidation)
            assertEquals(LayoutUnit(500f), result.layout.lines.single().lineBox.top)
            assertEquals(fixture.snapshot.range, result.layout.coveredRange)
        }
    }

    @Test
    fun staleCompletionCannotReplaceNewerAuditedPublication() {
        val firstFixture = incrementalRealFontFixture("fi \u0633\u0644\u0627\u0645")
        val newerFixture = firstFixture.withText("fi fi")
        val constraints = incrementalTestConstraints(width = 10_000f)

        openSession().use { session ->
            val first = success(session.layout(request(firstFixture, constraints = constraints)))
            val newer = success(session.layout(request(newerFixture, constraints = constraints)))
            assertEquivalentToFull(newer, fullLayout(newerFixture, constraints))
            assertIs<IncrementalLayoutResult.Obsolete>(session.publishForTesting(first, generation = 1L))
            assertEquals(newerFixture.snapshot.version, session.currentLayout()?.layout?.inputIdentity?.textVersion)
            assertEquals(listOf(3, 1, 3), session.currentLayout()?.layout?.lines?.single()?.glyphIds())
            assertEquals(newerFixture.snapshot.range, session.currentLayout()?.layout?.coveredRange)
        }
    }

    @Test
    fun cancellationKeepsThePreviousFullEquivalentPublication() {
        val initial = incrementalRealFontFixture("fi \u0633\u0644\u0627\u0645")
        val target = initial.withText("fi \u0633\u0644\u0627\u0645 fi \u0633\u0644\u0627\u0645")

        openSession().use { session ->
            val previous = success(session.layout(request(initial)))
            assertEquivalentToFull(previous, fullLayout(initial, incrementalTestConstraints()))
            val token = CancelAfterChecks(5)
            val cancelled = session.layout(
                request(
                    target,
                    previousState = previous.layout.state,
                    delta = LayoutDelta(text = change(initial, target, 7, 7, 7, 15)),
                    cancellationToken = token,
                ),
            )
            assertIs<IncrementalLayoutResult.Cancelled>(cancelled)
            assertTrue(token.checks > 5)
            assertEquals(previous.layout.inputIdentity, session.currentLayout()?.layout?.inputIdentity)
            assertEquals(listOf(listOf(3, 1), listOf(85, 3080, 3075, 1919)), session.currentLayout()?.layout?.lines?.map { it.glyphIds() })
            assertEquals(initial.snapshot.range, session.currentLayout()?.layout?.coveredRange)
        }
    }

    @Test
    fun globalFeatureChangeReflowsFromDocumentStartForLateVisibleCoverage() {
        val fonts = listOf(IncrementalFontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans"))
        val initial = incrementalRealFontFixture("office-office", fonts)
        val target = initial.withTypography(features = listOf(OpenTypeFeature("liga", 0)))
        val constraints = incrementalTestConstraints(width = 3_200f)
        val typographyDelta = TypographyDelta(initial.typography.version, target.typography.version)

        openSession().use { session ->
            val previous = success(session.layout(request(initial, constraints = constraints, language = "en")))
            val result = success(
                session.layout(
                    request(
                        target,
                        constraints = constraints,
                        requestedRange = target.snapshot.incrementalRange(7, 13),
                        previousState = previous.layout.state,
                        delta = LayoutDelta(typography = typographyDelta),
                        language = "en",
                    ),
                ),
            )
            assertEquivalentToFull(result, fullLayout(target, constraints, language = "en"))
            assertEquals(target.snapshot.range.start, result.diagnostics.reflowStart)
            assertTrue(result.diagnostics.usedConservativeInvalidation)
            assertEquals(target.snapshot.incrementalRange(7, 13), result.layout.coveredRange)
            assertEquals(listOf(82, 73, 73, 76, 70, 72), result.layout.lines.single().glyphIds())
            assertEquals(listOf(OpenTypeFeature("liga", 0)), result.layout.lines.single().positionedGlyphRuns.single().sourceRun.features)
        }
    }

    @Test
    fun realisticEditorSequenceMatchesIndependentFullLayoutAtEveryRevision() {
        val fonts = listOf(
            IncrementalFontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans"),
            IncrementalFontFixture("amiri/Amiri-Regular.ttf", "Amiri Regular"),
        )
        val initial = incrementalRealFontFixture("office cafe\nabc \u0633\u0644\u0627\u0645", fonts)
        val inserted = initial.withText("oXffice cafe\nabc \u0633\u0644\u0627\u0645")
        val replaced = inserted.withText("oXffice \uD83D\uDE00\nabc \u0633\u0644\u0627\u0645")
        val constraints = incrementalTestConstraints(width = 4_000f, height = 6_000f)

        openSession().use { session ->
            val first = success(
                session.layout(
                    request(
                        initial,
                        constraints = constraints,
                        requestedRange = initial.snapshot.incrementalRange(0, 6),
                        language = "en",
                    ),
                ),
            )
            assertEquivalentToFull(first, fullLayout(initial, constraints, language = "en"))
            val second = success(
                session.layout(
                    request(
                        inserted,
                        constraints = constraints,
                        requestedRange = inserted.snapshot.incrementalRange(0, 8),
                        previousState = first.layout.state,
                        delta = LayoutDelta(text = change(initial, inserted, 1, 1, 1, 2)),
                        language = "en",
                    ),
                ),
            )
            assertEquivalentToFull(second, fullLayout(inserted, constraints, language = "en"))
            val third = success(
                session.layout(
                    request(
                        replaced,
                        constraints = constraints,
                        requestedRange = replaced.snapshot.incrementalRange(0, 9),
                        previousState = second.layout.state,
                        delta = LayoutDelta(text = change(inserted, replaced, 8, 12, 8, 9)),
                        language = "en",
                    ),
                ),
            )
            assertEquivalentToFull(third, fullLayout(replaced, constraints, language = "en"))

            assertEquals(replaced.snapshot.incrementalRange(0, 10), third.layout.coveredRange)
            assertEquals(
                listOf(replaced.snapshot.incrementalRange(0, 8), replaced.snapshot.incrementalRange(8, 10)),
                third.layout.lines.map(LineLayout::range),
            )
            assertTrue(
                third.layout.lines[1].positionedGlyphRuns
                    .flatMap { it.sourceRun.clusters }
                    .any { it.sourceRange == replaced.snapshot.incrementalRange(8, 9) },
            )
            assertEquals(
                listOf(8, 9, 10),
                third.layout.lines[1].allCaretCandidates.map { candidate ->
                    listOf(8, 9, 10).single { ordinal -> candidate.position.index == replaced.snapshot.textIndexAtScalarBoundary(ordinal) }
                },
            )
        }
    }

    private fun openSession(): JvmIncrementalParagraphLayoutSession =
        assertIs<FontOperationResult.Success<JvmIncrementalParagraphLayoutSession>>(
            JvmIncrementalParagraphLayoutSession.open(),
        ).value

    private fun auditedFace(contentDigest: String): FontFaceId = FontFaceId(
        FontSourceId.Portable(FontContentDigest(contentDigest)),
        faceIndex = 0,
    )

    private fun assertFixedVisibleEdit(
        targetText: String,
        sourceStart: Int,
        sourceEnd: Int,
        targetStart: Int,
        targetEnd: Int,
        editedLineIndex: Int,
        expectedTop: Float,
        expectedCaretOrdinals: List<Int>,
    ) {
        val initial = incrementalRealFontFixture("fi\nfi\nfi")
        val target = initial.withText(targetText)
        val constraints = incrementalTestConstraints()
        val fixedVisibleRange = target.snapshot.incrementalRange(0, 8)

        openSession().use { session ->
            val previous = success(session.layout(request(initial, constraints = constraints)))
            val edited = success(
                session.layout(
                    request(
                        target,
                        constraints = constraints,
                        requestedRange = fixedVisibleRange,
                        previousState = previous.layout.state,
                        delta = LayoutDelta(
                            text = change(
                                initial,
                                target,
                                sourceStart,
                                sourceEnd,
                                targetStart,
                                targetEnd,
                            ),
                        ),
                    ),
                ),
            )

            assertEquivalentToFull(edited, fullLayout(target, constraints))
            assertEquals(
                listOf(
                    target.snapshot.incrementalRange(0, 3),
                    target.snapshot.incrementalRange(3, 6),
                    target.snapshot.incrementalRange(6, 8),
                ),
                edited.layout.lines.map(LineLayout::range),
            )
            assertEquals(fixedVisibleRange, edited.layout.coveredRange)
            assertEquals(
                LayoutRect(
                    LayoutUnit(100f),
                    LayoutUnit(expectedTop),
                    LayoutUnit(1_500f),
                    LayoutUnit(expectedTop + 1_200f),
                ),
                edited.layout.lines[editedLineIndex].lineBox,
            )
            assertEquals(
                expectedCaretOrdinals,
                edited.layout.lines[editedLineIndex].allCaretCandidates.map { candidate ->
                    expectedCaretOrdinals.single { ordinal ->
                        candidate.position.index == target.snapshot.textIndexAtScalarBoundary(ordinal)
                    }
                },
            )
        }
    }

    private fun request(
        fixture: IncrementalRealFontFixture,
        constraints: HorizontalParagraphConstraints = incrementalTestConstraints(),
        requestedRange: TextRange = fixture.snapshot.range,
        overscan: Int = 0,
        previousState: LayoutStateHandle? = null,
        delta: LayoutDelta? = null,
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        language: String = "ar",
        cancellationToken: CancellationToken = CancellationToken.none,
    ): JvmIncrementalParagraphLayoutRequest {
        val portable = assertIs<LayoutContractResult.Success<IncrementalLayoutRequest>>(
            createIncrementalLayoutRequest(
                input = LayoutInput(fixture.snapshot, fixture.typography),
                requestedRange = requestedRange,
                constraints = constraints,
                overscan = LineOverscan(overscan),
                previousState = previousState,
                delta = delta,
                cancellationToken = cancellationToken,
            ),
        ).value
        return JvmIncrementalParagraphLayoutRequest(
            request = portable,
            baseDirection = baseDirection,
            language = language,
            materialization = EditableLineMaterialization.LayoutOnly,
        )
    }

    private fun fullLayout(
        fixture: IncrementalRealFontFixture,
        constraints: HorizontalParagraphConstraints,
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        language: String = "ar",
    ): List<LineLayout> = assertIs<ParagraphLayoutResult.Success>(
        JvmEditableParagraphFacade.layout(
            JvmEditableParagraphFacadeRequest(
                snapshot = fixture.snapshot,
                constraints = constraints,
                baseDirection = baseDirection,
                language = language,
                fontCatalog = fixture.catalog,
                resolutionPolicy = fixture.policy,
                fontInstanceDescriptor = fixture.typography.fontInstanceDescriptor,
                features = fixture.typography.features,
            ),
        ),
    ).layout.lines

    private fun change(
        source: IncrementalRealFontFixture,
        target: IncrementalRealFontFixture,
        sourceStart: Int,
        sourceEnd: Int,
        targetStart: Int,
        targetEnd: Int,
    ): TextChangeSet = assertIs<LayoutContractResult.Success<TextChangeSet>>(
        TextChangeSet.create(
            source.snapshot,
            target.snapshot,
            listOf(
                TextChange(
                    source.snapshot.incrementalRange(sourceStart, sourceEnd),
                    target.snapshot.incrementalRange(targetStart, targetEnd),
                ),
            ),
        ),
    ).value

    private fun success(result: IncrementalLayoutResult): IncrementalLayoutResult.Success = assertIs(result)

    private fun assertEquivalentToFull(
        incremental: IncrementalLayoutResult.Success,
        full: List<LineLayout>,
    ) {
        val expected = incremental.layout.lines.map { line ->
            full.single { candidate -> candidate.range == line.range }
        }
        assertEquals(expected.map(::lineFingerprint), incremental.layout.lines.map(::lineFingerprint))
    }

    private fun lineFingerprint(line: LineLayout): List<Any> = listOf(
        line.range,
        line.baseDirection,
        line.verticalMetrics,
        line.baseline,
        line.contentMetrics,
        line.lineBox,
        line.designInkBounds,
        line.diagnostics,
        line.positionedGlyphRuns.map { run ->
            listOf(
                run.sourceRun.range,
                run.sourceRun.fontInstanceKey,
                run.sourceRun.backendIdentity,
                run.sourceRun.direction,
                run.sourceRun.script,
                run.sourceRun.language,
                run.sourceRun.bidiLevel,
                run.sourceRun.bot,
                run.sourceRun.eot,
                run.sourceRun.featurePolicy,
                run.sourceRun.features,
                run.sourceRun.graphemeClusters,
                run.sourceRun.clusters.map { cluster ->
                    listOf(
                        cluster.token,
                        cluster.sourceRange,
                        cluster.scalarRanges,
                        cluster.admissibleGraphemeBoundaries,
                    )
                },
                run.sourceRun.ligatureCaretFacts.map { fact ->
                    listOf(fact.glyphIndex, fact.state, fact.logicalSourceBoundaries, fact.positions)
                },
                run.visualOrder,
                run.renderAssetKey,
                run.glyphs.map { glyph ->
                    listOf(
                        glyph.shapedGlyph.glyphId,
                        glyph.shapedGlyph.xAdvance,
                        glyph.shapedGlyph.yAdvance,
                        glyph.shapedGlyph.xOffset,
                        glyph.shapedGlyph.yOffset,
                        glyph.shapedGlyph.safetyFlags,
                        glyph.shapedGlyph.clusterTokens,
                        glyph.advance,
                        glyph.origin,
                        glyph.renderAssetKey,
                        glyph.materializationCertificate,
                        glyph.sourceClusters.map { cluster ->
                            listOf(
                                cluster.sourceRange,
                                cluster.scalarRanges,
                                cluster.admissibleGraphemeBoundaries,
                            )
                        },
                    )
                },
            )
        },
        line.allCaretCandidates.map { candidate ->
            listOf(
                candidate.position,
                candidate.geometry,
                candidate.visualOrder,
                candidate.visualRunOrder,
                candidate.bidiLevel,
                candidate.direction,
                candidate.strength,
                candidate.edge,
            )
        },
    )

    private class CancelAfterChecks(private val allowedChecks: Int) : CancellationToken {
        var checks: Int = 0
            private set

        override fun isCancellationRequested(): Boolean {
            checks += 1
            return checks > allowedChecks
        }
    }
}
