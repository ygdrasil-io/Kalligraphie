package org.graphiks.kalligraphie.shaping

/**
 * Immutable admission bounds for one JVM HarfBuzz backend, independent of render-asset caches.
 *
 * Every bound includes active and idle prepared fonts. Zero disables admission; all bounds must
 * be nonnegative. Only idle fonts may be evicted. An oversized preparation returns
 * `FontError.ResourceLimitExceeded` before allocating its native source copy or HarfBuzz objects.
 * These bounds govern accounted bytes, not process RSS or an instrumented native allocator.
 */
public data class JvmPreparedFontCachePolicy(
    /** Maximum number of distinct prepared fonts, including active reservations. */
    public val maxEntries: Int,
    /** Maximum bytes copied from OpenType sources into retained native buffers. */
    public val maxSourceBytes: Long,
    /** Maximum conservative estimate of HarfBuzz objects and their retained internal caches. */
    public val maxEstimatedNativeBytes: Long,
    /** Maximum sum of source bytes and estimated native bytes. */
    public val maxTotalBytes: Long,
) {
    init {
        require(maxEntries >= 0 && maxSourceBytes >= 0 && maxEstimatedNativeBytes >= 0 && maxTotalBytes >= 0) {
            "Prepared font cache bounds must be nonnegative."
        }
    }

    public companion object {
        /** Default session admission policy: 16 fonts, 64 MiB source, 256 MiB estimated native. */
        public val default: JvmPreparedFontCachePolicy = JvmPreparedFontCachePolicy(
            16, 64L * 1024 * 1024, 256L * 1024 * 1024, 320L * 1024 * 1024,
        )

        /**
         * Estimator for pinned HarfBuzz 14.3.0: 256 KiB fixed overhead plus four source sizes.
         * The source buffer is counted separately. This deliberately cautious heuristic allows
         * room for faces, fonts, table accelerators and shape-plan caches; it is not a measured
         * upper bound on arbitrary fonts, transient shaping buffers or allocator overhead.
         */
        public const val nativeEstimatorVersion: String = "harfbuzz-14.3.0-4x-source-plus-256k-v1"
    }
}

/**
 * Immutable point-in-time accounting for one prepared-font cache, readable after close.
 *
 * A font's bytes appear once, in the active category while any lease exists, otherwise idle.
 * Active reservations count before native allocation. Closing drops idle resources immediately;
 * active bytes remain counted until the last lease returns. This is neither JVM heap nor RSS.
 */
public data class JvmPreparedFontCacheUsage(
    /** Retained fonts with no active lease. */
    public val idleEntries: Int,
    /** Outstanding uses, including reservations; several leases may share one font. */
    public val activeLeases: Int,
    /** Source-buffer bytes retained by idle fonts. */
    public val idleSourceBytes: Long,
    /** Source-buffer bytes reserved or retained by active fonts. */
    public val activeSourceBytes: Long,
    /** Versioned estimate retained by idle fonts. */
    public val idleEstimatedNativeBytes: Long,
    /** Versioned estimate reserved or retained by active fonts. */
    public val activeEstimatedNativeBytes: Long,
)
