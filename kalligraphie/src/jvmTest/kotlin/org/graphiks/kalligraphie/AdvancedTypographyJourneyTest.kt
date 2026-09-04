package org.graphiks.kalligraphie

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.EllipsisSide
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphMaterializationRoute
import org.graphiks.kalligraphie.api.GlyphProvenance
import org.graphiks.kalligraphie.api.GlyphProvenanceRole
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.HyphenationMode
import org.graphiks.kalligraphie.api.InlineObjectAlignment
import org.graphiks.kalligraphie.api.InlineObjectDefinition
import org.graphiks.kalligraphie.api.InlineObjectEntry
import org.graphiks.kalligraphie.api.InlineObjectId
import org.graphiks.kalligraphie.api.InlineObjectSnapshot
import org.graphiks.kalligraphie.api.JustificationMode
import org.graphiks.kalligraphie.api.LayoutPoint
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineLayout
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.OverflowPolicy
import org.graphiks.kalligraphie.api.ParagraphAlignment
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.ParagraphPositioningPolicy
import org.graphiks.kalligraphie.api.TabAlignment
import org.graphiks.kalligraphie.api.TabStop
import org.graphiks.kalligraphie.api.TextChange
import org.graphiks.kalligraphie.api.TextChangeSet
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.unicode.JvmPatternHyphenationService

/**
 * Business scenarios for advanced typography and derived content published
 * through the reference JVM route: provenance, soft and automatic
 * hyphenation, kashida justification, tab stops and leaders, ellipsis
 * truncation, inline objects, recertification, and incremental equality.
 * Every numeric oracle is a frozen external expectation over the checked-in
 * real font fixtures (DejaVu Sans 2.37, Amiri Regular, Liberation Sans);
 * glyph identifiers and advances come from audited HarfBuzz 14.3.0 output and
 * published font tables.
 */
class AdvancedTypographyJourneyTest {
    @Test
    fun directGlyphsReportDirectProvenanceWithTheirExactSourceRange() {
        val fixture = dejavuFixture("fi")

        val line = layoutLine(fixture, constraints(width = 1_400f, top = 50f, height = 1_200f), language = "en")

        val glyph = line.positionedGlyphRuns.single().glyphs.single()
        val direct = assertIs<GlyphProvenance.Direct>(glyph.provenance)
        assertEquals(fixture.range(0, 2), direct.sourceRange)
        assertEquals(fixture.range(0, 2), line.positionedGlyphRuns.single().sourceRun.clusters.single().sourceRange)
    }

    @Test
    fun softHyphenStaysInvisibleWhenTheLineDoesNotBreakThere() {
        val fixture = dejavuFixture("co\u00ADoperate")

        val line = layoutLine(fixture, constraints(width = 6_000f, top = 50f, height = 1_200f), language = "en")

        assertEquals(fixture.snapshot.range, line.range)
        val shyGlyph = firstGlyphOfRange(fixture, line, fixture.range(2, 3))
        assertEquals(0f, shyGlyph.advance.x.value)
        assertIs<GlyphProvenance.Direct>(shyGlyph.provenance)
        // The soft hyphen keeps exactly its two scalar boundaries; no glyph created a position.
        assertEquals(11, line.allCaretCandidates.size)
    }

    @Test
    fun brokenSoftHyphenPublishesAVisibleDerivedHyphenWithoutInventingTextIndexes() {
        val fixture = dejavuFixture("co\u00ADoperate")

        val layout = layoutParagraph(
            fixture,
            constraints(width = 2_000f, top = 50f, height = 2_400f),
            language = "en",
        )

        assertEquals(
            listOf(fixture.range(0, 3), fixture.range(3, 10)),
            layout.lines.map(LineLayout::range),
        )
        val first = layout.lines[0]
        val derivedGlyph = first.glyphs().single { it.provenance is GlyphProvenance.Derived }
        val derived = assertIs<GlyphProvenance.Derived>(derivedGlyph.provenance)
        assertEquals(GlyphProvenanceRole.SOFT_HYPHEN, derived.role)
        assertEquals(fixture.range(2, 3), derived.sourceRange)
        // The visible hyphen is derived from the soft hyphen source scalar, not a new position.
        assertEquals(4, first.allCaretCandidates.size)
    }

