package org.graphiks.kalligraphie

import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.ceil
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BitmapLimits
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontMaterializationCachePolicy
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphColorSpace
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphPaintCompositionMode
import org.graphiks.kalligraphie.api.GlyphPaintNodeKind
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.PaintGraphLimits
import org.graphiks.kalligraphie.api.PaintGraphProfile

class GlyphMaterializationBenchmarkTest {
    @Test
    fun runsEveryConfiguredMaterializationProfileOnlyWhenExplicitlyEnabled() {
        if (System.getenv(GLYPH_MATERIALIZATION_MEASUREMENT_ENVIRONMENT) != "true") return

        val warmupIterations = positiveEnvironmentInteger(GLYPH_MATERIALIZATION_WARMUP_ENVIRONMENT, defaultValue = 5)
        val iterations = positiveEnvironmentInteger(GLYPH_MATERIALIZATION_ITERATIONS_ENVIRONMENT, defaultValue = 20)
        val output = checkNotNull(System.getenv(GLYPH_MATERIALIZATION_OUTPUT_ENVIRONMENT)) {
            "$GLYPH_MATERIALIZATION_OUTPUT_ENVIRONMENT must name an absolute file outside the repository."
        }
        val outputPath = Path.of(output).toAbsolutePath().normalize()
        require(!outputPath.startsWith(repositoryRoot())) {
            "$GLYPH_MATERIALIZATION_OUTPUT_ENVIRONMENT must be outside the repository; received $outputPath."
        }

        val report = GlyphMaterializationBenchmark.run(warmupIterations, iterations)
        val rendered = report.toMarkdown()
        outputPath.parent?.let(Files::createDirectories)
        Files.writeString(outputPath, rendered)

        assertEquals(GlyphMaterializationBenchmark.requiredProfileNames, report.profiles.map(GlyphMaterializationMeasurementProfile::name))
        assertTrue(rendered.contains("Glyph materialization measurement"))
    }

    private fun positiveEnvironmentInteger(name: String, defaultValue: Int): Int {
        val value = System.getenv(name)?.toIntOrNull() ?: defaultValue
        require(value > 0) { "$name must be a positive integer." }
        return value
    }

    private fun repositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (Files.exists(candidate.resolve(".git"))) return candidate
            candidate = candidate.parent
        }
        error("Could not locate the repository root from the test working directory.")
    }

    private companion object {
        const val GLYPH_MATERIALIZATION_MEASUREMENT_ENVIRONMENT = "KALLIGRAPHIE_GLYPH_MATERIALIZATION_MEASUREMENT"
        const val GLYPH_MATERIALIZATION_WARMUP_ENVIRONMENT = "KALLIGRAPHIE_GLYPH_MATERIALIZATION_WARMUP"
        const val GLYPH_MATERIALIZATION_ITERATIONS_ENVIRONMENT = "KALLIGRAPHIE_GLYPH_MATERIALIZATION_ITERATIONS"
        const val GLYPH_MATERIALIZATION_OUTPUT_ENVIRONMENT = "KALLIGRAPHIE_GLYPH_MATERIALIZATION_OUTPUT"
    }
}

internal data class GlyphMaterializationMeasurementEnvironment(
    val commit: String,
    val machine: String,
    val operatingSystem: String,
    val jvm: String,
    val fontHashes: Map<String, String>,
    val gcPolicy: String,
)

internal data class GlyphMaterializationMeasurementCorpus(
    val id: String,
    val description: String,
    val glyphCount: Int,
)

internal data class GlyphMaterializationPercentiles(
    val p50Nanos: Long,
    val p95Nanos: Long,
    val p99Nanos: Long,
)

internal data class GlyphMaterializationMeasurementValue(
    val state: String,
    val value: Long?,
    val detail: String,
) {
    companion object {
        fun available(value: Number, detail: String): GlyphMaterializationMeasurementValue =
            GlyphMaterializationMeasurementValue("available", value.toLong(), detail)

        fun unavailable(detail: String): GlyphMaterializationMeasurementValue =
            GlyphMaterializationMeasurementValue("unavailable", null, detail)
    }
}

