package org.graphiks.kalligraphie.api

import kotlin.jvm.JvmInline

/**
 * Parsed, immutable view of the faces available in one catalog generation.
 *
 * A snapshot does not borrow mutable caller state. Consumers own every handle
 * returned by it and must close those handles when they are no longer needed.
 * Implementations must make face resolution deterministic and safe to call
 * concurrently; failures are returned as [FontOperationResult.Failure] rather
 * than thrown for invalid or unsupported font data.
 */
public interface FontCatalogSnapshot {
    /** Exact immutable generation captured by this catalogue. */
    public val generation: FontCatalogGeneration

    /** Stable face records exposed by this exact generation in provider order. */
    public val faces: List<FontFaceRecord>

    /**
     * Opens a resolver used to acquire render assets from this catalog.
     *
     * A successful result transfers ownership of the returned resolver to the
     * caller. `close()` is idempotent and linearizable (there is one observable
     * transition to the closed state), and may race with acquisitions. An
     * acquisition already admitted before that transition may finish; later
     * acquisitions fail with [FontError.ResourceClosed].
     */
    public fun openAssetResolver(): FontOperationResult<FontAssetResolverHandle>

    /**
     * Resolves a face while applying [requirements].
     *
     * The call has no ownership or lifecycle effect on the catalog. A
     * successful face can be retained by the caller and used concurrently;
     * malformed data, an unsupported face, or an unsupported representation
     * is reported as a typed failure.
     */
    public fun resolveFace(
        faceId: FontFaceId,
        requirements: FontAccessRequirementsSnapshot,
    ): FontOperationResult<FontFace>
}

/**
 * A face that can create layout or rendering instances.
 *
 * Faces are immutable snapshots of parsed font data. Implementations must
 * make [instantiate] safe for concurrent callers and must not retain a
 * caller-owned mutable descriptor.
 */
public interface FontFace {
    /** Stable identifier of this face. */
    public val id: FontFaceId

    /** Metadata declared by the face. */
    public val metadata: FontFaceMetadata

    /**
     * Creates an immutable instance for [descriptor].
     *
     * The successful instance is owned by the caller and remains independent
     * of later descriptor changes. Invalid descriptors are returned as
     * [FontError.InvalidInstanceDescriptor].
     */
    public fun instantiate(descriptor: FontInstanceDescriptor): FontOperationResult<FontInstance>
}

/** Descriptive and structural metadata for a font face. */
public data class FontFaceMetadata(
    /** Family name declared by the font. */
    public val familyName: String,
    /** Style name declared by the font. */
    public val styleName: String,
    /** Number of design units in one em. */
    public val unitsPerEm: Int,
    /** Number of glyphs addressable by the face. */
    public val glyphCount: Int,
) {
    init {
        require(familyName.isNotBlank()) { "familyName must not be blank." }
        require(styleName.isNotBlank()) { "styleName must not be blank." }
        require(unitsPerEm > 0) { "unitsPerEm must be positive." }
        require(glyphCount >= 0) { "glyphCount must be non-negative." }
    }
}

/** Immutable description of the data a caller needs from a face. */
public class FontAccessRequirementsSnapshot private constructor(
    /** Requested access mode. */
    public val mode: Mode,
    acceptedProfiles: List<GlyphRepresentationProfile>,
    /** Whether a native-only route is forbidden for this request. */
    public val portableDataRequired: Boolean,
) {
    /** Ordered immutable profiles a consumer can materialize. */
    public val acceptedProfiles: List<GlyphRepresentationProfile> = acceptedProfiles.immutableListSnapshot()

    /** First accepted outline profile, retained for compatibility with outline-only consumers. */
    public val outlineProfile: OutlineProfile? = this.acceptedProfiles.filterIsInstance<OutlineProfile>().firstOrNull()

    init {
        when (mode) {
            Mode.LAYOUT_ONLY -> require(this.acceptedProfiles.isEmpty()) {
                "LAYOUT_ONLY requirements must not accept a render profile."
            }

            Mode.RENDERABLE -> require(this.acceptedProfiles.isNotEmpty()) {
                "RENDERABLE requirements must accept at least one render profile."
            }
        }
        require(!portableDataRequired || this.acceptedProfiles.any { it !is NativeHandleProfile }) {
            "portableDataRequired cannot be satisfied by native-only profiles."
        }
    }

    /** Supported levels of font access. */
    public enum class Mode {
        /** Metrics and glyph mapping only. */
        LAYOUT_ONLY,

        /** Metrics, glyph mapping, and outlines. */
        RENDERABLE,
    }

    /** Factory methods for the supported access modes. */
    public companion object {
        /** Creates requirements for layout-only access. */
        public fun layoutOnly(): FontAccessRequirementsSnapshot =
            FontAccessRequirementsSnapshot(Mode.LAYOUT_ONLY, emptyList(), false)

        /** Creates requirements for bounded outline access. */
        public fun renderable(outlineProfile: OutlineProfile): FontAccessRequirementsSnapshot =
            renderable(listOf(outlineProfile))

        /**
         * Creates requirements for explicitly ordered render profiles.
         *
         * The order expresses consumer preference only. It never authorizes a less faithful
         * profile merely because an operation is cancelled or takes longer than expected.
         */
        public fun renderable(
            acceptedProfiles: List<GlyphRepresentationProfile>,
            portableDataRequired: Boolean = false,
        ): FontAccessRequirementsSnapshot =
            FontAccessRequirementsSnapshot(Mode.RENDERABLE, acceptedProfiles, portableDataRequired)
    }
}

/**
 * One versioned glyph-materialization profile accepted by a consumer.
 *
 * Implementations are immutable value snapshots. A provider may select only a profile declared
 * in [FontAccessRequirementsSnapshot.acceptedProfiles], and must reject a route that exceeds its
 * associated limits before publishing a certificate.
 */
public sealed interface GlyphRepresentationProfile {
    /** Version of the representation schema understood by the consumer. */
    public val schemaVersion: Int
}