    @Test
    fun automaticHyphenationUsesTheVersionedServiceAndMaterializesAtTheChosenBreak() {
        val fixture = dejavuFixture("hyphenation")

        val result = layout(
            fixture,
            constraints(width = 4_300f, top = 50f, height = 2_400f),
            language = "en",
            hyphenationMode = HyphenationMode.AUTO,
            hyphenationService = JvmPatternHyphenationService.english(),
        )
        val layout = assertIs<ParagraphLayoutResult.Success>(result)

        assertEquals(
            listOf("hyphen", "ation"),
            layout.layout.lines.map { line -> fixture.textOf(line.range) },
        )
        val first = layout.layout.lines[0]
        val hyphen = first.glyphs().first { it.provenance is GlyphProvenance.Derived }
        val derived = assertIs<GlyphProvenance.Derived>(hyphen.provenance)
        assertEquals(GlyphProvenanceRole.AUTOMATIC_HYPHEN, derived.role)
        assertEquals(16, hyphen.shapedGlyph.glyphId.value)
        assertEquals(360.83984f, hyphen.advance.x.value)
        assertEquals(3209.375f, hyphen.origin.x.value)
        // The break inserted no new document position: six scalars, seven boundaries.
        assertEquals(7, first.allCaretCandidates.size)
        // The second line starts immediately after the break.
        assertEquals("ation", fixture.textOf(layout.layout.lines[1].range))
    }

    @Test
    fun automaticHyphenationWithoutServiceDegradesToValidNoHyphenLayoutWithDiagnostic() {
        val fixture = dejavuFixture("hyphenation")

        val result = layout(
            fixture,
            constraints(width = 4_300f, top = 50f, height = 2_400f),
            language = "en",
            hyphenationMode = HyphenationMode.AUTO,
        )
        val layout = assertIs<ParagraphLayoutResult.Success>(result)
        assertEquals(listOf(fixture.snapshot.range), layout.layout.lines.map(LineLayout::range))
        assertTrue(
            layout.layout.lines.flatMap { line -> line.diagnostics }.any { it.code == "layout.hyphenation-service-absent" },
        )
    }

    @Test
    fun justifiedArabicLinePublishesSyntheticKashidaGlyphsWithAttestedProvenance() {
        val fixture = amiriFixture("\u0627\u0644\u0633\u0644\u0627\u0645 \u0645\u0646 \u0627\u0644\u0633\u0644\u0627\u0645")

        val layout = layoutParagraph(
            fixture,
            constraints(width = 5_200f, top = 50f, height = 2_400f),
            language = "ar",
            positioning = ParagraphPositioningPolicy(
                alignment = ParagraphAlignment.JUSTIFY,
                justificationMode = JustificationMode.KASHIDA,
            ),
        )

        val first = layout.lines[0]
        val kashidas = first.glyphs().filter { glyph ->
            val provenance = glyph.provenance
            provenance is GlyphProvenance.Synthetic && provenance.role == GlyphProvenanceRole.KASHIDA
        }
        assertEquals(7, kashidas.size)
        kashidas.forEach { glyph ->
            assertEquals(80, glyph.shapedGlyph.glyphId.value)
            assertEquals(185f, glyph.advance.x.value)
            val synthetic = assertIs<GlyphProvenance.Synthetic>(glyph.provenance)
            assertEquals(GlyphProvenanceRole.KASHIDA, synthetic.role)
            // Anchors are real snapshot boundaries: the first anchor is the first word scalar.
            assertTrue(synthetic.anchor >= fixture.snapshot.range.start)
        }
        assertEquals(
            listOf(715.0f, 1289.0f, 2432.0f, 3063.0f, 3493.0f, 4246.0f, 4606.0f),
            kashidas.map { glyph -> glyph.origin.x.value },
        )
        // The justified line spans exactly the requested inline extent.
        assertEquals(5_200f, first.contentMetrics.inlineAdvance.value)
    }

