package com.opengym.core.domain.pr

import com.opengym.core.domain.model.OneRepMaxFormula
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Weights are milligrams throughout, per the schema's convention — see [PersonalRecord]. */
class OneRepMaxCalculatorTest {

    // ─── Epley: weight × (1 + reps / 30) ────────────────────────────────────────────────

    @Test
    fun `epley at 100 kg for 5 reps`() {
        assertEstimate(116_666_667.0, weight = 100_000_000, reps = 5, formula = OneRepMaxFormula.EPLEY)
    }

    @Test
    fun `epley at 60 kg for 10 reps`() {
        // 60 × (1 + 10/30) = 80 exactly.
        assertEstimate(80_000_000.0, weight = 60_000_000, reps = 10, formula = OneRepMaxFormula.EPLEY)
    }

    @Test
    fun `epley at 80 kg for 1 rep is close to the weight itself`() {
        assertEstimate(82_666_667.0, weight = 80_000_000, reps = 1, formula = OneRepMaxFormula.EPLEY)
    }

    // ─── Brzycki: weight × 36 / (37 − reps) ─────────────────────────────────────────────

    @Test
    fun `brzycki at 100 kg for 5 reps`() {
        // 100 × 36/32 = 112.5 exactly.
        assertEstimate(112_500_000.0, weight = 100_000_000, reps = 5, formula = OneRepMaxFormula.BRZYCKI)
    }

    @Test
    fun `brzycki at 60 kg for 10 reps`() {
        // 60 × 36/27 = 80 exactly.
        assertEstimate(80_000_000.0, weight = 60_000_000, reps = 10, formula = OneRepMaxFormula.BRZYCKI)
    }

    @Test
    fun `brzycki at a single rep returns the weight itself`() {
        // 80 × 36/36 = 80 exactly — the denominator's other well-known fixed point.
        assertEstimate(80_000_000.0, weight = 80_000_000, reps = 1, formula = OneRepMaxFormula.BRZYCKI)
    }

    // ─── The clamp ───────────────────────────────────────────────────────────────────────

    @Test
    fun `brzycki falls back to epley at 37 reps, where its own denominator is zero`() {
        val epley = OneRepMaxCalculator.calculate(100_000_000, 37, OneRepMaxFormula.EPLEY)
        val brzycki = OneRepMaxCalculator.calculate(100_000_000, 37, OneRepMaxFormula.BRZYCKI)

        assertEquals(epley, brzycki, 0f)
    }

    @Test
    fun `brzycki falls back to epley well past 37 reps too, rather than going negative`() {
        val epley = OneRepMaxCalculator.calculate(100_000_000, 50, OneRepMaxFormula.EPLEY)
        val brzycki = OneRepMaxCalculator.calculate(100_000_000, 50, OneRepMaxFormula.BRZYCKI)

        assertEquals(epley, brzycki, 0f)
    }

    @Test
    fun `brzycki at 36 reps still uses its own formula, one short of the clamp`() {
        // 100 × 36/1 = 3600 — an absurd multiple, and exactly what the textbook formula
        // says at the last rep count before this class stops trusting it.
        assertEstimate(3_600_000_000.0, weight = 100_000_000, reps = 36, formula = OneRepMaxFormula.BRZYCKI)
    }

    // ─── Argument validation ─────────────────────────────────────────────────────────────

    @Test
    fun `a non-positive weight is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            OneRepMaxCalculator.calculate(0, 5, OneRepMaxFormula.EPLEY)
        }
    }

    @Test
    fun `fewer than one rep is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            OneRepMaxCalculator.calculate(100_000_000, 0, OneRepMaxFormula.EPLEY)
        }
    }

    /** Float precision over eight-figure milligram weights loses a handful of units; ±1 tolerates it. */
    private fun assertEstimate(
        expected: Double,
        weight: Long,
        reps: Int,
        formula: OneRepMaxFormula,
    ) {
        val actual = OneRepMaxCalculator.calculate(weight, reps, formula)
        assertEquals(expected, actual.toDouble(), EPSILON)
    }

    private companion object {
        const val EPSILON = 200.0
    }
}
