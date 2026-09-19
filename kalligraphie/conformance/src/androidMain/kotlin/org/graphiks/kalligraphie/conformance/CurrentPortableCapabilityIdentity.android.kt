package org.graphiks.kalligraphie.conformance

/**
 * Android declares the portable glyph representation route only: Unicode analysis, shaping and
 * end-to-end layout are absent until the portable shaping and analysis backends land.
 */
public actual fun currentPortableCapabilityIdentity(): PortableCapabilityIdentity =
    PortableCapabilityIdentity(
        platformId = "android",
        declarations = listOf(
            CapabilityDeclaration(PortableCapability.UNICODE_ANALYSIS, available = false, profileId = "absent"),
            CapabilityDeclaration(PortableCapability.SHAPING, available = false, profileId = "absent"),
            CapabilityDeclaration(PortableCapability.END_TO_END_LAYOUT, available = false, profileId = "absent"),
            CapabilityDeclaration(
                PortableCapability.GLYPH_REPRESENTATION_VARIANTS,
                available = true,
                profileId = "portable-glyph",
            ),
        ),
    )
