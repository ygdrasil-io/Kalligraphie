package org.graphiks.kalligraphie.conformance.corpus

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.TextDecodingResult
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.conformance.PortableCapability
import org.graphiks.kalligraphie.conformance.currentPortableCapabilityIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortableDivergenceTest {
    private val version = TextVersion.create()

    private val capabilityFingerprint = currentPortableCapabilityIdentity().canonicalFingerprint()

    private val expectedEnvelopes: Map<String, String> = mapOf(
        "ascii" to
            """scenario=ascii
scalars=65
widths=1
diagnostics=""",
        "multibyte-utf8" to
            """scenario=multibyte-utf8
scalars=65,233,128512
widths=1,2,4
diagnostics=""",
        "surrogate-utf16" to
            """scenario=surrogate-utf16
scalars=65,128512
widths=1,2
diagnostics=""",
        "malformed-utf8" to
            """scenario=malformed-utf8
scalars=65533,40
widths=1,1
diagnostics=text.malformed-utf8""",
    )

    private fun decode(scenarioId: String): TextDecodingResult = when (scenarioId) {
        "ascii" -> Kalligraphie.decodeUtf8(version, listOf(TextSlice.Utf8("A".encodeToByteArray())))
        "multibyte-utf8" ->
            Kalligraphie.decodeUtf8(version, listOf(TextSlice.Utf8("A\u00E9\uD83D\uDE00".encodeToByteArray())))
        "surrogate-utf16" ->
            Kalligraphie.decodeUtf16(version, listOf(TextSlice.Utf16("A\uD83D\uDE00".toCharArray())))
        "malformed-utf8" ->
            Kalligraphie.decodeUtf8(version, listOf(TextSlice.Utf8(byteArrayOf(0xC3.toByte(), 0x28))))
        else -> error("Unknown standard scenario: $scenarioId")
    }

    private fun observableEnvelope(scenario: ConformanceScenario): String {
        val result = decode(scenario.id)
        return ConformanceObservation(
            scenarioId = scenario.id,
            capabilityFingerprint = capabilityFingerprint,
            scalars = result.snapshot.scalars,
            sourceUnitWidths = result.snapshot.sourceRanges.map { it.endExclusive.value - it.start.value },
            diagnosticCodes = result.diagnostics.map { it.code },
        ).observableEnvelope()
    }

    @Test
    fun observablesArePlatformInvariant() {
        assertEquals(StandardConformanceCorpus.scenarios.map { it.id }.toSet(), expectedEnvelopes.keys)
        StandardConformanceCorpus.scenarios.forEach { scenario ->
            assertEquals(expectedEnvelopes.getValue(scenario.id), observableEnvelope(scenario), scenario.id)
        }
    }

    @Test
    fun documentsEveryCapabilityDivergence() {
        val absentByPlatform = mapOf(
            "ios" to setOf(
                PortableCapability.UNICODE_ANALYSIS,
                PortableCapability.SHAPING,
                PortableCapability.END_TO_END_LAYOUT,
            ),
            "android" to setOf(
                PortableCapability.UNICODE_ANALYSIS,
                PortableCapability.SHAPING,
                PortableCapability.END_TO_END_LAYOUT,
            ),
        )
        val documented = ReferenceDivergenceRecord.divergences
            .map { it.platformId to it.capability }
            .toSet()
        absentByPlatform.forEach { (platformId, capabilities) ->
            capabilities.forEach { capability ->
                assertTrue(
                    documented.contains(platformId to capability),
                    "Missing divergence for $platformId $capability",
                )
            }
        }
        assertEquals(absentByPlatform.values.sumOf { it.size }, documented.size)
        assertTrue(ReferenceDivergenceRecord.divergences.all { it.justification.isNotBlank() })
    }
}
