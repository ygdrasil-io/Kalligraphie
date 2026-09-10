@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.core

import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogGeneration
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontDiagnosticSeverity
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontFaceCapabilities
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontFaceRecord
import org.graphiks.kalligraphie.api.FontMaterializationCachePolicy
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.KalligraphieInternalApi
import org.graphiks.kalligraphie.api.FontProviderId
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderAssetKey
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontInstanceKey
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceId
import org.graphiks.kalligraphie.api.FontOperationResult.Success
import org.graphiks.kalligraphie.api.BitmapGlyphIR
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.GlyphRepresentationKey
import org.graphiks.kalligraphie.api.GlyphRepresentationProfile
import org.graphiks.kalligraphie.api.NativeHandleProfile
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.PaintGraphProfile
import org.graphiks.kalligraphie.api.immutableListSnapshot
import org.graphiks.kalligraphie.api.sortedDiagnostics
import org.graphiks.kalligraphie.api.toDiagnostic
import org.graphiks.kalligraphie.font.scaler.PreparedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.ColrCpalReader
import org.graphiks.kalligraphie.font.sfnt.EbdtFormatOneReader
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.SfntReader
import org.graphiks.kalligraphie.font.sfnt.SvgOpenTypeReader
import org.graphiks.kalligraphie.font.sfnt.slice
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Catalog implementation for immutable embedded TrueType sources.
 *
 * The catalog keeps the parsed snapshot and an explicit shared resource
 * owner. Faces and instances are immutable views of that owner; they do not
 * own an asset lease or expose its byte buffers. Resolver and render-asset
 * handles acquire independent leases, and a detached asset never stores a
 * reference to this catalog.
 *
 * @param generation stable identifier for this immutable catalog generation.
 * @param entries captured source bytes, provenance, and parsed metadata for every face.
 * @param cachePolicy bounded portable representation retention independently applied per face.
 */