    @Test
    fun decimalTabStopCentersTheDecimalCharacterExactlyAtTheStop() {
        val fixture = dejavuFixture("0.9\u00091.2")

        val layout = layoutParagraph(
            fixture,
            constraints(width = 6_000f, top = 50f, height = 2_400f),
            language = "en",
            positioning = ParagraphPositioningPolicy(
                tabStops = listOf(TabStop(LayoutUnit(3_300f), alignment = TabAlignment.DECIMAL)),
            ),
        )

        val line = layout.lines.single()
        val dots = line.glyphs().filter { glyph ->
            glyph.sourceClusters.any { cluster ->
                fixture.snapshot.scalarValues(cluster.sourceRange) == listOf(0x2E)
            }
        }
        // Both decimal dots exist; the second field's dot is centered exactly on the stop at
        // `line left 100 + stop 3300`.
        assertEquals(2, dots.size)
        val secondDot = dots[1]
        assertEquals(3_241.0645f, secondDot.origin.x.value)
        assertEquals(3_400.0f, secondDot.origin.x.value + secondDot.shapedGlyph.xAdvance.value / 2f)
        // Two carets around the tab scalar, no invented positions: seven scalars, eight boundaries.
        assertEquals(8, line.allCaretCandidates.size)
    }

    @Test
    fun tabLeaderIsSyntheticContentWithoutFakeDocumentCharacters() {
        val fixture = dejavuFixture("a\u0009b")

        val layout = layoutParagraph(
            fixture,
            constraints(width = 6_000f, top = 50f, height = 2_400f),
            language = "en",
            positioning = ParagraphPositioningPolicy(
                tabStops = listOf(TabStop(LayoutUnit(1_200f), leader = 0x2E)),
            ),
        )

        val line = layout.lines.single()
        val leaders = line.glyphs().filter { glyph ->
            val provenance = glyph.provenance
            provenance is GlyphProvenance.Synthetic && provenance.role == GlyphProvenanceRole.TAB_LEADER
        }
        assertEquals(1, leaders.size)
        assertEquals(17, leaders.single().shapedGlyph.glyphId.value)
        assertEquals(712.79297f, leaders.single().origin.x.value)
        assertEquals(317.8711f, leaders.single().advance.x.value)
        // The field after the leader starts exactly at the stop.
        val b = line.glyphs().first { glyph ->
            glyph.sourceClusters.any { cluster ->
                fixture.snapshot.scalarValues(cluster.sourceRange) == listOf(0x62)
            }
        }
        assertEquals(1_300.0f, b.origin.x.value)
        // The snapshot still contains exactly a, TAB, b: the leader added no document character.
        assertEquals(listOf(0x61, 0x09, 0x62), fixture.snapshot.scalars)
        assertEquals(4, line.allCaretCandidates.size)
    }

    @Test
    fun inlineEndEllipsisPublishesAnExplicitHiddenRangeAndSyntheticMarker() {
        val fixture = dejavuFixture("The quick brown fox jumps over the lazy dog")

        val result = layout(
            fixture,
            constraints(width = 1_700f, top = 50f, height = 1_200f),
            language = "en",
            overflowPolicy = OverflowPolicy.Ellipsis(EllipsisSide.INLINE_END),
        )
        val layout = assertIs<ParagraphLayoutResult.Success>(result)
        val truncation = assertNotNull(layout.truncation)
        assertEquals(EllipsisSide.INLINE_END, truncation.side)
        assertTrue(truncation.hiddenRange.start == fixture.textIndex(1))
        assertEquals(truncation.hiddenRange.endExclusive, fixture.snapshot.range.endExclusive)
        assertTrue(truncation.anchor == fixture.textIndex(1))

        val line = layout.layout.lines.single()
        val marker = line.glyphs().single { glyph ->
            val provenance = glyph.provenance
            provenance is GlyphProvenance.Synthetic && provenance.role == GlyphProvenanceRole.ELLIPSIS
        }
        assertEquals(2_825, marker.shapedGlyph.glyphId.value)
        assertEquals(710.83984f, marker.origin.x.value)
        // No document position was created: one boundary per scalar remains.
        assertEquals(fixture.snapshot.scalars.size + 1, line.allCaretCandidates.size)
    }