internal data class GlyphMaterializationMeasurementProfile(
    val name: String,
    val route: String,
    val timedBoundary: String,
    val cacheState: String,
    val warmupIterations: Int,
    val iterations: Int,
    val latency: GlyphMaterializationPercentiles,
    val allocations: GlyphMaterializationMeasurementValue,
    val retainedJvmMemory: GlyphMaterializationMeasurementValue,
    val retainedNativeMemory: GlyphMaterializationMeasurementValue,
    val nativeAllocations: GlyphMaterializationMeasurementValue,
    val sourceBytes: GlyphMaterializationMeasurementValue,
    val decodedBytes: GlyphMaterializationMeasurementValue,
    val normalizedNodes: GlyphMaterializationMeasurementValue,
    val decodedPixels: GlyphMaterializationMeasurementValue,
    val cancellationDelay: GlyphMaterializationMeasurementValue,
)

internal data class GlyphMaterializationMeasurementReport(
    val metadata: GlyphMaterializationMeasurementEnvironment,
    val corpus: GlyphMaterializationMeasurementCorpus,
    val profiles: List<GlyphMaterializationMeasurementProfile>,
) {
    fun toMarkdown(): String = buildString {
        appendLine("# Glyph materialization measurement")
        appendLine()
        appendLine("- Commit: `${metadata.commit}`")
        appendLine("- Machine: ${metadata.machine}")
        appendLine("- OS: ${metadata.operatingSystem}")
        appendLine("- JVM: ${metadata.jvm}")
        appendLine("- GC policy: ${metadata.gcPolicy}")
        appendLine("- Corpus: `${corpus.id}` — ${corpus.description}")
        appendLine("- Glyphs: ${corpus.glyphCount}")
        appendLine("- Font SHA-256:")
        metadata.fontHashes.forEach { (name, hash) -> appendLine("  - `$name`: `$hash`") }
        profiles.forEach { profile ->
            appendLine()
            appendLine("## ${profile.name}")
            appendLine()
            appendLine("- Route: ${profile.route}")
            appendLine("- Timed boundary: ${profile.timedBoundary}")
            appendLine("- Cache state: ${profile.cacheState}")
            appendLine("- Warmup iterations: ${profile.warmupIterations}")
            appendLine("- Measured iterations: ${profile.iterations}")
            appendLine("- Latency p50: ${profile.latency.p50Nanos} ns")
            appendLine("- Latency p95: ${profile.latency.p95Nanos} ns")
            appendLine("- Latency p99: ${profile.latency.p99Nanos} ns")
            appendMeasurement("Allocations", profile.allocations)
            appendMeasurement("Retained JVM memory", profile.retainedJvmMemory)
            appendMeasurement("Retained native memory", profile.retainedNativeMemory)
            appendMeasurement("Native allocations", profile.nativeAllocations)
            appendMeasurement("Source bytes", profile.sourceBytes)
            appendMeasurement("Decoded bytes", profile.decodedBytes)
            appendMeasurement("Normalized nodes", profile.normalizedNodes)
            appendMeasurement("Decoded pixels", profile.decodedPixels)
            appendMeasurement("Cancellation delay", profile.cancellationDelay)
        }
    }

    private fun StringBuilder.appendMeasurement(label: String, measurement: GlyphMaterializationMeasurementValue) {
        val value = measurement.value?.let { ": $it" } ?: ""
        appendLine("- $label: ${measurement.state}$value (${measurement.detail})")
    }
}

internal object GlyphMaterializationBenchmark {
    internal val requiredProfileNames: List<String> = listOf(
        "ColrColdNormalization",
        "ColrWarmResolution",
        "SvgColdNormalization",
        "SvgWarmResolution",
        "BitmapColdDecode",
        "BitmapWarmResolution",
        "PaletteChange",
        "CachePressureAndEviction",
        "CooperativeCancellation",
    )