/**
 * Explicit permission to borrow one platform-native materialization route.
 *
 * This profile carries only stable bridge metadata; it never exposes a platform object from the
 * common API. It is incompatible with [FontAccessRequirementsSnapshot.portableDataRequired]
 * when it is the only accepted profile.
 */
public data class NativeHandleProfile(
    /** Stable kind of the platform bridge, such as a platform-font bridge. */
    public val bridgeKind: String,
    /** Version of the platform bridge contract. */
    public val bridgeVersion: String,
    /** Version of the common native-route schema. */
    override val schemaVersion: Int = 1,
) : GlyphRepresentationProfile {
    init {
        require(bridgeKind.isNotBlank()) { "bridgeKind must not be blank." }
        require(bridgeVersion.isNotBlank()) { "bridgeVersion must not be blank." }
        require(schemaVersion > 0) { "schemaVersion must be positive." }
    }
}

/** Resource and geometry limits applied while materializing outlines. */
public data class OutlineProfile(
    /** Version of the outline representation contract. */
    public override val schemaVersion: Int = 1,
    /** Maximum number of bytes that may be materialized. */
    public val maxBytes: Int,
    /** Maximum number of contours in one outline. */
    public val maxContours: Int,
    /** Maximum number of points in one outline. */
    public val maxPoints: Int,
    /** Maximum depth of a composite glyph. */
    public val maxCompositeDepth: Int,
    /** Maximum number of composite components in one outline. */
    public val maxCompositeComponents: Int,
) : GlyphRepresentationProfile {
    init {
        require(schemaVersion > 0) { "schemaVersion must be positive." }
        require(maxBytes > 0) { "maxBytes must be positive." }
        require(maxContours > 0) { "maxContours must be positive." }
        require(maxPoints > 0) { "maxPoints must be positive." }
        require(maxCompositeDepth > 0) { "maxCompositeDepth must be positive." }
        require(maxCompositeComponents > 0) { "maxCompositeComponents must be positive." }
    }
}

/** Selects a rendering variant for a font instance. */
public data class FontRenderVariantKey(
    /** Stable variant key. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "FontRenderVariantKey value must not be blank." }
    }

    /** Factory values for render variants. */
    public companion object {
        /** Default variant used when no specialized renderer is selected. */
        public val default: FontRenderVariantKey = FontRenderVariantKey("default")
    }
}

/**
 * Portable identity of one acquired render asset.
 *
 * The key binds the exact catalog generation, font instance, render variant, and immutable
 * outline profile used by an asset. It owns only portable values, carries no native handle, and
 * is safe to retain or share between threads after the corresponding asset has been closed. A
 * key does not keep the catalog, resolver, or asset resource alive.
 */
public data class FontRenderAssetKey(
    /** Exact font instance served by the asset. */
    public val fontInstanceKey: FontInstanceKey,
    /** Render variant selected when the asset was acquired. */
    public val variant: FontRenderVariantKey,
    /** Outline representation profile enforced by the asset. */
    public val outlineProfile: OutlineProfile,
    /** Exact immutable catalogue generation through which this asset is reopenable. */
    public val generation: FontCatalogGeneration,
)

/** Selects a glyph by its numeric identifier. */
public data class FontGlyphRequest(
    /** Non-negative glyph identifier. */
    public val glyphId: Int,
) {
    init {
        require(glyphId >= 0) { "glyphId must be non-negative." }
    }

    /** Creates a request from the type-safe [GlyphId] wrapper. */
    public constructor(
        glyphId: GlyphId,
        @Suppress("UNUSED_PARAMETER") typedGlyphIdMarker: Unit = Unit,
    ) : this(glyphId.value)

    /** Returns [glyphId] as a type-safe identifier. */
    public val typedGlyphId: GlyphId
        get() = GlyphId(glyphId)
}

/** Cooperative cancellation signal for potentially expensive font operations. */
public fun interface CancellationToken {
    /** Returns whether the current operation should stop. */
    public fun isCancellationRequested(): Boolean

    /** Standard cancellation tokens. */
    public companion object {
        /** Token that never requests cancellation. */
        public val none: CancellationToken = CancellationToken { false }
        /** Token that is already cancelled. */
        public val cancelled: CancellationToken = CancellationToken { true }
    }
}

/**
 * Lifetime handle for render assets associated with one catalog generation.
 *
 * The resolver owns the resources needed for future acquisitions. Its
 * [close] operation is idempotent and linearizable, is safe to call from any
 * thread, and releases ownership of resources once in-flight acquisitions
 * have drained. Calling [close] again succeeds without reopening the
 * resolver; acquisitions after closure return [FontError.ResourceClosed].
 */
public interface FontAssetResolverHandle {
    /** Exact immutable catalogue generation served by this resolver. */
    public val generation: FontCatalogGeneration

    /**
     * Reopens the exact asset identified by [key].
     *
     * The key alone retains no resource. This operation succeeds only while this resolver is
     * open and [key] belongs to [generation]; otherwise it returns a typed `AssetUnavailable`,
     * `IncompatibleCatalogGeneration`, or `ResourceClosed` failure. A successful handle is
     * owned by the caller and remains independently closable or detachable.
     */
    public fun reopen(key: FontRenderAssetKey): FontOperationResult<FontRenderAssetHandle>

    /**
     * Closes this resolver.
     *
     * The operation is idempotent: repeated calls return success and never
     * reopen the resolver. It may run concurrently with acquisition, with the
     * linearization point deciding whether a new acquisition is admitted.
     */
    public fun close(): FontOperationResult<Unit>
}

/**
 * Handle providing glyph data for one face and render variant.
 *
 * The handle owns its render resources. Implementations must make [close],
 * [detach], and [resolveGlyph] safe to call concurrently. [close] is
 * idempotent and linearizable; a resolve admitted before closure may finish,
 * while a later resolve returns [FontError.ResourceClosed]. The caller owns a
 * successful detached handle and must close both handles independently.
 */
