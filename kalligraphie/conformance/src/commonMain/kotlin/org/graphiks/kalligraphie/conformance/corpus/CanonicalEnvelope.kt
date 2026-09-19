package org.graphiks.kalligraphie.conformance.corpus

/**
 * Versioned, deterministic text encoding of a [ConformanceObservation].
 *
 * The encoding is line based and uses a fixed schema version so two producers can be compared
 * byte for byte. It intentionally avoids any serialization dependency.
 */
public object CanonicalEnvelope {
    /** Schema version of the encoded envelope. */
    public const val SCHEMA_VERSION: Int = 1

    /** Encodes [observation] into its canonical envelope. */
    public fun encode(observation: ConformanceObservation): String = buildString {
        append("schema=").append(SCHEMA_VERSION).appendLine()
        append("scenario=").append(observation.scenarioId).appendLine()
        append("capabilities=").append(observation.capabilityFingerprint).appendLine()
        append("scalars=").append(observation.scalars.joinToString(",")).appendLine()
        append("widths=").append(observation.sourceUnitWidths.joinToString(",")).appendLine()
        append("diagnostics=").append(observation.diagnosticCodes.joinToString(","))
    }
}
