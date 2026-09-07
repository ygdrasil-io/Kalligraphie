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
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontProviderId
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderAssetKey
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceId
import org.graphiks.kalligraphie.api.sortedDiagnostics
import org.graphiks.kalligraphie.api.toDiagnostic
import org.graphiks.kalligraphie.font.scaler.PreparedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.ColrCpalReader
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
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
 */
public class EmbeddedFontCatalog(
    override val generation: FontCatalogGeneration,
    entries: List<EmbeddedFontCatalogEntry>,
) : FontCatalogSnapshot {
    private val resources: Map<FontFaceId, PreparedFontResource>
    private val parsedFonts: Map<FontFaceId, ParsedTrueTypeFont>
    private val paintGraphSupportedFaces: Set<FontFaceId>
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
            id to PreparedFontResource(PreparedTrueTypeFont(entry.source, entry.parsedFont))
        }
        parsedFonts = ids.zip(capturedEntries).associate { (id, entry) -> id to entry.parsedFont }
        paintGraphSupportedFaces = ids.filter { id ->
            supportsColrCpalV0(resources.getValue(id), parsedFonts.getValue(id))
        }.toSet()
        resolvedFaces = ids.associateWith { id ->
            TrueTypeFace(
                faceId = id,
                generation = generation,
                parsedFont = parsedFonts.getValue(id),
                resource = resources.getValue(id),
                paintGraphSupported = id in paintGraphSupportedFaces,
            )
        }
        faces = ids.map { id ->
            FontFaceRecord(
                id = id,
                metadata = parsedFonts.getValue(id).metadata,
                capabilities = FontFaceCapabilities(
                    characterMapping = true,
                    shaping = true,
                    outline = true,
                    paintGraph = id in paintGraphSupportedFaces,
                ),
            )
        }
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
                        message = "Only LAYOUT_ONLY, schemaVersion=1 outlines, and declared COLR/CPAL version 0 paint profiles are supported.",
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
            is org.graphiks.kalligraphie.api.OutlineProfile -> schemaVersion == 1
            is org.graphiks.kalligraphie.api.PaintGraphProfile ->
                schemaVersion == 1 && faceId in paintGraphSupportedFaces
            else -> false
        }

    private fun failure(error: FontError, diagnostics: List<FontDiagnostic> = listOf(error.toDiagnostic())): FontOperationResult.Failure =
        FontOperationResult.Failure(error, diagnostics.sortedDiagnostics())
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

/**
 * One captured OpenType source included in an [EmbeddedFontCatalog].
 *
 * The provider captures both values while creating its immutable generation. The source owns a
 * defensive copy of its bytes and parsed metadata is immutable; callers retain no provider
 * resource by retaining this entry.
 */
public data class EmbeddedFontCatalogEntry(
    /** Captured portable font source. */
    public val source: FontSource,
    /** Parsed metadata for the source's sole TrueType face. */
    public val parsedFont: ParsedTrueTypeFont,
)

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
            paintGraphSupported = supportsColrCpalV0(resource, parsedFont),
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
                    parsedFont.tableRecords.containsKey("glyf") && parsedFont.tableRecords.containsKey("loca")
            is org.graphiks.kalligraphie.api.PaintGraphProfile ->
                profile.schemaVersion == 1 &&
                    parsedFont.tableRecords.containsKey("COLR") && parsedFont.tableRecords.containsKey("CPAL")
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
) {
    private val leaseCount = AtomicInt(0)

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
            if (leaseCount.compareAndSet(current, current - 1)) return
        }
    }
}

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
