package org.graphiks.kalligraphie

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.stream.Collectors
import org.graphiks.kalligraphie.api.FontCatalogGeneration
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontProviderId
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceId
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.font.core.EmbeddedFontCatalog
import org.graphiks.kalligraphie.font.core.EmbeddedFontCatalogEntry
import org.graphiks.kalligraphie.font.sfnt.SfntReader

/**
 * Immutable limits and roots used while capturing one macOS system-font snapshot.
 *
 * [roots] contains filesystem locations rather than live platform objects. The provider captures
 * eligible `.ttf` files into memory, so later file-system changes cannot affect a successful
 * snapshot, its resolvers, or its detached render assets. `ttc` collections and `otf` files are
 * not claimed because the current TrueType parser does not implement those containers.
 */
public class MacosSystemFontCatalogOptions(
    roots: List<String> = defaultRoots(),
    /** Maximum number of accepted TrueType faces retained by one snapshot. */
    public val maxFaces: Int = 32,
    /** Maximum byte size accepted for one source file. */
    public val maxSourceBytes: Int = 16 * 1024 * 1024,
    /** Maximum aggregate source bytes retained by one snapshot. */
    public val maxTotalSourceBytes: Int = 64 * 1024 * 1024,
) {
    /** Immutable system font roots searched in deterministic lexical order. */
    public val roots: List<String> = Collections.unmodifiableList(roots.toList())

    init {
        require(this.roots.isNotEmpty()) { "At least one macOS font root is required." }
        require(this.roots.all(String::isNotBlank)) { "macOS font roots must not be blank." }
        require(this.roots.distinct().size == this.roots.size) { "macOS font roots must not repeat." }
        require(maxFaces > 0) { "maxFaces must be positive." }
        require(maxSourceBytes > 0) { "maxSourceBytes must be positive." }
        require(maxTotalSourceBytes >= maxSourceBytes) {
            "maxTotalSourceBytes must retain at least one maximum-sized source."
        }
    }

    /** Default roots owned by macOS and the current macOS user account. */
    public companion object {
        /** Returns the standard macOS font roots without probing or opening them. */
        public fun defaultRoots(): List<String> = listOf(
            "/System/Library/Fonts",
            "/Library/Fonts",
            "${System.getProperty("user.home")}/Library/Fonts",
        )
    }
}

/**
 * Captures portable TrueType data from the macOS system-font directories.
 *
 * Every [open] call creates a new immutable generation in the `macos-system-opentype` provider
 * domain, even if the same files are discovered. A face's portable content identity therefore
 * remains stable across generations, but an asset key can be reopened only by a live resolver
 * from its exact generation. The returned snapshot exposes only portable routes implemented by
 * the embedded TrueType provider; it does not retain a CoreText object or expose a native handle.
 */
public object MacosSystemFontCatalog {
    /**
     * Captures a bounded, deterministic snapshot of supported macOS system TrueType fonts.
     *
     * The operation walks [options.roots] lexically, considers regular `.ttf` files only, and
     * copies each accepted file before parsing. Unreadable or unsupported candidates are skipped
     * without becoming face records. The result fails when invoked off macOS, when no supported
     * face fits the limits, or when no configured root can provide one. Callers own and must close
     * each resolver and render asset obtained from the successful snapshot; closing either never
     * reopens or rereads a system file. Concurrent calls produce independent generations.
     *
     * @param options bounded discovery and retained-byte policy for this snapshot.
     * @return a detached portable catalog snapshot, or a typed unsupported, limit, or source
     * failure. No partial snapshot is returned on failure.
     */
    public fun open(
        options: MacosSystemFontCatalogOptions = MacosSystemFontCatalogOptions(),
    ): FontOperationResult<FontCatalogSnapshot> {
        if (!isMacos()) {
            return FontOperationResult.Failure(
                FontError.UnsupportedRepresentationProfile(
                    "The macOS system-font provider is available only on macOS.",
                ),
            )
        }
        val entries = mutableListOf<EmbeddedFontCatalogEntry>()
        val seenSources = mutableSetOf<FontSourceId>()
        var retainedBytes = 0L
        var skippedForLimit = false
        for (path in candidates(options.roots)) {
            if (entries.size == options.maxFaces) break
            val byteSize = runCatching { Files.size(path) }.getOrNull() ?: continue
            if (byteSize <= 0L || byteSize > options.maxSourceBytes.toLong() || retainedBytes > options.maxTotalSourceBytes.toLong() - byteSize) {
                skippedForLimit = true
                continue
            }
            val bytes = runCatching { Files.readAllBytes(path) }.getOrNull() ?: continue
            if (bytes.size.toLong() != byteSize) continue
            val source = FontSource(bytes, FontSourceProvenance(path.fileName.toString()))
            if (!seenSources.add(source.id)) continue
            when (val parsed = SfntReader.readMetadata(source)) {
                is FontOperationResult.Success -> {
                    entries += EmbeddedFontCatalogEntry(source, parsed.value)
                    retainedBytes += byteSize
                }

                is FontOperationResult.Failure,
                is FontOperationResult.Cancelled,
                -> Unit
            }
        }
        if (entries.isEmpty()) {
            val error = if (skippedForLimit) {
                FontError.ResourceLimitExceeded(
                    "No supported macOS TrueType source fits the configured capture limits.",
                    limitLocation(),
                )
            } else {
                FontError.InvalidFontData("No supported macOS TrueType source was found in the configured roots.")
            }
            return FontOperationResult.Failure(error)
        }
        val generation = FontCatalogGeneration(
            provider = FontProviderId("macos-system-opentype"),
            value = "snapshot-${nextGeneration.incrementAndGet()}",
        )
        return FontOperationResult.Success(EmbeddedFontCatalog(generation, entries))
    }

    private fun candidates(roots: List<String>): List<Path> =
        roots.flatMap { root ->
            runCatching {
                Files.walk(Path.of(root)).use { stream ->
                    stream
                        .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".ttf", ignoreCase = true) }
                        .collect(Collectors.toList())
                }
            }.getOrDefault(emptyList())
        }.sortedBy { path -> path.toAbsolutePath().normalize().toString() }

    private fun isMacos(): Boolean = System.getProperty("os.name").startsWith("Mac")
}

private val nextGeneration: AtomicLong = AtomicLong(0)

private fun limitLocation(): org.graphiks.kalligraphie.api.FontDiagnosticLocation =
    org.graphiks.kalligraphie.api.FontDiagnosticLocation.Source