    @Test
    fun middleEllipsisHidesTheMiddleAndKeepsExplicitSourceRelations() {
        val fixture = dejavuFixture("one two three four five six seven eight")

        val result = layout(
            fixture,
            constraints(width = 3_100f, top = 50f, height = 1_200f),
            language = "en",
            overflowPolicy = OverflowPolicy.Ellipsis(EllipsisSide.MIDDLE),
        )
        val layout = assertIs<ParagraphLayoutResult.Success>(result)
        val truncation = assertNotNull(layout.truncation)
        assertEquals(EllipsisSide.MIDDLE, truncation.side)
        assertEquals(fixture.textIndex(2), truncation.anchor)
        assertTrue(truncation.hiddenRange.start == fixture.textIndex(2))
        assertTrue(truncation.hiddenRange.endExclusive < fixture.snapshot.range.endExclusive)

        val line = layout.layout.lines.single()
        val markers = line.glyphs().filter { glyph ->
            val provenance = glyph.provenance
            provenance is GlyphProvenance.Synthetic && provenance.role == GlyphProvenanceRole.ELLIPSIS
        }
        assertEquals(1, markers.size)
        assertEquals(2_825, markers.single().shapedGlyph.glyphId.value)
        assertEquals(1_345.6055f, markers.single().origin.x.value)
        // The visible prefix is "on", the visible suffix starts with the final 't'.
        assertEquals(listOf(0x6F, 0x6E), fixture.snapshot.scalarValues(fixture.range(0, 2)))
        assertEquals(listOf(0x74), fixture.snapshot.scalarValues(fixture.range(38, 39)))
        assertEquals(fixture.snapshot.scalars.size + 1, line.allCaretCandidates.size)
    }

    @Test
    fun bidiEllipsisKeepsOneSyntheticMarkerAndCompleteCarets() {
        val fixture = dejavuFixture("abc \u05D0\u05D1\u05D2 abc")

        val result = layout(
            fixture,
            constraints(width = 1_700f, top = 50f, height = 1_200f),
            language = "en",
            baseDirection = BaseDirection.RIGHT_TO_LEFT,
            overflowPolicy = OverflowPolicy.Ellipsis(EllipsisSide.INLINE_END),
        )
        val layout = assertIs<ParagraphLayoutResult.Success>(result)
        val truncation = assertNotNull(layout.truncation)
        assertEquals(EllipsisSide.INLINE_END, truncation.side)
        val line = layout.layout.lines.single()
        val markers = line.glyphs().filter { glyph ->
            val provenance = glyph.provenance
            provenance is GlyphProvenance.Synthetic && provenance.role == GlyphProvenanceRole.ELLIPSIS
        }
        assertEquals(1, markers.size)
        // BiDi direction joins legitimately duplicate candidates at shared boundaries; the
        // synthetic marker never introduces a caret itself.
        assertEquals(15, line.allCaretCandidates.size)
    }