public interface FontRenderAssetHandle {
    /** Portable identity of this exact instance, variant, and outline profile. */
    public val key: FontRenderAssetKey

    /** Identifier of the face served by this asset. */
    public val faceId: FontFaceId

    /**
     * Creates a resolver-independent handle when detachment is supported.
     *
     * Detachment does not close or mutate this handle. Each successful call
     * returns a separately owned handle; callers must close every returned
     * handle. An implementation that cannot detach returns a typed failure.
     */
    public fun detach(): FontOperationResult<FontRenderAssetHandle> =
        unsupportedContractOperation("This render asset does not support detachment.")

    /**
     * Resolves [request] to a glyph representation.
     *
     * The operation is read-only and may be invoked concurrently. It returns
     * [FontError.GlyphOutOfRange] for an unknown glyph, a representation or
     * resource-limit failure when the requested output cannot be produced, and
     * [FontError.ResourceClosed] after the handle's close linearization point.
     */
    public fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation>

    /**
     * Resolves [request] while observing [cancellationToken] cooperatively.
     *
     * Cancellation is checked before work and at implementation-defined safe
     * points during decoding. A requested cancellation returns
     * [FontOperationResult.Cancelled] and does not transfer partial output.
     */
    public fun resolveGlyph(
        request: FontGlyphRequest,
        cancellationToken: CancellationToken,
    ): FontOperationResult<GlyphRepresentation> =
        if (cancellationToken.isCancellationRequested()) {
            FontOperationResult.Cancelled()
        } else {
            resolveGlyph(request)
        }

    /**
     * Closes this asset and releases its resources.
     *
     * The operation is idempotent, thread-safe, and linearizable. Repeated
     * calls succeed without reopening the asset; operations admitted before
     * closure may complete, while later operations fail with
     * [FontError.ResourceClosed].
     */
    public fun close(): FontOperationResult<Unit>
}

/** Selects the layout size and geometric interpretation for a font instance. */
public data class FontInstanceDescriptor(
    /** Requested size in layout units. */
    public val layoutSize: LayoutUnit = LayoutUnit(12f),
    /** Normalized variation and synthetic geometry parameters. */
    public val geometry: FontGeometryParameters = FontGeometryParameters(),
)

/**
 * Immutable operational view of a font face at one size.
 *
 * Instances are owned by their caller and may be shared between threads.
 * Implementations must not mutate the descriptor or expose mutable font
 * storage through these operations. Unsupported capabilities are reported as
 * typed failures rather than exceptions.
 */
public interface FontInstance {
    /** Stable key for this instance descriptor. */
    public val key: FontInstanceKey

    /**
     * Resolves a Unicode scalar value to a glyph.
     *
     * Invalid scalar values and missing mappings are represented by the
     * returned result and diagnostics; a missing mapping normally resolves to
     * glyph identifier zero.
     */
    public fun resolveGlyph(codePoint: Int): FontOperationResult<GlyphResolution> =
        unsupportedContractOperation("This font instance does not support glyph resolution.")

    /**
     * Resolves one Unicode variation sequence to the glyph selected by this instance.
     *
     * [codePoint] is the base Unicode scalar and [variationSelector] must be a standard or
     * ideographic variation selector. A successful result proves that this exact pair is
     * declared by the face's variation mapping; the result may differ from resolving
     * [codePoint] alone. Invalid values, an undeclared pair, and unsupported variation
     * mappings are returned as a typed failure or glyph identifier zero with diagnostics.
     * The operation is read-only and safe for concurrent callers.
     */
    public fun resolveGlyph(
        codePoint: Int,
        variationSelector: Int,
    ): FontOperationResult<GlyphResolution> =
        unsupportedContractOperation("This font instance does not support Unicode variation-sequence glyph resolution.")

    /**
     * Returns metrics for [glyphId] scaled to this instance's layout size.
     *
     * The result is read-only and safe for concurrent calls. Unknown glyphs
     * and malformed metric tables are reported as typed failures.
     */
    public fun metrics(glyphId: GlyphId): FontOperationResult<GlyphMetrics> =
        unsupportedContractOperation("This font instance does not support glyph metrics.")

    /**
     * Returns OpenType vertical metrics for [glyphId] at this instance size.
     *
     * Implementations return a typed unsupported-capability failure when the face has no usable
     * `vhea`/`vmtx` tables. Callers select whether that failure is fatal or permits the portable
     * vertical-metrics synthesis through [VerticalMetricsPolicy].
     */
    public fun verticalMetrics(glyphId: GlyphId): FontOperationResult<VerticalGlyphMetrics> =
        unsupportedContractOperation("This font instance does not support vertical glyph metrics.")

    /**
     * Returns an owned defensive copy of the OpenType bytes for this instance's face.
     *
     * The returned [OpenTypeFontData] remains independent of this instance and may be
     * shared between threads. The caller owns every byte-array copy requested from it;
     * modifying such a copy never changes the instance. Implementations that cannot
     * preserve this isolation return a typed capability failure rather than exposing
     * provider, platform, or native storage.
     */
    public fun copyOpenTypeData(): FontOperationResult<OpenTypeFontData> =
        unsupportedOpenTypeDataOperation()

    /**
     * Acquires a render asset for [variant] using [resolver] and [requirements].
     *
     * The resolver must belong to the catalog generation that contains this instance's face. A
     * successful asset is owned by the caller and must be closed; acquisition
     * failures do not transfer ownership.
     */
    public fun acquireRenderAsset(
        resolver: FontAssetResolverHandle,
        variant: FontRenderVariantKey,
        requirements: FontAccessRequirementsSnapshot,
    ): FontOperationResult<FontRenderAssetHandle> =
        unsupportedContractOperation("This font instance does not support render assets.")
}

