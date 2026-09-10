@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.shaping

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.io.path.absolutePathString
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontInstanceKey
import org.graphiks.kalligraphie.api.GdefLigatureCaretFact
import org.graphiks.kalligraphie.api.GdefLigatureCaretState
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.ShapedGlyph
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShaperCluster
import org.graphiks.kalligraphie.api.ShaperClusterToken
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.ShapingBackendIdentity
import org.graphiks.kalligraphie.api.ShapingDistributionProvenance
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShapingFeaturePolicy
import org.graphiks.kalligraphie.api.ShapingFeaturePolicyApplication
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.ShapingResourceLimit
import org.graphiks.kalligraphie.api.ShapingSemanticIdentity
import org.graphiks.kalligraphie.api.ShapingSafetyFlags
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.toDiagnostic

/**
 * Opens the pinned HarfBuzz reference backend for the current JVM platform.
 *
 * The backend loads only the hash-verified HarfBuzz resource embedded in this module. It is
 * available on Linux and macOS for x64 and arm64 JVMs; other platforms return a typed failure.
 * No JNI type, native handle, or platform dependency escapes through [ShapingBackend].
 * JVM launchers must enable native access with `--enable-native-access=ALL-UNNAMED`.
 */
public object JvmHarfBuzzShapingBackend {
    /**
     * Explicit baseline feature policy implemented by the pinned HarfBuzz reference backend.
     *
     * The policy delegates baseline selection to HarfBuzz 14.3.0 and therefore does not claim
     * to enumerate choices that HarfBuzz derives from font tables or segment properties.
     * Callers must place this policy in every [ShapingRequest] sent to this backend; individual
     * [OpenTypeFeature] values remain explicit request overrides.
     */
    public val pinnedFeaturePolicy: ShapingFeaturePolicy = PINNED_FEATURE_POLICY

    /** Opens and validates the pinned HarfBuzz library before exposing a backend. */
    public fun open(): FontOperationResult<ShapingBackend> =
        when (val loaded = HarfBuzzNativeLoader.load()) {
            is FontOperationResult.Success -> FontOperationResult.Success(HarfBuzzJvmBackend(loaded.value))
            is FontOperationResult.Failure -> loaded
            is FontOperationResult.Cancelled -> loaded
        }
}

private class HarfBuzzJvmBackend(
    private val nativeLibrary: HarfBuzzNativeLibrary,
) : ShapingBackend {
    override val identity: ShapingBackendIdentity = nativeLibrary.identity
    private val preparedFonts = BoundedPreparedResourceCache<FontInstanceKey, PreparedHarfBuzzFont>(
        maximumEntries = PREPARED_FONT_CACHE_MAX_ENTRIES,
        maximumWeightBytes = PREPARED_FONT_CACHE_MAX_BYTES,
        weightInBytes = PreparedHarfBuzzFont::retainedByteCount,
        release = PreparedHarfBuzzFont::close,
    )
    private val lifecycle = ReentrantReadWriteLock()
    private var closed: Boolean = false

    override fun shape(request: ShapingRequest): FontOperationResult<ShapedGlyphRun> {
        lifecycle.readLock().lock()
        try {
            if (closed) {
                return FontOperationResult.Failure(
                    FontError.ResourceClosed("The pinned HarfBuzz backend is closed."),
                )
            }
            if (request.cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            val scalarCount = request.snapshot.scalarCount(request.contextRange)
            if (scalarCount > request.resourceProfile.maxScalars) {
                return shapingResourceLimitFailure(ShapingResourceLimit.SCALARS, scalarCount)
            }
            if (request.featurePolicy != identity.semantic.featurePolicy) {
                return shapingFailure(
                    code = "font.shaping-feature-policy-unsupported",
                    message = "The requested OpenType feature policy is not implemented by this pinned HarfBuzz backend.",
                )
            }
            if (request.features.any { feature -> feature.tag in NON_DETERMINISTIC_FEATURES }) {
                return shapingFailure(
                    code = "font.shaping-feature-not-deterministic",
                    message = "The requested OpenType feature is non-deterministic and cannot be shaped reproducibly.",
                )
            }

            val layoutSize = request.font.key.layoutSize.value
            if (!layoutSize.isFinite() || layoutSize <= 0f) {
                return shapingFailure(
                    code = "font.shaping-scale-invalid",
                    message = "The font instance layout size must be finite and positive.",
                )
            }

            return try {
                val prepared = preparedFonts.acquire(request.font.key) { prepareFont(request, layoutSize) }
                try {
                    FontOperationResult.Success(
                        nativeLibrary.shape(
                            request = request,
                            preparedFont = prepared.value,
                        ),
                    )
                } finally {
                    prepared.close()
                }
            } catch (failure: PreparedFontFailure) {
                failure.result
            } catch (cancelled: PreparedFontCancelled) {
                cancelled.result
            } catch (_: ShapingCancelled) {
                FontOperationResult.Cancelled()
            } catch (limitExceeded: ShapingLimitExceeded) {
                shapingResourceLimitFailure(limitExceeded.limit, limitExceeded.observed)
            } catch (error: Throwable) {
                shapingFailure(
                    code = "font.shaping-native-failure",
                    message = "The pinned HarfBuzz backend failed while shaping: ${error.message ?: error::class.simpleName}.",
                )
            }
        } finally {
            lifecycle.readLock().unlock()
        }
    }

    override fun close(): FontOperationResult<Unit> {
        lifecycle.writeLock().lock()
        try {
            if (closed) return FontOperationResult.Success(Unit)
            closed = true
            return aggregateFailures(preparedFonts.close())?.let { error ->
                FontOperationResult.Failure(
                    FontError.FontDataFailure(
                        code = "font.shaping-native-release-failed",
                        message = "The pinned HarfBuzz backend could not release its native resources: ${error.message ?: error::class.simpleName}.",
                        location = FontDiagnosticLocation.Source,
                    ),
                )
            } ?: FontOperationResult.Success(Unit)
        } finally {
            lifecycle.writeLock().unlock()
        }
    }

    private fun prepareFont(request: ShapingRequest, layoutSize: Float): PreparedHarfBuzzFont {
        observeCancellation(request)
        val fontData = when (val result = request.font.copyOpenTypeData()) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> throw PreparedFontFailure(result)
            is FontOperationResult.Cancelled -> throw PreparedFontCancelled(result)
        }
        observeCancellation(request)
        if (fontData.face != request.font.key.face) {
            throw PreparedFontFailure(
                shapingFailure(
                    code = "font.shaping-font-data-mismatch",
                    message = "The OpenType bytes do not identify the requested font instance face.",
                ),
            )
        }
        val bytes = fontData.copyBytes()
        observeCancellation(request)
        val prepared = nativeLibrary.prepare(bytes, layoutSize)
        if (request.cancellationToken.isCancellationRequested()) {
            prepared.close()
            throw ShapingCancelled
        }
        return prepared
    }
}

