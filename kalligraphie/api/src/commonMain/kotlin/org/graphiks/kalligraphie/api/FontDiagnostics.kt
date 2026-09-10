package org.graphiks.kalligraphie.api

/** Severity assigned to a diagnostic emitted while processing a font. */
public enum class FontDiagnosticSeverity {
    /** Informational result that does not prevent the operation. */
    INFO,
    /** Recoverable issue detected during processing. */
    WARNING,
    /** Error that prevents the requested operation from succeeding. */
    ERROR,
}

/** Optional numeric context attached to a diagnostic. */
public data class FontDiagnosticData(
    /** Byte offset associated with the diagnostic. */
    public val offset: Long? = null,
    /** Byte length associated with the diagnostic. */
    public val length: Long? = null,
    /** Value observed while validating the font. */
    public val observedValue: Long? = null,
    /** Maximum or expected value used for validation. */
    public val limit: Long? = null,
) {
    init {
        require(offset == null || offset >= 0L) { "Diagnostic offset must be non-negative." }
        require(length == null || length >= 0L) { "Diagnostic length must be non-negative." }
        require(limit == null || limit >= 0L) { "Diagnostic limit must be non-negative." }
    }

    /** Factory value for diagnostics without numeric context. */
    public companion object {
        /** Empty context for diagnostics without numeric details. */
        public val empty: FontDiagnosticData = FontDiagnosticData()
    }
}

/** Location within a font source where a diagnostic applies. */
public sealed interface FontDiagnosticLocation {
    /** The source as a whole. */
    public data object Source : FontDiagnosticLocation

    /** A named SFNT table. */
    public data class Table(
        /** Four-character SFNT table tag. */
        public val tag: String,
    ) : FontDiagnosticLocation {
        init {
            require(tag.length == 4) { "Table tag must be exactly four characters." }
        }
    }

    /** A face selected by its zero-based index. */
    public data class Face(
        /** Zero-based face index. */
        public val faceIndex: Int,
    ) : FontDiagnosticLocation {
        init {
            require(faceIndex >= 0) { "faceIndex must be non-negative." }
        }
    }

    /** A face identified by its complete portable semantic identity. */
    public data class FaceId(
        /** Stable source-and-index identity of the selected face. */
        public val faceId: FontFaceId,
    ) : FontDiagnosticLocation

    /** A glyph selected by its numeric identifier. */
    public data class Glyph(
        /** Non-negative glyph identifier. */
        public val glyphId: Int,
    ) : FontDiagnosticLocation {
        init {
            require(glyphId >= 0) { "glyphId must be non-negative." }
        }
    }
}

/** Structured diagnostic emitted by a font operation. */
public data class FontDiagnostic(
    /** Stable diagnostic code. */
    public val code: String,
    /** Severity of the diagnostic. */
    public val severity: FontDiagnosticSeverity,
    /** Resource location associated with the diagnostic. */
    public val location: FontDiagnosticLocation,
    /** Human-readable diagnostic message. */
    public val message: String,
    /** Optional numeric context. */
    public val data: FontDiagnosticData = FontDiagnosticData.empty,
)

/** Typed failure categories returned by font operations. */
public sealed interface FontError {
    /** Stable machine-readable error code. */
    public val code: String
    /** Human-readable error message. */
    public val message: String
    /** Resource location associated with the error. */
    public val location: FontDiagnosticLocation

    /** The source bytes do not contain valid supported font data. */
    public data class InvalidFontData(
        /** Error message explaining why the source is invalid. */
        override val message: String,
        /** Location of the invalid data. */
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Source,
    ) : FontError {
        override val code: String = "font.invalid-font-data"
    }

    /** The source uses an unsupported font container. */
    public data class UnsupportedContainer(
        /** Error message identifying the unsupported container. */
        override val message: String,
        /** Location of the unsupported container. */
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Source,
    ) : FontError {
        override val code: String = "font.unsupported-container"
    }

    /** A required SFNT table is missing or unusable. */
    public data class MissingRequiredTable(
        /** Missing table tag. */
        public val tag: String,
        /** Error message describing the missing or invalid table. */
        override val message: String = "Missing required table: $tag",
        /** Location of the missing table. */
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Table(tag),
    ) : FontError {
        override val code: String = "font.missing-required-table"
    }

