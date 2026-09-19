package org.graphiks.kalligraphie.conformance.corpus

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.TextDecodingLimit
import org.graphiks.kalligraphie.api.TextDecodingOutcome
import org.graphiks.kalligraphie.api.TextDecodingProfile
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class CancellationConformanceTest {
    private val version = TextVersion.create()

    private val utf8 = listOf(TextSlice.Utf8("abc".encodeToByteArray()))
    private val utf16 = listOf(TextSlice.Utf16("abc".toCharArray()))

    @Test
    fun cancelsUtf8BeforePublishingASnapshot() {
        val outcome = Kalligraphie.decodeUtf8(
            version,
            utf8,
            TextDecodingProfile.unbounded,
            CancellationToken.cancelled,
        )
        assertIs<TextDecodingOutcome.Cancelled>(outcome)
    }

    @Test
    fun cancelsUtf16BeforePublishingASnapshot() {
        val outcome = Kalligraphie.decodeUtf16(
            version,
            utf16,
            TextDecodingProfile.unbounded,
            CancellationToken.cancelled,
        )
        assertIs<TextDecodingOutcome.Cancelled>(outcome)
    }

    @Test
    fun observesCancellationDuringTraversal() {
        var calls = 0
        val token = CancellationToken { ++calls > 1 }
        val outcome = Kalligraphie.decodeUtf8(
            version,
            listOf(TextSlice.Utf8("abcdef".encodeToByteArray())),
            TextDecodingProfile(cancellationCheckInterval = 1),
            token,
        )
        assertIs<TextDecodingOutcome.Cancelled>(outcome)
    }

    @Test
    fun rejectsAnExceededScalarBudgetAtomically() {
        val outcome = Kalligraphie.decodeUtf8(
            version,
            utf8,
            TextDecodingProfile(maxScalars = 1),
        )
        val exceeded = assertIs<TextDecodingOutcome.LimitExceeded>(outcome)
        assertEquals(TextDecodingLimit.SCALARS, exceeded.limit)
        assertEquals(2L, exceeded.observed)
    }

    @Test
    fun rejectsAnExceededSourceUnitBudgetAtomically() {
        val outcome = Kalligraphie.decodeUtf8(
            version,
            utf8,
            TextDecodingProfile(maxSourceUnits = 2),
        )
        val exceeded = assertIs<TextDecodingOutcome.LimitExceeded>(outcome)
        assertEquals(TextDecodingLimit.SOURCE_UNITS, exceeded.limit)
        assertEquals(3L, exceeded.observed)
    }

    @Test
    fun nonSuccessOutcomesCarryNoPartialSnapshot() {
        val cancelled = Kalligraphie.decodeUtf8(
            version,
            utf8,
            TextDecodingProfile.unbounded,
            CancellationToken.cancelled,
        )
        assertIs<TextDecodingOutcome.Cancelled>(cancelled)

        val scalarLimited = Kalligraphie.decodeUtf8(
            version,
            utf8,
            TextDecodingProfile(maxScalars = 1),
        )
        assertIs<TextDecodingOutcome.LimitExceeded>(scalarLimited)

        val sourceLimited = Kalligraphie.decodeUtf8(
            version,
            utf8,
            TextDecodingProfile(maxSourceUnits = 2),
        )
        assertIs<TextDecodingOutcome.LimitExceeded>(sourceLimited)

        val outcomes: List<TextDecodingOutcome> = listOf(cancelled, scalarLimited, sourceLimited)
        outcomes.forEach { outcome ->
            assertFalse(outcome is TextDecodingOutcome.Success)
        }
    }
}
