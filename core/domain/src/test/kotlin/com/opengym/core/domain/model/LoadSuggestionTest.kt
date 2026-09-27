package com.opengym.core.domain.model

import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The two things a suggestion may not say: lift nothing, or do it no times. */
class LoadSuggestionTest {

    @Test
    fun `a bodyweight suggestion carries no weight, not a weight of zero`() {
        assertNull(LoadSuggestion(weight = null, reps = 8, reason = SuggestionReason.ADD_REP).weight)
        assertThrows(IllegalArgumentException::class.java) {
            LoadSuggestion(weight = 0, reps = 8, reason = SuggestionReason.ADD_REP)
        }
    }

    @Test
    fun `a suggestion prescribes at least one rep`() {
        assertThrows(IllegalArgumentException::class.java) {
            LoadSuggestion(weight = 100_000_000, reps = 0, reason = SuggestionReason.DELOAD)
        }
    }

    @Test
    fun `a performance holds at least one set`() {
        assertThrows(IllegalArgumentException::class.java) { PastPerformance(emptyList()) }
    }
}
