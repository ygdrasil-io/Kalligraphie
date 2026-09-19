package org.graphiks.kalligraphie.conformance

/** JVM declares the complete reference capability surface. */
public actual fun currentPortableCapabilityIdentity(): PortableCapabilityIdentity =
    PortableCapabilityIdentity(
        platformId = "jvm",
        declarations = PortableCapability.entries.map {
            CapabilityDeclaration(it, available = true, profileId = "jvm-reference")
        },
    )