@KalligraphieInternalApi
public class EmbeddedFontCatalog(
    override val generation: FontCatalogGeneration,
    entries: List<EmbeddedFontCatalogEntry>,
    cachePolicy: FontMaterializationCachePolicy = FontMaterializationCachePolicy.disabled,
) : FontCatalogSnapshot {
    private val resources: Map<FontFaceId, PreparedFontResource>
    private val parsedFonts: Map<FontFaceId, ParsedTrueTypeFont>
    private val outlineRouteSupportedFaces: Set<FontFaceId>
    private val paintGraphSupportedFaces: Set<FontFaceId>
    private val svgRouteSupportedFaces: Set<FontFaceId>
    private val bitmapRouteSupportedFaces: Set<FontFaceId>
    private val resolvedFaces: Map<FontFaceId, TrueTypeFace>

    /** Stable records for every captured embedded face, in supplied order. */
    override val faces: List<FontFaceRecord>

    init {
        require(entries.isNotEmpty()) { "An embedded font catalog must contain at least one face." }
        val capturedEntries = entries.toList()
        val ids = capturedEntries.map { entry ->
            require(entry.source.id !is FontSourceId.Opaque) {
                "The embedded OpenType provider requires a portable source identity."
            }
            FontFaceId(entry.source.id, 0)
        }
        require(ids.distinct().size == ids.size) {
            "An embedded font catalog must not contain the same source twice."
        }
        resources = ids.zip(capturedEntries).associate { (id, entry) ->
            id to PreparedFontResource(
                preparedFont = PreparedTrueTypeFont(entry.source, entry.parsedFont),
                sourceByteSize = entry.source.sizeInBytes,
                cachePolicy = cachePolicy,
            )
        }
        parsedFonts = ids.zip(capturedEntries).associate { (id, entry) -> id to entry.parsedFont }
        outlineRouteSupportedFaces = ids.filter { id ->
            supportsGlyfOutlineRoute(resources.getValue(id), parsedFonts.getValue(id))
        }.toSet()
        paintGraphSupportedFaces = ids.filter { id ->
            supportsColrCpalV0(resources.getValue(id), parsedFonts.getValue(id))
        }.toSet()
        svgRouteSupportedFaces = ids.filter { id ->
            supportsSvgOpenTypeRoute(resources.getValue(id), parsedFonts.getValue(id))
        }.toSet()
        bitmapRouteSupportedFaces = ids.filter { id ->
            supportsEbdtFormatOneRoute(resources.getValue(id), parsedFonts.getValue(id))
        }.toSet()
        resolvedFaces = ids.associateWith { id ->
            TrueTypeFace(
                faceId = id,
                generation = generation,
                parsedFont = parsedFonts.getValue(id),
                resource = resources.getValue(id),
                outlineRouteSupported = id in outlineRouteSupportedFaces,
                paintGraphSupported = id in paintGraphSupportedFaces,
                svgRouteSupported = id in svgRouteSupportedFaces,
                bitmapRouteSupported = id in bitmapRouteSupportedFaces,
            )
        }
        faces = ids.map { id ->
            FontFaceRecord(
                id = id,
                metadata = parsedFonts.getValue(id).metadata,
                capabilities = FontFaceCapabilities(
                    characterMapping = true,
                    shaping = true,
                    outline = id in outlineRouteSupportedFaces,
                    paintGraph = id in paintGraphSupportedFaces || id in svgRouteSupportedFaces,
                    bitmap = id in bitmapRouteSupportedFaces,
                ),
            )
        }.immutableListSnapshot()
    }

    /** Creates a one-face embedded catalogue with a deterministic content-derived generation. */
    public constructor(
        source: FontSource,
        parsedFont: ParsedTrueTypeFont,
    ) : this(
        generation = FontCatalogGeneration(
            provider = FontProviderId("embedded-opentype"),
            value = "embedded-${(source.id as FontSourceId.Portable).contentDigest.value}",
        ),
        entries = listOf(EmbeddedFontCatalogEntry(source, parsedFont)),
    )

    /** Opens a resolver backed by the embedded source. */
    override fun openAssetResolver(): FontOperationResult<FontAssetResolverHandle> =
        FontOperationResult.Success(EmbeddedFontAssetResolver(generation, resources, parsedFonts))

    /** Resolves a face when the requested access profile is supported. */
    override fun resolveFace(
        faceId: FontFaceId,
        requirements: FontAccessRequirementsSnapshot,
    ): FontOperationResult<FontFace> {
        if (faceId !in faces.map(FontFaceRecord::id)) {
            return failure(
                FontError.InvalidFontData(
                    message = "The requested face does not belong to this embedded catalog generation.",
                    location = FontDiagnosticLocation.Source,
                ),
            )
        }
        if (!requirements.isSupportedForEmbeddedTrueType(faceId)) {
            return failure(
                FontError.UnsupportedRepresentationProfile(
                    message = "Unsupported font access requirements for this embedded TrueType catalog.",
                    location = FontDiagnosticLocation.Source,
                ),
                diagnostics = listOf(
                    FontDiagnostic(
                        code = "font.unsupported-representation-profile",
                        severity = FontDiagnosticSeverity.ERROR,
                        location = FontDiagnosticLocation.Source,
                        message = "Only LAYOUT_ONLY, schemaVersion=1 outlines, declared COLR/CPAL version 0 paint profiles, and declared EBDT format 1 bitmap profiles are supported.",
                    ),
                ),
            )
        }
        return FontOperationResult.Success(resolvedFaces.getValue(faceId))
    }

    private fun FontAccessRequirementsSnapshot.isSupportedForEmbeddedTrueType(faceId: FontFaceId): Boolean =
        when (mode) {
            FontAccessRequirementsSnapshot.Mode.LAYOUT_ONLY -> true
            FontAccessRequirementsSnapshot.Mode.RENDERABLE -> acceptedProfiles.any { profile ->
                profile.isSupportedForEmbeddedTrueType(faceId)
            }
        }

    private fun org.graphiks.kalligraphie.api.GlyphRepresentationProfile.isSupportedForEmbeddedTrueType(faceId: FontFaceId): Boolean =
        when (this) {
            is org.graphiks.kalligraphie.api.OutlineProfile ->
                schemaVersion == 1 && faceId in outlineRouteSupportedFaces
            is org.graphiks.kalligraphie.api.PaintGraphProfile ->
                schemaVersion == 1 && (faceId in paintGraphSupportedFaces || faceId in svgRouteSupportedFaces)
            is org.graphiks.kalligraphie.api.BitmapProfile ->
                schemaVersion == 1 && faceId in bitmapRouteSupportedFaces
            else -> false
        }

    private fun failure(error: FontError, diagnostics: List<FontDiagnostic> = listOf(error.toDiagnostic())): FontOperationResult.Failure =
        FontOperationResult.Failure(error, diagnostics.sortedDiagnostics())
}