    @Test
    fun inlineObjectAdvancesThePenAndKeepsDocumentaryEditingSemantics() {
        val fixture = dejavuFixture("a\uFFFCb")
        val objectId = InlineObjectId.create("photo")
        val definition = InlineObjectDefinition(
            id = objectId,
            width = LayoutUnit(400f),
            height = LayoutUnit(300f),
            baselineOffset = LayoutUnit(250f),
            alignment = InlineObjectAlignment.BASELINE,
        )
        val objectIndex = fixture.textIndex(1)
        val layout = layoutParagraph(
            fixture,
            constraints(width = 2_000f, top = 50f, height = 1_200f),
            language = "en",
            inlineObjects = InlineObjectSnapshot(listOf(InlineObjectEntry(objectIndex, definition))),
        )

        val line = layout.lines.single()
        assertEquals(listOf(0x61, 0xFFFC, 0x62), fixture.snapshot.scalars)
        val placed = line.positionedInlineObjects.single()
        assertEquals(objectId, placed.definition.id)
        assertEquals(
            LayoutRect(LayoutUnit(712.79297f), LayoutUnit(700f), LayoutUnit(1_112.793f), LayoutUnit(1_000f)),
            placed.rect,
        )
        // Caret traverses the object boundaries exactly: three scalars, four boundaries.
        assertEquals(4, line.allCaretCandidates.size)
        // Hit testing at the object center returns the upstream object boundary candidate.
        val candidate = layout.hitTest(LayoutPoint(LayoutUnit(912.79297f), LayoutUnit(950f)))
        assertEquals(fixture.textIndex(1), candidate.position.index)
        // Selection across the object includes its rectangle.
        val selection = layout.selectionGeometry(
            line.allCaretCandidates.first().position,
            line.allCaretCandidates.last().position,
        )
        assertTrue(selection.any { rectangle ->
            rectangle.left.value == 712.79297f && rectangle.right.value == 1_112.793f
        })
    }

    @Test
    fun finalGlyphsAreRecertifiedAfterKashidaTransform() {
        val fixture = amiriFixture("\u0627\u0644\u0633\u0644\u0627\u0645 \u0645\u0646 \u0627\u0644\u0633\u0644\u0627\u0645")
        val resolver = assertIs<FontOperationResult.Success<org.graphiks.kalligraphie.api.FontAssetResolverHandle>>(
            fixture.catalog.openAssetResolver(),
        ).value
        try {
            val result = layout(
                fixture,
                constraints(width = 5_200f, top = 50f, height = 2_400f),
                language = "ar",
                positioning = ParagraphPositioningPolicy(
                    alignment = ParagraphAlignment.JUSTIFY,
                    justificationMode = JustificationMode.KASHIDA,
                ),
                materialization = org.graphiks.kalligraphie.api.EditableLineMaterialization.Renderable(
                    resolver = resolver,
                    variant = org.graphiks.kalligraphie.api.FontRenderVariantKey.default,
                    outlineProfile = OUTLINE_PROFILE,
                ),
            )
            val paragraph = assertIs<ParagraphLayoutResult.Success>(result)
            val kashidas = paragraph.layout.lines.flatMap { line -> line.glyphs() }.filter { glyph ->
                val provenance = glyph.provenance
                provenance is GlyphProvenance.Synthetic && provenance.role == GlyphProvenanceRole.KASHIDA
            }
            assertTrue(kashidas.isNotEmpty())
            kashidas.forEach { glyph ->
                val certificate = assertNotNull(glyph.materializationCertificate)
                assertEquals(glyph.shapedGlyph.glyphId, certificate.glyphId)
                assertEquals(GlyphMaterializationRoute.OUTLINE, certificate.route)
                assertEquals(glyph.renderAssetKey, certificate.assetKey)
            }
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(resolver.close())
        }
    }

