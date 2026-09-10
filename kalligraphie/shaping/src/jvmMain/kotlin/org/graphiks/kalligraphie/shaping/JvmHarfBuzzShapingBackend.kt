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

    /**
     * Opens the pinned library with independent prepared-font admission bounds.
     * [preparedFontCachePolicy] counts active and idle resources; a rejected preparation is a
     * typed resource-limit failure. The owner must close the returned backend.
     */
    @JvmOverloads
    public fun open(
        preparedFontCachePolicy: JvmPreparedFontCachePolicy = JvmPreparedFontCachePolicy.default,
    ): FontOperationResult<ShapingBackend> =
        when (val loaded = HarfBuzzNativeLoader.load()) {
            is FontOperationResult.Success -> FontOperationResult.Success(HarfBuzzJvmBackend(loaded.value, preparedFontCachePolicy))
            is FontOperationResult.Failure -> loaded
            is FontOperationResult.Cancelled -> loaded
        }

    /**
     * Captures an internal accounting reader without exposing the native backend type.
     * Other backend implementations report an immutable empty usage.
     */
    @org.graphiks.kalligraphie.api.KalligraphieInternalApi
    public fun preparedFontCacheUsageInspector(backend: ShapingBackend): () -> JvmPreparedFontCacheUsage =
        if (backend is HarfBuzzJvmBackend) {
            { backend.preparedFontCacheUsage }
        } else {
            { JvmPreparedFontCacheUsage(0, 0, 0, 0, 0, 0) }
        }
}