private fun supportsGlyfOutlineRoute(
    resource: PreparedFontResource,
    parsedFont: ParsedTrueTypeFont,
): Boolean {
    val glyfRecord = parsedFont.tableRecords["glyf"] ?: return false
    val locaRecord = parsedFont.tableRecords["loca"] ?: return false
    val sourceBytes = resource.preparedFont.copySourceBytes()
    if (slice(sourceBytes, glyfRecord) == null) return false
    val loca = slice(sourceBytes, locaRecord) ?: return false
    val entrySize = when (parsedFont.indexToLocFormat) {
        0 -> 2
        1 -> 4
        else -> return false
    }
    val expectedLocaBytes = (parsedFont.metadata.glyphCount.toLong() + 1L) * entrySize.toLong()
    return expectedLocaBytes <= Int.MAX_VALUE.toLong() && loca.size == expectedLocaBytes.toInt()
}

private fun supportsColrCpalV0(
    resource: PreparedFontResource,
    parsedFont: ParsedTrueTypeFont,
): Boolean {
    val colrRecord = parsedFont.tableRecords["COLR"] ?: return false
    val cpalRecord = parsedFont.tableRecords["CPAL"] ?: return false
    val sourceBytes = resource.preparedFont.copySourceBytes()
    val colr = slice(sourceBytes, colrRecord) ?: return false
    val cpal = slice(sourceBytes, cpalRecord) ?: return false
    return ColrCpalReader.hasStructurallyValidVersionZeroTables(
        colrTable = colr,
        cpalTable = cpal,
        glyphCount = parsedFont.metadata.glyphCount,
    )
}

private fun supportsSvgOpenTypeRoute(
    resource: PreparedFontResource,
    parsedFont: ParsedTrueTypeFont,
): Boolean {
    val svgRecord = parsedFont.tableRecords["SVG "] ?: return false
    val svg = slice(resource.preparedFont.copySourceBytes(), svgRecord) ?: return false
    return SvgOpenTypeReader.hasStructurallyValidVersionZeroTable(svg, parsedFont.metadata.glyphCount)
}

private fun supportsEbdtFormatOneRoute(
    resource: PreparedFontResource,
    parsedFont: ParsedTrueTypeFont,
): Boolean {
    val eblcRecord = parsedFont.tableRecords["EBLC"] ?: return false
    val ebdtRecord = parsedFont.tableRecords["EBDT"] ?: return false
    val sourceBytes = resource.preparedFont.copySourceBytes()
    val eblc = slice(sourceBytes, eblcRecord) ?: return false
    val ebdt = slice(sourceBytes, ebdtRecord) ?: return false
    return EbdtFormatOneReader.hasStructurallyValidFormatOneTables(eblc, ebdt, parsedFont.metadata.glyphCount)
}

/**
 * One captured OpenType source included in an [EmbeddedFontCatalog].
 *
 * The provider captures both values while creating its immutable generation. The source owns a
 * defensive copy of its bytes and parsed metadata is immutable; callers retain no provider
 * resource by retaining this entry.
 */
@KalligraphieInternalApi
public data class EmbeddedFontCatalogEntry(
    /** Captured portable font source. */
    public val source: FontSource,
    /** Parsed metadata for the source's sole TrueType face. */
    public val parsedFont: ParsedTrueTypeFont,
)

/**
 * Implementation bridge from the supported facade to the embedded TrueType catalog.
 *
 * This factory exists only because the facade and the catalog implementation live in separate
 * Kotlin modules. Applications must call [org.graphiks.kalligraphie.Kalligraphie.embedded].
 */