/**
 * Immutable, owned OpenType source bytes for one concrete face.
 *
 * The container captures its input before construction and returns a fresh copy from
 * [copyBytes]. It contains no borrowed, native, or platform-specific storage. Instances
 * are safe to share between threads; callers own returned arrays and may mutate them.
 */
public class OpenTypeFontData(
    /** Identity of the face described by these bytes. */
    public val face: FontFaceId,
    sourceBytes: ByteArray,
) {
    private val capturedBytes: ByteArray = sourceBytes.copyOf()

    init {
        require(capturedBytes.isNotEmpty()) { "OpenType font data must not be empty." }
    }

    /** Number of captured OpenType bytes. */
    public val sizeInBytes: Int
        get() = capturedBytes.size

    /** Returns a caller-owned copy of the immutable captured OpenType bytes. */
    public fun copyBytes(): ByteArray = capturedBytes.copyOf()
}

@JvmInline
/** Type-safe non-negative glyph identifier. */
public value class GlyphId(
    /** Non-negative glyph identifier value. */
    public val value: Int,
) {
    init {
        require(value >= 0) { "GlyphId value must be non-negative." }
    }
}

/** Result of mapping a Unicode scalar to a glyph. */
public data class GlyphResolution(
    /** Unicode scalar value that was resolved. */
    public val codePoint: Int,
    /** Glyph selected for the code point. */
    public val glyphId: GlyphId,
)

/** Horizontal metrics and bounds for one glyph. */
public data class GlyphMetrics(
    /** Advance width in design units. */
    public val advanceWidthDesignUnits: Int,
    /** Left side bearing in design units. */
    public val leftSideBearingDesignUnits: Int,
    /** Advance width in layout units. */
    public val advanceWidth: LayoutUnit,
    /** Left side bearing in layout units. */
    public val leftSideBearing: LayoutUnit,
    /** Ink bounds in design units. */
    public val bounds: DesignBounds = DesignBounds.empty,
    /** Ink bounds in layout units. */
    public val scaledBounds: LayoutBounds = LayoutBounds.empty,
)

/**
 * OpenType vertical metrics for one glyph at one font-instance size.
 *
 * [advanceHeight] and [topSideBearing] originate in the font's `vmtx` table. A font that lacks
 * usable `vhea`/`vmtx` data reports a typed unsupported capability instead of silently using
 * horizontal metrics.
 */
public data class VerticalGlyphMetrics(
    /** Advance in the top-to-bottom inline direction. */
    public val advanceHeight: LayoutUnit,
    /** Distance from the vertical origin to the glyph's top ink-side bearing. */
    public val topSideBearing: LayoutUnit,
)

/** Representation returned for a resolved glyph. */
public sealed interface GlyphRepresentation {
    /** Represents a glyph without materialized outline data. */
    public data object Empty : GlyphRepresentation

    /** Represents a glyph with a materialized outline. */
    public data class Outline(
        /** Materialized outline intermediate representation. */
        public val outline: GlyphOutlineIR,
    ) : GlyphRepresentation

    /** Represents a glyph with a complete portable paint graph. */
    public data class Paint(
        /** Materialized paint-graph intermediate representation. */
        public val paint: GlyphPaintIR,
    ) : GlyphRepresentation

    /** Represents a glyph with decoded portable bitmap pixels. */
    public data class Bitmap(
        /** Materialized bitmap intermediate representation. */
        public val bitmap: BitmapGlyphIR,
    ) : GlyphRepresentation
}

/**
 * Immutable intermediate representation of a glyph outline.
 *
 * Contour command coordinates are `Double` design units so implicit TrueType
 * points and composite transforms keep their fractional values. [bounds] is
 * the conservative integer envelope used by the existing bounds contract;
 * consumers needing exact geometry should consume [contours].
 */