    fun reportFor(
        environment: GlyphMaterializationMeasurementEnvironment,
        corpus: GlyphMaterializationMeasurementCorpus,
        profiles: List<GlyphMaterializationMeasurementProfile>,
    ): GlyphMaterializationMeasurementReport {
        require(environment.commit.isNotBlank()) { "A measured commit is required." }
        require(environment.machine.isNotBlank()) { "A measured machine is required." }
        require(environment.operatingSystem.isNotBlank()) { "A measured operating system is required." }
        require(environment.jvm.isNotBlank()) { "A measured JVM is required." }
        require(environment.fontHashes.isNotEmpty()) { "At least one fixture hash is required." }
        require(corpus.id.isNotBlank() && corpus.description.isNotBlank() && corpus.glyphCount > 0) {
            "A non-empty materialization corpus is required."
        }
        require(profiles.map(GlyphMaterializationMeasurementProfile::name) == requiredProfileNames) {
            "Measurement reports must contain the required materialization profiles in their documented order."
        }
        require(profiles.all { profile ->
            profile.route.isNotBlank() &&
                profile.timedBoundary.isNotBlank() &&
                profile.cacheState.isNotBlank() &&
                profile.warmupIterations > 0 &&
                profile.iterations > 0
        }) { "Every materialization profile needs a route, timing boundary, cache state, and positive iteration counts." }
        return GlyphMaterializationMeasurementReport(environment, corpus, profiles.toList())
    }

    fun run(warmupIterations: Int, iterations: Int): GlyphMaterializationMeasurementReport {
        require(warmupIterations > 0) { "Warmup iterations must be positive." }
        require(iterations > 0) { "Measured iterations must be positive." }
        val colr = Fixture("BungeeColor-Regular.ttf", "Bungee Color COLR v0", GlyphId(43), colrBytes())
        val svg = Fixture("TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf", "TwitterColorEmoji SVG-in-OpenType", GlyphId(1), svgBytes())
        val bitmap = Fixture("ebdt_fmt1.ttf", "Skia EBDT format 1", GlyphId(3), bitmapBytes())
        val environment = GlyphMaterializationMeasurementEnvironment(
            commit = currentCommit(),
            machine = machineName(),
            operatingSystem = "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})",
            jvm = "${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}",
            fontHashes = listOf(colr, svg, bitmap).associate { fixture -> fixture.name to fixture.bytes.sha256Hex() },
            gcPolicy = GC_POLICY,
        )
        val profiles = listOf(
            coldProfile("ColrColdNormalization", colr, colrRequirements(), "COLR v0 / CPAL v0 to GlyphPaintIR", warmupIterations, iterations),
            warmProfile("ColrWarmResolution", colr, colrRequirements(), "COLR v0 / CPAL v0 cached GlyphPaintIR", warmupIterations, iterations),
            coldProfile("SvgColdNormalization", svg, svgRequirements(), "SVG-in-OpenType v0 to GlyphPaintIR", warmupIterations, iterations),
            warmProfile("SvgWarmResolution", svg, svgRequirements(), "SVG-in-OpenType v0 cached GlyphPaintIR", warmupIterations, iterations),
            coldProfile("BitmapColdDecode", bitmap, bitmapRequirements(), "EBLC v2 / EBDT v2 format 1 to BitmapGlyphIR", warmupIterations, iterations),
            warmProfile("BitmapWarmResolution", bitmap, bitmapRequirements(), "EBLC v2 / EBDT v2 cached BitmapGlyphIR", warmupIterations, iterations),
            paletteChangeProfile(colr, warmupIterations, iterations),
            cachePressureProfile(svg, warmupIterations, iterations),
            cancellationProfile(colr, warmupIterations, iterations),
        )
        return reportFor(
            environment = environment,
            corpus = GlyphMaterializationMeasurementCorpus(
                id = "portable-glyph-materialization-v1",
                description = "one audited COLR/CPAL glyph, one audited SVG-in-OpenType glyph, and one audited EBDT format 1 glyph",
                glyphCount = 3,
            ),
            profiles = profiles,
        )
    }

    private fun coldProfile(
        name: String,
        fixture: Fixture,
        requirements: FontAccessRequirementsSnapshot,
        route: String,
        warmupIterations: Int,
        iterations: Int,
    ): GlyphMaterializationMeasurementProfile = measuredProfile(
        name = name,
        route = route,
        timedBoundary = "starts before embedded catalog creation and ends after the resolved immutable representation is consumed; handle closure is excluded",
        cacheState = "cold: a new embedded catalog and resolver are created for every sample",
        warmupIterations = warmupIterations,
        iterations = iterations,
    ) { record ->
        repeat(warmupIterations + iterations) { index ->
            val sample = timed { resolveCold(fixture, requirements) }
            if (index >= warmupIterations) record(sample)
        }
    }