@KalligraphieInternalApi
public object EmbeddedFontCatalogFactory {
    /** Creates one immutable embedded catalog after validating every supplied source. */
    public fun create(
        sources: List<FontSource>,
        cachePolicy: FontMaterializationCachePolicy = FontMaterializationCachePolicy.disabled,
    ): FontOperationResult<FontCatalogSnapshot> {
        val capturedSources = sources.toList()
        if (capturedSources.isEmpty()) return invalidCatalog("An embedded font catalog requires at least one source.")
        if (capturedSources.map(FontSource::id).distinct().size != capturedSources.size) {
            return invalidCatalog("An embedded font catalog must not contain the same source twice.")
        }

        val diagnostics = mutableListOf<FontDiagnostic>()
        val entries = mutableListOf<EmbeddedFontCatalogEntry>()
        capturedSources.forEach { source ->
            when (val parsed = SfntReader.readMetadata(source)) {
                is FontOperationResult.Success<*> -> {
                    entries += EmbeddedFontCatalogEntry(source, parsed.value as ParsedTrueTypeFont)
                    diagnostics += parsed.diagnostics
                }

                is FontOperationResult.Failure -> return FontOperationResult.Failure(
                    parsed.error,
                    diagnostics + parsed.diagnostics,
                )

                is FontOperationResult.Cancelled -> return FontOperationResult.Cancelled(
                    diagnostics + parsed.diagnostics,
                )
            }
        }

        val generation = FontCatalogGeneration(
            provider = FontProviderId("embedded-opentype"),
            value = capturedSources.joinToString(prefix = "embedded-", separator = ".") { source ->
                (source.id as FontSourceId.Portable).contentDigest.value
            },
        )
        return FontOperationResult.Success(EmbeddedFontCatalog(generation, entries, cachePolicy), diagnostics)
    }

    private fun invalidCatalog(message: String): FontOperationResult.Failure =
        FontOperationResult.Failure(FontError.InvalidFontData(message))
}