public class GlyphOutlineIR(
    /** Numeric glyph identifier. */
    public val glyphId: Int,
    /** Design units in one em. */
    public val unitsPerEm: Int,
    /** Bounds of the glyph in design units. */
    public val bounds: DesignBounds,
    /** Contours making up the outline. */
    contours: List<GlyphContour>,
    /** Number of outline points. */
    public val pointCount: Int,
    /** Composite glyph references, when present. */
    components: List<GlyphComponentReference> = emptyList(),
    /** Limits used to produce this representation. */
    public val limits: GlyphOutlineLimits,
    /** Fill rule consumers should use when rasterizing the outline. */
    public val fillRule: FillRule = FillRule.NON_ZERO,
) {
    /** Immutable contour snapshot. */
    public val contours: List<GlyphContour> = contours.immutableListSnapshot()
    /** Immutable composite component snapshot. */
    public val components: List<GlyphComponentReference> = components.immutableListSnapshot()
    /** Flattened command view retained for compatibility with the original contract. */
    public val commands: List<Command> = this.contours
        .flatMap { contour -> contour.commands.map(GlyphOutlineCommand::toLegacyCommand) }
        .immutableListSnapshot()

    init {
        require(glyphId >= 0) { "glyphId must be non-negative." }
        require(unitsPerEm > 0) { "unitsPerEm must be positive." }
        require(pointCount >= 0) { "pointCount must be non-negative." }
    }

    /**
     * Creates an outline from the legacy flattened command representation.
     *
     * The commands are converted into validated contours and use unlimited compatibility limits.
     */
    public constructor(
        glyphId: Int,
        unitsPerEm: Int,
        bounds: DesignBounds,
        commands: List<Command>,
        fillRule: FillRule = FillRule.NON_ZERO,
    ) : this(
        glyphId = glyphId,
        unitsPerEm = unitsPerEm,
        bounds = bounds,
        contours = commands.toLegacyContours(),
        pointCount = commands.sumOf { command -> command.pointContribution() },
        components = emptyList(),
        limits = GlyphOutlineLimits.compatibility,
        fillRule = fillRule,
    )

    /** Winding rule used to fill an outline. */
    public enum class FillRule {
        /** Non-zero winding rule. */
        NON_ZERO,
    }

    /**
     * Legacy flattened command representation.
     *
     * Coordinates are finite, preserve valid fractions, and canonicalize
     * negative zero to positive zero when a command is constructed or copied.
     */
    public sealed interface Command {
        /** Starts a contour at a design-space point. */
        public class MoveTo(
            /** Horizontal coordinate, preserving fractional design units. */
            x: Double,
            /** Vertical coordinate, preserving fractional design units. */
            y: Double,
        ) : Command {
            /** Canonical finite horizontal coordinate. */
            public val x: Double = canonicalGlyphCoordinate(x)
            /** Canonical finite vertical coordinate. */
            public val y: Double = canonicalGlyphCoordinate(y)

            /** Creates a command from integral design-unit coordinates. */
            public constructor(x: Int, y: Int) : this(x.toDouble(), y.toDouble())

            /** Copies this command while revalidating and canonicalizing coordinates. */
            public fun copy(x: Double = this.x, y: Double = this.y): MoveTo = MoveTo(x, y)

            /** Returns the horizontal coordinate for destructuring. */
            public operator fun component1(): Double = x
            /** Returns the vertical coordinate for destructuring. */
            public operator fun component2(): Double = y

            override fun equals(other: Any?): Boolean = other is MoveTo && x == other.x && y == other.y
            override fun hashCode(): Int = 31 * x.hashCode() + y.hashCode()
            override fun toString(): String = "MoveTo(x=$x, y=$y)"
        }

        /** Adds a line segment to a design-space point. */
        public class LineTo(
            /** Horizontal coordinate, preserving fractional design units. */
            x: Double,
            /** Vertical coordinate, preserving fractional design units. */
            y: Double,
        ) : Command {
            /** Canonical finite horizontal coordinate. */
            public val x: Double = canonicalGlyphCoordinate(x)
            /** Canonical finite vertical coordinate. */
            public val y: Double = canonicalGlyphCoordinate(y)

            /** Creates a command from integral design-unit coordinates. */
            public constructor(x: Int, y: Int) : this(x.toDouble(), y.toDouble())

            /** Copies this command while revalidating and canonicalizing coordinates. */
            public fun copy(x: Double = this.x, y: Double = this.y): LineTo = LineTo(x, y)

            /** Returns the horizontal coordinate for destructuring. */
            public operator fun component1(): Double = x
            /** Returns the vertical coordinate for destructuring. */
            public operator fun component2(): Double = y

            override fun equals(other: Any?): Boolean = other is LineTo && x == other.x && y == other.y
            override fun hashCode(): Int = 31 * x.hashCode() + y.hashCode()
            override fun toString(): String = "LineTo(x=$x, y=$y)"
        }

        /** Adds a quadratic Bézier segment. */
        public class QuadraticTo(
            /** Control-point horizontal coordinate, preserving fractions. */
            controlX: Double,
            /** Control-point vertical coordinate, preserving fractions. */
            controlY: Double,
            /** End-point horizontal coordinate, preserving fractions. */
            endX: Double,
            /** End-point vertical coordinate, preserving fractions. */
            endY: Double,
        ) : Command {
            /** Canonical finite control-point horizontal coordinate. */
            public val controlX: Double = canonicalGlyphCoordinate(controlX)
            /** Canonical finite control-point vertical coordinate. */
            public val controlY: Double = canonicalGlyphCoordinate(controlY)
            /** Canonical finite end-point horizontal coordinate. */
            public val endX: Double = canonicalGlyphCoordinate(endX)
            /** Canonical finite end-point vertical coordinate. */
            public val endY: Double = canonicalGlyphCoordinate(endY)

            /** Creates a command from integral design-unit coordinates. */
            public constructor(controlX: Int, controlY: Int, endX: Int, endY: Int) : this(
                controlX.toDouble(),
                controlY.toDouble(),
                endX.toDouble(),
                endY.toDouble(),
            )

            /** Copies this command while revalidating and canonicalizing coordinates. */
            public fun copy(
                controlX: Double = this.controlX,
                controlY: Double = this.controlY,
                endX: Double = this.endX,
                endY: Double = this.endY,
            ): QuadraticTo = QuadraticTo(controlX, controlY, endX, endY)

            /** Returns the control-point horizontal coordinate for destructuring. */
            public operator fun component1(): Double = controlX
            /** Returns the control-point vertical coordinate for destructuring. */
            public operator fun component2(): Double = controlY
            /** Returns the end-point horizontal coordinate for destructuring. */
            public operator fun component3(): Double = endX
            /** Returns the end-point vertical coordinate for destructuring. */
            public operator fun component4(): Double = endY

            override fun equals(other: Any?): Boolean = other is QuadraticTo &&
                controlX == other.controlX && controlY == other.controlY &&
                endX == other.endX && endY == other.endY

            override fun hashCode(): Int {
                var result = controlX.hashCode()
                result = 31 * result + controlY.hashCode()
                result = 31 * result + endX.hashCode()
                result = 31 * result + endY.hashCode()
                return result
            }

            override fun toString(): String =
                "QuadraticTo(controlX=$controlX, controlY=$controlY, endX=$endX, endY=$endY)"
        }

        /** Closes the current contour. */
        public data object Close : Command
    }

    /** Returns the glyph identifier. */
    public operator fun component1(): Int = glyphId

    /** Returns the units-per-em value. */
    public operator fun component2(): Int = unitsPerEm

    /** Returns the design-space bounds. */
    public operator fun component3(): DesignBounds = bounds

    /** Returns the compatibility command view. */
    public operator fun component4(): List<Command> = commands

    /** Returns the fill rule. */
    public operator fun component5(): FillRule = fillRule

    /** Copies the legacy view of this outline. */
    public fun copy(
        glyphId: Int = this.glyphId,
        unitsPerEm: Int = this.unitsPerEm,
        bounds: DesignBounds = this.bounds,
        commands: List<Command> = this.commands,
        fillRule: FillRule = this.fillRule,
    ): GlyphOutlineIR = GlyphOutlineIR(
        glyphId = glyphId,
        unitsPerEm = unitsPerEm,
        bounds = bounds,
        commands = commands,
        fillRule = fillRule,
    )

    /** Copies the structured outline representation. */
    public fun copy(
        glyphId: Int = this.glyphId,
        unitsPerEm: Int = this.unitsPerEm,
        bounds: DesignBounds = this.bounds,
        contours: List<GlyphContour>,
        pointCount: Int = this.pointCount,
        components: List<GlyphComponentReference> = this.components,
        limits: GlyphOutlineLimits = this.limits,
        fillRule: FillRule = this.fillRule,
    ): GlyphOutlineIR = GlyphOutlineIR(
        glyphId,
        unitsPerEm,
        bounds,
        contours,
        pointCount,
        components,
        limits,
        fillRule,
    )

    /** Compares all immutable outline fields and their ordered contents. */
    override fun equals(other: Any?): Boolean =
        this === other || other is GlyphOutlineIR &&
            glyphId == other.glyphId &&
            unitsPerEm == other.unitsPerEm &&
            bounds == other.bounds &&
            contours == other.contours &&
            pointCount == other.pointCount &&
            components == other.components &&
            limits == other.limits &&
            fillRule == other.fillRule

    /** Returns a hash derived from all immutable outline fields. */
    override fun hashCode(): Int {
        var result = glyphId
        result = 31 * result + unitsPerEm
        result = 31 * result + bounds.hashCode()
        result = 31 * result + contours.hashCode()
        result = 31 * result + pointCount
        result = 31 * result + components.hashCode()
        result = 31 * result + limits.hashCode()
        result = 31 * result + fillRule.hashCode()
        return result
    }

    /** Returns a diagnostic representation containing all outline fields. */
    override fun toString(): String =
        "GlyphOutlineIR(glyphId=$glyphId, unitsPerEm=$unitsPerEm, bounds=$bounds, contours=$contours, " +
            "pointCount=$pointCount, components=$components, limits=$limits, fillRule=$fillRule)"
}

