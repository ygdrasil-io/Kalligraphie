package org.graphiks.kalligraphie.api

/** Immutable affine transform applied in glyph design coordinates. */
public data class GlyphPaintTransform(
    /** First-column horizontal component. */
    public val scaleX: Double,
    /** First-column vertical component. */
    public val shearY: Double,
    /** Second-column horizontal component. */
    public val shearX: Double,
    /** Second-column vertical component. */
    public val scaleY: Double,
    /** Horizontal translation. */
    public val translateX: Double,
    /** Vertical translation. */
    public val translateY: Double,
) {
    init {
        require(
            listOf(scaleX, shearY, shearX, scaleY, translateX, translateY).all(Double::isFinite),
        ) { "Paint transform components must be finite." }
    }

    /** Composes this parent transform with [child] in paint order. */
    public fun followedBy(child: GlyphPaintTransform): GlyphPaintTransform = GlyphPaintTransform(
        scaleX = scaleX * child.scaleX + shearX * child.shearY,
        shearY = shearY * child.scaleX + scaleY * child.shearY,
        shearX = scaleX * child.shearX + shearX * child.scaleY,
        scaleY = shearY * child.shearX + scaleY * child.scaleY,
        translateX = scaleX * child.translateX + shearX * child.translateY + translateX,
        translateY = shearY * child.translateX + scaleY * child.translateY + translateY,
    )

    /** Well-known transform values. */
    public companion object {
        /** Transform that leaves coordinates unchanged. */
        public val identity: GlyphPaintTransform = GlyphPaintTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    }
}

/** Fill winding rule for a portable vector paint path. */
public enum class GlyphPaintFillRule {
    /** Fill points whose non-zero winding number crosses the path. */
    NON_ZERO,

    /** Fill points crossed an odd number of times by the path. */
    EVEN_ODD,
}

/** Immutable portable vector path used by a paint node. */
public class GlyphPaintPath(
    commands: List<GlyphPaintPathCommand>,
    /** Winding rule that applies to every closed contour. */
    public val fillRule: GlyphPaintFillRule = GlyphPaintFillRule.NON_ZERO,
) {
    /** Immutable sequence of normalized path commands. */
    public val commands: List<GlyphPaintPathCommand> = commands.immutableListSnapshot()

    init {
        require(this.commands.isNotEmpty()) { "A paint path must contain at least one command." }
        require(this.commands.first() is GlyphPaintPathCommand.MoveTo) { "A paint path must begin with MoveTo." }
        require(this.commands.all(GlyphPaintPathCommand::hasFiniteCoordinates)) {
            "Paint path coordinates must be finite."
        }
        require(this.commands.filterIsInstance<GlyphPaintPathCommand.ArcTo>().all { arc ->
            arc.radiusX >= 0.0 && arc.radiusY >= 0.0
        }) { "Paint arc radii must be non-negative." }
    }

    override fun equals(other: Any?): Boolean =
        other is GlyphPaintPath && commands == other.commands && fillRule == other.fillRule

    override fun hashCode(): Int = 31 * commands.hashCode() + fillRule.hashCode()

    override fun toString(): String = "GlyphPaintPath(commands=$commands, fillRule=$fillRule)"
}

/** One normalized command in a portable vector paint path. */
public sealed interface GlyphPaintPathCommand {
    /** Starts a contour at one finite point. */
    public data class MoveTo(
        /** Horizontal endpoint. */
        public val x: Double,
        /** Vertical endpoint. */
        public val y: Double,
    ) : GlyphPaintPathCommand

    /** Adds a straight segment to one finite point. */
    public data class LineTo(
        /** Horizontal endpoint. */
        public val x: Double,
        /** Vertical endpoint. */
        public val y: Double,
    ) : GlyphPaintPathCommand

    /** Adds a quadratic Bézier segment. */
    public data class QuadraticTo(
        /** Horizontal control coordinate. */
        public val controlX: Double,
        /** Vertical control coordinate. */
        public val controlY: Double,
        /** Horizontal endpoint. */
        public val x: Double,
        /** Vertical endpoint. */
        public val y: Double,
    ) : GlyphPaintPathCommand

    /** Adds a cubic Bézier segment. */
    public data class CubicTo(
        /** First control horizontal coordinate. */
        public val control1X: Double,
        /** First control vertical coordinate. */
        public val control1Y: Double,
        /** Second control horizontal coordinate. */
        public val control2X: Double,
        /** Second control vertical coordinate. */
        public val control2Y: Double,
        /** Horizontal endpoint. */
        public val x: Double,
        /** Vertical endpoint. */
        public val y: Double,
    ) : GlyphPaintPathCommand

    /** Adds an elliptical arc without retaining SVG source syntax. */
    public data class ArcTo(
        /** Horizontal radius. */
        public val radiusX: Double,
        /** Vertical radius. */
        public val radiusY: Double,
        /** Rotation of the ellipse's horizontal axis in degrees. */
        public val xAxisRotationDegrees: Double,
        /** Whether the long arc is selected. */
        public val largeArc: Boolean,
        /** Whether the arc progresses in the positive-angle direction. */
        public val sweep: Boolean,
        /** Horizontal endpoint. */
        public val x: Double,
        /** Vertical endpoint. */
        public val y: Double,
    ) : GlyphPaintPathCommand

    /** Closes the current contour. */
    public data object Close : GlyphPaintPathCommand
}