    /** A read would extend beyond the available bytes. */
    public data class OutOfBounds(
        /** Error message describing the invalid range. */
        override val message: String,
        /** Location of the invalid range. */
        override val location: FontDiagnosticLocation,
    ) : FontError {
        override val code: String = "font.out-of-bounds"
    }

    /** A configured resource limit was exceeded. */
    public data class ResourceLimitExceeded(
        /** Error message describing the exceeded limit. */
        override val message: String,
        /** Location at which the limit was exceeded. */
        override val location: FontDiagnosticLocation,
    ) : FontError {
        override val code: String = "font.resource-limit-exceeded"
    }

    /** One shaping operation exceeded its explicit scalar or glyph budget. */
    public data class ShapingResourceLimitExceeded(
        /** Resource dimension that rejected the shaping operation. */
        public val limit: ShapingResourceLimit,
        /** Observed scalar or glyph count. */
        public val observed: Int,
    ) : FontError {
        override val code: String = "font.shaping-resource-limit-exceeded"
        override val message: String = "Shaping exceeded $limit at $observed."
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Source
    }

    /** A complete high-level editor operation exceeded its shared resource policy. */
    public data class EditorOperationLimitExceeded(
        /** Exact resource dimension, configured maximum, and observed count. */
        public val exceeded: org.graphiks.kalligraphie.api.EditorOperationLimitExceeded,
    ) : FontError {
        override val code: String = "font.editor-operation-limit-exceeded"
        override val message: String =
            "Editor operation exceeded ${exceeded.kind} at ${exceeded.observed} (maximum ${exceeded.maximum})."
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Source
    }

    /** The requested resource has already been closed. */
    public data class ResourceClosed(
        /** Error message describing the closed resource. */
        override val message: String,
        /** Location of the closed resource. */
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Source,
    ) : FontError {
        override val code: String = "font.resource-closed"
    }

    /** The requested representation profile is not supported. */
    public data class UnsupportedRepresentationProfile(
        /** Error message describing the unsupported profile. */
        override val message: String,
        /** Location at which the profile was requested. */
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Source,
    ) : FontError {
        override val code: String = "font.unsupported-representation-profile"
    }

    /** A glyph identifier is outside the face's glyph range. */
    public data class GlyphOutOfRange(
        /** Out-of-range glyph identifier. */
        public val glyphId: Int,
        /** Error message describing the invalid glyph. */
        override val message: String = "Glyph $glyphId is out of range.",
        /** Location of the invalid glyph. */
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Glyph(glyphId),
    ) : FontError {
        override val code: String = "font.glyph-out-of-range"
    }

    /**
     * The selected route cannot materialize an in-range glyph.
     *
     * This is distinct from an empty glyph: a provider returns it when the requested glyph lacks
     * data on an otherwise accepted route, so a consumer never treats an absent source record as
     * drawable-free content. A certificate is issued only after this condition has been excluded
     * for its exact glyph, asset, variant, and profile.
     */
    public data class GlyphRepresentationUnavailable(
        /** In-range glyph identifier unavailable from the selected representation route. */
        public val glyphId: Int,
        /** Error message describing why the route cannot materialize the glyph. */
        override val message: String,
        /** Location of the unavailable glyph representation. */
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Glyph(glyphId),
    ) : FontError {
        override val code: String = "font.glyph-representation-unavailable"
    }

    /** A numeric geometry conversion could not be represented safely. */
    public data class GeometryOverflow(
        /** Error message describing the overflow. */
        override val message: String,
        /** Location of the overflowing geometry. */
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Source,
    ) : FontError {
        override val code: String = "font.geometry-overflow"
    }

    /** A requested font instance descriptor is invalid. */
    public data class InvalidInstanceDescriptor(
        /** Error message describing the invalid descriptor. */
        override val message: String,
        /** Location associated with the descriptor. */
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Source,
    ) : FontError {
        override val code: String = "font.invalid-instance-descriptor"
    }

    /** A font-specific failure with a caller-defined diagnostic code. */
    public data class FontDataFailure(
        /** Stable domain-specific error code. */
        override val code: String,
        /** Error message describing the font data failure. */
        override val message: String,
        /** Location of the failure. */
        override val location: FontDiagnosticLocation,
    ) : FontError {
        init {
            require(code.startsWith("font.")) { "Font data failure code must start with font." }
            require(code.isNotBlank()) { "Font data failure code must not be blank." }
            require(message.isNotBlank()) { "Font data failure message must not be blank." }
        }
    }

