package org.graphiks.kalligraphie

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.name
import org.graphiks.kalligraphie.api.FontCatalogGeneration
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontProviderId
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.font.sfnt.SfntReader

/** JVM entry points for snapshots sourced from installed system font files. */
public object JvmFontCatalogs {
    /**
     * Opens one immutable snapshot from supported TrueType files installed on the current JVM host.
     *
     * The provider searches the standard macOS, Linux, and Windows font directories, orders paths
     * deterministically, captures at most [maxFaces] readable supported TrueType files, and closes
     * every directory walk before publishing the result. It does not expose `java.*` values in the
     * common contracts, bind a renderer, or borrow a platform-native font handle. Each invocation
     * creates a fresh generation, so resolvers and keys from a previous invocation cannot reopen
     * assets in the new snapshot even when a portable font-content identity is unchanged.
     *
     * @param maxFaces positive upper bound on captured faces; this bounds directory traversal
     * result retention, not the host's installed-font count.
     * @return a caller-owned catalog snapshot, or a typed failure when no supported readable
     * TrueType file is available. The snapshot's resolvers and assets follow the common close and
     * concurrent-use guarantees.
     */
    public fun openSystemFontCatalog(maxFaces: Int = DEFAULT_MAX_FACES): FontOperationResult<FontCatalogSnapshot> {
        require(maxFaces > 0) { "maxFaces must be positive." }
        val sources = discoverSources(maxFaces)
        if (sources.isEmpty()) {
            return FontOperationResult.Failure(
                FontError.AssetUnavailable("No supported TrueType file was found in the JVM system-font directories."),
            )
        }
        val generation = FontCatalogGeneration(
            provider = providerId,
            value = "system-snapshot-${generationCounter.incrementAndGet()}",
        )
        return createTrueTypeCatalog(sources, generation)
    }

    private fun discoverSources(maxFaces: Int): List<FontSource> {
        val paths = standardDirectories()
            .flatMap(::trueTypeFiles)
            .distinct()
            .sortedBy { path -> path.toAbsolutePath().normalize().toString() }
        val sources = ArrayList<FontSource>(maxFaces)
        for (path in paths) {
            if (sources.size == maxFaces) break
            val bytes = runCatching { Files.readAllBytes(path) }.getOrNull() ?: continue
            val source = FontSource(bytes, FontSourceProvenance(path.toAbsolutePath().normalize().toString()))
            if (SfntReader.readMetadata(source) is FontOperationResult.Success) {
                sources += source
            }
        }
        return sources
    }

    private fun standardDirectories(): List<Path> {
        val userHome = System.getProperty("user.home").orEmpty()
        return listOf(
            "/System/Library/Fonts",
            "/Library/Fonts",
            "$userHome/Library/Fonts",
            "/usr/share/fonts",
            "/usr/local/share/fonts",
            "$userHome/.local/share/fonts",
            "C:/Windows/Fonts",
        ).map(Path::of).filter(Files::isDirectory)
    }

    private fun trueTypeFiles(directory: Path): List<Path> = runCatching {
        Files.walk(directory, MAXIMUM_DIRECTORY_DEPTH).use { stream ->
            val files = ArrayList<Path>()
            stream.forEach { path ->
                if (Files.isRegularFile(path) && path.name.endsWith(".ttf", ignoreCase = true)) files.add(path)
            }
            files
        }
    }.getOrDefault(emptyList())

    private const val DEFAULT_MAX_FACES: Int = 64
    private const val MAXIMUM_DIRECTORY_DEPTH: Int = 8
    private val providerId: FontProviderId = FontProviderId("jvm-system-files")
    private val generationCounter: AtomicLong = AtomicLong(0)
}
