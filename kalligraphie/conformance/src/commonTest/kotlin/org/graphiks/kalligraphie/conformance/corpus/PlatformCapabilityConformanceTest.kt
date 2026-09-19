package org.graphiks.kalligraphie.conformance.corpus

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.conformance.CAPABILITY_ABSENCE_DIAGNOSTIC_CODE
import org.graphiks.kalligraphie.conformance.PortableCapability
import org.graphiks.kalligraphie.conformance.currentPortableCapabilityIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformCapabilityConformanceTest {
    private fun expectedAvailability(platformId: String): Map<PortableCapability, Boolean> = when (platformId) {
        "jvm" -> PortableCapability.entries.associateWith { true }
        "ios", "android" -> mapOf(
            PortableCapability.UNICODE_ANALYSIS to false,
            PortableCapability.SHAPING to false,
            PortableCapability.END_TO_END_LAYOUT to false,
            PortableCapability.GLYPH_REPRESENTATION_VARIANTS to true,
        )
        else -> error("Unexpected platform identity: $platformId")
    }

    @Test
    fun declaresTheExpectedCapabilityMatrix() {
        val identity = currentPortableCapabilityIdentity()
        val expected = expectedAvailability(identity.platformId)
        assertEquals(expected.keys, PortableCapability.entries.toSet())
        PortableCapability.entries.forEach { capability ->
            assertEquals(expected.getValue(capability), identity.presenceOf(capability), capability.name)
        }
    }

    @Test
    fun gatesShapingExactlyWhenItIsUnavailable() {
        val scenario = ConformanceScenario(
            "shaping-gate",
            "Requires shaping.",
            ConformanceScenarioKind.DECODING,
            setOf(PortableCapability.SHAPING),
        )
        val identity = currentPortableCapabilityIdentity()
        val expectedAvailable = expectedAvailability(identity.platformId).getValue(PortableCapability.SHAPING)
        assertEquals(expectedAvailable, identity.presenceOf(PortableCapability.SHAPING))
        val diagnostic = identity.blockingDiagnostic(scenario)
        assertEquals(expectedAvailable, diagnostic == null)
        if (!expectedAvailable) {
            assertEquals(CAPABILITY_ABSENCE_DIAGNOSTIC_CODE, diagnostic?.code)
        }
    }

    @Test
    fun decodingScenariosAlwaysExecute() {
        val identity = currentPortableCapabilityIdentity()
        val scenario = ConformanceScenario("ascii", "One ASCII scalar.", ConformanceScenarioKind.DECODING)
        assertNull(identity.blockingDiagnostic(scenario))
        val snapshot = Kalligraphie.decodeUtf8(
            TextVersion.create(),
            listOf(TextSlice.Utf8("A".encodeToByteArray())),
        ).snapshot
        assertEquals(listOf(0x41), snapshot.scalars)
    }
}