    /** The operation was cancelled before completion. */
    public data class Cancelled(
        /** Cancellation message. */
        override val message: String = "Operation cancelled.",
        /** Location at which cancellation was observed. */
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Source,
    ) : FontError {
        override val code: String = "font.cancelled"
    }

    /** A key cannot be reopened because its asset is no longer available in this generation. */
    public data class AssetUnavailable(
        /** Error message explaining why the asset cannot be reopened. */
        override val message: String,
        /** Location associated with the missing asset. */
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Source,
    ) : FontError {
        override val code: String = "font.asset-unavailable"
    }

    /** A resolver and an asset key belong to different immutable catalogue generations. */
    public data class IncompatibleCatalogGeneration(
        /** Error message identifying the incompatible generations. */
        override val message: String,
        /** Location associated with the incompatible request. */
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Source,
    ) : FontError {
        override val code: String = "font.incompatible-catalog-generation"
    }

    /** Every deterministic candidate was rejected for one indivisible fallback unit. */
    public class UnrenderableFontResolution(
        /** Error message describing the exhausted fallback operation. */
        override val message: String,
        /** Location associated with the unresolved source range. */
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Source,
        fallbackDiagnostics: List<FontFallbackDiagnostic> = emptyList(),
    ) : FontError {
        /** Every local rejection in canonical source, stage and policy order; immutable. */
        public val fallbackDiagnostics: List<FontFallbackDiagnostic> = fallbackDiagnostics.canonicalFallbackDiagnostics()

        override val code: String = "font.unrenderable-font-resolution"

        /** Copies the exhausted operation while defensively capturing its rejection list. */
        public fun copy(
            message: String = this.message,
            location: FontDiagnosticLocation = this.location,
            fallbackDiagnostics: List<FontFallbackDiagnostic> = this.fallbackDiagnostics,
        ): UnrenderableFontResolution = UnrenderableFontResolution(message, location, fallbackDiagnostics)

        /** Returns the message for source-compatible destructuring. */
        public operator fun component1(): String = message

        /** Returns the location for source-compatible destructuring. */
        public operator fun component2(): FontDiagnosticLocation = location

        /** Compares independently captured exhaustion results by value. */
        override fun equals(other: Any?): Boolean = other is UnrenderableFontResolution &&
            message == other.message && location == other.location && fallbackDiagnostics == other.fallbackDiagnostics

        /** Hashes the captured error and all localized rejection decisions. */
        override fun hashCode(): Int = listOf(message, location, fallbackDiagnostics).hashCode()
    }
}

/** Outcome of a font operation, including diagnostics. */
public sealed interface FontOperationResult<out T> {
    /** Successful operation result. */
    public class Success<T>(
        /** Value produced by the operation. */
        public val value: T,
        diagnostics: List<FontDiagnostic> = emptyList(),
    ) : FontOperationResult<T> {
        /** Canonically ordered diagnostics associated with the value. */
        public val diagnostics: List<FontDiagnostic> = diagnostics.canonicalDiagnostics()

        /** Creates a success result from any diagnostic iterable. */
        public constructor(value: T, diagnostics: Iterable<FontDiagnostic>) : this(value, diagnostics.toList())

        /** Returns the successful value for destructuring. */
        public operator fun component1(): T = value

        /** Returns the diagnostics for destructuring. */
        public operator fun component2(): List<FontDiagnostic> = diagnostics

        /** Copies this result with selected fields changed. */
        public fun copy(
            value: T = this.value,
            diagnostics: List<FontDiagnostic> = this.diagnostics,
        ): Success<T> = Success(value, diagnostics)

        /** Compares the payload and canonical diagnostics. */
        override fun equals(other: Any?): Boolean =
            this === other || other is Success<*> && value == other.value && diagnostics == other.diagnostics

        /** Returns a hash derived from the payload and canonical diagnostics. */
        override fun hashCode(): Int = 31 * (value?.hashCode() ?: 0) + diagnostics.hashCode()

        /** Returns a diagnostic representation of this successful result. */
        override fun toString(): String = "Success(value=$value, diagnostics=$diagnostics)"
    }