    private fun warmProfile(
        name: String,
        fixture: Fixture,
        requirements: FontAccessRequirementsSnapshot,
        route: String,
        warmupIterations: Int,
        iterations: Int,
    ): GlyphMaterializationMeasurementProfile = measuredProfile(
        name = name,
        route = route,
        timedBoundary = "starts immediately before resolveGlyph and ends after the cached immutable representation is consumed; setup and closure are excluded",
        cacheState = "warm: one untimed successful seed and configured warmup populate the per-face portable representation cache",
        warmupIterations = warmupIterations,
        iterations = iterations,
    ) { record ->
        val opened = openAsset(fixture, requirements, cachePolicy = CACHE_POLICY)
        try {
            consume(success(opened.asset.resolveGlyph(FontGlyphRequest(fixture.glyphId))))
            repeat(warmupIterations + iterations) { index ->
                val sample = timed {
                    observe(success(opened.asset.resolveGlyph(FontGlyphRequest(fixture.glyphId))), sourceBytes = 0)
                }
                if (index >= warmupIterations) record(sample)
            }
        } finally {
            opened.close()
        }
    }

    private fun paletteChangeProfile(
        fixture: Fixture,
        warmupIterations: Int,
        iterations: Int,
    ): GlyphMaterializationMeasurementProfile = measuredProfile(
        name = "PaletteChange",
        route = "COLR v0 / CPAL v0 palette 0 to palette 1",
        timedBoundary = "starts before palette-1 asset acquisition and ends after its paint graph is consumed; the palette-0 seed and closure are excluded",
        cacheState = "palette 0 is seeded before each sample; palette 1 is a distinct render variant and representation key",
        warmupIterations = warmupIterations,
        iterations = iterations,
    ) { record ->
        repeat(warmupIterations + iterations) { index ->
            val opened = openAsset(fixture, colrRequirements(), FontRenderVariantSnapshot(cpalPaletteIndex = 0), CACHE_POLICY)
            try {
                consume(success(opened.asset.resolveGlyph(FontGlyphRequest(fixture.glyphId))))
                val sample = timed {
                    val paletteOne = success(
                        opened.instance.acquireRenderAsset(
                            opened.resolver,
                            FontRenderVariantSnapshot(cpalPaletteIndex = 1),
                            colrRequirements(),
                        ),
                    )
                    try {
                        observe(success(paletteOne.resolveGlyph(FontGlyphRequest(fixture.glyphId))), sourceBytes = 0)
                    } finally {
                        paletteOne.close()
                    }
                }
                if (index >= warmupIterations) record(sample)
            } finally {
                opened.close()
            }
        }
    }

    private fun cachePressureProfile(
        fixture: Fixture,
        warmupIterations: Int,
        iterations: Int,
    ): GlyphMaterializationMeasurementProfile = measuredProfile(
        name = "CachePressureAndEviction",
        route = "SVG-in-OpenType v0 with profile-key pressure and LRU eviction",
        timedBoundary = "starts before the original glyph is seeded, includes five distinct certified profile keys, and ends after the original glyph is resolved again",
        cacheState = "10,000-byte face budget; the pressure profile is intentionally distinct while producing the same SVG route",
        warmupIterations = warmupIterations,
        iterations = iterations,
    ) { record ->
        repeat(warmupIterations + iterations) { index ->
            val sample = timed {
                val opened = openAsset(fixture, svgRequirements(), cachePolicy = PRESSURE_CACHE_POLICY)
                try {
                    consume(success(opened.asset.resolveGlyph(FontGlyphRequest(fixture.glyphId))))
                    repeat(5) { profileOffset ->
                        val pressure = success(
                            opened.instance.acquireRenderAsset(
                                opened.resolver,
                                FontRenderVariantSnapshot.default,
                                svgRequirements(maxSourceBytes = 16 * 1024 + profileOffset + 1),
                            ),
                        )
                        try {
                            consume(success(pressure.resolveGlyph(FontGlyphRequest(fixture.glyphId))))
                        } finally {
                            pressure.close()
                        }
                    }
                    observe(success(opened.asset.resolveGlyph(FontGlyphRequest(fixture.glyphId))), fixture.bytes.size.toLong())
                } finally {
                    opened.close()
                }
            }
            if (index >= warmupIterations) record(sample)
        }
    }

