package com.titan.core.domain.gamification

import com.titan.core.domain.model.Pet
import com.titan.core.domain.model.PetStats
import com.titan.core.domain.model.PowerSnapshot
import com.titan.core.domain.model.SparBout
import com.titan.core.domain.model.SparRival
import com.titan.core.domain.model.SparRivalKind
import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.model.TitanVitals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SparRulesTest {

    // ─── Odds ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `odds are even at equal power and move 2_2 points a point`() {
        assertEquals(50, SparRules.winChance(power = 33, rivalPower = 33))
        assertEquals(83, SparRules.winChance(power = 48, rivalPower = 33))
        assertEquals(17, SparRules.winChance(power = 33, rivalPower = 48))
    }

    @Test
    fun `odds are clamped at both ends`() {
        assertEquals(95, SparRules.winChance(power = 1_000, rivalPower = 0))
        assertEquals(5, SparRules.winChance(power = 0, rivalPower = 1_000))
        assertEquals(95, SparRules.winChance(power = Int.MAX_VALUE, rivalPower = Int.MIN_VALUE))
    }

    // ─── Weeks ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a week starts on its Monday at midnight UTC`() {
        val monday = Instant.parse("2026-11-09T00:00:00Z").toEpochMilli()

        assertEquals(monday, SparRules.weekStart(monday))
        assertEquals(monday, SparRules.weekStart(Instant.parse("2026-11-15T23:59:59Z").toEpochMilli()))
        assertEquals(monday + WEEK, SparRules.weekStart(Instant.parse("2026-11-16T00:00:00Z").toEpochMilli()))
    }

    // ─── Rivals ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a titan two weeks old is offered last week and its first week only`() {
        val now = MONDAY + 3 * DAY
        val pet = pet(hatchedAt = MONDAY - 2 * WEEK + DAY)

        val rivals = SparRules.rivals(pet, now, snapshots = emptyList(), bouts = emptyList())

        assertEquals(listOf(SparRivalKind.LAST_WEEK, SparRivalKind.FIRST_WEEK), rivals.map { it.kind })
    }

    @Test
    fun `a titan hatched this week has no rival yet`() {
        assertTrue(
            SparRules.rivals(pet(hatchedAt = MONDAY + DAY), MONDAY + 2 * DAY, emptyList(), emptyList()).isEmpty(),
        )
    }

    @Test
    fun `a titan a week old is offered last week once, not twice`() {
        val rivals = SparRules.rivals(pet(hatchedAt = MONDAY - WEEK + DAY), MONDAY + DAY, emptyList(), emptyList())

        assertEquals(listOf(SparRivalKind.LAST_WEEK), rivals.map { it.kind })
    }

    @Test
    fun `an old titan is offered all four, each rebuilt from the latest snapshot at or before its week`() {
        val pet = pet(hatchedAt = MONDAY - 20 * WEEK)
        val snapshots = listOf(
            PowerSnapshot(MONDAY - 20 * WEEK, level = 2, power = 4),
            PowerSnapshot(MONDAY - 13 * WEEK, level = 6, power = 14),
            PowerSnapshot(MONDAY - 5 * WEEK, level = 11, power = 33),
            PowerSnapshot(MONDAY - WEEK, level = 14, power = 46),
        )

        val rivals = SparRules.rivals(pet, MONDAY + DAY, snapshots, emptyList())

        assertEquals(SparRivalKind.entries, rivals.map { it.kind })
        assertEquals(listOf(46, 33, 14, 4), rivals.map { it.power })
        assertEquals(listOf(14, 11, 6, 2), rivals.map { it.level })
    }

    @Test
    fun `a week before any snapshot is the newly hatched titan`() {
        val rivals = SparRules.rivals(pet(hatchedAt = MONDAY - 3 * WEEK), MONDAY + DAY, emptyList(), emptyList())

        assertTrue(rivals.all { it.level == 1 && it.power == TitanRules.power(1, PetStats.ZERO) })
    }

    @Test
    fun `a rival is beaten once a bout against that week was won`() {
        val pet = pet(hatchedAt = MONDAY - 3 * WEEK)
        val lastWeek = MONDAY - WEEK
        val bouts = listOf(
            SparBout(SparRivalKind.LAST_WEEK.key, rivalWeek = lastWeek, won = true),
            SparBout(SparRivalKind.FIRST_WEEK.key, rivalWeek = MONDAY - 3 * WEEK, won = false),
        )

        val rivals = SparRules.rivals(pet, MONDAY + DAY, emptyList(), bouts)

        assertEquals(listOf(true, false), rivals.map { it.beaten })
        // A new week is a new "last week", not yet beaten.
        assertTrue(SparRules.rivals(pet, MONDAY + WEEK + DAY, emptyList(), bouts).none { it.beaten })
    }

    // ─── The bout ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a fixed sequence gives an exact result and round score`() {
        val rival = rival(winChance = 83)

        assertEquals(Triple(true, 3, 1), SparRules.fight(rival, numbers(82, 1)))
        assertEquals(Triple(false, 2, 3), SparRules.fight(rival, numbers(83, 2)))
    }

    @Test
    fun `a bout spends energy, pays spirit by result, and sets the titan sparring`() {
        val state = TitanRules.starting(0).copy(vitals = TitanVitals(60, 80, 50))

        val won = SparRules.afterBout(state, won = true, now = 1_000)
        val lost = SparRules.afterBout(state, won = false, now = 1_000)

        assertEquals(TitanVitals(60, 60, 58), won.vitals)
        assertEquals(TitanVitals(60, 60, 53), lost.vitals)
        assertEquals(TitanActivity.SPAR, won.activity)
    }

    @Test
    fun `the rival keys are the ones a stored bout names`() {
        assertEquals(
            listOf("last_week", "four_weeks_ago", "twelve_weeks_ago", "first_week"),
            SparRivalKind.entries.map { it.key },
        )
        assertEquals(SparRivalKind.FIRST_WEEK, SparRivalKind.byKey("first_week"))
        assertEquals(null, SparRivalKind.byKey("yesterday"))
    }

    private fun numbers(vararg values: Int): com.titan.core.domain.random.RandomSource {
        val queue = ArrayDeque(values.toList())
        return com.titan.core.domain.random.RandomSource { queue.removeFirst() }
    }

    private fun rival(winChance: Int) =
        SparRival(SparRivalKind.LAST_WEEK, MONDAY, level = 11, power = 33, winChance, beaten = false)

    private fun pet(hatchedAt: Long) =
        Pet(
            1,
            "iron_cub",
            Instant.ofEpochMilli(hatchedAt),
            xp = 0,
            stats = PetStats.ZERO,
            nickname = null,
            isActive = true,
        )

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
        const val WEEK = 7 * DAY
        val MONDAY: Long = Instant.parse("2026-11-09T00:00:00Z").toEpochMilli()
    }
}