internal class EmbeddedFontAssetResolver(
    override val generation: FontCatalogGeneration,
    private val resources: Map<FontFaceId, PreparedFontResource>,
    private val parsedFonts: Map<FontFaceId, ParsedTrueTypeFont>,
) : FontAssetResolverHandle {
    private val resourceLeases: MutableMap<FontFaceId, PreparedFontResourceLease> =
        resources.mapValues { (_, resource) -> resource.acquireLease() }.toMutableMap()
    private val lifecycle = FontHandleLifecycle(::releaseResourceLeases)

    val isClosed: Boolean
        get() = !lifecycle.isOpenForNewOperations()

    fun acquireAssetLease(faceId: FontFaceId): PreparedFontResourceLease? {
        val operationLease = lifecycle.acquireLease() ?: return null
        return try {
            resourceLeases[faceId]?.resource?.acquireLease()
        } finally {
            operationLease.release()
        }
    }

    override fun reopen(key: FontRenderAssetKey): FontOperationResult<FontRenderAssetHandle> {
        if (key.generation != generation) {
            return failure(FontError.IncompatibleCatalogGeneration("Asset key does not belong to this catalog generation."))
        }
        if (!isReopenableEmbeddedKey(key)) {
            return failure(FontError.AssetUnavailable("Asset key does not identify an embedded TrueType render asset in this catalog generation."))
        }
        val variant = key.variantSnapshot ?: FontRenderVariantSnapshot.default
        if (variant.key != key.variant) {
            return failure(FontError.AssetUnavailable("Render variant context does not match the requested asset key."))
        }
        val face = key.fontInstanceKey.face
        val resource = resources[face]
            ?: return failure(FontError.AssetUnavailable("The asset face is not available in this catalog generation."))
        val parsedFont = parsedFonts[face]
            ?: return failure(FontError.AssetUnavailable("The asset face metadata is not available in this catalog generation."))
        return TrueTypeFontInstance(
            key = key.fontInstanceKey,
            descriptor = FontInstanceDescriptor(key.fontInstanceKey.layoutSize, key.fontInstanceKey.geometry),
            resource = resource,
            faceId = face,
            generation = generation,
            parsedFont = parsedFont,
            outlineRouteSupported = supportsGlyfOutlineRoute(resource, parsedFont),
            paintGraphSupported = supportsColrCpalV0(resource, parsedFont),
            svgRouteSupported = supportsSvgOpenTypeRoute(resource, parsedFont),
            bitmapRouteSupported = supportsEbdtFormatOneRoute(resource, parsedFont),
        ).acquireRenderAsset(
            resolver = this,
            renderVariant = variant,
            requirements = FontAccessRequirementsSnapshot.renderable(listOf(key.representationProfile)),
        )
    }

    override fun close(): FontOperationResult<Unit> {
        lifecycle.close()
        return FontOperationResult.Success(Unit)
    }

    private fun releaseResourceLeases() {
        resourceLeases.values.forEach(PreparedFontResourceLease::release)
        resourceLeases.clear()
    }

    private fun isReopenableEmbeddedKey(key: FontRenderAssetKey): Boolean {
        val instance = key.fontInstanceKey
        val parsedFont = parsedFonts[instance.face] ?: return false
        val representationIsSupported = when (val profile = key.representationProfile) {
            is org.graphiks.kalligraphie.api.OutlineProfile ->
                key.variant == FontRenderVariantKey.default &&
                    profile.schemaVersion == 1 &&
                    resources[instance.face]?.let { resource ->
                        supportsGlyfOutlineRoute(resource, parsedFont)
                    } == true
            is org.graphiks.kalligraphie.api.PaintGraphProfile ->
                profile.schemaVersion == 1 &&
                    resources[instance.face]?.let { resource ->
                        supportsColrCpalV0(resource, parsedFont) || supportsSvgOpenTypeRoute(resource, parsedFont)
                    } == true
            is org.graphiks.kalligraphie.api.BitmapProfile ->
                key.variant == FontRenderVariantKey.default &&
                profile.schemaVersion == 1 &&
                    resources[instance.face]?.let { resource ->
                        supportsEbdtFormatOneRoute(resource, parsedFont)
                    } == true
            else -> false
        }
        return representationIsSupported &&
            instance.face in resources &&
            instance.interpretation.pipelineId == "org.graphiks.kalligraphie.true-type" &&
            instance.interpretation.version == "1" &&
            instance.layoutSize.value.isFinite() &&
            instance.layoutSize.value > 0f &&
            instance.geometry.normalizedAxes.isEmpty() &&
            !instance.geometry.syntheticBold &&
            !instance.geometry.syntheticItalic
    }
}

@OptIn(ExperimentalAtomicApi::class)
internal class FontHandleLifecycle(
    private val onDrained: () -> Unit = {},
) {
    private val state = AtomicInt(0)

    fun isOpenForNewOperations(): Boolean = state.load() >= 0

    fun acquireLease(): FontHandleLease? {
        while (true) {
            val current = state.load()
            if (current < 0) {
                return null
            }
            if (state.compareAndSet(current, current + 1)) {
                return FontHandleLease(this)
            }
        }
    }

    fun close() {
        while (true) {
            val current = state.load()
            if (current < 0) {
                return
            }
            val next = Int.MIN_VALUE + current
            if (state.compareAndSet(current, next)) {
                if (current == 0) onDrained()
                return
            }
        }
    }

    internal fun releaseLease() {
        while (true) {
            val current = state.load()
            val next = when {
                current > 0 -> current - 1
                current > Int.MIN_VALUE -> current - 1
                else -> return
            }
            if (state.compareAndSet(current, next)) {
                if (next == Int.MIN_VALUE) onDrained()
                return
            }
        }
    }
}