/** A validated contour made of outline commands. */
public class GlyphContour(
    commands: List<GlyphOutlineCommand>,
) {
    /** Immutable commands forming this contour. */
    public val commands: List<GlyphOutlineCommand> = commands.immutableListSnapshot()

    init {
        require(commands.isNotEmpty()) { "GlyphContour commands must not be empty." }
        require(commands.first() is GlyphOutlineCommand.MoveTo) { "GlyphContour must start with MoveTo." }
        require(commands.last() is GlyphOutlineCommand.Close) { "GlyphContour must end with Close." }
    }

    /** Returns the contour commands. */
    public operator fun component1(): List<GlyphOutlineCommand> = commands

    /** Copies this contour with a new command list. */
    public fun copy(commands: List<GlyphOutlineCommand> = this.commands): GlyphContour = GlyphContour(commands)

    /** Compares the ordered immutable command list. */
    override fun equals(other: Any?): Boolean = this === other || other is GlyphContour && commands == other.commands

    /** Returns a hash derived from the ordered command list. */
    override fun hashCode(): Int = commands.hashCode()

    /** Returns a diagnostic representation containing the contour commands. */
    override fun toString(): String = "GlyphContour(commands=$commands)"
}

/**
 * Command in a structured glyph contour.
 *
 * Coordinate values are finite, preserve valid fractions, and canonicalize
 * negative zero to positive zero at construction and copy boundaries.
 */
public sealed interface GlyphOutlineCommand {
    /** Starts a contour at a design-space point. */
    public class MoveTo(
        /** Horizontal coordinate, preserving fractional design units. */
        x: Double,
        /** Vertical coordinate, preserving fractional design units. */
        y: Double,
    ) : GlyphOutlineCommand {
        /** Canonical finite horizontal coordinate. */
        public val x: Double = canonicalGlyphCoordinate(x)
        /** Canonical finite vertical coordinate. */
        public val y: Double = canonicalGlyphCoordinate(y)

        /** Creates a command from integral design-unit coordinates. */
        public constructor(x: Int, y: Int) : this(x.toDouble(), y.toDouble())

        /** Copies this command while revalidating and canonicalizing coordinates. */
        public fun copy(x: Double = this.x, y: Double = this.y): MoveTo = MoveTo(x, y)

        /** Returns the horizontal coordinate for destructuring. */
        public operator fun component1(): Double = x
        /** Returns the vertical coordinate for destructuring. */
        public operator fun component2(): Double = y

        override fun equals(other: Any?): Boolean = other is MoveTo && x == other.x && y == other.y
        override fun hashCode(): Int = 31 * x.hashCode() + y.hashCode()
        override fun toString(): String = "MoveTo(x=$x, y=$y)"
    }

    /** Adds a line segment to a design-space point. */
    public class LineTo(
        /** Horizontal coordinate, preserving fractional design units. */
        x: Double,
        /** Vertical coordinate, preserving fractional design units. */
        y: Double,
    ) : GlyphOutlineCommand {
        /** Canonical finite horizontal coordinate. */
        public val x: Double = canonicalGlyphCoordinate(x)
        /** Canonical finite vertical coordinate. */
        public val y: Double = canonicalGlyphCoordinate(y)

        /** Creates a command from integral design-unit coordinates. */
        public constructor(x: Int, y: Int) : this(x.toDouble(), y.toDouble())

        /** Copies this command while revalidating and canonicalizing coordinates. */
        public fun copy(x: Double = this.x, y: Double = this.y): LineTo = LineTo(x, y)

        /** Returns the horizontal coordinate for destructuring. */
        public operator fun component1(): Double = x
        /** Returns the vertical coordinate for destructuring. */
        public operator fun component2(): Double = y

        override fun equals(other: Any?): Boolean = other is LineTo && x == other.x && y == other.y
        override fun hashCode(): Int = 31 * x.hashCode() + y.hashCode()
        override fun toString(): String = "LineTo(x=$x, y=$y)"
    }