    private fun cancellationProfile(
        fixture: Fixture,
        warmupIterations: Int,
        iterations: Int,
    ): GlyphMaterializationMeasurementProfile = measuredProfile(
        name = "CooperativeCancellation",
        route = "COLR v0 / CPAL v0 outline materialization",
        timedBoundary = "starts before resolveGlyph with a cooperative token and ends at the typed cancellation return",
        cacheState = "cache disabled so cancellation reaches a real two-layer COLR materialization instead of a cache hit",
        warmupIterations = warmupIterations,
        iterations = iterations,
        cancellation = true,
    ) { record ->
        val opened = openAsset(fixture, colrRequirements(), cachePolicy = FontMaterializationCachePolicy.disabled)
        try {
            repeat(warmupIterations + iterations) { index ->
                val token = CancelsOnCheck(2)
                val sample = timed {
                    check(opened.asset.resolveGlyph(FontGlyphRequest(fixture.glyphId), token) is FontOperationResult.Cancelled) {
                        "Cancellation profile must return a typed cancelled result."
                    }
                    Observation.empty(checkNotNull(token.signaledAtNanos) { "Cancellation token did not signal during materialization." })
                }
                if (index >= warmupIterations) record(sample)
            }
        } finally {
            opened.close()
        }
    }

    private fun resolveCold(fixture: Fixture, requirements: FontAccessRequirementsSnapshot): Observation {
        val opened = openAsset(fixture, requirements, cachePolicy = CACHE_POLICY)
        return try {
            observe(success(opened.asset.resolveGlyph(FontGlyphRequest(fixture.glyphId))), fixture.bytes.size.toLong())
        } finally {
            opened.close()
        }
    }

    private fun openAsset(
        fixture: Fixture,
        requirements: FontAccessRequirementsSnapshot,
        variant: FontRenderVariantSnapshot = FontRenderVariantSnapshot.default,
        cachePolicy: FontMaterializationCachePolicy,
    ): OpenAsset {
        val catalog = success(Kalligraphie.embedded(fixture.bytes, FontSourceProvenance(fixture.provenance), cachePolicy))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val asset = success(instance.acquireRenderAsset(resolver, variant, requirements))
        return OpenAsset(resolver, instance, asset)
    }

    private fun measuredProfile(
        name: String,
        route: String,
        timedBoundary: String,
        cacheState: String,
        warmupIterations: Int,
        iterations: Int,
        cancellation: Boolean = false,
        run: ((Sample) -> Unit) -> Unit,
    ): GlyphMaterializationMeasurementProfile {
        forceGc()
        val retainedBefore = usedHeapBytes()
        val samples = mutableListOf<Sample>()
        run(samples::add)
        forceGc()
        val retainedAfter = usedHeapBytes()
        check(samples.size == iterations) { "$name recorded ${samples.size} measured samples instead of $iterations." }
        val allocations = samples.mapNotNull(Sample::allocatedBytes)
        val cancellationDelays = samples.mapNotNull(Sample::cancellationDelayNanos)
        return GlyphMaterializationMeasurementProfile(
            name = name,
            route = route,
            timedBoundary = timedBoundary,
            cacheState = cacheState,
            warmupIterations = warmupIterations,
            iterations = iterations,
            latency = percentiles(samples.map(Sample::elapsedNanos)),
            allocations = if (allocations.size == samples.size) measurement(allocations.averageAsLong(), "bytes allocated by the measured thread per iteration") else unavailable("thread allocation counters are unavailable on this JVM"),
            retainedJvmMemory = measurement(retainedAfter - retainedBefore, "signed used-heap delta after documented forced-GC samples"),
            retainedNativeMemory = unavailable("portable routes expose no retained native-memory accounting boundary"),
            nativeAllocations = unavailable("portable routes expose no native-allocation counter"),
            sourceBytes = measurement(samples.map { it.observation.sourceBytes }.averageAsLong(), "fixture source bytes supplied to embedded catalogs during the timed boundary"),
            decodedBytes = measurement(samples.map { it.observation.decodedBytes }.averageAsLong(), "decoded immutable bitmap bytes produced per measured iteration"),
            normalizedNodes = measurement(samples.map { it.observation.normalizedNodes }.averageAsLong(), "portable paint-graph nodes produced per measured iteration"),
            decodedPixels = measurement(samples.map { it.observation.decodedPixels }.averageAsLong(), "decoded bitmap pixels produced per measured iteration"),
            cancellationDelay = if (cancellation) {
                check(cancellationDelays.size == samples.size) { "$name must observe a cancellation signal in every measured sample." }
                measurement(percentiles(cancellationDelays).p95Nanos, "p95 nanoseconds from the first in-operation token signal to typed cancellation return")
            } else {
                unavailable("profile does not signal cancellation")
            },
        )
    }

