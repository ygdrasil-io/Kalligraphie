package org.graphiks.kalligraphie.conformance.corpus

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.conformance.CapabilityDeclaration
import org.graphiks.kalligraphie.conformance.ComparisonClass
import org.graphiks.kalligraphie.conformance.ComparisonQuantity
import org.graphiks.kalligraphie.conformance.PortableCapability
import org.graphiks.kalligraphie.conformance.PortableCapabilityIdentity
import org.graphiks.kalligraphie.conformance.declaredClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReferenceOracleTest {
    private val version = TextVersion.create()

    private val referenceIdentity = PortableCapabilityIdentity(
        platformId = "reference",
        declarations = PortableCapability.entries.map { capability ->
            CapabilityDeclaration(capability, available = true, profileId = "reference")
        },
    )

    private val expectedEnvelopes: Map<String, String> = mapOf(
        "ascii" to
            """schema=1
scenario=ascii
capabilities=reference|END_TO_END_LAYOUT:true:reference|GLYPH_REPRESENTATION_VARIANTS:true:reference|SHAPING:true:reference|UNICODE_ANALYSIS:true:reference
scalars=65
widths=1
diagnostics=""",
        "multibyte-utf8" to
            """schema=1
scenario=multibyte-utf8
capabilities=reference|END_TO_END_LAYOUT:true:reference|GLYPH_REPRESENTATION_VARIANTS:true:reference|SHAPING:true:reference|UNICODE_ANALYSIS:true:reference
scalars=65,233,128512
widths=1,2,4
diagnostics=""",
        "surrogate-utf16" to
            """schema=1
scenario=surrogate-utf16
capabilities=reference|END_TO_END_LAYOUT:true:reference|GLYPH_REPRESENTATION_VARIANTS:true:reference|SHAPING:true:reference|UNICODE_ANALYSIS:true:reference
scalars=65,128512
widths=1,2
diagnostics=""",
        "malformed-utf8" to
            """schema=1
scenario=malformed-utf8
capabilities=reference|END_TO_END_LAYOUT:true:reference|GLYPH_REPRESENTATION_VARIANTS:true:reference|SHAPING:true:reference|UNICODE_ANALYSIS:true:reference
scalars=65533,40
widths=1,1
diagnostics=text.malformed-utf8""",
    )

    private fun execute(scenarioId: String): ConformanceObservation {
        val result = when (scenarioId) {
            "ascii" -> Kalligraphie.decodeUtf8(version, listOf(TextSlice.Utf8("A".encodeToByteArray())))
            "multibyte-utf8" ->
                Kalligraphie.decodeUtf8(version, listOf(TextSlice.Utf8("A\u00E9\uD83D\uDE00".encodeToByteArray())))
            "surrogate-utf16" ->
                Kalligraphie.decodeUtf16(version, listOf(TextSlice.Utf16("A\uD83D\uDE00".toCharArray())))
            "malformed-utf8" ->
                Kalligraphie.decodeUtf8(version, listOf(TextSlice.Utf8(byteArrayOf(0xC3.toByte(), 0x28))))
            else -> error("Unknown standard scenario: $scenarioId")
        }
        return ConformanceObservation(
            scenarioId = scenarioId,
            capabilityFingerprint = referenceIdentity.canonicalFingerprint(),
            scalars = result.snapshot.scalars,
            sourceUnitWidths = result.snapshot.sourceRanges.map { it.endExclusive.value - it.start.value },
            diagnosticCodes = result.diagnostics.map { it.code },
        )
    }

    @Test
    fun oracleCoversEveryStandardScenario() {
        assertEquals(StandardConformanceCorpus.scenarios.map { it.id }.toSet(), expectedEnvelopes.keys)
    }

    @Test
    fun reproducesEveryExpectedEnvelope() {
        StandardConformanceCorpus.scenarios.forEach { scenario ->
            assertEquals(expectedEnvelopes.getValue(scenario.id), CanonicalEnvelope.encode(execute(scenario.id)))
        }
    }

    @Test
    fun decodingConformanceIsBitIdenticalAndNeedsNoTolerance() {
        // Decoding conformance carries no floating quantity: every observable is an integer
        // (a scalar value or a source-unit width) or a typed diagnostic code, so it is
        // compared bit-identically and contributes no entry to the tolerance catalog.
        assertEquals(ComparisonClass.BIT_IDENTICAL, ComparisonQuantity.CODEPOINT_BOUNDARY.declaredClass())
        assertEquals(ComparisonClass.BIT_IDENTICAL, ComparisonQuantity.DIAGNOSTIC_ORDER.declaredClass())
        // The reference corpus requires no numeric tolerance while decoding results are bit-identical.
        assertTrue(ReferenceToleranceCatalog.tolerances.isEmpty())
    }
}
