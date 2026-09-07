package org.graphiks.kalligraphie.api

/**
 * Bounded retention policy for portable glyph representations materialized by one font face.
 *
 * The budget controls only an implementation cache: it never changes route selection,
 * [GlyphRepresentationKey] identity, certificates, diagnostics, or the representation returned
 * to a caller. Providers retain only complete immutable portable successes; cancellation and
 * operational failures are never cache entries. A value is applied when opening a catalog and is
 * shared only by assets of the same captured face and catalog generation. Closing the last such
 * asset or resolver releases its evictable cache entries.
 */
public data class FontMaterializationCachePolicy(
    /** Maximum estimated bytes that may be retained for one captured face, or zero to disable retention. */
    public val maxEvictableBytesPerFace: Long,
) {
    init {
        require(maxEvictableBytesPerFace >= 0L) { "maxEvictableBytesPerFace must not be negative." }
    }

    /** Standard policies for portable font materialization caches. */
    public companion object {
        /** Disables retention of materialized representations while preserving all render results. */
        public val disabled: FontMaterializationCachePolicy = FontMaterializationCachePolicy(0L)
    }
}