    private fun timed(operation: () -> Observation): Sample {
        val allocationsBefore = ThreadAllocationProbe.currentBytes()
        val started = System.nanoTime()
        val observation = operation()
        val completed = System.nanoTime()
        val allocationsAfter = ThreadAllocationProbe.currentBytes()
        consume(observation.representation)
        return Sample(
            elapsedNanos = max(1L, completed - started),
            allocatedBytes = if (allocationsBefore != null && allocationsAfter != null) max(0L, allocationsAfter - allocationsBefore) else null,
            observation = observation,
            cancellationDelayNanos = observation.cancellationSignaledAtNanos?.let { signal -> completed - signal },
        )
    }

    private fun observe(representation: GlyphRepresentation, sourceBytes: Long): Observation = when (representation) {
        is GlyphRepresentation.Paint -> Observation(representation, sourceBytes, 0L, representation.paint.nodes.size.toLong(), 0L)
        is GlyphRepresentation.Bitmap -> Observation(representation, sourceBytes, representation.bitmap.decodedByteCount.toLong(), 0L, representation.bitmap.width.toLong() * representation.bitmap.height)
        else -> Observation(representation, sourceBytes, 0L, 0L, 0L)
    }

    private fun consume(representation: GlyphRepresentation) {
        blackhole = blackhole xor representation.hashCode().toLong()
    }

    private fun <T> success(result: FontOperationResult<T>): T = when (result) {
        is FontOperationResult.Success -> result.value
        is FontOperationResult.Failure -> error("Materialization measurement failed: ${result.error.code}: ${result.error.message}")
        is FontOperationResult.Cancelled -> error("Materialization measurement was unexpectedly cancelled.")
    }

