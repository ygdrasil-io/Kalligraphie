package org.graphiks.kalligraphie.conformance.corpus

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.TextDecodingResult
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.conformance.CAPABILITY_ABSENCE_DIAGNOSTIC_CODE
import org.graphiks.kalligraphie.conformance.PortableCapability
import org.graphiks.kalligraphie.conformance.currentPortableCapabilityIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PlatformCapabilityConformanceTest {
    private val version = TextVersion.create()

    private fun observation(scenario: ConformanceScenario, result: TextDecodingResult): ConformanceObservation =
        ConformanceObservation(
            scenarioId = scenario.id,
            capabilityFingerprint = currentPortableCapabilityIdentity().canonicalFingerprint(),
            scalars = result.snapshot.scalars,
            sourceUnitWidths = result.snapshot.sourceRanges.map { it.endExclusive.value - it.start.value },
            diagnosticCodes = result.diagnostics.map { it.code },
        )

    @Test
    fun declaresEveryCapability() {
        val identity = currentPortableCapabilityIdentity()

        for (capability in PortableCapability.entries) {
            assertNotNull(identity.presenceOf(capability))
        }
    }

    @Test
    fun gatesShapingExactlyWhenItIsUnavailable() {
        val scenario = ConformanceScenario(
            id = "shaping-gate",
            description = "Requires shaping.",
            kind = ConformanceScenarioKind.DECODING,
            requiredCapabilities = setOf(PortableCapability.SHAPING),
        )
        val identity = currentPortableCapabilityIdentity()

        val diagnostic = identity.blockingDiagnostic(scenario)

        assertEquals(
            identity.presenceOf(PortableCapability.SHAPING) == true,
            diagnostic == null,
        )
        if (diagnostic != null) {
            assertEquals(CAPABILITY_ABSENCE_DIAGNOSTIC_CODE, diagnostic.code)
        }
    }

    @Test
    fun decodingScenariosAlwaysExecute() {
        val scenario = ConformanceScenario(
            id = "ascii",
            description = "Decodes one ASCII scalar from a single UTF-8 byte.",
            kind = ConformanceScenarioKind.DECODING,
        )

        val observation = observation(
            scenario,
            Kalligraphie.decodeUtf8(version, listOf(TextSlice.Utf8("A".encodeToByteArray()))),
        )

        assertEquals(listOf(0x41), observation.scalars)
    }
}