private const val PREPARED_FONT_CACHE_MAX_ENTRIES: Int = 16
private const val PREPARED_FONT_CACHE_MAX_BYTES: Long = 64L * 1024L * 1024L

/**
 * Bounded least-recently-used cache whose resources stay alive only while retained or leased.
 *
 * An active lease prevents eviction and release. Once a lease ends, the cache evicts idle
 * resources until both bounds hold. Closing the cache prevents future acquisition, releases
 * idle resources immediately, and defers active-resource release until their last lease ends.
 */
internal class BoundedPreparedResourceCache<Key : Any, Value : Any>(
    private val maximumEntries: Int,
    private val maximumWeightBytes: Long,
    private val weightInBytes: (Value) -> Long,
    private val release: (Value) -> Unit,
) {
    private val lock = Any()
    private val entries = LinkedHashMap<Key, Entry<Value>>(16, 0.75f, true)
    private var cachedWeightBytes: Long = 0L
    private var closed: Boolean = false

    init {
        require(maximumEntries > 0) { "The prepared resource cache must retain at least one entry." }
        require(maximumWeightBytes > 0L) { "The prepared resource cache weight bound must be positive." }
    }

    /** Acquires a lease for [key], preparing and retaining a resource when it is absent. */
    fun acquire(key: Key, create: () -> Value): Lease<Value> {
        synchronized(lock) {
            entries[key]?.let { entry ->
                entry.activeLeaseCount += 1
                return leaseFor(entry)
            }
            check(!closed) { "The prepared resource cache is closed." }
        }

        val created = create()
        var redundant: Value? = null
        var evicted: List<Value> = emptyList()
        var rejected = false
        val lease = synchronized(lock) {
            if (closed) {
                redundant = created
                rejected = true
                null
            } else {
                entries[key]?.let { entry ->
                    entry.activeLeaseCount += 1
                    redundant = created
                    return@synchronized leaseFor(entry)
                }
                val weight = weightInBytes(created)
                require(weight >= 0L) { "A prepared resource cache weight must not be negative." }
                val entry = Entry(value = created, weightBytes = weight, activeLeaseCount = 1)
                entries[key] = entry
                cachedWeightBytes = saturatedAdd(cachedWeightBytes, weight)
                evicted = evictIdleEntriesLocked()
                leaseFor(entry)
            }
        }
        val releaseFailures = releaseAll(listOfNotNull(redundant) + evicted)
        if (rejected) {
            val closedError = IllegalStateException("The prepared resource cache closed while a resource was being prepared.")
            releaseFailures.forEach(closedError::addSuppressed)
            throw closedError
        }
        releaseFailures.firstOrNull()?.let { releaseFailure ->
            try {
                checkNotNull(lease).close()
            } catch (cleanupFailure: Throwable) {
                releaseFailure.addSuppressed(cleanupFailure)
            }
            throw releaseFailure
        }
        return checkNotNull(lease)
    }

    /** Releases all retained idle resources and defers active-resource release to their leases. */
    fun close(): List<Throwable> {
        val idleResources = synchronized(lock) {
            if (closed) return emptyList()
            closed = true
            val values = entries.values.mapNotNull { entry ->
                entry.retained = false
                entry.value.takeIf { entry.activeLeaseCount == 0 }
            }
            entries.clear()
            cachedWeightBytes = 0L
            values
        }
        return releaseAll(idleResources)
    }

    private fun leaseFor(entry: Entry<Value>): Lease<Value> = Lease(entry.value) {
        releaseLease(entry)
    }

    private fun releaseLease(entry: Entry<Value>) {
        val resourcesToRelease = synchronized(lock) {
            check(entry.activeLeaseCount > 0) { "A prepared resource lease was released more than once." }
            entry.activeLeaseCount -= 1
            when {
                entry.activeLeaseCount == 0 && !entry.retained -> listOf(entry.value)
                else -> evictIdleEntriesLocked()
            }
        }
        releaseAll(resourcesToRelease).firstOrNull()?.let { throw it }
    }

    private fun evictIdleEntriesLocked(): List<Value> {
        val evicted = mutableListOf<Value>()
        while (entries.size > maximumEntries || cachedWeightBytes > maximumWeightBytes) {
            val candidate = entries.entries.firstOrNull { (_, entry) -> entry.activeLeaseCount == 0 } ?: break
            entries.remove(candidate.key)
            candidate.value.retained = false
            cachedWeightBytes -= candidate.value.weightBytes
            evicted += candidate.value.value
        }
        return evicted
    }

    private fun releaseAll(values: List<Value>): List<Throwable> = buildList {
        values.forEach { value ->
            try {
                release(value)
            } catch (error: Throwable) {
                add(error)
            }
        }
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private data class Entry<Value>(
        val value: Value,
        val weightBytes: Long,
        var activeLeaseCount: Int,
        var retained: Boolean = true,
    )

    /** Handle that protects one prepared resource against release until [close]. */
    internal class Lease<Value> internal constructor(
        val value: Value,
        private val release: () -> Unit,
    ) : AutoCloseable {
        private var closed: Boolean = false

        override fun close() {
            val shouldRelease = synchronized(this) {
                if (closed) false else {
                    closed = true
                    true
                }
            }
            if (shouldRelease) release()
        }
    }
}

private class PreparedFontFailure(
    val result: FontOperationResult.Failure,
) : RuntimeException()

private class PreparedFontCancelled(
    val result: FontOperationResult.Cancelled,
) : RuntimeException()

internal data class HarfBuzzPlatform(
    val osName: String,
    val architecture: String,
) {
    internal companion object {
        fun detect(): HarfBuzzPlatform = HarfBuzzPlatform(
            osName = System.getProperty("os.name").orEmpty(),
            architecture = System.getProperty("os.arch").orEmpty(),
        )
    }
}

internal object HarfBuzzNativeLoader {
    private val cache: MutableMap<HarfBuzzPlatform, FontOperationResult<HarfBuzzNativeLibrary>> = mutableMapOf()

    fun load(platform: HarfBuzzPlatform = HarfBuzzPlatform.detect()): FontOperationResult<HarfBuzzNativeLibrary> = synchronized(cache) {
        cache.getOrPut(platform) { loadUncached(platform) }
    }

    private fun loadUncached(platform: HarfBuzzPlatform): FontOperationResult<HarfBuzzNativeLibrary> {
        val target = nativeTargetFor(platform) ?: return shapingFailure(
            code = "font.shaping-native-platform-unsupported",
            message = "The pinned HarfBuzz backend supports only Linux or macOS on x64 or arm64; received ${platform.osName}/${platform.architecture}.",
        )
        return try {
            val bytes = HarfBuzzNativeLoader::class.java.getResourceAsStream(target.resourcePath)
                ?.use { stream -> stream.readBytes() }
                ?: return shapingFailure(
                    code = "font.shaping-native-resource-missing",
                    message = "The pinned HarfBuzz resource ${target.resourcePath} is missing.",
                )
            if (bytes.sha256Hex() != target.librarySha256) {
                return shapingFailure(
                    code = "font.shaping-native-resource-corrupt",
                    message = "The pinned HarfBuzz resource failed its SHA-256 verification.",
                )
            }

            val libraryPath = materializeLibrary(target, bytes)
            System.load(libraryPath.absolutePathString())
            val lookup = SymbolLookup.libraryLookup(libraryPath, Arena.global())
            val nativeLibrary = HarfBuzzNativeLibrary(lookup, target)
            if (nativeLibrary.versionString() != HARFBUZZ_VERSION) {
                return shapingFailure(
                    code = "font.shaping-native-version-mismatch",
                    message = "The loaded HarfBuzz library does not report the pinned version $HARFBUZZ_VERSION.",
                )
            }
            FontOperationResult.Success(nativeLibrary)
        } catch (error: Throwable) {
            shapingFailure(
                code = "font.shaping-native-load-failed",
                message = "The pinned HarfBuzz library could not be loaded: ${error.message ?: error::class.simpleName}.",
            )
        }
    }

    private fun materializeLibrary(target: HarfBuzzNativeTarget, bytes: ByteArray): Path {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "kalligraphie-harfbuzz", target.librarySha256)
        Files.createDirectories(directory)
        val destination = directory.resolve(target.fileName)
        if (Files.isRegularFile(destination) && Files.readAllBytes(destination).sha256Hex() == target.librarySha256) {
            return destination
        }

        val temporary = Files.createTempFile(directory, "libharfbuzz-", ".part")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        check(Files.readAllBytes(destination).sha256Hex() == target.librarySha256) {
            "The extracted HarfBuzz library did not preserve its verified digest."
        }
        return destination
    }
}