    /** Adds a quadratic Bézier segment. */
    public class QuadraticTo(
        /** Control-point horizontal coordinate, preserving fractions. */
        controlX: Double,
        /** Control-point vertical coordinate, preserving fractions. */
        controlY: Double,
        /** End-point horizontal coordinate, preserving fractions. */
        endX: Double,
        /** End-point vertical coordinate, preserving fractions. */
        endY: Double,
    ) : GlyphOutlineCommand {
        /** Canonical finite control-point horizontal coordinate. */
        public val controlX: Double = canonicalGlyphCoordinate(controlX)
        /** Canonical finite control-point vertical coordinate. */
        public val controlY: Double = canonicalGlyphCoordinate(controlY)
        /** Canonical finite end-point horizontal coordinate. */
        public val endX: Double = canonicalGlyphCoordinate(endX)
        /** Canonical finite end-point vertical coordinate. */
        public val endY: Double = canonicalGlyphCoordinate(endY)

        /** Creates a command from integral design-unit coordinates. */
        public constructor(controlX: Int, controlY: Int, endX: Int, endY: Int) : this(
            controlX.toDouble(),
            controlY.toDouble(),
            endX.toDouble(),
            endY.toDouble(),
        )

        /** Copies this command while revalidating and canonicalizing coordinates. */
        public fun copy(
            controlX: Double = this.controlX,
            controlY: Double = this.controlY,
            endX: Double = this.endX,
            endY: Double = this.endY,
        ): QuadraticTo = QuadraticTo(controlX, controlY, endX, endY)

        /** Returns the control-point horizontal coordinate for destructuring. */
        public operator fun component1(): Double = controlX
        /** Returns the control-point vertical coordinate for destructuring. */
        public operator fun component2(): Double = controlY
        /** Returns the end-point horizontal coordinate for destructuring. */
        public operator fun component3(): Double = endX
        /** Returns the end-point vertical coordinate for destructuring. */
        public operator fun component4(): Double = endY

        override fun equals(other: Any?): Boolean = other is QuadraticTo &&
            controlX == other.controlX && controlY == other.controlY &&
            endX == other.endX && endY == other.endY

        override fun hashCode(): Int {
            var result = controlX.hashCode()
            result = 31 * result + controlY.hashCode()
            result = 31 * result + endX.hashCode()
            result = 31 * result + endY.hashCode()
            return result
        }

        override fun toString(): String =
            "QuadraticTo(controlX=$controlX, controlY=$controlY, endX=$endX, endY=$endY)"
    }

    /** Closes the current contour. */
    public data object Close : GlyphOutlineCommand
}

/** Reference to a component glyph and its transform. */
public data class GlyphComponentReference(
    /** Referenced glyph identifier. */
    public val glyphId: Int,
    /** Transform applied to the component. */
    public val transform: GlyphComponentTransform,
) {
    init {
        require(glyphId >= 0) { "Component glyphId must be non-negative." }
    }
}

/**
 * Two-dimensional affine transform for a composite glyph component.
 *
 * Translation values are finite, preserve valid fractions, and canonicalize
 * negative zero to positive zero. Matrix coefficients use signed F2DOT14
 * design-space units.
 */
public class GlyphComponentTransform(
    /** Horizontal translation in design units, preserving fractions. */
    translationX: Double,
    /** Vertical translation in design units, preserving fractions. */
    translationY: Double,
    /** F2DOT14 horizontal-to-horizontal scale. */
    public val xxF2Dot14: Int = 16_384,
    /** F2DOT14 horizontal-to-vertical shear. */
    public val yxF2Dot14: Int = 0,
    /** F2DOT14 vertical-to-horizontal shear. */
    public val xyF2Dot14: Int = 0,
    /** F2DOT14 vertical-to-vertical scale. */
    public val yyF2Dot14: Int = 16_384,
) {
    /** Canonical finite horizontal translation. */
    public val translationX: Double = canonicalGlyphCoordinate(translationX)
    /** Canonical finite vertical translation. */
    public val translationY: Double = canonicalGlyphCoordinate(translationY)

    /** Creates a transform from integral design-unit translations. */
    public constructor(
        translationX: Int,
        translationY: Int,
        xxF2Dot14: Int = 16_384,
        yxF2Dot14: Int = 0,
        xyF2Dot14: Int = 0,
        yyF2Dot14: Int = 16_384,
    ) : this(
        translationX.toDouble(),
        translationY.toDouble(),
        xxF2Dot14,
        yxF2Dot14,
        xyF2Dot14,
        yyF2Dot14,
    )

    /** Copies this transform while revalidating and canonicalizing translations. */
    public fun copy(
        translationX: Double = this.translationX,
        translationY: Double = this.translationY,
        xxF2Dot14: Int = this.xxF2Dot14,
        yxF2Dot14: Int = this.yxF2Dot14,
        xyF2Dot14: Int = this.xyF2Dot14,
        yyF2Dot14: Int = this.yyF2Dot14,
    ): GlyphComponentTransform = GlyphComponentTransform(
        translationX,
        translationY,
        xxF2Dot14,
        yxF2Dot14,
        xyF2Dot14,
        yyF2Dot14,
    )

    /** Returns the horizontal translation for destructuring. */
    public operator fun component1(): Double = translationX
    /** Returns the vertical translation for destructuring. */
    public operator fun component2(): Double = translationY
    /** Returns the horizontal scale matrix element for destructuring. */
    public operator fun component3(): Int = xxF2Dot14
    /** Returns the first shear matrix element for destructuring. */
    public operator fun component4(): Int = yxF2Dot14
    /** Returns the second shear matrix element for destructuring. */
    public operator fun component5(): Int = xyF2Dot14
    /** Returns the vertical scale matrix element for destructuring. */
    public operator fun component6(): Int = yyF2Dot14

    override fun equals(other: Any?): Boolean = other is GlyphComponentTransform &&
        translationX == other.translationX && translationY == other.translationY &&
        xxF2Dot14 == other.xxF2Dot14 && yxF2Dot14 == other.yxF2Dot14 &&
        xyF2Dot14 == other.xyF2Dot14 && yyF2Dot14 == other.yyF2Dot14

    override fun hashCode(): Int {
        var result = translationX.hashCode()
        result = 31 * result + translationY.hashCode()
        result = 31 * result + xxF2Dot14
        result = 31 * result + yxF2Dot14
        result = 31 * result + xyF2Dot14
        result = 31 * result + yyF2Dot14
        return result
    }

    override fun toString(): String =
        "GlyphComponentTransform(translationX=$translationX, translationY=$translationY, " +
            "xxF2Dot14=$xxF2Dot14, yxF2Dot14=$yxF2Dot14, xyF2Dot14=$xyF2Dot14, yyF2Dot14=$yyF2Dot14)"
}

