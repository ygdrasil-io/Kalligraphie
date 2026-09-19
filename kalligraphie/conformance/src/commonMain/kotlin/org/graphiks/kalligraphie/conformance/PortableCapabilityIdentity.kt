package org.graphiks.kalligraphie.conformance

import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontDiagnosticSeverity

/** Portable capability whose availability must be declared explicitly by every platform. */
public enum class PortableCapability {
    UNICODE_ANALYSIS,
    SHAPING,
    END_TO_END_LAYOUT,
    GLYPH_REPRESENTATION_VARIANTS,
}

/** Stable diagnostic code emitted when a declared capability is unavailable. */
public const val CAPABILITY_ABSENCE_DIAGNOSTIC_CODE: String = "font.portable-capability-absent"

/** Explicit declaration of one [PortableCapability] on one platform. */
public data class CapabilityDeclaration(
    /** Capability being declared. */
    public val capability: PortableCapability,
    /** Whether the capability is available on this platform; never inferred from compilation. */
    public val available: Boolean,
    /** Stable identifier of the profile implementing or withholding the capability. */
    public val profileId: String,
)

/** Platform-specific portable capability surface. */
public data class PortableCapabilityIdentity(
    /** Stable platform identifier. */
    public val platformId: String,
    /** One declaration per capability, in any order. */
    public val declarations: List<CapabilityDeclaration>,
) {
    init {
        require(declarations.map { it.capability }.toSet().size == declarations.size) {
            "A capability may be declared at most once."
        }
        require(declarations.all { it.profileId.isNotBlank() }) {
            "Every declaration requires a non-blank profile identifier."
        }
    }

    /** Declared availability of [capability], or null when it is not declared. */
    public fun presenceOf(capability: PortableCapability): Boolean? =
        declarations.firstOrNull { it.capability == capability }?.available

    /** Order-independent canonical fingerprint of the declared capability surface. */
    public fun canonicalFingerprint(): String =
        declarations
            .sortedBy { it.capability.ordinal }
            .joinToString(separator = "|") { declaration ->
                "${declaration.capability.name}:${declaration.available}:${declaration.profileId}"
            }

    /**
     * Deterministic diagnostic for an unavailable [capability], or null when the capability is
     * declared available or not declared at all.
     */
    public fun absenceDiagnostic(capability: PortableCapability): FontDiagnostic? {
        val declaration = declarations.firstOrNull { it.capability == capability } ?: return null
        if (declaration.available) return null
        return FontDiagnostic(
            code = CAPABILITY_ABSENCE_DIAGNOSTIC_CODE,
            severity = FontDiagnosticSeverity.WARNING,
            location = FontDiagnosticLocation.Source,
            message = "Capability ${capability.name} is unavailable on platform $platformId " +
                "(profile ${declaration.profileId}).",
        )
    }
}
