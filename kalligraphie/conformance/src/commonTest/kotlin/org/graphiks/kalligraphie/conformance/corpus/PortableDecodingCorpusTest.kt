package org.graphiks.kalligraphie.conformance.corpus

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.TextDecodingResult
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.conformance.CapabilityDeclaration
import org.graphiks.kalligraphie.conformance.PortableCapability
import org.graphiks.kalligraphie.conformance.PortableCapabilityIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortableDecodingCorpusTest {
    private val version = TextVersion.create()

    private val capabilityFingerprint = PortableCapabilityIdentity(
        platformId = "portable-test",
        declarations = PortableCapability.entries.map { capability ->
            CapabilityDeclaration(capability, available = true, profileId = "profile-${capability.name.lowercase()}")
        },
    ).canonicalFingerprint()

    private val asciiScenario = ConformanceScenario(
        id = "ascii",
        description = "Decodes one ASCII scalar from a single UTF-8 byte.",
        kind = ConformanceScenarioKind.DECODING,
    )

    private val multibyteUtf8Scenario = ConformanceScenario(
        id = "multibyte-utf8",
        description = "Decodes ASCII, two-byte and four-byte UTF-8 scalars into logical order.",
        kind = ConformanceScenarioKind.DECODING,
    )

    private val surrogateUtf16Scenario = ConformanceScenario(
        id = "surrogate-utf16",
        description = "Decodes an ASCII unit and a UTF-16 surrogate pair into scalars.",
        kind = ConformanceScenarioKind.DECODING,
    )

    private val malformedUtf8Scenario = ConformanceScenario(
        id = "malformed-utf8",
        description = "Replaces malformed UTF-8 with U+FFFD and emits a diagnostic.",
        kind = ConformanceScenarioKind.DECODING,
    )

    private fun observation(scenario: ConformanceScenario, result: TextDecodingResult): ConformanceObservation =
        ConformanceObservation(
            scenarioId = scenario.id,
            capabilityFingerprint = capabilityFingerprint,
            scalars = result.snapshot.scalars,
            sourceUnitWidths = result.snapshot.sourceRanges.map { it.endExclusive.value - it.start.value },
            diagnosticCodes = result.diagnostics.map { it.code },
        )

    private fun assertStableEnvelope(observation: ConformanceObservation) {
        assertEquals(capabilityFingerprint, observation.capabilityFingerprint)
        val once = CanonicalEnvelope.encode(observation)
        assertEquals(once, CanonicalEnvelope.encode(observation))
        assertTrue(once.startsWith("schema=1\nscenario=${observation.scenarioId}\n"))
    }

    @Test
    fun asciiUtf8ProducesOneScalarOfOneSourceUnit() {
        val observation = observation(
            asciiScenario,
            Kalligraphie.decodeUtf8(version, listOf(TextSlice.Utf8("A".encodeToByteArray()))),
        )

        assertEquals("ascii", observation.scenarioId)
        assertEquals(listOf(0x41), observation.scalars)
        assertEquals(listOf(1), observation.sourceUnitWidths)
        assertEquals(emptyList(), observation.diagnosticCodes)
        assertStableEnvelope(observation)
    }

    @Test
    fun multibyteUtf8ProducesOrderedScalarsAndWidths() {
        val observation = observation(
            multibyteUtf8Scenario,
            Kalligraphie.decodeUtf8(
                version,
                listOf(TextSlice.Utf8("A\u00E9\uD83D\uDE00".encodeToByteArray())),
            ),
        )

        assertEquals("multibyte-utf8", observation.scenarioId)
        assertEquals(listOf(0x41, 0xE9, 0x1F600), observation.scalars)
        assertEquals(listOf(1, 2, 4), observation.sourceUnitWidths)
        assertEquals(emptyList(), observation.diagnosticCodes)
        assertStableEnvelope(observation)
    }

    @Test
    fun surrogateUtf16ProducesOneScalarOfTwoSourceUnits() {
        val observation = observation(
            surrogateUtf16Scenario,
            Kalligraphie.decodeUtf16(version, listOf(TextSlice.Utf16("A\uD83D\uDE00".toCharArray()))),
        )

        assertEquals("surrogate-utf16", observation.scenarioId)
        assertEquals(listOf(0x41, 0x1F600), observation.scalars)
        assertEquals(listOf(1, 2), observation.sourceUnitWidths)
        assertEquals(emptyList(), observation.diagnosticCodes)
        assertStableEnvelope(observation)
    }

    @Test
    fun malformedUtf8ProducesReplacementScalarsAndADiagnosticCode() {
        val observation = observation(
            malformedUtf8Scenario,
            Kalligraphie.decodeUtf8(version, listOf(TextSlice.Utf8(byteArrayOf(0xC3.toByte(), 0x28)))),
        )

        assertEquals("malformed-utf8", observation.scenarioId)
        assertEquals(listOf(0xFFFD, 0x28), observation.scalars)
        assertEquals(listOf(1, 1), observation.sourceUnitWidths)
        assertEquals(listOf("text.malformed-utf8"), observation.diagnosticCodes)
        assertStableEnvelope(observation)
    }
}