@OptIn(ExperimentalAtomicApi::class)
internal class PreparedFontResource(
    internal val preparedFont: PreparedTrueTypeFont,
    internal val sourceByteSize: Int,
    cachePolicy: FontMaterializationCachePolicy,
) {
    private val leaseCount = AtomicInt(0)
    private val representations = WeightedEvictableCache<GlyphRepresentationKey, Success<GlyphRepresentation>>(
        cachePolicy.maxEvictableBytesPerFace,
    )

    internal fun cachedRepresentation(key: GlyphRepresentationKey): Success<GlyphRepresentation>? = representations.get(key)

    internal fun cacheRepresentation(
        key: GlyphRepresentationKey,
        result: Success<GlyphRepresentation>,
    ) {
        representations.put(key, result, cachedRepresentationRetainedBytes(key, result))
    }

    internal fun acquireLease(): PreparedFontResourceLease {
        while (true) {
            val current = leaseCount.load()
            check(current < Int.MAX_VALUE) { "Prepared font resource lease count overflowed." }
            if (leaseCount.compareAndSet(current, current + 1)) {
                return PreparedFontResourceLease(this)
            }
        }
    }

    internal fun releaseLease() {
        while (true) {
            val current = leaseCount.load()
            check(current > 0) { "Prepared font resource lease released more than once." }
            if (leaseCount.compareAndSet(current, current - 1)) {
                if (current == 1) representations.clear()
                return
            }
        }
    }
}

/** Conservative retained-byte estimate for one embedded operation-owned render asset. */
internal fun estimateEmbeddedRenderAssetBytes(
    resource: PreparedFontResource,
    parsedFont: ParsedTrueTypeFont,
    instanceKey: FontInstanceKey,
    renderVariant: FontRenderVariantSnapshot,
    profile: GlyphRepresentationProfile,
): Long {
    var total = 512L
        .saturatingAdd(resource.sourceByteSize.toLong())
        .saturatingAdd(instanceKey.geometry.normalizedAxes.size.toLong().saturatingMultiply(32L))
        .saturatingAdd(renderVariant.estimatedRetainedBytes())
        .saturatingAdd(profile.estimatedRetainedBytes())
    total = when (profile) {
        is OutlineProfile -> total
            .saturatingAdd(profile.maxBytes.toLong())
            .saturatingAdd(profile.maxPoints.toLong().saturatingMultiply(48L))
            .saturatingAdd(profile.maxContours.toLong().saturatingMultiply(24L))
            .saturatingAdd(profile.maxCompositeComponents.toLong().saturatingMultiply(64L))
        is PaintGraphProfile -> total
            .saturatingAdd(profile.limits.maxSourceBytes.toLong())
            .saturatingAdd(profile.limits.maxDecodedPaletteBytes.toLong())
            .saturatingAdd(profile.limits.maxNodes.toLong().saturatingMultiply(96L))
            .saturatingAdd(profile.limits.maxReferences.toLong().saturatingMultiply(8L))
            .saturatingAdd(profile.outlineProfile.maxBytes.toLong())
            .saturatingAdd(estimateColrCpalRetainedBytes(resource, parsedFont))
            .saturatingAdd(estimateSvgRetainedBytes(parsedFont, profile))
        is BitmapProfile -> total
            .saturatingAdd(profile.limits.maxIndexTableBytes.toLong())
            .saturatingAdd(profile.limits.maxBitmapTableBytes.toLong())
            .saturatingAdd(profile.limits.maxTotalDecodedBytes.toLong())
        is NativeHandleProfile -> total
    }
    return total
}

private fun estimateColrCpalRetainedBytes(
    resource: PreparedFontResource,
    parsedFont: ParsedTrueTypeFont,
): Long {
    val colrRecord = parsedFont.tableRecords["COLR"] ?: return 0L
    val cpalRecord = parsedFont.tableRecords["CPAL"] ?: return 0L
    val source = resource.preparedFont.copySourceBytes()
    val colr = slice(source, colrRecord) ?: return Long.MAX_VALUE
    val cpal = slice(source, cpalRecord) ?: return Long.MAX_VALUE
    val baseGlyphCount = colr.unsignedShortAt(2)?.toLong() ?: return Long.MAX_VALUE
    val layerCount = colr.unsignedShortAt(12)?.toLong() ?: return Long.MAX_VALUE
    val paletteEntryCount = cpal.unsignedShortAt(2)?.toLong() ?: return Long.MAX_VALUE
    val paletteCount = cpal.unsignedShortAt(4)?.toLong() ?: return Long.MAX_VALUE

    // Every base-glyph record may retain a complete copy of the layer-reference list. Palette
    // colors are expanded objects rather than packed four-byte source records.
    return baseGlyphCount.saturatingMultiply(96L)
        .saturatingAdd(layerCount.saturatingMultiply(32L))
        .saturatingAdd(baseGlyphCount.saturatingMultiply(layerCount).saturatingMultiply(8L))
        .saturatingAdd(paletteCount.saturatingMultiply(48L))
        .saturatingAdd(paletteCount.saturatingMultiply(paletteEntryCount).saturatingMultiply(56L))
}

