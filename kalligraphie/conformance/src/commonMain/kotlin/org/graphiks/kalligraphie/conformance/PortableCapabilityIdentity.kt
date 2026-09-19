package org.graphiks.kalligraphie.conformance

import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontDiagnosticSeverity

/** Portable capability whose availability must be declared explicitly by every platform. */
public enum class PortableCapability {
    /** Unicode text analysis: segmentation, bidi, line breaking and orientation. */
    UNICODE_ANALYSIS,
    /** Glyph shaping. */
    SHAPING,
    /** End-to-end portable layout execution. */
    END_TO_END_LAYOUT,
    /** Glyph representation variants. */
    GLYPH_REPRESENTATION_VARIANTS,
}

/** Stable diagnostic code emitted when a declared capability is unavailable. */
public const val CAPABILITY_ABSENCE_DIAGNOSTIC_CODE: String = "conformance.portable-capability-absent"

/** Explicit declaration of one [PortableCapability] on one platform. */
public data class CapabilityDeclaration(
    /** Capability being declared. */
    public val capability: PortableCapability,
    /** Whether the capability is available on this platform; never inferred from compilation. */
    public val available: Boolean,
    /** Stable identifier of the profile implementing or withholding the capability. */
    public val profileId: String,
) {
    init {
        require(profileId.isNotBlank()) {
            "A capability declaration requires a non-blank profile identifier."
        }
    }
}

/**
 * Platform-specific portable capability surface.
 *
 * Every [PortableCapability] must be declared exactly once: an omitted capability would otherwise
 * be indistinguishable from a satisfied one, which the contract forbids. Declarations are stored
 * in canonical capability-name order so equality and the canonical fingerprint are order-independent.
 */
public class PortableCapabilityIdentity(
    /** Stable platform identifier. */
    public val platformId: String,
    declarations: List<CapabilityDeclaration>,
) {
    /** One declaration per [PortableCapability], in canonical capability-name order. */
    public val declarations: List<CapabilityDeclaration>

    init {
        require(platformId.isNotBlank()) {
            "A capability identity requires a non-blank platform identifier."
        }
        val declared = declarations.map { it.capability }
        require(declared.toSet().size == declared.size) {
            "A capability may be declared at most once."
        }
        require(declared.toSet() == PortableCapability.entries.toSet()) {
            "Every portable capability must be declared."
        }
        this.declarations = declarations.sortedBy { it.capability.name }.toList()
    }

    /** Declared availability of [capability]. */
    public fun presenceOf(capability: PortableCapability): Boolean =
        declarations.first { it.capability == capability }.available

    /** Order-independent canonical fingerprint of the platform capability identity. */
    public fun canonicalFingerprint(): String =
        platformId + "|" + declarations.joinToString(separator = "|") { declaration ->
            "${declaration.capability.name}:${declaration.available}:${declaration.profileId}"
        }

    /** Deterministic diagnostic for [capability] when unavailable, or null when available. */
    public fun absenceDiagnostic(capability: PortableCapability): FontDiagnostic? {
        val declaration = declarations.first { it.capability == capability }
        return if (declaration.available) null else diagnosticFor(declaration)
    }

    /** Deterministic diagnostics for every unavailable capability, in canonical order. */
    public fun absenceDiagnostics(): List<FontDiagnostic> =
        declarations.filter { !it.available }.map { diagnosticFor(it) }

    private fun diagnosticFor(declaration: CapabilityDeclaration): FontDiagnostic =
        FontDiagnostic(
            code = CAPABILITY_ABSENCE_DIAGNOSTIC_CODE,
            severity = FontDiagnosticSeverity.WARNING,
            location = FontDiagnosticLocation.Source,
            message = "Capability ${declaration.capability.name} is unavailable on platform " +
                "$platformId (profile ${declaration.profileId}).",
        )

    override fun equals(other: Any?): Boolean =
        this === other || (other is PortableCapabilityIdentity &&
            platformId == other.platformId &&
            declarations == other.declarations)

    override fun hashCode(): Int = 31 * platformId.hashCode() + declarations.hashCode()

    override fun toString(): String =
        "PortableCapabilityIdentity(platformId=$platformId, declarations=$declarations)"
}
