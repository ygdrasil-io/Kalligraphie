package org.graphiks.kalligraphie

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import org.graphiks.kalligraphie.api.FontCatalogGeneration
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontMaterializationCachePolicy
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
    /**
     * Maximum filesystem paths inspected while discovering one snapshot.
     *
     * The provider also considers at most this many configured roots, so nonexistent roots cannot
     * make discovery unbounded. A root directory itself consumes one inspected path.
     */
    public val maxPathsToVisit: Int = 512,
    /** Maximum number of accepted TrueType faces retained by one snapshot. */
    public val maxFaces: Int = 32,
    /** Maximum byte size accepted for one source file. */
    public val maxSourceBytes: Int = 16 * 1024 * 1024,
    /** Maximum aggregate source bytes retained by one snapshot. */
    public val maxTotalSourceBytes: Int = 64 * 1024 * 1024,
    /** Bounded portable representation retention applied independently to every captured face. */
    public val materializationCachePolicy: FontMaterializationCachePolicy = FontMaterializationCachePolicy.disabled,
) {
    /** Immutable system font roots searched in their supplied order. */
    public val roots: List<String> = Collections.unmodifiableList(roots.toList())

    init {
        require(this.roots.isNotEmpty()) { "At least one macOS font root is required." }
        require(this.roots.all(String::isNotBlank)) { "macOS font roots must not be blank." }
        require(this.roots.distinct().size == this.roots.size) { "macOS font roots must not repeat." }
        require(maxPathsToVisit > 0) { "maxPathsToVisit must be positive." }
        require(maxFaces > 0) { "maxFaces must be positive." }
        require(maxSourceBytes > 0) { "maxSourceBytes must be positive." }
        require(maxTotalSourceBytes > 0) { "maxTotalSourceBytes must be positive." }
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
     * Captures a bounded snapshot of supported macOS system TrueType fonts.
     *
     * The operation inspects no more than [MacosSystemFontCatalogOptions.maxPathsToVisit] paths,
     * considers regular `.ttf` files only, then orders the captured candidates lexically. The
     * operating system's directory enumeration can affect which candidates fit a truncated
     * discovery budget; snapshots retain their exact captured records and a controlled unchanged
     * root preserves the portable identity of every retained source across generations. Each
     * source is copied through a byte limit before parsing, so a file changing between discovery
     * and capture cannot bypass [MacosSystemFontCatalogOptions.maxSourceBytes]. Unreadable or
     * unsupported candidates are skipped without becoming face records. Reaching a discovery
     * limit may yield a partial successful snapshot, but returns a typed limit error if it leaves
     * no retained face. The result also fails when invoked off macOS or when no configured root
     * can provide a supported face. Callers own and must close each resolver and render asset
     * obtained from the successful snapshot; closing either never reopens or rereads a system
     * file. Concurrent calls produce independent generations.
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
        val discovery = candidates(options.roots, options.maxPathsToVisit)
        var skippedForLimit = discovery.truncated
        for (path in discovery.paths) {
            if (entries.size == options.maxFaces) break
            val remainingBytes = options.maxTotalSourceBytes.toLong() - retainedBytes
            if (remainingBytes <= 0L) {
                skippedForLimit = true
                continue
            }
            val maximumBytes = minOf(options.maxSourceBytes.toLong(), remainingBytes).toInt()
            val bytes = when (val capture = readBounded(path, maximumBytes)) {
                is BoundedSourceRead.Bytes -> capture.value
                BoundedSourceRead.TooLarge -> {
                    skippedForLimit = true
                    continue
                }

                BoundedSourceRead.Unreadable -> continue
            }
            val source = FontSource(bytes, FontSourceProvenance(path.fileName.toString()))
            if (!seenSources.add(source.id)) continue
            when (val parsed = SfntReader.readMetadata(source)) {
                is FontOperationResult.Success -> {
                    entries += EmbeddedFontCatalogEntry(source, parsed.value)
                    retainedBytes += bytes.size.toLong()
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
        return FontOperationResult.Success(EmbeddedFontCatalog(generation, entries, options.materializationCachePolicy))
    }

    private fun candidates(roots: List<String>, maximumPaths: Int): Discovery {
        val paths = mutableListOf<Path>()
        var inspectedPaths = 0
        var truncated = false
        for ((rootIndex, root) in roots.withIndex()) {
            if (rootIndex >= maximumPaths || inspectedPaths >= maximumPaths) {
                truncated = true
                break
            }
            runCatching {
                Files.walk(Path.of(root)).use { stream ->
                    val iterator = stream.iterator()
                    while (inspectedPaths < maximumPaths && iterator.hasNext()) {
                        val path = iterator.next()
                        inspectedPaths += 1
                        if (Files.isRegularFile(path) && path.fileName.toString().endsWith(".ttf", ignoreCase = true)) {
                            paths.add(path)
                        }
                    }
                    if (inspectedPaths >= maximumPaths) truncated = true
                }
            }
        }
        if (roots.size > maximumPaths) truncated = true
        return Discovery(paths.sortedBy { path -> path.toAbsolutePath().normalize().toString() }, truncated)
    }

    private fun isMacos(): Boolean = System.getProperty("os.name").startsWith("Mac")

}

private data class Discovery(
    val paths: List<Path>,
    val truncated: Boolean,
)

private sealed interface BoundedSourceRead {
    data class Bytes(val value: ByteArray) : BoundedSourceRead

    data object TooLarge : BoundedSourceRead

    data object Unreadable : BoundedSourceRead
}

private fun readBounded(path: Path, maximumBytes: Int): BoundedSourceRead =
    runCatching {
        Files.newInputStream(path).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var retainedBytes = 0
            while (retainedBytes < maximumBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size, maximumBytes - retainedBytes))
                if (count < 0) return@use BoundedSourceRead.Bytes(output.toByteArray())
                output.write(buffer, 0, count)
                retainedBytes += count
            }
            if (input.read() < 0) BoundedSourceRead.Bytes(output.toByteArray()) else BoundedSourceRead.TooLarge
        }
    }.getOrElse { BoundedSourceRead.Unreadable }

private val nextGeneration: AtomicLong = AtomicLong(0)

private fun limitLocation(): org.graphiks.kalligraphie.api.FontDiagnosticLocation =
    org.graphiks.kalligraphie.api.FontDiagnosticLocation.Source
