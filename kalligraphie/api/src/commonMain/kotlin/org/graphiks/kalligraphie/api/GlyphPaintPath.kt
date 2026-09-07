package org.graphiks.kalligraphie.api

/** Fill rule applied while painting one portable path. */
public enum class GlyphPaintFillRule {
    /** Fills regions whose winding number is non-zero. */
    NON_ZERO,
}

/**
 * One immutable, closed path used by a portable paint graph.
 *
 * The path stores resolved design-space coordinates only: it carries no SVG source, reference,
 * native object, or renderer state. A path can contain several closed contours, each beginning
 * with [GlyphPaintPathCommand.MoveTo]. Coordinates are finite and immutable, so the value is safe
 * to retain and share after its font asset has closed.
 */
public class GlyphPaintPath(
    commands: List<GlyphPaintPathCommand>,
    /** Fill rule selected while normalizing the source graphic. */
    public val fillRule: GlyphPaintFillRule = GlyphPaintFillRule.NON_ZERO,
) {
    /** Immutable path commands in paint order. */
    public val commands: List<GlyphPaintPathCommand> = commands.immutableListSnapshot()

    /** Number of coordinate points, including Bézier control points. */
    public val pointCount: Int = this.commands.sumOf(GlyphPaintPathCommand::pointContribution)

    /** Conservative axis-aligned envelope of all path and control points. */
    public val bounds: DesignBounds = this.commands.designBounds()

    /** Conservative byte weight used when enforcing geometry resource limits. */
    public val estimatedByteSize: Int = this.commands.estimatedByteSize()

    /** Number of closed contours contained by this path. */
    public val contourCount: Int = this.commands.count { command -> command is GlyphPaintPathCommand.Close }

    init {
        require(this.commands.isNotEmpty()) { "A paint path must contain commands." }
        var contourOpen = false
        this.commands.forEach { command ->
            when (command) {
                is GlyphPaintPathCommand.MoveTo -> {
                    require(!contourOpen) { "A paint contour must close before another MoveTo." }
                    contourOpen = true
                }

                is GlyphPaintPathCommand.LineTo,
                is GlyphPaintPathCommand.CubicTo,
                -> require(contourOpen) { "A paint drawing command requires an open contour." }

                GlyphPaintPathCommand.Close -> {
                    require(contourOpen) { "A paint contour cannot close before MoveTo." }
                    contourOpen = false
                }
            }
        }
        require(!contourOpen) { "A paint path must end with Close." }
    }

    /** Returns a structurally identical path with selected immutable values replaced. */
    public fun copy(
        commands: List<GlyphPaintPathCommand> = this.commands,
        fillRule: GlyphPaintFillRule = this.fillRule,
    ): GlyphPaintPath = GlyphPaintPath(commands, fillRule)

    /** Compares the complete path content and fill rule. */
    override fun equals(other: Any?): Boolean =
        this === other || other is GlyphPaintPath && commands == other.commands && fillRule == other.fillRule

    /** Returns a hash derived from the complete path content and fill rule. */
    override fun hashCode(): Int = 31 * commands.hashCode() + fillRule.hashCode()

    /** Returns a diagnostic representation of the path without any source document. */
    override fun toString(): String = "GlyphPaintPath(commands=$commands, fillRule=$fillRule)"
}

/** One command in an immutable portable paint path. */
public sealed interface GlyphPaintPathCommand {
    /** Starts one closed contour at a design-space point. */
    public class MoveTo(
        x: Double,
        y: Double,
    ) : GlyphPaintPathCommand {
        /** Canonical finite horizontal coordinate. */
        public val x: Double = canonicalGlyphCoordinate(x)
        /** Canonical finite vertical coordinate. */
        public val y: Double = canonicalGlyphCoordinate(y)

        /** Returns a command with selected coordinates replaced. */
        public fun copy(x: Double = this.x, y: Double = this.y): MoveTo = MoveTo(x, y)

        /** Returns the horizontal coordinate for destructuring. */
        public operator fun component1(): Double = x
        /** Returns the vertical coordinate for destructuring. */
        public operator fun component2(): Double = y

        override fun equals(other: Any?): Boolean = other is MoveTo && x == other.x && y == other.y
        override fun hashCode(): Int = 31 * x.hashCode() + y.hashCode()
        override fun toString(): String = "MoveTo(x=$x, y=$y)"
    }

    /** Adds a straight segment ending at a design-space point. */
    public class LineTo(
        x: Double,
        y: Double,
    ) : GlyphPaintPathCommand {
        /** Canonical finite horizontal coordinate. */
        public val x: Double = canonicalGlyphCoordinate(x)
        /** Canonical finite vertical coordinate. */
        public val y: Double = canonicalGlyphCoordinate(y)

        /** Returns a command with selected coordinates replaced. */
        public fun copy(x: Double = this.x, y: Double = this.y): LineTo = LineTo(x, y)

        /** Returns the horizontal coordinate for destructuring. */
        public operator fun component1(): Double = x
        /** Returns the vertical coordinate for destructuring. */
        public operator fun component2(): Double = y

        override fun equals(other: Any?): Boolean = other is LineTo && x == other.x && y == other.y
        override fun hashCode(): Int = 31 * x.hashCode() + y.hashCode()
        override fun toString(): String = "LineTo(x=$x, y=$y)"
    }

