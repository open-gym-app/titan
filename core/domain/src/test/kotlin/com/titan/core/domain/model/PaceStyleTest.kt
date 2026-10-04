package com.titan.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PaceStyleTest {

    @Test
    fun `an exercise with neither tag reads a pace`() {
        assertEquals(PaceStyle.PACE, PaceStyle.of(listOf("PUSH", "COMPOUND")))
    }

    @Test
    fun `the speed tag reads a speed and the swim tag a pace per hundred`() {
        assertEquals(PaceStyle.SPEED, PaceStyle.of(listOf(PaceStyle.SPEED_TAG)))
        assertEquals(PaceStyle.SWIM_PACE, PaceStyle.of(listOf(PaceStyle.SWIM_TAG)))
    }

    @Test
    fun `speed wins when an exercise carries both`() {
        assertEquals(PaceStyle.SPEED, PaceStyle.of(listOf(PaceStyle.SWIM_TAG, PaceStyle.SPEED_TAG)))
    }

    @Test
    fun `the tag names are the ones the seed generator writes`() {
        // tools/seed/generate_seed.py's SYSTEM_TAGS: a rename on either side has to be both.
        assertEquals(listOf("SPEED", "SWIM"), PaceStyle.TAG_NAMES)
    }
}
