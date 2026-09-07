package org.graphiks.kalligraphie.api

/** Kind of representation described by a profile key. */
public enum class GlyphRepresentationProfileKind {
    /** A portable vector outline. */
    OUTLINE,

    /** A portable paint graph. */
    PAINT_GRAPH,

    /** A portable decoded bitmap. */
    BITMAP,

    /** A platform-native borrowed handle. */
    NATIVE_HANDLE,
}

/**
 * Comparable immutable identity of one selected materialization profile.
 *
 * The key records every profile parameter that may change a portable payload. It is safe for
 * consumer caches but is not a locator and does not retain a font provider or asset.
 */
public data class GlyphRepresentationProfileKey(
    /** Representation category selected by the consumer. */
    public val kind: GlyphRepresentationProfileKind,
    /** Consumer-understood schema version. */
    public val schemaVersion: Int,
    /** Canonical immutable profile parameter fingerprint. */
    public val parameters: String,
) {
    init {
        require(schemaVersion > 0) { "schemaVersion must be positive." }
        require(parameters.isNotBlank()) { "parameters must not be blank." }
    }

    /** Factories that construct canonical keys for the common profile types. */
    public companion object {
        /** Returns the complete identity of one outline profile. */
        public fun outline(profile: OutlineProfile): GlyphRepresentationProfileKey =
            GlyphRepresentationProfileKey(
                kind = GlyphRepresentationProfileKind.OUTLINE,
                schemaVersion = profile.schemaVersion,
                parameters = listOf(
                    profile.maxBytes,
                    profile.maxContours,
                    profile.maxPoints,
                    profile.maxCompositeDepth,
                    profile.maxCompositeComponents,
                ).joinToString(":"),
            )

        /** Returns the complete identity of one portable paint-graph profile. */
        public fun paintGraph(profile: PaintGraphProfile): GlyphRepresentationProfileKey =
            GlyphRepresentationProfileKey(
                kind = GlyphRepresentationProfileKind.PAINT_GRAPH,
                schemaVersion = profile.schemaVersion,
                parameters = listOf(
                    "nodes=${profile.acceptedNodeKinds.joinToString(",")}",
                    "composition=${profile.acceptedCompositionModes.joinToString(",")}",
                    "gradients=${profile.acceptedGradientKinds.joinToString(",")}",
                    "spreads=${profile.acceptedGradientSpreads.joinToString(",")}",
                    "limits=${paintLimits(profile.limits)}",
                    "outline=${outline(profile.outlineProfile).parameters}",
                ).joinToString(";"),
            )

        /** Returns the complete identity of one portable bitmap profile. */
        public fun bitmap(profile: BitmapProfile): GlyphRepresentationProfileKey =
            GlyphRepresentationProfileKey(
                kind = GlyphRepresentationProfileKind.BITMAP,
                schemaVersion = profile.schemaVersion,
                parameters = listOf(
                    "strike=${profile.strike.pixelsPerEmX}x${profile.strike.pixelsPerEmY}",
                    "formats=${profile.acceptedPixelFormats.joinToString(",")}",
                    "spaces=${profile.acceptedColorSpaces.joinToString(",")}",
                    "limits=${bitmapLimits(profile.limits)}",
                ).joinToString(";"),
            )

        /** Returns the complete identity of one native-handle profile. */
        public fun nativeHandle(profile: NativeHandleProfile): GlyphRepresentationProfileKey =
            GlyphRepresentationProfileKey(
                kind = GlyphRepresentationProfileKind.NATIVE_HANDLE,
                schemaVersion = profile.schemaVersion,
                parameters = "${profile.bridgeKind}:${profile.bridgeVersion}",
            )

        private fun paintLimits(limits: PaintGraphLimits): String = listOf(
            limits.maxNodes,
            limits.maxReferences,
            limits.maxDepth,
            limits.maxSourceBytes,
            limits.maxPaths,
            limits.maxGradients,
            limits.maxPalettes,
            limits.maxPaletteEntries,
            limits.maxColorRecords,
            limits.maxBaseGlyphRecords,
            limits.maxLayerRecords,
            limits.maxCompressedSvgBytes,
            limits.maxDecompressedSvgBytes,
            limits.maxSvgDepth,
            limits.maxSvgPathCommands,
            limits.maxSvgGradientStops,
        ).joinToString(":")

        private fun bitmapLimits(limits: BitmapLimits): String = listOf(
            limits.maxStrikes,
            limits.maxWidth,
            limits.maxHeight,
            limits.maxPixels,
            limits.maxCompressedBytes,
            limits.maxDecodedBytes,
            limits.maxIndexSubtables,
            limits.maxRecordCount,
            limits.maxTotalCompressedBytes,
            limits.maxTotalDecodedBytes,
            limits.maxIndexTableBytes,
            limits.maxBitmapTableBytes,
        ).joinToString(":")
    }
}

/**
 * Stable cache identity of one glyph representation request.
 *
 * A key binds a complete asset, glyph id, visual variant, and selected profile. It has no
 * reopening capability: reopening still requires a live resolver in the matching provider and
 * generation domain.
 */
public data class GlyphRepresentationKey(
    /** Exact asset whose source and provider generation are bound by the key. */
    public val assetKey: FontRenderAssetKey,
    /** Glyph selected from the asset's face. */
    public val glyphId: GlyphId,
    /** Geometry-neutral visual variant used while materializing the payload. */
    public val variant: FontRenderVariantKey,
    /** Exact representation profile accepted by the consumer. */
    public val profile: GlyphRepresentationProfileKey,
    /** Canonical parameters specific to a bitmap or native representation, if any. */
    public val routeParameters: String = "none",
) {
    init {
        require(assetKey.variant == variant) { "Glyph representation variant must match its asset key." }
        require(routeParameters.isNotBlank()) { "routeParameters must not be blank." }
    }
}