private fun estimateSvgRetainedBytes(
    parsedFont: ParsedTrueTypeFont,
    profile: PaintGraphProfile,
): Long {
    if ("SVG " !in parsedFont.tableRecords) return 0L
    val glyphCount = parsedFont.metadata.glyphCount.toLong()
    return profile.limits.maxSvgDocuments.toLong().saturatingMultiply(96L)
        .saturatingAdd(glyphCount.saturatingMultiply(112L))
        .saturatingAdd(glyphCount.saturatingMultiply(profile.limits.maxNodes.toLong()).saturatingMultiply(96L))
        .saturatingAdd(profile.limits.maxSourceBytes.toLong().saturatingMultiply(96L))
}

private fun ByteArray.unsignedShortAt(offset: Int): Int? =
    if (offset < 0 || offset > size - 2) null
    else ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

internal fun cachedRepresentationRetainedBytes(
    key: GlyphRepresentationKey,
    result: Success<GlyphRepresentation>,
): Long =
    CACHE_ENTRY_ENVELOPE_BYTES
        .saturatingAdd(key.estimatedRetainedBytes())
        .saturatingAdd(result.estimatedRetainedBytes())

private fun Success<GlyphRepresentation>.estimatedRetainedBytes(): Long =
    value.estimatedRetainedBytes().saturatingAdd(diagnostics.estimatedRetainedBytes())

private fun GlyphRepresentationKey.estimatedRetainedBytes(): Long {
    val asset = assetIdentity
    var total = 80L
    total = total.saturatingAdd(profile.parameters.estimatedRetainedBytes())
    total = total.saturatingAdd(routeParameters.estimatedRetainedBytes())
    total = total.saturatingAdd(asset.estimatedRetainedBytes())
    return total
}

private fun org.graphiks.kalligraphie.api.FontRenderAssetSemanticIdentity.estimatedRetainedBytes(): Long {
    val instance = fontInstanceKey
    var total = 112L
    total = total.saturatingAdd(variant.value.estimatedRetainedBytes())
    total = total.saturatingAdd(representationProfile.estimatedRetainedBytes())
    total = total.saturatingAdd(variantSnapshot?.estimatedRetainedBytes() ?: 0L)
    total = total.saturatingAdd(instance.face.estimatedRetainedBytes())
    total = total.saturatingAdd(instance.interpretation.pipelineId.estimatedRetainedBytes())
    total = total.saturatingAdd(instance.interpretation.version.estimatedRetainedBytes())
    total = total.saturatingAdd(instance.geometry.normalizedAxes.size.toLong().saturatingMultiply(24L))
    for (axis in instance.geometry.normalizedAxes) {
        total = total.saturatingAdd(axis.tag.estimatedRetainedBytes())
    }
    return total
}

private fun org.graphiks.kalligraphie.api.FontFaceId.estimatedRetainedBytes(): Long =
    48L.saturatingAdd(source.estimatedRetainedBytes())

private fun FontSourceId.estimatedRetainedBytes(): Long = when (this) {
    is FontSourceId.Portable -> 32L.saturatingAdd(contentDigest.value.estimatedRetainedBytes())
    is FontSourceId.Opaque -> 48L
        .saturatingAdd(providerId.estimatedRetainedBytes())
        .saturatingAdd(catalogGeneration.estimatedRetainedBytes())
        .saturatingAdd(sourceToken.estimatedRetainedBytes())
}