    @Test
    fun incrementalPublicationEqualsFullLayoutAfterAHyphenationAffectingEdit() {
        val session = openJourneySession()
        try {
            val source = incrementalRealFontFixture(
                "hyphenation",
                fonts = listOf(IncrementalFontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans")),
            )
            val target = source.withText("hyphenator")
            val changeResult2 = TextChangeSet.create(
                source.snapshot,
                target.snapshot,
                listOf(TextChange(range(source.snapshot, 6, 11), range(target.snapshot, 6, 10))),
            )
            val changeSet = assertIs<org.graphiks.kalligraphie.api.LayoutContractResult.Success<TextChangeSet>>(changeResult2).value

            val validated = assertIs<org.graphiks.kalligraphie.api.LayoutContractResult.Success<org.graphiks.kalligraphie.api.IncrementalLayoutRequest>>(
                org.graphiks.kalligraphie.api.createIncrementalLayoutRequest(
                    input = org.graphiks.kalligraphie.api.LayoutInput(
                        text = target.snapshot,
                        typography = source.typography,
                    ),
                    requestedRange = target.snapshot.range,
                    constraints = incrementalTestConstraints(width = 4_300f, height = 2_400f),
                    overscan = org.graphiks.kalligraphie.api.LineOverscan(0),
                    previousState = null,
                    delta = null,
                    cancellationToken = org.graphiks.kalligraphie.api.CancellationToken.none,
                ),
            ).value
            val incremental = assertIs<org.graphiks.kalligraphie.api.IncrementalLayoutResult.Success>(
                session.layout(
                    JvmIncrementalParagraphLayoutRequest(
                        request = validated,
                        baseDirection = BaseDirection.LEFT_TO_RIGHT,
                        language = "en",
                        hyphenationMode = HyphenationMode.AUTO,
                        hyphenationService = JvmPatternHyphenationService.english(),
                    ),
                ),
            )

            val incrementalLines = incremental.layout.lines
            val fullLines = layoutParagraph(
                journify(target.snapshot, source),
                constraints(width = 4_300f, top = 50f, height = 2_400f),
                language = "en",
                hyphenationMode = HyphenationMode.AUTO,
                hyphenationService = JvmPatternHyphenationService.english(),
            ).lines
            assertEquals(fullLines.map(::lineFingerprint), incrementalLines.map(::lineFingerprint))
        } finally {
            session.close()
        }
    }

    private fun journify(
        snapshot: TextSnapshot,
        source: IncrementalRealFontFixture,
    ): JourneyFixture = JourneyFixture(
        snapshot,
        source.catalog,
        source.policy,
    )

    private fun lineFingerprint(line: LineLayout): List<Any> = listOf(
        line.range,
        line.baseline,
        line.contentMetrics,
        line.lineBox,
        line.positionedGlyphRuns.flatMap { run ->
            run.glyphs.map { glyph -> glyph.shapedGlyph.glyphId to glyph.origin to glyph.provenance }
        },
        line.allCaretCandidates.map { candidate -> candidate.position to candidate.geometry },
        line.positionedInlineObjects.map { obj -> obj.sourceRange to obj.rect },
    )

    private fun openJourneySession(): JvmIncrementalParagraphLayoutSession =
        assertIs<FontOperationResult.Success<JvmIncrementalParagraphLayoutSession>>(
            JvmIncrementalParagraphLayoutSession.open(),
        ).value

    private fun range(snapshot: TextSnapshot, start: Int, endExclusive: Int): TextRange = TextRange(
        snapshot.textIndexAtScalarBoundary(start),
        snapshot.textIndexAtScalarBoundary(endExclusive),
    )

    private fun layoutLine(
        fixture: JourneyFixture,
        constraints: HorizontalParagraphConstraints,
        language: String,
    ): LineLayout = layoutParagraph(fixture, constraints, language).lines.single()

    private fun layoutParagraph(
        fixture: JourneyFixture,
        constraints: HorizontalParagraphConstraints,
        language: String,
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        positioning: ParagraphPositioningPolicy = ParagraphPositioningPolicy(),
        hyphenationMode: HyphenationMode = HyphenationMode.MANUAL,
        hyphenationService: org.graphiks.kalligraphie.api.HyphenationService? = null,
        inlineObjects: InlineObjectSnapshot? = null,
        overflowPolicy: OverflowPolicy = OverflowPolicy.Continue,
        materialization: org.graphiks.kalligraphie.api.EditableLineMaterialization =
            org.graphiks.kalligraphie.api.EditableLineMaterialization.LayoutOnly,
    ): org.graphiks.kalligraphie.api.ParagraphLayout {
        val result = layout(
            fixture,
            constraints,
            language,
            baseDirection,
            positioning,
            hyphenationMode,
            hyphenationService,
            inlineObjects,
            overflowPolicy,
            materialization,
        )
        return assertIs<ParagraphLayoutResult.Success>(result).layout
    }

    private fun layout(
        fixture: JourneyFixture,
        constraints: HorizontalParagraphConstraints,
        language: String,
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        positioning: ParagraphPositioningPolicy = ParagraphPositioningPolicy(),
        hyphenationMode: HyphenationMode = HyphenationMode.MANUAL,
        hyphenationService: org.graphiks.kalligraphie.api.HyphenationService? = null,
        inlineObjects: InlineObjectSnapshot? = null,
        overflowPolicy: OverflowPolicy = OverflowPolicy.Continue,
        materialization: org.graphiks.kalligraphie.api.EditableLineMaterialization =
            org.graphiks.kalligraphie.api.EditableLineMaterialization.LayoutOnly,
    ): ParagraphLayoutResult = JvmEditableParagraphFacade.layout(
        JvmEditableParagraphFacadeRequest(
            snapshot = fixture.snapshot,
            constraints = constraints,
            baseDirection = baseDirection,
            language = language,
            fontCatalog = fixture.catalog,
            resolutionPolicy = fixture.policy,
            fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
            materialization = materialization,
            positioning = positioning,
            hyphenationMode = hyphenationMode,
            hyphenationService = hyphenationService,
            inlineObjects = inlineObjects,
            overflowPolicy = overflowPolicy,
        ),
    )

    private fun dejavuFixture(value: String): JourneyFixture = jornedFixture("dejavu/DejaVuSans.ttf", value)

    private fun amiriFixture(value: String): JourneyFixture = jornedFixture("amiri/Amiri-Regular.ttf", value)

    private fun jornedFixture(font: String, value: String): JourneyFixture {
        val sources = listOf(FontSource(fixtureBytes(font), FontSourceProvenance("journey fixture")))
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            Kalligraphie.embedded(sources),
        ).value
        val faces = sources.map { source -> FontFaceId(source.id, 0) }
        val policy = FontResolutionPolicySnapshot(
            generation = catalog.generation,
            policyId = "journey-advanced-typography",
            version = "1",
            candidates = faces.map(::FontResolutionCandidate),
            lastResortFace = faces.last(),
        )
        val snapshot = Kalligraphie.decodeUtf16(
            TextVersion.create(),
            listOf(TextSlice.Utf16(value.toCharArray())),
        ).snapshot
        return JourneyFixture(snapshot, catalog, policy)
    }