/** One stop in a portable gradient. */
public data class GlyphPaintGradientStop(
    /** Offset in the inclusive normalized interval `0..1`. */
    public val offset: Double,
    /** Non-premultiplied color at this offset. */
    public val color: GlyphColor,
) {
    init {
        require(offset.isFinite() && offset in 0.0..1.0) { "Gradient stop offset must be finite and in 0..1." }
    }
}

/** Repeat behavior outside a gradient's normalized interval. */
public enum class GlyphPaintGradientSpread {
    /** Extend the first and last colors. */
    PAD,

    /** Repeat the gradient interval. */
    REPEAT,

    /** Repeat alternating mirrored gradient intervals. */
    REFLECT,
}

/** Portable brush applied to one vector path. */
public sealed interface GlyphPaintBrush {
    /** Solid non-premultiplied color. */
    public data class Solid(
        /** Applied color. */
        public val color: GlyphColor,
    ) : GlyphPaintBrush

    /** Linear gradient in user-space coordinates. */
    public class LinearGradient(
        /** Horizontal start coordinate. */
        public val startX: Double,
        /** Vertical start coordinate. */
        public val startY: Double,
        /** Horizontal end coordinate. */
        public val endX: Double,
        /** Vertical end coordinate. */
        public val endY: Double,
        stops: List<GlyphPaintGradientStop>,
        /** Repeat behavior outside the stop interval. */
        public val spread: GlyphPaintGradientSpread = GlyphPaintGradientSpread.PAD,
        /** Transform from gradient coordinates to glyph coordinates. */
        public val transform: GlyphPaintTransform = GlyphPaintTransform.identity,
    ) : GlyphPaintBrush {
        /** Ordered immutable color stops. */
        public val stops: List<GlyphPaintGradientStop> = stops.immutableListSnapshot()

        init {
            require(listOf(startX, startY, endX, endY).all(Double::isFinite)) { "Linear gradient coordinates must be finite." }
            require(this.stops.isNotEmpty()) { "A linear gradient must contain at least one stop." }
            require(this.stops.zipWithNext().all { (left, right) -> left.offset <= right.offset }) {
                "Gradient stop offsets must be ordered."
            }
        }

        override fun equals(other: Any?): Boolean = other is LinearGradient &&
            startX == other.startX && startY == other.startY && endX == other.endX && endY == other.endY &&
            stops == other.stops && spread == other.spread && transform == other.transform

        override fun hashCode(): Int {
            var result = startX.hashCode()
            result = 31 * result + startY.hashCode()
            result = 31 * result + endX.hashCode()
            result = 31 * result + endY.hashCode()
            result = 31 * result + stops.hashCode()
            result = 31 * result + spread.hashCode()
            return 31 * result + transform.hashCode()
        }
    }

    /** Radial gradient in user-space coordinates. */
    public class RadialGradient(
        /** Horizontal center coordinate. */
        public val centerX: Double,
        /** Vertical center coordinate. */
        public val centerY: Double,
        /** Non-negative radius. */
        public val radius: Double,
        /** Horizontal focal coordinate. */
        public val focalX: Double = centerX,
        /** Vertical focal coordinate. */
        public val focalY: Double = centerY,
        stops: List<GlyphPaintGradientStop>,
        /** Repeat behavior outside the stop interval. */
        public val spread: GlyphPaintGradientSpread = GlyphPaintGradientSpread.PAD,
        /** Transform from gradient coordinates to glyph coordinates. */
        public val transform: GlyphPaintTransform = GlyphPaintTransform.identity,
    ) : GlyphPaintBrush {
        /** Ordered immutable color stops. */
        public val stops: List<GlyphPaintGradientStop> = stops.immutableListSnapshot()

        init {
            require(listOf(centerX, centerY, radius, focalX, focalY).all(Double::isFinite)) {
                "Radial gradient coordinates must be finite."
            }
            require(radius > 0.0) { "Radial gradient radius must be positive." }
            require(this.stops.isNotEmpty()) { "A radial gradient must contain at least one stop." }
            require(this.stops.zipWithNext().all { (left, right) -> left.offset <= right.offset }) {
                "Gradient stop offsets must be ordered."
            }
        }

        override fun equals(other: Any?): Boolean = other is RadialGradient &&
            centerX == other.centerX && centerY == other.centerY && radius == other.radius &&
            focalX == other.focalX && focalY == other.focalY && stops == other.stops &&
            spread == other.spread && transform == other.transform

        override fun hashCode(): Int {
            var result = centerX.hashCode()
            result = 31 * result + centerY.hashCode()
            result = 31 * result + radius.hashCode()
            result = 31 * result + focalX.hashCode()
            result = 31 * result + focalY.hashCode()
            result = 31 * result + stops.hashCode()
            result = 31 * result + spread.hashCode()
            return 31 * result + transform.hashCode()
        }
    }
}

private fun GlyphPaintPathCommand.hasFiniteCoordinates(): Boolean = when (this) {
    is GlyphPaintPathCommand.MoveTo -> x.isFinite() && y.isFinite()
    is GlyphPaintPathCommand.LineTo -> x.isFinite() && y.isFinite()
    is GlyphPaintPathCommand.QuadraticTo -> controlX.isFinite() && controlY.isFinite() && x.isFinite() && y.isFinite()
    is GlyphPaintPathCommand.CubicTo -> control1X.isFinite() && control1Y.isFinite() &&
        control2X.isFinite() && control2Y.isFinite() && x.isFinite() && y.isFinite()
    is GlyphPaintPathCommand.ArcTo -> radiusX.isFinite() && radiusY.isFinite() &&
        xAxisRotationDegrees.isFinite() && x.isFinite() && y.isFinite()
    GlyphPaintPathCommand.Close -> true
}