private fun GlyphRepresentationProfile.estimatedRetainedBytes(): Long = when (this) {
    is OutlineProfile -> 80L
    is PaintGraphProfile -> 112L
        .saturatingAdd(acceptedNodeKinds.size.toLong().saturatingMultiply(8L))
        .saturatingAdd(acceptedCompositionModes.size.toLong().saturatingMultiply(8L))
    is BitmapProfile -> 112L
        .saturatingAdd(acceptedPixelFormats.size.toLong().saturatingMultiply(8L))
        .saturatingAdd(acceptedColorSpaces.size.toLong().saturatingMultiply(8L))
    is NativeHandleProfile -> 64L
        .saturatingAdd(bridgeKind.estimatedRetainedBytes())
        .saturatingAdd(bridgeVersion.estimatedRetainedBytes())
}

private fun FontRenderVariantSnapshot.estimatedRetainedBytes(): Long =
    40L.saturatingAdd(if (foregroundColor == null) 0L else 16L)

private fun String.estimatedRetainedBytes(): Long =
    24L.saturatingAdd(length.toLong().saturatingMultiply(2L))

internal fun GlyphRepresentation.estimatedRetainedBytes(): Long = when (this) {
    GlyphRepresentation.Empty -> 1L
    is GlyphRepresentation.Outline -> outline.estimatedRetainedBytes()
    is GlyphRepresentation.Paint -> paint.estimatedRetainedBytes()
    is GlyphRepresentation.Bitmap -> bitmap.estimatedRetainedBytes()
}

private fun GlyphOutlineIR.estimatedRetainedBytes(): Long {
    var total = 96L
    for (contour in contours) {
        total = total.saturatingAdd(24L)
        for (command in contour.commands) {
            total = total.saturatingAdd(
                when (command) {
                    is GlyphOutlineCommand.MoveTo,
                    is GlyphOutlineCommand.LineTo,
                    -> 32L

                    is GlyphOutlineCommand.QuadraticTo -> 48L
                    GlyphOutlineCommand.Close -> 16L
                },
            )
        }
    }
    for (component in components) total = total.saturatingAdd(64L)
    return total
}

private fun GlyphPaintIR.estimatedRetainedBytes(): Long {
    var total = 80L
    for (node in nodes) {
        total = total.saturatingAdd(32L)
        total = total.saturatingAdd(
            when (node) {
                is GlyphPaintNode.SolidOutline -> node.outline.estimatedRetainedBytes().saturatingAdd(16L)
                is GlyphPaintNode.Path -> node.path.estimatedByteSize.toLong().saturatingAdd(16L)
                is GlyphPaintNode.Group -> node.children.size.toLong().saturatingMultiply(4L).saturatingAdd(16L)
            },
        )
    }
    return total
}

private fun BitmapGlyphIR.estimatedRetainedBytes(): Long =
    64L.saturatingAdd(decodedByteCount.toLong())

private fun List<FontDiagnostic>.estimatedRetainedBytes(): Long = fold(0L) { total, diagnostic ->
    total.saturatingAdd(64L)
        .saturatingAdd(diagnostic.code.length.toLong() * 2L)
        .saturatingAdd(diagnostic.message.length.toLong() * 2L)
}

private fun Long.saturatingAdd(other: Long): Long =
    if (other > Long.MAX_VALUE - this) Long.MAX_VALUE else this + other

private fun Long.saturatingMultiply(other: Long): Long =
    if (this == 0L || other == 0L) 0L else if (this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other

private const val CACHE_ENTRY_ENVELOPE_BYTES: Long = 120L

@OptIn(ExperimentalAtomicApi::class)
internal class PreparedFontResourceLease(
    internal val resource: PreparedFontResource,
) {
    private val released = AtomicInt(0)

    internal val preparedFont: PreparedTrueTypeFont
        get() = resource.preparedFont

    internal fun release() {
        if (released.compareAndSet(0, 1)) resource.releaseLease()
    }
}

@OptIn(ExperimentalAtomicApi::class)
internal class FontHandleLease(
    private val lifecycle: FontHandleLifecycle,
) {
    private val released = AtomicInt(0)

    fun release() {
        if (released.compareAndSet(0, 1)) {
            lifecycle.releaseLease()
        }
    }
}
