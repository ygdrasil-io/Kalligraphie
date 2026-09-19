package org.graphiks.kalligraphie.conformance.corpus

import org.graphiks.kalligraphie.conformance.CAPABILITY_ABSENCE_DIAGNOSTIC_CODE
import org.graphiks.kalligraphie.conformance.CapabilityDeclaration
import org.graphiks.kalligraphie.conformance.PortableCapability
import org.graphiks.kalligraphie.conformance.PortableCapabilityIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapabilityGatedCorpusTest {
    private fun identity(vararg available: PortableCapability): PortableCapabilityIdentity =
        PortableCapabilityIdentity(
            platformId = "portable-test",
            declarations = PortableCapability.entries.map { capability ->
                CapabilityDeclaration(
                    capability = capability,
                    available = capability in available,
                    profileId = "profile-${capability.name.lowercase()}",
                )
            },
        )

    private val shapingScenario = ConformanceScenario(
        id = "shaping",
        description = "A scenario requiring glyph shaping.",
        kind = ConformanceScenarioKind.DECODING,
        requiredCapabilities = setOf(PortableCapability.SHAPING),
    )

    @Test
    fun reportsAnAbsenceDiagnosticWhenARequiredCapabilityIsUnavailable() {
        val diagnostic = identity().blockingDiagnostic(shapingScenario)

        assertEquals(CAPABILITY_ABSENCE_DIAGNOSTIC_CODE, diagnostic?.code)
        assertTrue(diagnostic != null && diagnostic.message.contains(PortableCapability.SHAPING.name))
    }

    @Test
    fun reportsNoDiagnosticWhenEveryRequiredCapabilityIsAvailable() {
        assertNull(identity(PortableCapability.SHAPING).blockingDiagnostic(shapingScenario))
    }

    @Test
    fun reportsNoDiagnosticWhenNoCapabilityIsRequired() {
        val scenario = ConformanceScenario(
            id = "requirement-free",
            description = "A scenario without required capabilities.",
            kind = ConformanceScenarioKind.DECODING,
        )

        assertNull(identity().blockingDiagnostic(scenario))
    }

    @Test
    fun reportsTheFirstAbsentCapabilityInCapabilityNameOrder() {
        val scenario = ConformanceScenario(
            id = "analysis-and-shaping",
            description = "A scenario requiring text analysis and shaping.",
            kind = ConformanceScenarioKind.DECODING,
            requiredCapabilities = setOf(PortableCapability.UNICODE_ANALYSIS, PortableCapability.SHAPING),
        )

        val diagnostic = identity().blockingDiagnostic(scenario)

        assertEquals(CAPABILITY_ABSENCE_DIAGNOSTIC_CODE, diagnostic?.code)
        assertTrue(diagnostic != null && diagnostic.message.contains(PortableCapability.SHAPING.name))
        assertTrue(diagnostic != null && !diagnostic.message.contains(PortableCapability.UNICODE_ANALYSIS.name))
    }

    @Test
    fun reportsTheAlphabeticallyFirstAbsentCapabilityAcrossDistinctNames() {
        val scenario = ConformanceScenario(
            id = "layout-and-shaping",
            description = "A scenario requiring end-to-end layout and shaping.",
            kind = ConformanceScenarioKind.DECODING,
            requiredCapabilities = setOf(PortableCapability.SHAPING, PortableCapability.END_TO_END_LAYOUT),
        )

        val diagnostic = identity().blockingDiagnostic(scenario)

        assertEquals(CAPABILITY_ABSENCE_DIAGNOSTIC_CODE, diagnostic?.code)
        assertTrue(diagnostic != null && diagnostic.message.contains(PortableCapability.END_TO_END_LAYOUT.name))
    }
}