    /** Adds a cubic Bézier segment with two control points and one endpoint. */
    public class CubicTo(
        control1X: Double,
        control1Y: Double,
        control2X: Double,
        control2Y: Double,
        endX: Double,
        endY: Double,
    ) : GlyphPaintPathCommand {
        /** Canonical finite first control-point horizontal coordinate. */
        public val control1X: Double = canonicalGlyphCoordinate(control1X)
        /** Canonical finite first control-point vertical coordinate. */
        public val control1Y: Double = canonicalGlyphCoordinate(control1Y)
        /** Canonical finite second control-point horizontal coordinate. */
        public val control2X: Double = canonicalGlyphCoordinate(control2X)
        /** Canonical finite second control-point vertical coordinate. */
        public val control2Y: Double = canonicalGlyphCoordinate(control2Y)
        /** Canonical finite endpoint horizontal coordinate. */
        public val endX: Double = canonicalGlyphCoordinate(endX)
        /** Canonical finite endpoint vertical coordinate. */
        public val endY: Double = canonicalGlyphCoordinate(endY)

        /** Returns a command with selected coordinates replaced. */
        public fun copy(
            control1X: Double = this.control1X,
            control1Y: Double = this.control1Y,
            control2X: Double = this.control2X,
            control2Y: Double = this.control2Y,
            endX: Double = this.endX,
            endY: Double = this.endY,
        ): CubicTo = CubicTo(control1X, control1Y, control2X, control2Y, endX, endY)

        /** Returns the first control-point horizontal coordinate for destructuring. */
        public operator fun component1(): Double = control1X
        /** Returns the first control-point vertical coordinate for destructuring. */
        public operator fun component2(): Double = control1Y
        /** Returns the second control-point horizontal coordinate for destructuring. */
        public operator fun component3(): Double = control2X
        /** Returns the second control-point vertical coordinate for destructuring. */
        public operator fun component4(): Double = control2Y
        /** Returns the endpoint horizontal coordinate for destructuring. */
        public operator fun component5(): Double = endX
        /** Returns the endpoint vertical coordinate for destructuring. */
        public operator fun component6(): Double = endY

        override fun equals(other: Any?): Boolean = other is CubicTo &&
            control1X == other.control1X && control1Y == other.control1Y &&
            control2X == other.control2X && control2Y == other.control2Y &&
            endX == other.endX && endY == other.endY

        override fun hashCode(): Int {
            var result = control1X.hashCode()
            result = 31 * result + control1Y.hashCode()
            result = 31 * result + control2X.hashCode()
            result = 31 * result + control2Y.hashCode()
            result = 31 * result + endX.hashCode()
            return 31 * result + endY.hashCode()
        }

        override fun toString(): String =
            "CubicTo(control1X=$control1X, control1Y=$control1Y, control2X=$control2X, " +
                "control2Y=$control2Y, endX=$endX, endY=$endY)"
    }

    /** Closes the current contour. */
    public data object Close : GlyphPaintPathCommand
}

private fun GlyphPaintPathCommand.pointContribution(): Int = when (this) {
    is GlyphPaintPathCommand.MoveTo,
    is GlyphPaintPathCommand.LineTo,
    -> 1
    is GlyphPaintPathCommand.CubicTo -> 3
    GlyphPaintPathCommand.Close -> 0
}

private fun List<GlyphPaintPathCommand>.designBounds(): DesignBounds {
    val points = flatMap(GlyphPaintPathCommand::points)
    if (points.isEmpty()) return DesignBounds.empty
    val minX = points.minOf { point -> point.first }
    val minY = points.minOf { point -> point.second }
    val maxX = points.maxOf { point -> point.first }
    val maxY = points.maxOf { point -> point.second }
    require(minX >= Int.MIN_VALUE && minY >= Int.MIN_VALUE && maxX <= Int.MAX_VALUE && maxY <= Int.MAX_VALUE) {
        "Paint path bounds exceed the DesignBounds integer domain."
    }
    return DesignBounds(
        minX = kotlin.math.floor(minX).toInt(),
        minY = kotlin.math.floor(minY).toInt(),
        maxX = kotlin.math.ceil(maxX).toInt(),
        maxY = kotlin.math.ceil(maxY).toInt(),
    )
}

private fun GlyphPaintPathCommand.points(): List<Pair<Double, Double>> = when (this) {
    is GlyphPaintPathCommand.MoveTo -> listOf(x to y)
    is GlyphPaintPathCommand.LineTo -> listOf(x to y)
    is GlyphPaintPathCommand.CubicTo -> listOf(control1X to control1Y, control2X to control2Y, endX to endY)
    GlyphPaintPathCommand.Close -> emptyList()
}

private fun List<GlyphPaintPathCommand>.estimatedByteSize(): Int {
    var total = 32L
    for (command in this) {
        total += when (command) {
            is GlyphPaintPathCommand.MoveTo,
            is GlyphPaintPathCommand.LineTo,
            -> 16L
            is GlyphPaintPathCommand.CubicTo -> 48L
            GlyphPaintPathCommand.Close -> 1L
        }
        if (total > Int.MAX_VALUE) return Int.MAX_VALUE
    }
    return total.toInt()
}
