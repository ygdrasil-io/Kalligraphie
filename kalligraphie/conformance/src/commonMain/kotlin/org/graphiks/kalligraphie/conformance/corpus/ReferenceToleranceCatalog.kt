package org.graphiks.kalligraphie.conformance.corpus

import org.graphiks.kalligraphie.conformance.Tolerance

/**
 * Tolerances calibrated by the reference oracle.
 *
 * Empty while the portable corpus produces only bit-identical integer results (decoding).
 * Numeric tolerances are added here, with a canonical-unit justification, when portable
 * geometry becomes observable; the reference oracle calibrates the values then.
 */
public object ReferenceToleranceCatalog {
    /** Calibrated tolerances, keyed by observable quantity. */
    public val tolerances: List<Tolerance> = emptyList()
}