internal data class HarfBuzzNativeTarget(
    val operatingSystem: String,
    val architecture: String,
    val resourcePath: String,
    val fileName: String,
    val nativeSourceRevision: String,
    val nativeArtifactId: String,
    val librarySha256: String,
    val buildChainIdentity: String,
)

private fun nativeTargetFor(platform: HarfBuzzPlatform): HarfBuzzNativeTarget? = when (
    platform.osName.lowercase() to platform.architecture.lowercase()
) {
    "linux" to "amd64",
    "linux" to "x86_64",
    -> HarfBuzzNativeTarget(
        operatingSystem = "linux",
        architecture = "x64",
        resourcePath = "/kalligraphie/harfbuzz/linux/x64/libharfbuzz.so",
        fileName = "libharfbuzz.so",
        nativeSourceRevision = LWJGL_HARFBUZZ_SOURCE_REVISION,
        nativeArtifactId = "org.lwjgl:lwjgl-harfbuzz:3.4.3:natives-linux/libharfbuzz.so",
        librarySha256 = "9a5e3576912c2f8c8b2533d4a264fec1eac9667adfd64f7e71e80179ba118614",
        buildChainIdentity = "lwjgl-harfbuzz-3.4.3",
    )

    "linux" to "aarch64",
    "linux" to "arm64",
    -> HarfBuzzNativeTarget(
        operatingSystem = "linux",
        architecture = "arm64",
        resourcePath = "/kalligraphie/harfbuzz/linux/arm64/libharfbuzz.so",
        fileName = "libharfbuzz.so",
        nativeSourceRevision = LWJGL_HARFBUZZ_SOURCE_REVISION,
        nativeArtifactId = "org.lwjgl:lwjgl-harfbuzz:3.4.3:natives-linux-arm64/libharfbuzz.so",
        librarySha256 = "b1c7c67034297763e0ce46f3749c4da33a4bb4064929868446cb5a3d81dc26bc",
        buildChainIdentity = "lwjgl-harfbuzz-3.4.3",
    )

    "mac os x" to "x86_64",
    "mac os x" to "amd64",
    -> HarfBuzzNativeTarget(
        operatingSystem = "macos",
        architecture = "x64",
        resourcePath = "/kalligraphie/harfbuzz/macos/x64/libharfbuzz.dylib",
        fileName = "libharfbuzz.dylib",
        nativeSourceRevision = HARFBUZZ_RELEASE_SOURCE_REVISION,
        nativeArtifactId = "harfbuzz-source:14.3.0:4c2aa804671d7276e8a0eb95da07202ead05c843:macos-x64/libharfbuzz.dylib",
        librarySha256 = "9d1ee85a217d781f91c00627248c8f9611058796f49aaf146dc88c1a1439776c",
        buildChainIdentity = MACOS_BUILD_CHAIN_IDENTITY,
    )

    "mac os x" to "aarch64",
    "mac os x" to "arm64",
    -> HarfBuzzNativeTarget(
        operatingSystem = "macos",
        architecture = "arm64",
        resourcePath = "/kalligraphie/harfbuzz/macos/arm64/libharfbuzz.dylib",
        fileName = "libharfbuzz.dylib",
        nativeSourceRevision = HARFBUZZ_RELEASE_SOURCE_REVISION,
        nativeArtifactId = "harfbuzz-source:14.3.0:4c2aa804671d7276e8a0eb95da07202ead05c843:macos-arm64/libharfbuzz.dylib",
        librarySha256 = "504948a7301dc70b1bf9c2f8dc02171c7b7bf35b14d4d5590a8af2a813d73e22",
        buildChainIdentity = MACOS_BUILD_CHAIN_IDENTITY,
    )

    else -> null
}

