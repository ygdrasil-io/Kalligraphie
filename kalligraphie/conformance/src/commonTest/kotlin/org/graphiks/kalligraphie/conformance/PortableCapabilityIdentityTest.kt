package org.graphiks.kalligraphie.conformance

import org.graphiks.kalligraphie.api.FontDiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortableCapabilityIdentityTest {
    private fun identity(
        platformId: String,
        u: Boolean,
        s: Boolean,
        l: Boolean,
        g: Boolean,
    ): PortableCapabilityIdentity = PortableCapabilityIdentity(
        platformId = platformId,
        declarations = listOf(
            CapabilityDeclaration(PortableCapability.UNICODE_ANALYSIS, u, "profile-u"),
            CapabilityDeclaration(PortableCapability.SHAPING, s, "profile-s"),
            CapabilityDeclaration(PortableCapability.END_TO_END_LAYOUT, l, "profile-l"),
            CapabilityDeclaration(PortableCapability.GLYPH_REPRESENTATION_VARIANTS, g, "profile-g"),
        ),
    )

    @Test
    fun reportsDeclaredPresence() {
        val identity = identity("jvm", u = true, s = false, l = true, g = false)

        assertEquals(true, identity.presenceOf(PortableCapability.UNICODE_ANALYSIS))
        assertEquals(false, identity.presenceOf(PortableCapability.SHAPING))
        assertEquals(true, identity.presenceOf(PortableCapability.END_TO_END_LAYOUT))
        assertEquals(false, identity.presenceOf(PortableCapability.GLYPH_REPRESENTATION_VARIANTS))
    }

    @Test
    fun absenceDiagnosticIsNullForAnAvailableCapability() {
        val identity = identity("jvm", u = true, s = true, l = true, g = true)

        assertNull(identity.absenceDiagnostic(PortableCapability.END_TO_END_LAYOUT))
    }

    @Test
    fun emitsADeterministicDiagnosticForAnAbsentCapability() {
        val identity = identity("ios", u = true, s = false, l = true, g = true)

        val diagnostic = identity.absenceDiagnostic(PortableCapability.SHAPING)

        assertEquals(CAPABILITY_ABSENCE_DIAGNOSTIC_CODE, diagnostic?.code)
        assertEquals("conformance.portable-capability-absent", diagnostic?.code)
        assertEquals(FontDiagnosticSeverity.WARNING, diagnostic?.severity)
        assertTrue(diagnostic != null && diagnostic.message.contains("ios"))
    }

    @Test
    fun absenceDiagnosticsListsEveryUnavailableCapability() {
        val identity = identity("ios", u = true, s = false, l = false, g = true)

        val diagnostics = identity.absenceDiagnostics()

        assertEquals(2, diagnostics.size)
        assertTrue(diagnostics[0].message.contains(PortableCapability.END_TO_END_LAYOUT.name))
        assertTrue(diagnostics[1].message.contains(PortableCapability.SHAPING.name))
    }

    @Test
    fun rejectsDuplicateCapabilityDeclarations() {
        assertFailsWith<IllegalArgumentException> {
            PortableCapabilityIdentity(
                platformId = "android",
                declarations = listOf(
                    CapabilityDeclaration(PortableCapability.UNICODE_ANALYSIS, true, "profile-u"),
                    CapabilityDeclaration(PortableCapability.SHAPING, false, "profile-s"),
                    CapabilityDeclaration(PortableCapability.END_TO_END_LAYOUT, true, "profile-l"),
                    CapabilityDeclaration(PortableCapability.GLYPH_REPRESENTATION_VARIANTS, true, "profile-g"),
                    CapabilityDeclaration(PortableCapability.SHAPING, true, "harfbuzz-native"),
                ),
            )
        }
    }

    @Test
    fun rejectsAnOmittedCapability() {
        assertFailsWith<IllegalArgumentException> {
            PortableCapabilityIdentity(
                platformId = "android",
                declarations = listOf(
                    CapabilityDeclaration(PortableCapability.UNICODE_ANALYSIS, true, "profile-u"),
                    CapabilityDeclaration(PortableCapability.SHAPING, false, "profile-s"),
                    CapabilityDeclaration(PortableCapability.END_TO_END_LAYOUT, true, "profile-l"),
                ),
            )
        }
    }

    @Test
    fun rejectsABlankProfileId() {
        assertFailsWith<IllegalArgumentException> {
            PortableCapabilityIdentity(
                platformId = "android",
                declarations = listOf(
                    CapabilityDeclaration(PortableCapability.UNICODE_ANALYSIS, true, "  "),
                    CapabilityDeclaration(PortableCapability.SHAPING, false, "profile-s"),
                    CapabilityDeclaration(PortableCapability.END_TO_END_LAYOUT, true, "profile-l"),
                    CapabilityDeclaration(PortableCapability.GLYPH_REPRESENTATION_VARIANTS, true, "profile-g"),
                ),
            )
        }
    }

    @Test
    fun rejectsABlankPlatformId() {
        assertFailsWith<IllegalArgumentException> {
            identity("", u = true, s = true, l = true, g = true)
        }
    }

    @Test
    fun canonicalFingerprintIncludesThePlatformId() {
        val jvm = identity("jvm", u = true, s = true, l = true, g = true)
        val ios = identity("ios", u = true, s = true, l = true, g = true)

        assertNotEquals(jvm.canonicalFingerprint(), ios.canonicalFingerprint())
    }

    @Test
    fun canonicalFingerprintIsOrderIndependent() {
        val forward = identity("ios", u = true, s = false, l = true, g = false)
        val reversed = PortableCapabilityIdentity(
            platformId = "ios",
            declarations = forward.declarations.reversed(),
        )

        assertEquals(forward.canonicalFingerprint(), reversed.canonicalFingerprint())
        assertTrue(forward.canonicalFingerprint().isNotBlank())
    }

    @Test
    fun canonicalFingerprintChangesWithAvailability() {
        val allAvailable = identity("ios", u = true, s = true, l = true, g = true)
        val shapingAbsent = identity("ios", u = true, s = false, l = true, g = true)

        assertNotEquals(allAvailable.canonicalFingerprint(), shapingAbsent.canonicalFingerprint())
    }

    @Test
    fun treatsIdentitiesWithTheSameSurfaceAsEqualRegardlessOfDeclarationOrder() {
        val forward = identity("ios", u = true, s = true, l = false, g = false)
        val reversed = PortableCapabilityIdentity(
            platformId = "ios",
            declarations = forward.declarations.reversed(),
        )

        assertEquals(forward, reversed)
        assertEquals(forward.hashCode(), reversed.hashCode())
    }
}
