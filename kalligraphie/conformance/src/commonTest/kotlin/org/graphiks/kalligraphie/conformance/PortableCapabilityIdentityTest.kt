package org.graphiks.kalligraphie.conformance

import org.graphiks.kalligraphie.api.FontDiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortableCapabilityIdentityTest {
    @Test
    fun reportsDeclaredPresenceAndUndefinedCapabilities() {
        val identity = PortableCapabilityIdentity(
            platformId = "jvm",
            declarations = listOf(
                CapabilityDeclaration(PortableCapability.UNICODE_ANALYSIS, available = true, profileId = "icu4j"),
                CapabilityDeclaration(PortableCapability.SHAPING, available = true, profileId = "harfbuzz-native"),
            ),
        )
        assertEquals(true, identity.presenceOf(PortableCapability.SHAPING))
        assertNull(identity.presenceOf(PortableCapability.END_TO_END_LAYOUT))
    }

    @Test
    fun emitsADeterministicDiagnosticForAnAbsentCapability() {
        val identity = PortableCapabilityIdentity(
            platformId = "ios",
            declarations = listOf(
                CapabilityDeclaration(PortableCapability.SHAPING, available = false, profileId = "absent"),
            ),
        )
        val diagnostic = identity.absenceDiagnostic(PortableCapability.SHAPING)
        assertIs<org.graphiks.kalligraphie.api.FontDiagnostic>(diagnostic)
        assertEquals("font.portable-capability-absent", diagnostic.code)
        assertEquals(FontDiagnosticSeverity.WARNING, diagnostic.severity)
        assertTrue(diagnostic.message.contains("ios"))
        assertNull(identity.absenceDiagnostic(PortableCapability.UNICODE_ANALYSIS))
    }

    @Test
    fun rejectsDuplicateCapabilityDeclarations() {
        assertFailsWith<IllegalArgumentException> {
            PortableCapabilityIdentity(
                platformId = "android",
                declarations = listOf(
                    CapabilityDeclaration(PortableCapability.SHAPING, available = false, profileId = "absent"),
                    CapabilityDeclaration(PortableCapability.SHAPING, available = true, profileId = "harfbuzz-native"),
                ),
            )
        }
    }

    @Test
    fun canonicalFingerprintIsStableRegardlessOfDeclarationOrder() {
        val first = PortableCapabilityIdentity(
            platformId = "ios",
            declarations = listOf(
                CapabilityDeclaration(PortableCapability.SHAPING, available = false, profileId = "absent"),
                CapabilityDeclaration(PortableCapability.UNICODE_ANALYSIS, available = false, profileId = "absent"),
            ),
        )
        val second = PortableCapabilityIdentity(
            platformId = "ios",
            declarations = listOf(
                CapabilityDeclaration(PortableCapability.UNICODE_ANALYSIS, available = false, profileId = "absent"),
                CapabilityDeclaration(PortableCapability.SHAPING, available = false, profileId = "absent"),
            ),
        )
        assertEquals(first.canonicalFingerprint(), second.canonicalFingerprint())
        assertTrue(first.canonicalFingerprint().isNotBlank())
    }
}
