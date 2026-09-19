package org.graphiks.kalligraphie.conformance

/** Comparison scope in which a numeric tolerance is admissible. */
public enum class ToleranceScope {
    /** Both operands come from the same route; a native route unit is admissible. */
    INTRA_PLATFORM,

    /** Operands come from different routes; the canonical unit is mandatory. */
    CROSS_PLATFORM,
}

/** Unit in which a tolerance is expressed. */
public sealed interface ToleranceUnit {
    /** Canonical design unit, valid in every scope. */
    public data class Canonical(public val unit: CanonicalUnit) : ToleranceUnit

    /** Route-native unit, valid only in [ToleranceScope.INTRA_PLATFORM]. */
    public data class RouteNative(public val routeUnitId: String) : ToleranceUnit {
        init {
            require(routeUnitId.isNotBlank()) { "A route-native unit requires a non-blank identifier." }
        }
    }
}

/**
 * Declared numeric tolerance for one floating observable quantity.
 *
 * A tolerance always carries a human justification tied to its unit; it is never a global
 * epsilon and is never widened to hide a regression.
 */
public class Tolerance private constructor(
    /** Quantity this tolerance applies to; its declared class is [ComparisonClass.NUMERIC_TOLERANCE]. */
    public val quantity: ComparisonQuantity,
    /** Scope in which the tolerance may be applied. */
    public val scope: ToleranceScope,
    /** Unit in which [maxDeviation] is expressed. */
    public val unit: ToleranceUnit,
    /** Strictly positive maximum absolute deviation, in [unit]. */
    public val maxDeviation: Double,
    /** Non-blank justification tied to [unit]. */
    public val justification: String,
) {
    init {
        require(quantity.declaredClass() == ComparisonClass.NUMERIC_TOLERANCE) {
            "$quantity is not compared under a numeric tolerance."
        }
        require(maxDeviation > 0.0 && maxDeviation.isFinite()) {
            "maxDeviation must be finite and strictly positive."
        }
        require(justification.isNotBlank()) {
            "A tolerance requires a justification tied to its unit."
        }
        require(scope == ToleranceScope.INTRA_PLATFORM || unit is ToleranceUnit.Canonical) {
            "A cross-platform tolerance must be expressed in the canonical unit."
        }
    }

    /** Compares every declared tolerance field, giving tolerances value semantics. */
    override fun equals(other: Any?): Boolean =
        this === other || (other is Tolerance &&
            quantity == other.quantity &&
            scope == other.scope &&
            unit == other.unit &&
            maxDeviation == other.maxDeviation &&
            justification == other.justification)

    /** Returns a stable hash of every declared tolerance field. */
    override fun hashCode(): Int {
        var result = quantity.hashCode()
        result = 31 * result + scope.hashCode()
        result = 31 * result + unit.hashCode()
        result = 31 * result + maxDeviation.hashCode()
        result = 31 * result + justification.hashCode()
        return result
    }

    /** Returns a diagnostic form containing every declared tolerance field. */
    override fun toString(): String =
        "Tolerance(quantity=$quantity, scope=$scope, unit=$unit, " +
            "maxDeviation=$maxDeviation, justification=$justification)"

    /** Factories for scope-valid, justified tolerances. */
    public companion object {
        /** Declares a tolerance, enforcing every conformance invariant. */
        public fun declare(
            quantity: ComparisonQuantity,
            scope: ToleranceScope,
            unit: ToleranceUnit,
            maxDeviation: Double,
            justification: String,
        ): Tolerance = Tolerance(quantity, scope, unit, maxDeviation, justification)
    }
}
