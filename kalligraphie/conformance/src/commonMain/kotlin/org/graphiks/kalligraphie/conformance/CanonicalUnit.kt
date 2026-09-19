package org.graphiks.kalligraphie.conformance

import kotlin.jvm.JvmInline

/** Unit in which cross-platform numeric comparison is performed. */
public enum class CanonicalUnit {
    /**
     * Font design units of the declared reference face. Every route converts its native
     * unit into design units before any cross-platform comparison.
     */
    FONT_DESIGN_UNIT,
}

/**
 * Conversion from one route's native layout unit into [CanonicalUnit.FONT_DESIGN_UNIT].
 *
 * The reference face is declared by the profile owning the scale; this type deliberately
 * carries only the conversion factor so it stays independent of a particular font.
 *
 * @property routeUnitsPerCanonicalUnit strictly positive number of native route units
 *   that equal one canonical design unit.
 */
@JvmInline
public value class CanonicalScale(public val routeUnitsPerCanonicalUnit: Double) {
    init {
        require(routeUnitsPerCanonicalUnit > 0.0 && routeUnitsPerCanonicalUnit.isFinite()) {
            "routeUnitsPerCanonicalUnit must be finite and strictly positive."
        }
    }

    /** Converts [routeValue] expressed in the route's native unit into canonical design units. */
    public fun toCanonical(routeValue: Double): Double = routeValue / routeUnitsPerCanonicalUnit
}