private class HarfBuzzJvmBackend(
    private val nativeLibrary: HarfBuzzNativeLibrary,
    policy: JvmPreparedFontCachePolicy,
) : ShapingBackend {
    override val identity: ShapingBackendIdentity = nativeLibrary.identity
    private val preparedFonts = PreparedFontCache(policy)
    val preparedFontCacheUsage: JvmPreparedFontCacheUsage get() = preparedFonts.usage
    @Volatile
    private var closed: Boolean = false

    override fun shape(request: ShapingRequest): FontOperationResult<ShapedGlyphRun> {
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
            val prepared = preparedFonts.acquire(
                request.font.key,
                source = { copyFontBytes(request) },
                create = { bytes -> nativeLibrary.prepare(bytes, layoutSize) },
            )
            try {
                observeCancellation(request)
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
    }

    @Synchronized
    override fun close(): FontOperationResult<Unit> {
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
    }

    private fun copyFontBytes(request: ShapingRequest): ByteArray {
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
        return bytes
    }
}

private data class PreparedFontFootprint(
    val sourceBytes: Long,
    val estimatedNativeBytes: Long,
    val estimatorVersion: String,
) {
    val totalBytes: Long get() = saturatedAdd(sourceBytes, estimatedNativeBytes)
}

/** See JvmPreparedFontCachePolicy.nativeEstimatorVersion for scope and limitations. */
private fun preparedFontFootprint(sourceBytes: Long): PreparedFontFootprint = PreparedFontFootprint(
    sourceBytes,
    saturatedAdd(256L * 1024, if (sourceBytes > Long.MAX_VALUE / 4) Long.MAX_VALUE else sourceBytes * 4),
    JvmPreparedFontCachePolicy.nativeEstimatorVersion,
)

private fun saturatedAdd(left: Long, right: Long): Long =
    if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

/**
 * Source copying and its public callbacks run outside the lock. Admission, native preparation
 * and release share one lock; after copying, acquisition rechecks closure and an existing font
 * before reserving any native allocation. Keeping destruction under the lock prevents another
 * allocation from racing an idle eviction.
 */
private class PreparedFontCache(private val policy: JvmPreparedFontCachePolicy) {
    private val lock = Any()
    private val entries = LinkedHashMap<FontInstanceKey, Entry>()
    private var closed = false
    private var releaseFailed = false

    val usage: JvmPreparedFontCacheUsage get() = synchronized(lock) {
        val idle = entries.values.filter { it.activeLeases == 0 }
        val active = entries.values.filter { it.activeLeases > 0 }
        JvmPreparedFontCacheUsage(
            idle.size, active.sumOf { it.activeLeases },
            idle.sumOf { it.footprint.sourceBytes }, active.sumOf { it.footprint.sourceBytes },
            idle.sumOf { it.footprint.estimatedNativeBytes }, active.sumOf { it.footprint.estimatedNativeBytes },
        )
    }

    fun acquire(
        key: FontInstanceKey,
        source: () -> ByteArray,
        create: (ByteArray) -> PreparedHarfBuzzFont,
    ): Lease {
        synchronized(lock) {
            if (closed || releaseFailed) throw PreparedFontFailure(FontOperationResult.Failure(
                FontError.ResourceClosed("The pinned HarfBuzz backend is closed."),
            ))
            entries[key]?.let { entry ->
                entry.activeLeases += 1
                return Lease(checkNotNull(entry.value)) { releaseLease(key, entry) }
            }
        }

        // Font providers and cancellation tokens may reenter this backend or close it.
        val bytes = source()
        val footprint = preparedFontFootprint(bytes.size.toLong())
        return synchronized(lock) {
            if (closed || releaseFailed) throw PreparedFontFailure(FontOperationResult.Failure(
                FontError.ResourceClosed("The pinned HarfBuzz backend is closed."),
            ))
            // A concurrent or nested acquisition may have prepared this key while copying.
            entries[key]?.let { entry ->
                entry.activeLeases += 1
                return@synchronized Lease(checkNotNull(entry.value)) { releaseLease(key, entry) }
            }
            if (!fits(footprint, emptyList())) reject()
            while (!fits(footprint, entries.values)) {
                val idle = entries.entries.firstOrNull { it.value.activeLeases == 0 } ?: reject()
                // Release before subtracting accounting or admitting new native memory.
                try {
                    checkNotNull(idle.value.value).close()
                } catch (error: Throwable) {
                    // A failed native destruction must neither be retried nor permit more allocation.
                    releaseFailed = true
                    throw error
                } finally {
                    entries.remove(idle.key)
                }
            }
            val reservation = Entry(footprint, activeLeases = 1)
            entries[key] = reservation
            try {
                reservation.value = create(bytes)
                Lease(checkNotNull(reservation.value)) { releaseLease(key, reservation) }
            } catch (error: Throwable) {
                entries.remove(key)
                throw error
            }
        }
    }

    private fun fits(footprint: PreparedFontFootprint, retained: Collection<Entry>): Boolean {
        if (retained.size >= policy.maxEntries) return false
        val source = retained.sumOf { it.footprint.sourceBytes }
        val native = retained.sumOf { it.footprint.estimatedNativeBytes }
        val total = source + native // Existing admission guarantees this sum fits in Long.
        return footprint.sourceBytes <= policy.maxSourceBytes - source &&
            footprint.estimatedNativeBytes <= policy.maxEstimatedNativeBytes - native &&
            footprint.totalBytes <= policy.maxTotalBytes - total
    }

    private fun reject(): Nothing = throw PreparedFontFailure(FontOperationResult.Failure(
        FontError.ResourceLimitExceeded(
            "The prepared HarfBuzz font cannot fit the session cache policy, including active leases.",
            FontDiagnosticLocation.Source,
        ),
    ))

    fun close(): List<Throwable> = synchronized(lock) {
        if (closed) return@synchronized emptyList()
        closed = true
        val failures = mutableListOf<Throwable>()
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.activeLeases == 0) {
                try {
                    checkNotNull(entry.value).close()
                } catch (error: Throwable) {
                    failures += error
                } finally {
                    iterator.remove()
                }
            }
        }
        failures
    }

    private fun releaseLease(key: FontInstanceKey, entry: Entry) = synchronized(lock) {
        check(entry.activeLeases > 0)
        entry.activeLeases -= 1
        if (closed && entry.activeLeases == 0) {
            try {
                checkNotNull(entry.value).close()
            } finally {
                entries.remove(key)
            }
        }
    }

    private class Entry(
        val footprint: PreparedFontFootprint,
        var activeLeases: Int,
        var value: PreparedHarfBuzzFont? = null,
    )

    class Lease(val value: PreparedHarfBuzzFont, private val release: () -> Unit) : AutoCloseable {
        private var closed = false

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            release()
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
