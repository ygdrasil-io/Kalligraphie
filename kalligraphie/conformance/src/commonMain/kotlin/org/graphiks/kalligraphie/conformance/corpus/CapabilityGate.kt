package org.graphiks.kalligraphie.conformance.corpus

import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.conformance.PortableCapabilityIdentity

/**
 * Returns the first deterministic absence diagnostic among the scenario's required
 * capabilities, in capability-name order, or null when every required capability is available.
 */
public fun PortableCapabilityIdentity.blockingDiagnostic(scenario: ConformanceScenario): FontDiagnostic? =
    scenario.requiredCapabilities
        .sortedBy { it.name }
        .firstNotNullOfOrNull { absenceDiagnostic(it) }
