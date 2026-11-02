package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.*

/** Immutable roots and limits for macOS directory capture. */
public class MacosSystemFontCatalogOptions(
    roots: List<String> = defaultRoots(),
    /** Inspected paths, including root directories and absent roots. */
    public val maxPathsToVisit: Int = 2048,
    /** Accepted faces retained by the snapshot. */
    public val maxFaces: Int = 256,
    /** Bytes per original source container. */
    public val maxSourceBytes: Int = 16 * 1024 * 1024,
    /** Retained bytes of unique accepted containers, excluding copies and decoder memory. */
    public val maxTotalSourceBytes: Int = 256 * 1024 * 1024,
    /** Existing portable representation retention policy. */
    public val materializationCachePolicy: FontMaterializationCachePolicy = FontMaterializationCachePolicy.disabled,
    /** Optional shared scope; closed scopes permit uncached operations. */
    public val cacheScope: FontCacheScope? = null,
    /** Examined faces, including rejected attempts; collections must fit whole in the remaining budget. */
    public val maxFacesToExamine: Int = 1024,
    /** Returned diagnostics, including diagnostic truncation. */
    public val maxDiagnostics: Int = 64,
) {
    internal val directoryOptions = FontDirectoryCatalogOptions(roots, maxPathsToVisit, maxFaces, maxSourceBytes, maxTotalSourceBytes, materializationCachePolicy, cacheScope, maxFacesToExamine, maxDiagnostics)
    /** Immutable roots inspected in supplied order. */
    public val roots: List<String> = directoryOptions.roots
    /** Standard system and user macOS font locations. */
    public companion object {
        /**
         * Returns standard roots without probing the filesystem.
         *
         * The class defaults are sized so a stock system volume fits whole: a macOS 27 system
         * directory exposes about 370 candidate sources holding roughly 790 faces — the classic
         * pre-installed families such as Menlo, Helvetica, Times, Courier, Monaco or Geneva sit
         * beyond the 165th face in the documented lexical capture order, so accepted-face and
         * examination budgets below these defaults omit them. Sources larger than
         * [maxSourceBytes] are rejected whole before retention, which keeps a full stock
         * capture near 80 MB of retained sources despite the 256 MB aggregate ceiling.
         */
        public fun defaultRoots(): List<String> = listOf("/System/Library/Fonts", "/Library/Fonts", "${System.getProperty("user.home")}/Library/Fonts")
    }
}

/** Captures portable macOS font files; this is not the CoreText registry. */
public object MacosSystemFontCatalog {
    /**
     * Uses [FontDirectoryCatalog] capture semantics in the `macos-system-opentype` domain.
     * Every success owns a fresh generation and detached captured bytes. Off macOS returns a typed
     * unsupported error. Cancellation publishes no partial catalog. Callers close acquired assets
     * and resolvers; retained resources remain usable after filesystem replacement or removal.
     */
    public fun open(options: MacosSystemFontCatalogOptions = MacosSystemFontCatalogOptions(), cancellationToken: CancellationToken = CancellationToken.none): FontOperationResult<FontCatalogSnapshot> {
        if (!System.getProperty("os.name").startsWith("Mac")) return FontOperationResult.Failure(FontError.UnsupportedRepresentationProfile("The macOS font provider is available only on macOS."))
        return captureFontDirectories(options.directoryOptions, cancellationToken, "macos-system-opentype")
    }
}