    private fun colrRequirements(): FontAccessRequirementsSnapshot = FontAccessRequirementsSnapshot.renderable(listOf(
        PaintGraphProfile(
            acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE, GlyphPaintNodeKind.GROUP),
            acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
            limits = PaintGraphLimits(3, 2, 2, 16_384, maxPaths = 2, maxPalettes = 9, maxPaletteEntries = 2, maxColorRecords = 16, maxDecodedPaletteBytes = 72, maxBaseGlyphRecords = 288, maxLayerRecords = 576),
            outlineProfile = outlineProfile(),
        ),
    ))

    private fun svgRequirements(maxSourceBytes: Int = 16 * 1024): FontAccessRequirementsSnapshot = FontAccessRequirementsSnapshot.renderable(listOf(
        PaintGraphProfile(
            acceptedNodeKinds = listOf(GlyphPaintNodeKind.PATH, GlyphPaintNodeKind.GROUP),
            acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
            limits = PaintGraphLimits(4, 4, 2, maxSourceBytes, maxPaths = 2),
            outlineProfile = outlineProfile(),
        ),
    ))

    private fun bitmapRequirements(): FontAccessRequirementsSnapshot = FontAccessRequirementsSnapshot.renderable(listOf(
        BitmapProfile(
            strike = BitmapStrike(16, 16),
            acceptedPixelFormats = listOf(BitmapPixelFormat.ALPHA_8),
            acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
            limits = BitmapLimits(3, 16, 16, 16_384, 16_384, 16, 16, 256, 64, 1_024, 256, 1_024),
        ),
    ))

    private fun outlineProfile(): OutlineProfile = OutlineProfile(
        maxBytes = 1_000_000,
        maxContours = 1_024,
        maxPoints = 65_536,
        maxCompositeDepth = 16,
        maxCompositeComponents = 256,
    )

    private fun colrBytes(): ByteArray = resourceBytes("/fonts/bungee-color/BungeeColor-Regular.ttf")

    private fun svgBytes(): ByteArray = Base64.getMimeDecoder().decode(resourceBytes("/fonts/twemoji-svginot-glyph5/TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf.base64"))

    private fun bitmapBytes(): ByteArray = resourceBytes("/fonts/skia-ebdt-format1/ebdt_fmt1.ttf")

    private fun resourceBytes(path: String): ByteArray = checkNotNull(GlyphMaterializationBenchmark::class.java.getResourceAsStream(path)) {
        "Missing materialization fixture $path."
    }.use { input -> input.readBytes() }

    private fun currentCommit(): String {
        val process = ProcessBuilder("git", "rev-parse", "HEAD").directory(repositoryRoot().toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.waitFor() == 0 && output.matches(Regex("[0-9a-f]{40}"))) { "Could not identify the measured commit: $output" }
        return output
    }

    private fun repositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (Files.exists(candidate.resolve(".git"))) return candidate
            candidate = candidate.parent
        }
        error("Could not locate the repository root from the test working directory.")
    }

    private fun machineName(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrElse { System.getenv("HOSTNAME") ?: "unknown-host" }.ifBlank { "unknown-host" }

    private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte -> "%02x".format(byte) }

    private fun percentiles(values: List<Long>): GlyphMaterializationPercentiles {
        val sorted = values.sorted()
        fun nearestRank(percentile: Double): Long = sorted[(ceil(percentile * sorted.size).toInt() - 1).coerceAtLeast(0)]
        return GlyphMaterializationPercentiles(nearestRank(0.50), nearestRank(0.95), nearestRank(0.99))
    }

    private fun List<Long>.averageAsLong(): Long = sum() / size

    private fun measurement(value: Long, detail: String): GlyphMaterializationMeasurementValue = GlyphMaterializationMeasurementValue.available(value, detail)

    private fun unavailable(detail: String): GlyphMaterializationMeasurementValue = GlyphMaterializationMeasurementValue.unavailable(detail)

    private fun forceGc() { repeat(2) { System.gc() } }

    private fun usedHeapBytes(): Long = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

    private data class Fixture(val name: String, val provenance: String, val glyphId: GlyphId, val bytes: ByteArray)

    private class OpenAsset(val resolver: org.graphiks.kalligraphie.api.FontAssetResolverHandle, val instance: FontInstance, val asset: FontRenderAssetHandle) {
        fun close() { asset.close(); resolver.close() }
    }

    private data class Observation(
        val representation: GlyphRepresentation = GlyphRepresentation.Empty,
        val sourceBytes: Long = 0L,
        val decodedBytes: Long = 0L,
        val normalizedNodes: Long = 0L,
        val decodedPixels: Long = 0L,
        val cancellationSignaledAtNanos: Long? = null,
    ) {
        companion object {
            fun empty(cancellationSignaledAtNanos: Long): Observation = Observation(cancellationSignaledAtNanos = cancellationSignaledAtNanos)
        }
    }

    private data class Sample(val elapsedNanos: Long, val allocatedBytes: Long?, val observation: Observation, val cancellationDelayNanos: Long?)

    private class CancelsOnCheck(private val cancelAtCheck: Int) : CancellationToken {
        private var checks: Int = 0
        var signaledAtNanos: Long? = null
            private set

        override fun isCancellationRequested(): Boolean {
            checks += 1
            if (checks < cancelAtCheck) return false
            if (signaledAtNanos == null) signaledAtNanos = System.nanoTime()
            return true
        }
    }

    private object ThreadAllocationProbe {
        private val bean: com.sun.management.ThreadMXBean? =
            (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)?.takeIf { candidate ->
                candidate.isThreadAllocatedMemorySupported && runCatching {
                    if (!candidate.isThreadAllocatedMemoryEnabled) candidate.isThreadAllocatedMemoryEnabled = true
                }.isSuccess
            }

        fun currentBytes(): Long? = bean?.getThreadAllocatedBytes(Thread.currentThread().threadId())?.takeIf { it >= 0L }
    }

    private const val GC_POLICY: String = "System.gc() twice before and after each profile; no requested GC between samples"
    private val CACHE_POLICY = FontMaterializationCachePolicy(maxEvictableBytesPerFace = 1_000_000)
    private val PRESSURE_CACHE_POLICY = FontMaterializationCachePolicy(maxEvictableBytesPerFace = 10_000)

    @Volatile
    private var blackhole: Long = 0L
}