    /** Failed operation result. */
    public class Failure(
        /** Typed error describing the failure. */
        public val error: FontError,
        diagnostics: List<FontDiagnostic> = emptyList(),
    ) : FontOperationResult<Nothing> {
        /** Canonically ordered diagnostics associated with the error. */
        public val diagnostics: List<FontDiagnostic> = diagnostics.canonicalDiagnostics()

        /** Creates a failure result from any diagnostic iterable. */
        public constructor(error: FontError, diagnostics: Iterable<FontDiagnostic>) : this(error, diagnostics.toList())

        /** Returns the error for destructuring. */
        public operator fun component1(): FontError = error

        /** Returns the diagnostics for destructuring. */
        public operator fun component2(): List<FontDiagnostic> = diagnostics

        /** Copies this result with selected fields changed. */
        public fun copy(
            error: FontError = this.error,
            diagnostics: List<FontDiagnostic> = this.diagnostics,
        ): Failure = Failure(error, diagnostics)

        /** Compares the typed error and canonical diagnostics. */
        override fun equals(other: Any?): Boolean =
            this === other || other is Failure && error == other.error && diagnostics == other.diagnostics

        /** Returns a hash derived from the typed error and diagnostics. */
        override fun hashCode(): Int = 31 * error.hashCode() + diagnostics.hashCode()

        /** Returns a diagnostic representation of this failed result. */
        override fun toString(): String = "Failure(error=$error, diagnostics=$diagnostics)"
    }

    /** Operation result that stopped because cancellation was requested. */
    public class Cancelled(
        diagnostics: List<FontDiagnostic> = emptyList(),
    ) : FontOperationResult<Nothing> {
        /** Canonically ordered diagnostics associated with cancellation. */
        public val diagnostics: List<FontDiagnostic> = diagnostics.canonicalDiagnostics()

        /** Creates a cancelled result from any diagnostic iterable. */
        public constructor(diagnostics: Iterable<FontDiagnostic>) : this(diagnostics.toList())

        /** Returns the diagnostics for destructuring. */
        public operator fun component1(): List<FontDiagnostic> = diagnostics

        /** Copies this result with selected fields changed. */
        public fun copy(
            diagnostics: List<FontDiagnostic> = this.diagnostics,
        ): Cancelled = Cancelled(diagnostics)

        /** Compares the canonical cancellation diagnostics. */
        override fun equals(other: Any?): Boolean =
            this === other || other is Cancelled && diagnostics == other.diagnostics

        /** Returns a hash derived from the cancellation diagnostics. */
        override fun hashCode(): Int = diagnostics.hashCode()

        /** Returns a diagnostic representation of this cancelled result. */
        override fun toString(): String = "Cancelled(diagnostics=$diagnostics)"
    }
}

/** Converts an error into an error-severity diagnostic. */
public fun FontError.toDiagnostic(
    data: FontDiagnosticData = FontDiagnosticData.empty,
): FontDiagnostic =
    FontDiagnostic(
        code = code,
        severity = FontDiagnosticSeverity.ERROR,
        location = location,
        message = message,
        data = data,
    )

/** Returns diagnostics in their canonical deterministic order. */
public fun Iterable<FontDiagnostic>.sortedDiagnostics(): List<FontDiagnostic> =
    toList().sortedWith(
        compareBy<FontDiagnostic>(
            { it.code },
            { it.location.sortKey() },
            { it.data.offset },
            { it.data.length },
            { it.data.observedValue },
            { it.data.limit },
            { it.severity.ordinal },
            { it.message },
        ),
    ).immutableListSnapshot()

private fun List<FontDiagnostic>.canonicalDiagnostics(): List<FontDiagnostic> = sortedDiagnostics()

private fun FontDiagnosticLocation.sortKey(): String =
    when (this) {
        FontDiagnosticLocation.Source -> "source"
        is FontDiagnosticLocation.Table -> "table:$tag"
        is FontDiagnosticLocation.Face -> "face:$faceIndex"
        is FontDiagnosticLocation.FaceId -> "face-id:${faceId.source}:${faceId.faceIndex}"
        is FontDiagnosticLocation.Glyph -> "glyph:$glyphId"
    }