    private fun constraints(
        width: Float,
        top: Float,
        height: Float,
    ): HorizontalParagraphConstraints = HorizontalParagraphConstraints(
        region = LayoutRect(LayoutUnit(100f), LayoutUnit(top), LayoutUnit(100f + width), LayoutUnit(top + height)),
        lineMetrics = LineVerticalMetrics(LayoutUnit(900f), LayoutUnit(300f)),
    )

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

    private fun firstGlyphOfRange(
        fixture: JourneyFixture,
        line: LineLayout,
        expectedRange: TextRange,
    ): org.graphiks.kalligraphie.api.PositionedGlyph = assertNotNull(
        line.glyphs().firstOrNull { glyph ->
            glyph.sourceClusters.any { cluster -> cluster.sourceRange == expectedRange }
        },
    )

    private class JourneyFixture(
        val snapshot: TextSnapshot,
        val catalog: FontCatalogSnapshot,
        val policy: FontResolutionPolicySnapshot,
    ) {
        fun textIndex(ordinal: Int): org.graphiks.kalligraphie.api.TextIndex = snapshot.textIndexAtScalarBoundary(ordinal)
        fun range(start: Int, endExclusive: Int): TextRange = TextRange(textIndex(start), textIndex(endExclusive))
        fun textOf(range: TextRange): String =
            snapshot.scalarValues(range).joinToString("") { it.toChar().toString() }
    }

    private fun LineLayout.glyphs(): List<org.graphiks.kalligraphie.api.PositionedGlyph> =
        positionedGlyphRuns.flatMap { run -> run.glyphs }

    private companion object {
        val OUTLINE_PROFILE = org.graphiks.kalligraphie.api.OutlineProfile(
            maxBytes = 1 shl 18,
            maxContours = 2048,
            maxPoints = 8192,
            maxCompositeDepth = 8,
            maxCompositeComponents = 64,
        )
    }
}