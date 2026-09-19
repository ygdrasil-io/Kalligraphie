package org.graphiks.kalligraphie.conformance.corpus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CanonicalEnvelopeTest {
    private val observation = ConformanceObservation(
        scenarioId = "decoding-ascii",
        capabilityFingerprint = "jvm|SHAPING:true:profile-s",
        scalars = listOf(0x41, 0xE9),
        sourceUnitWidths = listOf(1, 2),
        diagnosticCodes = listOf("text.malformed-utf8"),
    )

    @Test
    fun declaresItsSchemaVersion() {
        assertEquals(1, CanonicalEnvelope.SCHEMA_VERSION)
    }

    @Test
    fun encodesTheCanonicalEnvelopeLiterally() {
        assertEquals(
            listOf(
                "schema=1",
                "scenario=decoding-ascii",
                "capabilities=jvm|SHAPING:true:profile-s",
                "scalars=65,233",
                "widths=1,2",
                "diagnostics=text.malformed-utf8",
            ),
            CanonicalEnvelope.encode(observation).lines(),
        )
    }

    @Test
    fun encodesWithoutATrailingLineBreak() {
        assertEquals(CanonicalEnvelope.encode(observation), CanonicalEnvelope.encode(observation).trimEnd('\n'))
    }

    @Test
    fun encodesTheSameObservationIdentically() {
        assertEquals(CanonicalEnvelope.encode(observation), CanonicalEnvelope.encode(observation))
    }

    @Test
    fun changingOneFieldChangesOnlyItsLine() {
        val baseLines = CanonicalEnvelope.encode(observation).lines()
        val changedLines = CanonicalEnvelope.encode(observation.copy(scalars = listOf(0x42))).lines()

        assertNotEquals(baseLines, changedLines)
        assertEquals(baseLines.size, changedLines.size)
        baseLines.indices.forEach { index ->
            if (index == 3) {
                assertNotEquals(baseLines[index], changedLines[index])
            } else {
                assertEquals(baseLines[index], changedLines[index])
            }
        }
    }

    @Test
    fun encodesEmptyListsAsEmptyFields() {
        val empty = ConformanceObservation(
            scenarioId = "decoding-empty",
            capabilityFingerprint = "jvm|none",
            scalars = emptyList(),
            sourceUnitWidths = emptyList(),
            diagnosticCodes = emptyList(),
        )

        assertEquals(
            listOf("schema=1", "scenario=decoding-empty", "capabilities=jvm|none", "scalars=", "widths=", "diagnostics="),
            CanonicalEnvelope.encode(empty).lines(),
        )
    }
}