internal class HarfBuzzNativeLibrary(
    lookup: SymbolLookup,
    target: HarfBuzzNativeTarget,
) {
    val identity: ShapingBackendIdentity = ShapingBackendIdentity(
        semantic = HARFBUZZ_SEMANTIC_IDENTITY,
        provenance = ShapingDistributionProvenance(
            operatingSystem = target.operatingSystem,
            architecture = target.architecture,
            artifactId = target.nativeArtifactId,
            artifactSha256 = target.librarySha256,
            sourceProject = "harfbuzz",
            sourceRevision = target.nativeSourceRevision,
            buildChainIdentity = target.buildChainIdentity,
        ),
    )

    private val linker: Linker = Linker.nativeLinker()
    private val versionString: MethodHandle = handle(lookup, "hb_version_string", FunctionDescriptor.of(ValueLayout.ADDRESS))
    private val blobCreate: MethodHandle = handle(
        lookup,
        "hb_blob_create",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    private val blobDestroy: MethodHandle = handle(lookup, "hb_blob_destroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
    private val faceCreate: MethodHandle = handle(
        lookup,
        "hb_face_create",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    private val faceDestroy: MethodHandle = handle(lookup, "hb_face_destroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
    private val faceGetUpem: MethodHandle = handle(lookup, "hb_face_get_upem", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
    private val faceMakeImmutable: MethodHandle = handle(lookup, "hb_face_make_immutable", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
    private val fontCreate: MethodHandle = handle(lookup, "hb_font_create", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS))
    private val fontDestroy: MethodHandle = handle(lookup, "hb_font_destroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
    private val fontMakeImmutable: MethodHandle = handle(lookup, "hb_font_make_immutable", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
    private val otFontSetFuncs: MethodHandle = handle(lookup, "hb_ot_font_set_funcs", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
    private val fontSetScale: MethodHandle = handle(
        lookup,
        "hb_font_set_scale",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )
    private val bufferCreate: MethodHandle = handle(lookup, "hb_buffer_create", FunctionDescriptor.of(ValueLayout.ADDRESS))
    private val bufferDestroy: MethodHandle = handle(lookup, "hb_buffer_destroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
    private val bufferSetDirection: MethodHandle = handle(
        lookup,
        "hb_buffer_set_direction",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    private val bufferSetScript: MethodHandle = handle(
        lookup,
        "hb_buffer_set_script",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    private val bufferSetLanguage: MethodHandle = handle(
        lookup,
        "hb_buffer_set_language",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    private val bufferSetClusterLevel: MethodHandle = handle(
        lookup,
        "hb_buffer_set_cluster_level",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    private val bufferSetFlags: MethodHandle = handle(
        lookup,
        "hb_buffer_set_flags",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    private val bufferAddUtf32: MethodHandle = handle(
        lookup,
        "hb_buffer_add_utf32",
        FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
        ),
    )
    private val languageFromString: MethodHandle = handle(
        lookup,
        "hb_language_from_string",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    private val scriptFromString: MethodHandle = handle(
        lookup,
        "hb_script_from_string",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    private val shapeFull: MethodHandle = handle(
        lookup,
        "hb_shape_full",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )
    private val bufferGetLength: MethodHandle = handle(
        lookup,
        "hb_buffer_get_length",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )
    private val bufferGetGlyphInfos: MethodHandle = handle(
        lookup,
        "hb_buffer_get_glyph_infos",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    private val bufferGetGlyphPositions: MethodHandle = handle(
        lookup,
        "hb_buffer_get_glyph_positions",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    private val glyphInfoGetGlyphFlags: MethodHandle = handle(
        lookup,
        "hb_glyph_info_get_glyph_flags",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )
    private val fontGetGlyphHorizontalAdvance: MethodHandle = handle(
        lookup,
        "hb_font_get_glyph_h_advance",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    private val ligatureCarets: MethodHandle = handle(
        lookup,
        "hb_ot_layout_get_ligature_carets",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        ),
    )

    fun versionString(): String = address(versionString).reinterpret(MAX_VERSION_BYTES).getString(0)

    fun prepare(fontBytes: ByteArray, layoutSize: Float): PreparedHarfBuzzFont {
        val arena = Arena.ofShared()
        var blob: MemorySegment = MemorySegment.NULL
        var face: MemorySegment = MemorySegment.NULL
        var font: MemorySegment = MemorySegment.NULL
        try {
            val copiedFont = arena.allocate(fontBytes.size.toLong(), 1)
            copiedFont.copyFrom(MemorySegment.ofArray(fontBytes))
            blob = requireNativeHandle(address(blobCreate, copiedFont, fontBytes.size, HB_MEMORY_MODE_READONLY, MemorySegment.NULL, MemorySegment.NULL), "blob")
            face = requireNativeHandle(address(faceCreate, blob, 0), "face")
            val designToLayout = DesignToLayoutScale.create(layoutSize, int(faceGetUpem, face))
            font = requireNativeHandle(address(fontCreate, face), "font")
            callVoid(otFontSetFuncs, font)
            callVoid(fontSetScale, font, designToLayout.unitsPerEm, designToLayout.unitsPerEm)
            callVoid(faceMakeImmutable, face)
            callVoid(fontMakeImmutable, font)
            return PreparedHarfBuzzFont(
                nativeLibrary = this,
                arena = arena,
                blob = blob,
                face = face,
                font = font,
                designToLayout = designToLayout,
                retainedByteCount = fontBytes.size.toLong(),
            )
        } catch (error: Throwable) {
            if (font != MemorySegment.NULL) callVoid(fontDestroy, font)
            if (face != MemorySegment.NULL) callVoid(faceDestroy, face)
            if (blob != MemorySegment.NULL) callVoid(blobDestroy, blob)
            arena.close()
            throw error
        }
    }

    fun shape(request: ShapingRequest, preparedFont: PreparedHarfBuzzFont): ShapedGlyphRun = Arena.ofConfined().use { arena ->
        val buffer = requireNativeHandle(address(bufferCreate), "buffer")
        try {
            configureBuffer(arena, buffer, request)
            val scalarTable = mutableListOf<ContextScalar>()
            val itemScalarRanges = mutableListOf<TextRange>()
            val textLength = request.snapshot.scalarCount(request.contextRange)
            val text = arena.allocate(ValueLayout.JAVA_INT, textLength.toLong())
            var itemOffset = 0
            request.snapshot.forEachScalar(request.contextRange) { scalar, scalarRange ->
                val contextToken = scalarTable.size
                observeCancellation(request, contextToken)
                text.setAtIndex(ValueLayout.JAVA_INT, contextToken.toLong(), scalar)
                val belongsToItem = scalarRange.start >= request.itemRange.start &&
                    scalarRange.endExclusive <= request.itemRange.endExclusive
                if (scalarRange.endExclusive <= request.itemRange.start) itemOffset += 1
                val itemToken = if (belongsToItem) {
                    val token = ShaperClusterToken(itemScalarRanges.size)
                    itemScalarRanges += scalarRange
                    token
                } else {
                    null
                }
                scalarTable += ContextScalar(
                    sourceRange = scalarRange,
                    itemToken = itemToken,
                )
            }
            // HarfBuzz retains pre/post context for joining, but only adds the item to the
            // glyph buffer. Cross-boundary ligatures require a larger item before shaping.
            callVoid(bufferAddUtf32, buffer, text, textLength, itemOffset, itemScalarRanges.size)
            observeCancellation(request)
            val features = featureArray(arena, request)
            val shapers = explicitOpenTypeShapers(arena)
            observeCancellation(request)
            val accepted = int(
                shapeFull,
                preparedFont.font,
                buffer,
                features,
                request.features.size,
                shapers,
            ) != 0
            observeCancellation(request)
            check(accepted) {
                "HarfBuzz did not accept the explicit OpenType shaper configuration."
            }
            shapedRun(arena, request, preparedFont.font, buffer, scalarTable, itemScalarRanges, preparedFont.designToLayout)
        } finally {
            callVoid(bufferDestroy, buffer)
        }
    }

    private fun configureBuffer(arena: Arena, buffer: MemorySegment, request: ShapingRequest) {
        callVoid(bufferSetDirection, buffer, request.direction.toHarfBuzzDirection())
        val script = arena.allocateFrom(request.script.value)
        callVoid(bufferSetScript, buffer, int(scriptFromString, script, -1))
        val language = arena.allocateFrom(request.language)
        callVoid(bufferSetLanguage, buffer, address(languageFromString, language, -1))
        callVoid(bufferSetClusterLevel, buffer, HB_BUFFER_CLUSTER_LEVEL_MONOTONE_CHARACTERS)
        val flags = HB_BUFFER_FLAG_PRODUCE_UNSAFE_TO_CONCAT or
            (if (request.bot) HB_BUFFER_FLAG_BOT else 0) or
            (if (request.eot) HB_BUFFER_FLAG_EOT else 0)
        callVoid(bufferSetFlags, buffer, flags)
    }

    private fun featureArray(arena: Arena, request: ShapingRequest): MemorySegment {
        val features = request.features
        if (features.isEmpty()) return MemorySegment.NULL
        val result = arena.allocate(FEATURE_BYTES * features.size, ValueLayout.JAVA_INT.byteAlignment())
        features.forEachIndexed { index, feature ->
            observeCancellation(request, index)
            val offset = index.toLong() * FEATURE_BYTES
            result.set(ValueLayout.JAVA_INT, offset, openTypeTag(feature.tag))
            result.set(ValueLayout.JAVA_INT, offset + 4, feature.value)
            result.set(ValueLayout.JAVA_INT, offset + 8, 0)
            result.set(ValueLayout.JAVA_INT, offset + 12, -1)
        }
        return result
    }

    private fun explicitOpenTypeShapers(arena: Arena): MemorySegment {
        val shapers = arena.allocate(ValueLayout.ADDRESS, 2)
        shapers.setAtIndex(ValueLayout.ADDRESS, 0, arena.allocateFrom("ot"))
        shapers.setAtIndex(ValueLayout.ADDRESS, 1, MemorySegment.NULL)
        return shapers
    }

    private fun shapedRun(
        arena: Arena,
        request: ShapingRequest,
        font: MemorySegment,
        buffer: MemorySegment,
        scalarTable: List<ContextScalar>,
        itemScalarRanges: List<TextRange>,
        designToLayout: DesignToLayoutScale,
    ): ShapedGlyphRun {
        val glyphCount = int(bufferGetLength, buffer)
        if (glyphCount > request.resourceProfile.maxGlyphs) {
            throw ShapingLimitExceeded(ShapingResourceLimit.GLYPHS, glyphCount)
        }
        observeCancellation(request)
        val infos = address(bufferGetGlyphInfos, buffer, MemorySegment.NULL).reinterpret(glyphCount.toLong() * GLYPH_INFO_BYTES)
        val positions = address(bufferGetGlyphPositions, buffer, MemorySegment.NULL).reinterpret(glyphCount.toLong() * GLYPH_POSITION_BYTES)
        val glyphRecords = List(glyphCount) { glyphIndex ->
            observeCancellation(request, glyphIndex)
            val infoOffset = glyphIndex.toLong() * GLYPH_INFO_BYTES
            val positionOffset = glyphIndex.toLong() * GLYPH_POSITION_BYTES
            val tokenValue = infos.get(ValueLayout.JAVA_INT, infoOffset + 8)
            require(tokenValue in scalarTable.indices) { "HarfBuzz returned a cluster token outside this shaping context." }
            val itemToken = requireNotNull(scalarTable[tokenValue].itemToken) {
                "A published glyph must be wholly attributable to ShapingRequest.itemRange."
            }
            NativeGlyphRecord(
                glyphId = infos.get(ValueLayout.JAVA_INT, infoOffset),
                tokenValue = itemToken.value,
                safetyMask = int(glyphInfoGetGlyphFlags, infos.asSlice(infoOffset, GLYPH_INFO_BYTES)),
                xAdvance = positions.get(ValueLayout.JAVA_INT, positionOffset),
                yAdvance = positions.get(ValueLayout.JAVA_INT, positionOffset + 4),
                xOffset = positions.get(ValueLayout.JAVA_INT, positionOffset + 8),
                yOffset = positions.get(ValueLayout.JAVA_INT, positionOffset + 12),
            )
        }
        val clusters = buildClusters(request, itemScalarRanges, glyphRecords)
        val clustersByToken = clusters.associateBy { cluster -> cluster.token }
        val glyphs = glyphRecords.mapIndexed { glyphIndex, record ->
            observeCancellation(request, glyphIndex)
            val flags = ShapingSafetyFlags(
                unsafeToBreak = record.safetyMask and HB_GLYPH_FLAG_UNSAFE_TO_BREAK != 0,
                unsafeToConcat = record.safetyMask and HB_GLYPH_FLAG_UNSAFE_TO_CONCAT != 0,
            )
            ShapedGlyph(
                glyphId = GlyphId(record.glyphId),
                xAdvance = designToLayout.convert(record.xAdvance),
                yAdvance = record.yAdvance.toPhysicalVerticalCoordinate(request.direction, designToLayout),
                xOffset = designToLayout.convert(record.xOffset),
                yOffset = record.yOffset.toPhysicalVerticalCoordinate(request.direction, designToLayout),
                safetyFlags = flags,
                clusterTokens = listOf(ShaperClusterToken(record.tokenValue)),
            )
        }
        val caretFacts = glyphRecords.mapIndexedNotNull { glyphIndex, record ->
            observeCancellation(request, glyphIndex)
            val cluster = clustersByToken.getValue(ShaperClusterToken(record.tokenValue))
            if (cluster.internalAdmissibleGraphemeBoundaries().isEmpty()) {
                null
            } else {
                ligatureCaretFact(
                    arena,
                    font,
                    request.direction,
                    record.glyphId,
                    glyphIndex,
                    cluster,
                    finalAdvanceMatchesUnshapedAdvance = advancesMatch(
                        shapedAdvance = record.xAdvance,
                        unshapedAdvance = int(fontGetGlyphHorizontalAdvance, font, record.glyphId),
                    ),
                    designToLayout,
                )
            }
        }
        return ShapedGlyphRun(
            range = request.itemRange,
            fontInstanceKey = request.font.key,
            backendIdentity = identity,
            direction = request.direction,
            script = request.script,
            language = request.language,
            bidiLevel = request.bidiLevel,
            bot = request.bot,
            eot = request.eot,
            featurePolicy = request.featurePolicy,
            features = request.features,
            graphemeClusters = request.graphemeClusters,
            glyphs = glyphs,
            clusters = clusters,
            ligatureCaretFacts = caretFacts,
        )
    }

    private fun buildClusters(
        request: ShapingRequest,
        scalarRanges: List<TextRange>,
        glyphs: List<NativeGlyphRecord>,
    ): List<ShaperCluster> {
        if (scalarRanges.isEmpty()) return emptyList()
        val observedTokens = buildSet {
            glyphs.forEachIndexed { glyphIndex, glyph ->
                observeCancellation(request, glyphIndex)
                add(glyph.tokenValue)
            }
        }.sorted()
        if (observedTokens.isEmpty()) return emptyList()
        val requestBoundaries = buildList {
            request.graphemeClusters.forEachIndexed { graphemeIndex, grapheme ->
                observeCancellation(request, graphemeIndex)
                if (graphemeIndex == 0) add(grapheme.start)
                add(grapheme.endExclusive)
            }
        }
        var firstBoundaryIndex = 0
        var cancellationCountdown = request.resourceProfile.cancellationCheckInterval
        fun observeBoundaryCancellation() {
            cancellationCountdown -= 1
            if (cancellationCountdown == 0) {
                observeCancellation(request)
                cancellationCountdown = request.resourceProfile.cancellationCheckInterval
            }
        }
        fun compareBoundary(boundary: TextIndex, limit: TextIndex): Int {
            observeBoundaryCancellation()
            return boundary.compareTo(limit)
        }
        return observedTokens.mapIndexed { index, tokenValue ->
            observeCancellation(request, index)
            val endTokenExclusive = observedTokens.getOrNull(index + 1) ?: scalarRanges.size
            val sourceScalars = scalarRanges.subList(tokenValue, endTokenExclusive)
            val sourceRange = TextRange(sourceScalars.first().start, sourceScalars.last().endExclusive)
            while (
                firstBoundaryIndex + 1 < requestBoundaries.size &&
                compareBoundary(requestBoundaries[firstBoundaryIndex + 1], sourceRange.start) <= 0
            ) {
                firstBoundaryIndex += 1
            }
            val admissibleBoundaries = buildList {
                var boundaryIndex = firstBoundaryIndex
                while (
                    boundaryIndex < requestBoundaries.size &&
                    compareBoundary(requestBoundaries[boundaryIndex], sourceRange.endExclusive) <= 0
                ) {
                    val boundary = requestBoundaries[boundaryIndex]
                    if (compareBoundary(boundary, sourceRange.start) >= 0) add(boundary)
                    boundaryIndex += 1
                }
                firstBoundaryIndex = (boundaryIndex - 1).coerceAtLeast(firstBoundaryIndex)
            }
            ShaperCluster(
                token = ShaperClusterToken(tokenValue),
                sourceRange = sourceRange,
                scalarRanges = sourceScalars,
                admissibleGraphemeBoundaries = admissibleBoundaries,
            )
        }
    }

    private fun ligatureCaretFact(
        arena: Arena,
        font: MemorySegment,
        direction: ShapingDirection,
        glyphId: Int,
        glyphIndex: Int,
        cluster: ShaperCluster,
        finalAdvanceMatchesUnshapedAdvance: Boolean,
        designToLayout: DesignToLayoutScale,
    ): GdefLigatureCaretFact {
        val expectedCaretCount = cluster.internalAdmissibleGraphemeBoundaries().size
        val count = arena.allocateFrom(ValueLayout.JAVA_INT, expectedCaretCount)
        val positions = arena.allocate(ValueLayout.JAVA_INT, expectedCaretCount.toLong())
        val totalCount = int(
            ligatureCarets,
            font,
            direction.toHarfBuzzDirection(),
            glyphId,
            0,
            count,
            positions,
        )
        val copiedCount = count.get(ValueLayout.JAVA_INT, 0)
        val safelyReadableCount = copiedCount.coerceIn(0, expectedCaretCount)
        return LigatureCaretFactInterpreter.fromNativeResponse(
            glyphIndex = glyphIndex,
            direction = direction,
            cluster = cluster,
            response = NativeLigatureCaretResponse(
                totalCount = totalCount,
                copiedCount = copiedCount,
                finalAdvanceMatchesUnshapedAdvance = finalAdvanceMatchesUnshapedAdvance,
                positions = List(safelyReadableCount) { index ->
                    designToLayout.convert(positions.getAtIndex(ValueLayout.JAVA_INT, index.toLong()))
                },
            ),
        )
    }

    private fun handle(lookup: SymbolLookup, name: String, descriptor: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(lookup.findOrThrow(name), descriptor)

    fun release(preparedFont: PreparedHarfBuzzFont) {
        val failures = buildList {
            runCatching { callVoid(fontDestroy, preparedFont.font) }.exceptionOrNull()?.let(::add)
            runCatching { callVoid(faceDestroy, preparedFont.face) }.exceptionOrNull()?.let(::add)
            runCatching { callVoid(blobDestroy, preparedFont.blob) }.exceptionOrNull()?.let(::add)
            runCatching { preparedFont.arena.close() }.exceptionOrNull()?.let(::add)
        }
        aggregateFailures(failures)?.let { throw it }
    }
}

private fun aggregateFailures(failures: List<Throwable>): Throwable? {
    val primary = failures.firstOrNull() ?: return null
    failures.drop(1).forEach { failure ->
        if (failure !== primary) primary.addSuppressed(failure)
    }
    return primary
}

internal class PreparedHarfBuzzFont(
    private val nativeLibrary: HarfBuzzNativeLibrary,
    internal val arena: Arena,
    internal val blob: MemorySegment,
    internal val face: MemorySegment,
    internal val font: MemorySegment,
    internal val designToLayout: DesignToLayoutScale,
    internal val retainedByteCount: Long,
) {
    fun close() {
        nativeLibrary.release(this)
    }
}

private data class ContextScalar(
    val sourceRange: TextRange,
    val itemToken: ShaperClusterToken?,
)

private data class NativeGlyphRecord(
    val glyphId: Int,
    val tokenValue: Int,
    val safetyMask: Int,
    val xAdvance: Int,
    val yAdvance: Int,
    val xOffset: Int,
    val yOffset: Int,
)

/**
 * Direct result of the native `hb_ot_layout_get_ligature_carets` ABI call.
 *
 * [totalCount] is the function return value and [copiedCount] is the in/out count after the
 * call. They are deliberately retained separately so the portable interpretation can reject a
 * truncated or otherwise inconsistent native response. Tests may construct this value to audit
 * the ABI boundary without using a second live HarfBuzz invocation as an oracle.
 */
internal data class NativeLigatureCaretResponse(
    val totalCount: Int,
    val copiedCount: Int,
    /** Whether the final shaped horizontal advance still equals the unshaped glyph advance. */
    val finalAdvanceMatchesUnshapedAdvance: Boolean = true,
    val positions: List<LayoutUnit>,
)

/** Interprets an audited native GDEF response against a cluster's editable grapheme boundaries. */
internal object LigatureCaretFactInterpreter {
    /**
     * Returns a fact whose boundaries are logical-source ordered, independently of glyph output
     * order. HarfBuzz exposes GDEF carets in increasing glyph-coordinate order; that order is
     * reversed for right-to-left source text while each signed position remains relative to the
     * same glyph origin and baseline.
     */
    fun fromNativeResponse(
        glyphIndex: Int,
        direction: ShapingDirection,
        cluster: ShaperCluster,
        response: NativeLigatureCaretResponse,
    ): GdefLigatureCaretFact {
        val logicalSourceBoundaries = cluster.internalAdmissibleGraphemeBoundaries()
        require(logicalSourceBoundaries.isNotEmpty()) {
            "GDEF caret interpretation requires an editable internal grapheme boundary."
        }
        val expectedCount = logicalSourceBoundaries.size
        return when {
            response.totalCount == 0 && response.copiedCount == 0 ->
                GdefLigatureCaretFact(
                    glyphIndex = glyphIndex,
                    state = GdefLigatureCaretState.ABSENT,
                    logicalSourceBoundaries = logicalSourceBoundaries,
                )

            response.totalCount != expectedCount ||
                response.copiedCount != expectedCount ||
                response.positions.size != expectedCount ||
                !response.finalAdvanceMatchesUnshapedAdvance ->
                GdefLigatureCaretFact(
                    glyphIndex = glyphIndex,
                    state = GdefLigatureCaretState.INCONSISTENT,
                    logicalSourceBoundaries = logicalSourceBoundaries,
                )

            else ->
                GdefLigatureCaretFact(
                    glyphIndex = glyphIndex,
                    state = GdefLigatureCaretState.AVAILABLE,
                    logicalSourceBoundaries = logicalSourceBoundaries,
                    positions = if (direction == ShapingDirection.RIGHT_TO_LEFT) {
                        response.positions.asReversed()
                    } else {
                        response.positions
                    },
                )
        }
    }
}

private fun ShaperCluster.internalAdmissibleGraphemeBoundaries() = admissibleGraphemeBoundaries.filter { boundary ->
    boundary.compareTo(sourceRange.start) > 0 && boundary.compareTo(sourceRange.endExclusive) < 0
}

private fun advancesMatch(shapedAdvance: Int, unshapedAdvance: Int): Boolean =
    absoluteMagnitude(shapedAdvance) == absoluteMagnitude(unshapedAdvance)

private fun absoluteMagnitude(value: Int): Long =
    value.toLong().let { if (it < 0L) -it else it }

internal class DesignToLayoutScale private constructor(
    private val layoutSize: Double,
    val unitsPerEm: Int,
) {
    fun convert(designUnit: Int): LayoutUnit {
        val converted = designUnit.toDouble() * layoutSize / unitsPerEm.toDouble()
        val narrowed = converted.toFloat()
        require(converted.isFinite() && narrowed.isFinite()) {
            "A HarfBuzz design-unit value cannot be represented as a finite layout unit."
        }
        return LayoutUnit(narrowed)
    }

    companion object {
        fun create(layoutSize: Float, unitsPerEm: Int): DesignToLayoutScale {
            require(layoutSize.isFinite() && layoutSize > 0f) {
                "The layout size must be finite and positive."
            }
            require(unitsPerEm > 0) { "HarfBuzz returned a non-positive face units-per-em value." }
            return DesignToLayoutScale(layoutSize.toDouble(), unitsPerEm)
        }
    }
}

private fun ShapingDirection.toHarfBuzzDirection(): Int = when (this) {
    ShapingDirection.LEFT_TO_RIGHT -> HB_DIRECTION_LTR
    ShapingDirection.RIGHT_TO_LEFT -> HB_DIRECTION_RTL
    ShapingDirection.TOP_TO_BOTTOM -> HB_DIRECTION_TTB
}

/** Converts HarfBuzz's upward-positive vertical values to portable downward-positive layout values. */
private fun Int.toPhysicalVerticalCoordinate(
    direction: ShapingDirection,
    scale: DesignToLayoutScale,
): LayoutUnit = when (direction) {
    ShapingDirection.TOP_TO_BOTTOM -> scale.convert(-this)
    ShapingDirection.LEFT_TO_RIGHT,
    ShapingDirection.RIGHT_TO_LEFT,
    -> scale.convert(this)
}

private fun openTypeTag(tag: String): Int =
    (tag[0].code shl 24) or (tag[1].code shl 16) or (tag[2].code shl 8) or tag[3].code

private fun requireNativeHandle(handle: MemorySegment, label: String): MemorySegment {
    require(handle != MemorySegment.NULL) { "HarfBuzz could not create a native $label." }
    return handle
}

private fun address(handle: MethodHandle, vararg arguments: Any): MemorySegment =
    handle.invokeWithArguments(*arguments) as MemorySegment

private fun int(handle: MethodHandle, vararg arguments: Any): Int =
    handle.invokeWithArguments(*arguments) as Int

private fun callVoid(handle: MethodHandle, vararg arguments: Any) {
    handle.invokeWithArguments(*arguments)
}

private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun shapingFailure(code: String, message: String): FontOperationResult.Failure {
    val error = FontError.FontDataFailure(code, message, FontDiagnosticLocation.Source)
    return FontOperationResult.Failure(error, listOf(error.toDiagnostic()))
}

private fun shapingResourceLimitFailure(
    limit: ShapingResourceLimit,
    observed: Int,
): FontOperationResult.Failure {
    val error = FontError.ShapingResourceLimitExceeded(limit, observed)
    return FontOperationResult.Failure(error, listOf(error.toDiagnostic()))
}

private fun observeCancellation(request: ShapingRequest) {
    if (request.cancellationToken.isCancellationRequested()) throw ShapingCancelled
}

private fun observeCancellation(request: ShapingRequest, itemIndex: Int) {
    if (itemIndex % request.resourceProfile.cancellationCheckInterval == 0) observeCancellation(request)
}

private data object ShapingCancelled : RuntimeException()

private class ShapingLimitExceeded(
    val limit: ShapingResourceLimit,
    val observed: Int,
) : RuntimeException()

private const val HARFBUZZ_VERSION: String = "14.3.0"
private const val LWJGL_HARFBUZZ_SOURCE_REVISION: String = "9f2f03173b7fee860cc00d999857d09fa4a362e2"
private const val HARFBUZZ_RELEASE_SOURCE_REVISION: String = "4c2aa804671d7276e8a0eb95da07202ead05c843"
private const val MACOS_BUILD_CHAIN_IDENTITY: String =
    "cmake-4.4.3;appleclang-21.0.0;macos-sdk-26.5;deployment-target-11.0"
private val PINNED_FEATURE_POLICY: ShapingFeaturePolicy = ShapingFeaturePolicy(
    policyId = "harfbuzz-defaults",
    version = HARFBUZZ_VERSION,
    application = ShapingFeaturePolicyApplication.PINNED_BACKEND_DEFAULTS,
)
private const val CONFIGURATION_FINGERPRINT: String =
    "harfbuzz-14.3.0;shaper=ot;ot-font-funcs;scale=face-upem;layout-conversion=layout-size-over-upem;explicit-direction-script-language-bot-eot;" +
        "cluster-level=monotone-characters;flags=produce-unsafe-to-concat;feature-policy=harfbuzz-defaults@14.3.0;feature-overrides=explicit"
private val HARFBUZZ_SEMANTIC_IDENTITY: ShapingSemanticIdentity = ShapingSemanticIdentity(
    backendId = "harfbuzz-jvm",
    engineId = "harfbuzz",
    engineVersion = HARFBUZZ_VERSION,
    shaperId = "ot",
    featurePolicy = PINNED_FEATURE_POLICY,
    configurationFingerprint = CONFIGURATION_FINGERPRINT,
)
private val NON_DETERMINISTIC_FEATURES: Set<String> = setOf("rand")
private const val HB_MEMORY_MODE_READONLY: Int = 1
private const val HB_DIRECTION_LTR: Int = 4
private const val HB_DIRECTION_RTL: Int = 5
private const val HB_DIRECTION_TTB: Int = 6
private const val HB_BUFFER_FLAG_BOT: Int = 0x00000001
private const val HB_BUFFER_FLAG_EOT: Int = 0x00000002
private const val HB_BUFFER_FLAG_PRODUCE_UNSAFE_TO_CONCAT: Int = 0x00000040
private const val HB_BUFFER_CLUSTER_LEVEL_MONOTONE_CHARACTERS: Int = 1
private const val HB_GLYPH_FLAG_UNSAFE_TO_BREAK: Int = 0x00000001
private const val HB_GLYPH_FLAG_UNSAFE_TO_CONCAT: Int = 0x00000002
private const val FEATURE_BYTES: Long = 16L
private const val GLYPH_INFO_BYTES: Long = 20L
private const val GLYPH_POSITION_BYTES: Long = 20L
private const val MAX_VERSION_BYTES: Long = 32L