/** Resource limits attached to a materialized outline. */
public data class GlyphOutlineLimits(
    /** Maximum materialized byte count. */
    public val maxBytes: Int,
    /** Maximum contour count. */
    public val maxContours: Int,
    /** Maximum point count. */
    public val maxPoints: Int,
    /** Maximum composite depth. */
    public val maxCompositeDepth: Int,
    /** Maximum component count. */
    public val maxCompositeComponents: Int,
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive." }
        require(maxContours > 0) { "maxContours must be positive." }
        require(maxPoints > 0) { "maxPoints must be positive." }
        require(maxCompositeDepth > 0) { "maxCompositeDepth must be positive." }
        require(maxCompositeComponents > 0) { "maxCompositeComponents must be positive." }
    }

    /** Compatibility and factory values for outline limits. */
    public companion object {
        /** Unlimited limits used by the legacy constructor. */
        public val compatibility: GlyphOutlineLimits = GlyphOutlineLimits(
            maxBytes = Int.MAX_VALUE,
            maxContours = Int.MAX_VALUE,
            maxPoints = Int.MAX_VALUE,
            maxCompositeDepth = Int.MAX_VALUE,
            maxCompositeComponents = Int.MAX_VALUE,
        )
    }
}

/** Converts public access requirements to the outline limits used by the scaler. */
public fun OutlineProfile.toGlyphOutlineLimits(): GlyphOutlineLimits =
    GlyphOutlineLimits(
        maxBytes = maxBytes,
        maxContours = maxContours,
        maxPoints = maxPoints,
        maxCompositeDepth = maxCompositeDepth,
        maxCompositeComponents = maxCompositeComponents,
    )

private fun List<GlyphOutlineIR.Command>.toLegacyContours(): List<GlyphContour> {
    if (isEmpty()) return emptyList()
    val contours = mutableListOf<GlyphContour>()
    var current = mutableListOf<GlyphOutlineCommand>()
    for (command in this) {
        val contourCommand = command.toContourCommand()
        if (contourCommand is GlyphOutlineCommand.MoveTo && current.isNotEmpty()) {
            require(current.last() is GlyphOutlineCommand.Close) { "Each legacy contour must end with Close." }
            contours += GlyphContour(current)
            current = mutableListOf()
        }
        current += contourCommand
        if (contourCommand is GlyphOutlineCommand.Close) {
            contours += GlyphContour(current)
            current = mutableListOf()
        }
    }
    require(current.isEmpty()) { "Legacy outline commands must end with Close." }
    return contours
}

private fun GlyphOutlineIR.Command.pointContribution(): Int =
    when (this) {
        is GlyphOutlineIR.Command.MoveTo,
        is GlyphOutlineIR.Command.LineTo -> 1
        is GlyphOutlineIR.Command.QuadraticTo -> 2
        GlyphOutlineIR.Command.Close -> 0
    }

private fun GlyphOutlineIR.Command.toContourCommand(): GlyphOutlineCommand =
    when (this) {
        is GlyphOutlineIR.Command.MoveTo -> GlyphOutlineCommand.MoveTo(x, y)
        is GlyphOutlineIR.Command.LineTo -> GlyphOutlineCommand.LineTo(x, y)
        is GlyphOutlineIR.Command.QuadraticTo ->
            GlyphOutlineCommand.QuadraticTo(controlX, controlY, endX, endY)
        GlyphOutlineIR.Command.Close -> GlyphOutlineCommand.Close
    }

private fun GlyphOutlineCommand.toLegacyCommand(): GlyphOutlineIR.Command =
    when (this) {
        is GlyphOutlineCommand.MoveTo -> GlyphOutlineIR.Command.MoveTo(x, y)
        is GlyphOutlineCommand.LineTo -> GlyphOutlineIR.Command.LineTo(x, y)
        is GlyphOutlineCommand.QuadraticTo ->
            GlyphOutlineIR.Command.QuadraticTo(controlX, controlY, endX, endY)
        GlyphOutlineCommand.Close -> GlyphOutlineIR.Command.Close
    }

/** Returns the canonical finite representation used by all public glyph coordinates. */
internal fun canonicalGlyphCoordinate(value: Double): Double {
    require(value.isFinite()) { "Glyph coordinates must be finite." }
    return if (value == 0.0) 0.0 else value
}

private fun <Value> unsupportedContractOperation(message: String): FontOperationResult<Value> {
    val error = FontError.UnsupportedRepresentationProfile(message)
    return FontOperationResult.Failure(error, listOf(error.toDiagnostic()))
}

private fun unsupportedOpenTypeDataOperation(): FontOperationResult<OpenTypeFontData> {
    val error = FontError.FontDataFailure(
        code = "font.open-type-data-unavailable",
        message = "This font instance cannot provide isolated OpenType bytes.",
        location = FontDiagnosticLocation.Source,
    )
    return FontOperationResult.Failure(error, listOf(error.toDiagnostic()))
}
