package com.opengym.core.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which levels are worth extra noise.
 *
 * The rule is one modulo, so what is worth pinning is not the arithmetic but the *shape*: every
 * crossing is celebrated, the round ones are louder, and a level that is neither is still
 * [LevelCeremony.ORDINARY] rather than nothing. A future tier adds a case and a row here.
 */
class LevelCeremonyTest {

    @Test
    fun `a level ending in zero or five is round`() {
        listOf(5, 10, 15, 20, 50, 100).forEach { level ->
            assertEquals("level $level should be round", LevelCeremony.ROUND, LevelCeremony.of(level))
        }
    }

    @Test
    fun `every other level is ordinary, not unmarked`() {
        listOf(1, 2, 3, 4, 6, 9, 11, 99).forEach { level ->
            assertEquals(
                "level $level should be ordinary",
                LevelCeremony.ORDINARY,
                LevelCeremony.of(level),
            )
        }
    }

    @Test
    fun `level zero is not a milestone`() {
        // Nobody is level zero — the curve starts at one — but the modulo would call it round,
        // and a guard that only holds for values the caller happens to pass is not a guard.
        assertEquals(LevelCeremony.ORDINARY, LevelCeremony.of(0))
    }

    @Test
    fun `a round crossing is louder than an ordinary one on every axis`() {
        // Asserted as a relation rather than against the numbers themselves: the values are
        // tuned by eye and will move, but a round level that threw less confetti than an
        // ordinary one would be a bug in every future tuning too.
        assertEquals(
            true,
            LevelCeremony.ROUND.kick > LevelCeremony.ORDINARY.kick &&
                LevelCeremony.ROUND.confettiPieces > LevelCeremony.ORDINARY.confettiPieces,
        )
    }
}
