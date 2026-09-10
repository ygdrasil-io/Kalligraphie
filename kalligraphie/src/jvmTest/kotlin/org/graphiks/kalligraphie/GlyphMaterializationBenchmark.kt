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
import org.graphiks.kalligraphie.api.BitmapLimits
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontMaterializationCachePolicy
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphColorSpace
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphPaintCompositionMode
import org.graphiks.kalligraphie.api.GlyphPaintNodeKind
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.PaintGraphLimits
import org.graphiks.kalligraphie.api.PaintGraphProfile
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.shaping.JvmPreparedFontCachePolicy

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
    val maximumLiveAssets: GlyphMaterializationMeasurementValue,
    val estimatedAssetBytes: GlyphMaterializationMeasurementValue,
    val assetOpenings: GlyphMaterializationMeasurementValue,
    val operationReuses: GlyphMaterializationMeasurementValue,
    val preparedSourceBytes: GlyphMaterializationMeasurementValue,
    val estimatedPreparedNativeBytes: GlyphMaterializationMeasurementValue,
    val backendReuses: GlyphMaterializationMeasurementValue,
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
            appendMeasurement("Maximum live assets", profile.maximumLiveAssets)
            appendMeasurement("Estimated asset bytes", profile.estimatedAssetBytes)
            appendMeasurement("Asset openings", profile.assetOpenings)
            appendMeasurement("Operation reuses", profile.operationReuses)
            appendMeasurement("Prepared font source bytes copied", profile.preparedSourceBytes)
            appendMeasurement("Estimated prepared native bytes", profile.estimatedPreparedNativeBytes)
            appendMeasurement("Backend reuses", profile.backendReuses)
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
        "RenderableConsumerColdSingleFont",
        "RenderableConsumerWarmSingleFont",
        "RenderableConsumerColdMixedBidi",
        "RenderableConsumerWarmMixedBidi",
        "SessionColdSingleFont",
        "SessionWarmSingleFont",
        "SessionColdMixedBidi",
        "SessionWarmMixedBidi",
        "TrueTypeColdPreparation",
        "TrueTypeWarmPreparation",
        "TrueTypeColdTextMapping",
        "TrueTypeWarmTextMapping",
        "TrueTypeColdMetrics",
        "TrueTypeWarmMetrics",
        "TrueTypeColdOutlines",
        "TrueTypeWarmOutlines",
        "TrueTypeColdDetach",
        "TrueTypeWarmDetach",
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
        val liberation = Fixture("LiberationSans-Regular.ttf", "Liberation Sans Regular", GlyphId(36), liberationBytes())
        val trueTypeScalars = TRUE_TYPE_PARAGRAPH.codePoints().toArray().toList()
        val trueTypeGlyphIds = trueTypeParagraphGlyphIds(liberation, trueTypeScalars)
        val consumerSingle = ConsumerScenario(
            id = "single-font",
            fixtures = listOf(colr),
            text = "A",
            language = "en",
            requirements = colrRequirements(),
        )
        val consumerMixedBidi = ConsumerScenario(
            id = "mixed-bidi",
            fixtures = listOf(colr, liberation),
            text = "Aא",
            language = "he",
            requirements = FontAccessRequirementsSnapshot.renderable(listOf(
                colrRequirements().acceptedProfiles.single(),
                outlineProfile(),
            )),
        )
        val environment = GlyphMaterializationMeasurementEnvironment(
            commit = currentCommit(),
            machine = machineName(),
            operatingSystem = "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})",
            jvm = "${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}",
            fontHashes = listOf(colr, svg, bitmap, liberation).associate { fixture -> fixture.name to fixture.bytes.sha256Hex() },
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
            consumerColdProfile(consumerSingle, warmupIterations, iterations),
            consumerWarmProfile(consumerSingle, warmupIterations, iterations),
            consumerColdProfile(consumerMixedBidi, warmupIterations, iterations),
            consumerWarmProfile(consumerMixedBidi, warmupIterations, iterations),
            sessionProfile(consumerSingle, false, warmupIterations, iterations),
            sessionProfile(consumerSingle, true, warmupIterations, iterations),
            sessionProfile(consumerMixedBidi, false, warmupIterations, iterations),
            sessionProfile(consumerMixedBidi, true, warmupIterations, iterations),
            trueTypeColdPreparationProfile(liberation, warmupIterations, iterations),
            trueTypeWarmPreparationProfile(liberation, warmupIterations, iterations),
            trueTypeColdTextMappingProfile(liberation, trueTypeScalars, warmupIterations, iterations),
            trueTypeWarmTextMappingProfile(liberation, trueTypeScalars, warmupIterations, iterations),
            trueTypeColdMetricsProfile(liberation, trueTypeScalars, warmupIterations, iterations),
            trueTypeWarmMetricsProfile(liberation, trueTypeScalars, warmupIterations, iterations),
            trueTypeColdOutlinesProfile(liberation, trueTypeGlyphIds, warmupIterations, iterations),
            trueTypeWarmOutlinesProfile(liberation, trueTypeGlyphIds, warmupIterations, iterations),
            trueTypeColdDetachProfile(liberation, warmupIterations, iterations),
            trueTypeWarmDetachProfile(liberation, warmupIterations, iterations),
        )
        return reportFor(
            environment = environment,
            corpus = GlyphMaterializationMeasurementCorpus(
                id = "portable-glyph-materialization-v3",
                description = "23 profiles including ten portable TrueType editor stages over Liberation Sans and the stable paragraph \"$TRUE_TYPE_PARAGRAPH\"",
                glyphCount = 6 + trueTypeScalars.size,
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

    private fun consumerColdProfile(
        scenario: ConsumerScenario,
        warmupIterations: Int,
        iterations: Int,
    ): GlyphMaterializationMeasurementProfile = measuredProfile(
        name = "RenderableConsumerCold${scenario.profileSuffix}",
        route = "JVM RENDERABLE consumer journey (${scenario.routeDescription})",
        timedBoundary = "starts before embedded catalog creation and ends after the certified paragraph layout is consumed; resolver closure is excluded",
        cacheState = "cold: a new embedded catalog and resolver are created for every sample",
        warmupIterations = warmupIterations,
        iterations = iterations,
    ) { record ->
        repeat(warmupIterations + iterations) { index ->
            val sample = timed {
                val opened = openConsumerScenario(scenario)
                try {
                    observeConsumerLayout(layoutConsumerScenario(opened), opened, scenario.sourceBytes)
                } finally {
                    opened.close()
                }
            }
            if (index >= warmupIterations) record(sample)
        }
    }

    private fun consumerWarmProfile(
        scenario: ConsumerScenario,
        warmupIterations: Int,
        iterations: Int,
    ): GlyphMaterializationMeasurementProfile = measuredProfile(
        name = "RenderableConsumerWarm${scenario.profileSuffix}",
        route = "JVM RENDERABLE consumer journey (${scenario.routeDescription})",
        timedBoundary = "starts immediately before the public paragraph facade and ends after the certified layout is consumed; setup and resolver closure are excluded",
        cacheState = "warm: one catalog and resolver remain open; an untimed first layout seeds the per-face representation cache",
        warmupIterations = warmupIterations,
        iterations = iterations,
    ) { record ->
        val opened = openConsumerScenario(scenario)
        try {
            observeConsumerLayout(layoutConsumerScenario(opened), opened, sourceBytes = 0L)
            repeat(warmupIterations + iterations) { index ->
                val sample = timed {
                    observeConsumerLayout(layoutConsumerScenario(opened), opened, sourceBytes = 0L)
                }
                if (index >= warmupIterations) record(sample)
            }
        } finally {
            opened.close()
        }
    }

    private fun sessionProfile(
        scenario: ConsumerScenario,
        warm: Boolean,
        warmupIterations: Int,
        iterations: Int,
    ): GlyphMaterializationMeasurementProfile = measuredProfile(
        name = "Session${if (warm) "Warm" else "Cold"}${scenario.profileSuffix}",
        route = "reusable JVM RENDERABLE incremental session (${scenario.routeDescription})",
        timedBoundary = if (warm) {
            "new text revision, session layout and certified-result consumption; catalog, resolver, session setup and closure excluded"
        } else {
            "session opening, new text revision, layout and certified-result consumption; catalog, resolver setup and all closure excluded"
        },
        cacheState = if (warm) {
            "one session/backend with a seeded prepared-font cache; every sample supplies a fresh text version"
        } else {
            "a fresh session/backend per sample; shared catalog and resolver seeded outside timing"
        },
        warmupIterations = warmupIterations,
        iterations = iterations,
    ) { record ->
        val opened = openConsumerScenario(scenario)
        try {
            // Both profiles start with identical warmed portable render-asset state.
            observeConsumerLayout(layoutConsumerScenario(opened), opened, 0)
            val reusedSession = if (warm) success(JvmIncrementalParagraphLayoutSession.open()) else null
            try {
                if (reusedSession != null) observeSessionLayout(reusedSession, opened, backendReused = false)
                repeat(warmupIterations + iterations) { index ->
                    var coldSession: JvmIncrementalParagraphLayoutSession? = null
                    val sample = timed(cleanup = { coldSession?.close() }) {
                        val session = reusedSession ?: success(JvmIncrementalParagraphLayoutSession.open()).also { coldSession = it }
                        observeSessionLayout(session, opened, backendReused = warm)
                    }
                    if (index >= warmupIterations) record(sample)
                }
            } finally {
                reusedSession?.close()
            }
        } finally {
            opened.close()
        }
    }

    private fun observeSessionLayout(
        session: JvmIncrementalParagraphLayoutSession,
        opened: OpenConsumerScenario,
        backendReused: Boolean,
    ): Observation {
        val snapshot = Kalligraphie.decodeUtf8(
            TextVersion.create(), listOf(TextSlice.Utf8(opened.scenario.text.encodeToByteArray())),
        ).snapshot
        val request = org.graphiks.kalligraphie.api.createIncrementalLayoutRequest(
            input = org.graphiks.kalligraphie.api.LayoutInput(
                snapshot,
                org.graphiks.kalligraphie.api.TypographySnapshot(
                    version = org.graphiks.kalligraphie.api.TypographyVersion.create(),
                    fontCatalog = opened.catalog,
                    resolutionPolicy = opened.policy,
                    fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
                ),
            ),
            requestedRange = snapshot.range,
            constraints = HorizontalParagraphConstraints(
                region = LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(8_000f), LayoutUnit(1_000f)),
                lineMetrics = org.graphiks.kalligraphie.api.LineVerticalMetrics(LayoutUnit(800f), LayoutUnit(200f)),
            ),
            overscan = org.graphiks.kalligraphie.api.LineOverscan(0),
            previousState = null,
            delta = null,
            cancellationToken = CancellationToken.none,
        )
        val valid = when (request) {
            is org.graphiks.kalligraphie.api.LayoutContractResult.Success -> request.value
            is org.graphiks.kalligraphie.api.LayoutContractResult.Failure -> error("Invalid session measurement request: ${request.error}")
        }
        val result = session.layout(JvmIncrementalParagraphLayoutRequest(
            request = valid,
            baseDirection = BaseDirection.LEFT_TO_RIGHT,
            language = opened.scenario.language,
            materialization = EditableLineMaterialization.Renderable(
                resolver = opened.resolver,
                renderVariant = FontRenderVariantSnapshot.default,
                requirements = opened.scenario.requirements,
            ),
        ))
        val layout = when (result) {
            is org.graphiks.kalligraphie.api.IncrementalLayoutResult.Success -> result.layout
            else -> error("Session measurement failed: $result")
        }
        val usage = session.preparedFontCacheUsage
        check(usage.activeLeases == 0 && usage.idleEntries == opened.scenario.expectedFaceCount)
        return observeConsumerLines(layout.lines, opened, 0).copy(
            preparedSourceBytes = if (backendReused) 0 else usage.idleSourceBytes,
            estimatedPreparedNativeBytes = usage.idleEstimatedNativeBytes,
            backendReuses = if (backendReused) 1 else 0,
        )
    }

    private fun trueTypeColdPreparationProfile(
        fixture: Fixture,
        warmupIterations: Int,
        iterations: Int,
    ): GlyphMaterializationMeasurementProfile = measuredProfile(
        name = "TrueTypeColdPreparation",
        route = "Liberation Sans embedded TrueType catalog capture, face resolution, and instance creation",
        timedBoundary = "starts before embedded catalog capture and ends after the resolved face and created instance are consumed",
        cacheState = "cold: a new embedded catalog is captured for every sample",
        warmupIterations = warmupIterations,
        iterations = iterations,
    ) { record ->
        repeat(warmupIterations + iterations) { index ->
            val sample = timed {
                consumePreparedTrueType(prepareTrueType(fixture))
                Observation(sourceBytes = fixture.bytes.size.toLong())
            }
            if (index >= warmupIterations) record(sample)
        }
    }

    private fun trueTypeWarmPreparationProfile(
        fixture: Fixture,
        warmupIterations: Int,
        iterations: Int,
    ): GlyphMaterializationMeasurementProfile = measuredProfile(
        name = "TrueTypeWarmPreparation",
        route = "Liberation Sans face resolution and instance creation from one captured embedded catalog",
        timedBoundary = "starts before face resolution and ends after the created instance is consumed; catalog capture is excluded",
        cacheState = "warm: one captured embedded catalog is reused for every face resolution and instance creation",
        warmupIterations = warmupIterations,
        iterations = iterations,
    ) { record ->
        val catalog = captureTrueTypeCatalog(fixture)
        repeat(warmupIterations + iterations) { index ->
            val sample = timed {
                consumePreparedTrueType(instantiateTrueType(catalog))
                Observation()
            }
            if (index >= warmupIterations) record(sample)
        }
    }

    private fun trueTypeColdTextMappingProfile(
        fixture: Fixture,
        scalars: List<Int>,
        warmupIterations: Int,
        iterations: Int,
    ): GlyphMaterializationMeasurementProfile = measuredProfile(
        name = "TrueTypeColdTextMapping",
        route = "Liberation Sans Unicode scalar to glyph mapping for the stable editor paragraph",
        timedBoundary = "starts before new catalog, face, and instance preparation and ends after every paragraph glyph id is consumed",
        cacheState = "cold: a new embedded catalog and font instance are created for every sample",
        warmupIterations = warmupIterations,
        iterations = iterations,
    ) { record ->
        repeat(warmupIterations + iterations) { index ->
            val sample = timed {
                val prepared = prepareTrueType(fixture)
                resolveParagraphGlyphs(prepared.instance, scalars)
                Observation(sourceBytes = fixture.bytes.size.toLong())
            }
            if (index >= warmupIterations) record(sample)
        }
    }

    private fun trueTypeWarmTextMappingProfile(
        fixture: Fixture,
        scalars: List<Int>,
        warmupIterations: Int,
        iterations: Int,
    ): GlyphMaterializationMeasurementProfile = measuredProfile(
        name = "TrueTypeWarmTextMapping",
        route = "Liberation Sans Unicode scalar to glyph mapping for the stable editor paragraph",
        timedBoundary = "starts before resolving the first scalar and ends after every paragraph glyph id is consumed; instance preparation is excluded",
        cacheState = "warm: one prepared font instance is reused and the paragraph scalars are enumerated outside the timed operations",
        warmupIterations = warmupIterations,
        iterations = iterations,
    ) { record ->
        val instance = prepareTrueType(fixture).instance
        repeat(warmupIterations + iterations) { index ->
            val sample = timed {
                resolveParagraphGlyphs(instance, scalars)
                Observation()
            }
            if (index >= warmupIterations) record(sample)
        }
    }

    private fun trueTypeColdMetricsProfile(
        fixture: Fixture,
        scalars: List<Int>,
        warmupIterations: Int,
        iterations: Int,
    ): GlyphMaterializationMeasurementProfile = measuredProfile(
        name = "TrueTypeColdMetrics",
        route = "Liberation Sans mapping and horizontal metrics for every stable-paragraph glyph",
        timedBoundary = "starts before new catalog, face, and instance preparation, includes full paragraph mapping, and ends after every advance and bounds value is consumed",
        cacheState = "cold: a new embedded catalog and font instance are created for every sample",
        warmupIterations = warmupIterations,
        iterations = iterations,
    ) { record ->
        repeat(warmupIterations + iterations) { index ->
            val sample = timed {
                val prepared = prepareTrueType(fixture)
                consumeGlyphMetrics(prepared.instance, resolveParagraphGlyphs(prepared.instance, scalars))
                Observation(sourceBytes = fixture.bytes.size.toLong())
            }
            if (index >= warmupIterations) record(sample)
        }
    }

    private fun trueTypeWarmMetricsProfile(
        fixture: Fixture,
        scalars: List<Int>,
        warmupIterations: Int,
        iterations: Int,
    ): GlyphMaterializationMeasurementProfile = measuredProfile(
        name = "TrueTypeWarmMetrics",
        route = "Liberation Sans horizontal metrics for pre-mapped stable-paragraph glyphs",
        timedBoundary = "starts before the first metrics lookup and ends after every advance and bounds value is consumed; preparation and mapping are excluded",
        cacheState = "warm: one prepared instance and one pre-mapped paragraph glyph sequence are reused",
        warmupIterations = warmupIterations,
        iterations = iterations,
    ) { record ->
        val instance = prepareTrueType(fixture).instance
        val glyphIds = resolveParagraphGlyphs(instance, scalars)
        repeat(warmupIterations + iterations) { index ->
            val sample = timed {
                consumeGlyphMetrics(instance, glyphIds)
                Observation()
            }
            if (index >= warmupIterations) record(sample)
        }
    }

    private fun trueTypeColdOutlinesProfile(
        fixture: Fixture,
        glyphIds: List<GlyphId>,
        warmupIterations: Int,
        iterations: Int,
    ): GlyphMaterializationMeasurementProfile = measuredProfile(
        name = "TrueTypeColdOutlines",
        route = "Liberation Sans portable glyf outlines for distinct nonzero stable-paragraph glyphs",
        timedBoundary = "starts before new catalog, resolver, instance, and asset creation and ends after every distinct nonzero paragraph representation is consumed",
        cacheState = "cold: a new resolver and attached outline asset are created and closed for every sample",
        warmupIterations = warmupIterations,
        iterations = iterations,
    ) { record ->
        repeat(warmupIterations + iterations) { index ->
            var opened: OpenAsset? = null
            val sample = timed(cleanup = { opened?.close() }) {
                opened = openAsset(fixture, trueTypeRequirements(), cachePolicy = CACHE_POLICY)
                consumeOutlines(checkNotNull(opened).asset, glyphIds)
                Observation(sourceBytes = fixture.bytes.size.toLong())
            }
            if (index >= warmupIterations) record(sample)
        }
    }

    private fun trueTypeWarmOutlinesProfile(
        fixture: Fixture,
        glyphIds: List<GlyphId>,
        warmupIterations: Int,
        iterations: Int,
    ): GlyphMaterializationMeasurementProfile = measuredProfile(
        name = "TrueTypeWarmOutlines",
        route = "Liberation Sans cached portable glyf outlines for distinct nonzero stable-paragraph glyphs",
        timedBoundary = "starts before the first cached outline resolution and ends after every distinct nonzero paragraph representation is consumed; setup, seed, and closure are excluded",
        cacheState = "warm: one attached asset is seeded for every distinct nonzero paragraph glyph before repeated resolution",
        warmupIterations = warmupIterations,
        iterations = iterations,
    ) { record ->
        val opened = openAsset(fixture, trueTypeRequirements(), cachePolicy = CACHE_POLICY)
        try {
            consumeOutlines(opened.asset, glyphIds)
            repeat(warmupIterations + iterations) { index ->
                val sample = timed {
                    consumeOutlines(opened.asset, glyphIds)
                    Observation()
                }
                if (index >= warmupIterations) record(sample)
            }
        } finally {
            opened.close()
        }
    }

    private fun trueTypeColdDetachProfile(
        fixture: Fixture,
        warmupIterations: Int,
        iterations: Int,
    ): GlyphMaterializationMeasurementProfile = measuredProfile(
        name = "TrueTypeColdDetach",
        route = "Liberation Sans attached-to-detached portable outline asset lifetime",
        timedBoundary = "starts before new attached asset creation, includes detachment and attached-owner closure, and ends after glyph 36 is resolved and consumed through the detached handle",
        cacheState = "cold: a new catalog, resolver, and attached asset are created for every detach sample",
        warmupIterations = warmupIterations,
        iterations = iterations,
    ) { record ->
        repeat(warmupIterations + iterations) { index ->
            var opened: OpenAsset? = null
            var detached: FontRenderAssetHandle? = null
            val sample = timed(cleanup = {
                detached?.close()
                opened?.close()
            }) {
                opened = openAsset(fixture, trueTypeRequirements(), cachePolicy = CACHE_POLICY)
                val attached = checkNotNull(opened)
                detached = success(attached.asset.detach())
                success(attached.asset.close())
                success(attached.resolver.close())
                consumeOutlineRepresentation(
                    glyphId = fixture.glyphId,
                    representation = success(checkNotNull(detached).resolveGlyph(FontGlyphRequest(fixture.glyphId))),
                )
                Observation(sourceBytes = fixture.bytes.size.toLong())
            }
            if (index >= warmupIterations) record(sample)
        }
    }

    private fun trueTypeWarmDetachProfile(
        fixture: Fixture,
        warmupIterations: Int,
        iterations: Int,
    ): GlyphMaterializationMeasurementProfile = measuredProfile(
        name = "TrueTypeWarmDetach",
        route = "Liberation Sans repeated independent detached outline handles",
        timedBoundary = "starts before detachment from one prepared attached asset and ends after glyph 36 is resolved, consumed, and the independent detached handle is closed",
        cacheState = "warm: one prepared attached asset remains open across independent detach, consume, and close cycles",
        warmupIterations = warmupIterations,
        iterations = iterations,
    ) { record ->
        val opened = openAsset(fixture, trueTypeRequirements(), cachePolicy = CACHE_POLICY)
        try {
            consumeOutlineRepresentation(
                glyphId = fixture.glyphId,
                representation = success(opened.asset.resolveGlyph(FontGlyphRequest(fixture.glyphId))),
            )
            repeat(warmupIterations + iterations) { index ->
                val sample = timed {
                    var detached: FontRenderAssetHandle? = null
                    try {
                        detached = success(opened.asset.detach())
                        consumeOutlineRepresentation(
                            glyphId = fixture.glyphId,
                            representation = success(detached.resolveGlyph(FontGlyphRequest(fixture.glyphId))),
                        )
                        Observation()
                    } finally {
                        detached?.close()
                    }
                }
                if (index >= warmupIterations) record(sample)
            }
        } finally {
            opened.close()
        }
    }

    private fun trueTypeParagraphGlyphIds(fixture: Fixture, scalars: List<Int>): List<GlyphId> {
        val glyphIds = resolveParagraphGlyphs(prepareTrueType(fixture).instance, scalars)
            .filter { glyphId -> glyphId.value != 0 }
            .distinct()
        check(glyphIds.isNotEmpty()) { "The portable TrueType paragraph must resolve at least one nonzero glyph." }
        return glyphIds
    }

    private fun captureTrueTypeCatalog(fixture: Fixture): FontCatalogSnapshot = success(
        Kalligraphie.embedded(fixture.bytes, FontSourceProvenance(fixture.provenance), CACHE_POLICY),
    )

    private fun prepareTrueType(fixture: Fixture): PreparedTrueType =
        instantiateTrueType(captureTrueTypeCatalog(fixture))

    private fun instantiateTrueType(catalog: FontCatalogSnapshot): PreparedTrueType {
        val face = success(catalog.resolveFace(catalog.faces.single().id, trueTypeRequirements()))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2_048f))))
        return PreparedTrueType(catalog, face, instance)
    }

    private fun consumePreparedTrueType(prepared: PreparedTrueType) {
        consumeValue(prepared.catalog.generation.hashCode().toLong())
        consumeValue(prepared.face.id.hashCode().toLong())
        consumeValue(prepared.face.metadata.unitsPerEm.toLong())
        consumeValue(prepared.instance.key.hashCode().toLong())
    }

    private fun resolveParagraphGlyphs(instance: FontInstance, scalars: List<Int>): List<GlyphId> =
        scalars.map { scalar ->
            val resolution = success(instance.resolveGlyph(scalar))
            consumeValue(resolution.glyphId.value.toLong())
            resolution.glyphId
        }

    private fun consumeGlyphMetrics(instance: FontInstance, glyphIds: List<GlyphId>) {
        glyphIds.forEach { glyphId ->
            val metrics = success(instance.metrics(glyphId))
            consumeValue(metrics.advanceWidthDesignUnits.toLong())
            consumeValue(metrics.advanceWidth.value.toRawBits().toLong())
            consumeValue(metrics.bounds.minX.toLong())
            consumeValue(metrics.bounds.minY.toLong())
            consumeValue(metrics.bounds.maxX.toLong())
            consumeValue(metrics.bounds.maxY.toLong())
            consumeValue(metrics.scaledBounds.minX.value.toRawBits().toLong())
            consumeValue(metrics.scaledBounds.minY.value.toRawBits().toLong())
            consumeValue(metrics.scaledBounds.maxX.value.toRawBits().toLong())
            consumeValue(metrics.scaledBounds.maxY.value.toRawBits().toLong())
        }
    }

    private fun consumeOutlines(asset: FontRenderAssetHandle, glyphIds: List<GlyphId>) {
        glyphIds.forEach { glyphId ->
            consumeOutlineRepresentation(
                glyphId = glyphId,
                representation = success(asset.resolveGlyph(FontGlyphRequest(glyphId))),
            )
        }
    }

    private fun consumeOutlineRepresentation(glyphId: GlyphId, representation: GlyphRepresentation) {
        when (representation) {
            is GlyphRepresentation.Outline -> {
                val outline = representation.outline
                consumeValue(outline.glyphId.toLong())
                consumeValue(outline.unitsPerEm.toLong())
                consumeValue(outline.bounds.minX.toLong())
                consumeValue(outline.bounds.minY.toLong())
                consumeValue(outline.bounds.maxX.toLong())
                consumeValue(outline.bounds.maxY.toLong())
                consumeValue(outline.contours.size.toLong())
                consumeValue(outline.commands.size.toLong())
            }
            GlyphRepresentation.Empty -> consumeValue(glyphId.value.toLong())
            else -> error("Portable TrueType outline measurement received ${representation::class.simpleName} for glyph ${glyphId.value}.")
        }
    }

    private fun consumeValue(value: Long) {
        blackhole = blackhole xor value
    }

    private fun resolveCold(fixture: Fixture, requirements: FontAccessRequirementsSnapshot): Observation {
        val opened = openAsset(fixture, requirements, cachePolicy = CACHE_POLICY)
        return try {
            observe(success(opened.asset.resolveGlyph(FontGlyphRequest(fixture.glyphId))), fixture.bytes.size.toLong())
        } finally {
            opened.close()
        }
    }

    private fun openConsumerScenario(scenario: ConsumerScenario): OpenConsumerScenario {
        val catalog = success(
            Kalligraphie.embedded(
                sources = scenario.fixtures.map { fixture ->
                    FontSource(fixture.bytes, FontSourceProvenance(fixture.provenance))
                },
                cachePolicy = CACHE_POLICY,
            ),
        )
        val resolver = success(catalog.openAssetResolver())
        val policy = FontResolutionPolicySnapshot(
            generation = catalog.generation,
            policyId = "glyph-materialization-${scenario.id}",
            version = "1",
            candidates = catalog.faces.map { record -> FontResolutionCandidate(record.id) },
            lastResortFace = catalog.faces.last().id,
        )
        val snapshot = Kalligraphie.decodeUtf8(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf8(scenario.text.encodeToByteArray())),
        ).snapshot
        return OpenConsumerScenario(scenario, catalog, resolver, policy, snapshot)
    }

    private fun layoutConsumerScenario(opened: OpenConsumerScenario): ParagraphLayoutResult =
        JvmEditableParagraphFacade.layout(
            JvmEditableParagraphFacadeRequest(
                snapshot = opened.snapshot,
                constraints = HorizontalParagraphConstraints(
                    region = LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(8_000f), LayoutUnit(1_000f)),
                    lineMetrics = org.graphiks.kalligraphie.api.LineVerticalMetrics(LayoutUnit(800f), LayoutUnit(200f)),
                ),
                baseDirection = BaseDirection.LEFT_TO_RIGHT,
                language = opened.scenario.language,
                fontCatalog = opened.catalog,
                resolutionPolicy = opened.policy,
                fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
                materialization = EditableLineMaterialization.Renderable(
                    resolver = opened.resolver,
                    renderVariant = FontRenderVariantSnapshot.default,
                    requirements = opened.scenario.requirements,
                ),
            ),
        )

    private fun observeConsumerLayout(
        result: ParagraphLayoutResult,
        opened: OpenConsumerScenario,
        sourceBytes: Long,
    ): Observation {
        val layout = when (result) {
            is ParagraphLayoutResult.Success -> result.layout
            is ParagraphLayoutResult.Failure -> error("Consumer measurement failed: ${result.error}")
            is ParagraphLayoutResult.Cancelled -> error("Consumer measurement was unexpectedly cancelled.")
        }
        return observeConsumerLines(layout.lines, opened, sourceBytes)
    }

    private fun observeConsumerLines(
        lines: List<org.graphiks.kalligraphie.api.LineLayout>,
        opened: OpenConsumerScenario,
        sourceBytes: Long,
    ): Observation {
        val scenario = opened.scenario
        val glyphs = lines.flatMap { line -> line.positionedGlyphRuns.flatMap { run -> run.glyphs } }
        check(glyphs.isNotEmpty()) { "Consumer measurement must publish final glyphs." }
        check(glyphs.all { glyph -> glyph.materializationCertificate != null }) {
            "Consumer measurement must publish only certified glyphs."
        }
        val faceCount = lines
            .flatMap { line -> line.positionedGlyphRuns }
            .map { run -> run.fontInstanceKey.face }
            .distinct()
            .size
        check(faceCount == scenario.expectedFaceCount) {
            "Consumer scenario ${scenario.id} expected ${scenario.expectedFaceCount} selected faces but received $faceCount."
        }
        val assetKeys = glyphs.map { glyph -> checkNotNull(glyph.materializationCertificate).assetKey }.distinct()
        val estimatedAssetBytes = assetKeys.fold(0L) { total, key ->
            val requirements = FontAccessRequirementsSnapshot.renderable(
                acceptedProfiles = listOf(key.representationProfile),
                portableDataRequired = scenario.requirements.portableDataRequired,
            )
            val face = success(opened.catalog.resolveFace(key.fontInstanceKey.face, requirements))
            val instance = success(
                face.instantiate(
                    FontInstanceDescriptor(
                        layoutSize = key.fontInstanceKey.layoutSize,
                        geometry = key.fontInstanceKey.geometry,
                    ),
                ),
            )
            val estimate = success(
                instance.estimateRenderAssetBytes(
                    renderVariant = key.variantSnapshot ?: FontRenderVariantSnapshot.default,
                    profile = key.representationProfile,
                ),
            )
            if (total > Long.MAX_VALUE - estimate) Long.MAX_VALUE else total + estimate
        }
        blackhole = blackhole xor glyphs.size.toLong()
        return Observation(
            representation = GlyphRepresentation.Empty,
            sourceBytes = sourceBytes,
            maximumLiveAssets = assetKeys.size.toLong(),
            estimatedAssetBytes = estimatedAssetBytes,
            assetOpenings = assetKeys.size.toLong(),
            operationReuses = glyphs.size.toLong(),
        )
    }

    private fun openAsset(
        fixture: Fixture,
        requirements: FontAccessRequirementsSnapshot,
        variant: FontRenderVariantSnapshot = FontRenderVariantSnapshot.default,
        cachePolicy: FontMaterializationCachePolicy,
    ): OpenAsset {
        val catalog = success(Kalligraphie.embedded(fixture.bytes, FontSourceProvenance(fixture.provenance), cachePolicy))
        val resolver = success(catalog.openAssetResolver())
        return try {
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
            val asset = success(instance.acquireRenderAsset(resolver, variant, requirements))
            OpenAsset(resolver, instance, asset)
        } catch (failure: Throwable) {
            try {
                resolver.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
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
            maximumLiveAssets = operationMeasurement(samples, Observation::maximumLiveAssets, "maximum simultaneously live operation-owned render assets per measured iteration"),
            estimatedAssetBytes = operationMeasurement(samples, Observation::estimatedAssetBytes, "conservative bytes estimated for operation-owned render assets per measured iteration"),
            assetOpenings = operationMeasurement(samples, Observation::assetOpenings, "distinct operation-owned render assets opened per measured iteration"),
            operationReuses = operationMeasurement(samples, Observation::operationReuses, "final-glyph proofs reused from earlier materialization in the same operation per measured iteration"),
            preparedSourceBytes = operationMeasurement(samples, Observation::preparedSourceBytes, "OpenType bytes copied into the session's native source buffers during this sample; retained seeded fonts need no new copy"),
            estimatedPreparedNativeBytes = operationMeasurement(samples, Observation::estimatedPreparedNativeBytes, "retained estimate at sample end using ${JvmPreparedFontCachePolicy.nativeEstimatorVersion}; excludes source buffers and is not measured native allocation"),
            backendReuses = operationMeasurement(samples, Observation::backendReuses, "existing session/backend used per sample (0 cold, 1 warm); lifecycle defined by the runner"),
        )
    }

    private fun operationMeasurement(
        samples: List<Sample>,
        selector: (Observation) -> Long?,
        detail: String,
    ): GlyphMaterializationMeasurementValue {
        val values = samples.mapNotNull { sample -> selector(sample.observation) }
        return if (values.size == samples.size) {
            measurement(values.averageAsLong(), detail)
        } else {
            unavailable("profile does not observe this operation or session accounting dimension")
        }
    }

    private fun timed(
        cleanup: () -> Unit = {},
        operation: () -> Observation,
    ): Sample {
        try {
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
        } finally {
            cleanup()
        }
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

    private fun trueTypeRequirements(): FontAccessRequirementsSnapshot =
        FontAccessRequirementsSnapshot.renderable(outlineProfile())

    private fun colrBytes(): ByteArray = resourceBytes("/fonts/bungee-color/BungeeColor-Regular.ttf")

    private fun svgBytes(): ByteArray = Base64.getMimeDecoder().decode(resourceBytes("/fonts/twemoji-svginot-glyph5/TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf.base64"))

    private fun bitmapBytes(): ByteArray = resourceBytes("/fonts/skia-ebdt-format1/ebdt_fmt1.ttf")

    private fun liberationBytes(): ByteArray = resourceBytes("/fonts/liberation/LiberationSans-Regular.ttf")

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

    private data class PreparedTrueType(
        val catalog: FontCatalogSnapshot,
        val face: FontFace,
        val instance: FontInstance,
    )

    private data class ConsumerScenario(
        val id: String,
        val fixtures: List<Fixture>,
        val text: String,
        val language: String,
        val requirements: FontAccessRequirementsSnapshot,
    ) {
        val profileSuffix: String = when (id) {
            "single-font" -> "SingleFont"
            "mixed-bidi" -> "MixedBidi"
            else -> error("Unsupported consumer measurement scenario: $id")
        }

        val routeDescription: String = when (id) {
            "single-font" -> "one Bungee Color Latin glyph"
            "mixed-bidi" -> "Bungee Color Latin plus Liberation Sans Hebrew fallback"
            else -> error("Unsupported consumer measurement scenario: $id")
        }

        val sourceBytes: Long = fixtures.sumOf { fixture -> fixture.bytes.size.toLong() }

        val expectedFaceCount: Int = when (id) {
            "single-font" -> 1
            "mixed-bidi" -> 2
            else -> error("Unsupported consumer measurement scenario: $id")
        }
    }

    private class OpenAsset(val resolver: org.graphiks.kalligraphie.api.FontAssetResolverHandle, val instance: FontInstance, val asset: FontRenderAssetHandle) {
        fun close() { asset.close(); resolver.close() }
    }

    private class OpenConsumerScenario(
        val scenario: ConsumerScenario,
        val catalog: FontCatalogSnapshot,
        val resolver: FontAssetResolverHandle,
        val policy: FontResolutionPolicySnapshot,
        val snapshot: org.graphiks.kalligraphie.api.TextSnapshot,
    ) {
        fun close() {
            resolver.close()
        }
    }

    private data class Observation(
        val representation: GlyphRepresentation = GlyphRepresentation.Empty,
        val sourceBytes: Long = 0L,
        val decodedBytes: Long = 0L,
        val normalizedNodes: Long = 0L,
        val decodedPixels: Long = 0L,
        val cancellationSignaledAtNanos: Long? = null,
        val maximumLiveAssets: Long? = null,
        val estimatedAssetBytes: Long? = null,
        val assetOpenings: Long? = null,
        val operationReuses: Long? = null,
        val preparedSourceBytes: Long? = null,
        val estimatedPreparedNativeBytes: Long? = null,
        val backendReuses: Long? = null,
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
    private const val TRUE_TYPE_PARAGRAPH: String =
        "Readable typography keeps words, punctuation, carets, and 0123456789 responsive while an editor changes text."
    private val CACHE_POLICY = FontMaterializationCachePolicy(maxEvictableBytesPerFace = 1_000_000)
    private val PRESSURE_CACHE_POLICY = FontMaterializationCachePolicy(maxEvictableBytesPerFace = 10_000)

    @Volatile
    private var blackhole: Long = 0L
}
