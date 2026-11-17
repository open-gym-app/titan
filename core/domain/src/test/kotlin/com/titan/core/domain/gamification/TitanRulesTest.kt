package com.titan.core.domain.gamification

import com.titan.core.domain.model.ChooseActivityResult
import com.titan.core.domain.model.PetStats
import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.model.TitanPlace
import com.titan.core.domain.model.TitanState
import com.titan.core.domain.model.TitanVital
import com.titan.core.domain.model.TitanVitals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TitanRulesTest {

    // ─── Vitals ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a vital outside its range is refused, and an unchecked one is clamped`() {
        assertThrows(IllegalArgumentException::class.java) { TitanVitals(-1, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { TitanVitals(0, 101, 0) }
        assertThrows(IllegalArgumentException::class.java) { TitanVitals(0, 0, 101) }
        assertEquals(TitanVitals(0, 100, 50), TitanVitals.clamped(-5, 1_000, 50))
    }

    @Test
    fun `every write is clamped into the range`() {
        val vitals = TitanVitals(fuel = 95, energy = 3, spirit = 50)

        assertEquals(100, vitals.plus(TitanVital.FUEL, 40).fuel)
        assertEquals(0, vitals.plus(TitanVital.ENERGY, -20).energy)
        assertEquals(100, vitals.plus(TitanVital.SPIRIT, Int.MAX_VALUE).spirit)
    }

    @Test
    fun `low is below 25`() {
        assertTrue(TitanVitals(24, 100, 100).isLow(TitanVital.FUEL))
        assertTrue(!TitanVitals(25, 100, 100).isLow(TitanVital.FUEL))
    }

    @Test
    fun `only the lowest vital under 25 raises an alert`() {
        assertEquals(TitanVital.FUEL, TitanRules.alertFor(TitanVitals(fuel = 22, energy = 24, spirit = 90)))
        assertEquals(TitanVital.ENERGY, TitanRules.alertFor(TitanVitals(fuel = 24, energy = 14, spirit = 90)))
        assertEquals(TitanVital.SPIRIT, TitanRules.alertFor(TitanVitals(fuel = 60, energy = 80, spirit = 18)))
        assertNull(TitanRules.alertFor(TitanVitals(fuel = 25, energy = 25, spirit = 25)))
    }

    @Test
    fun `a tie resolves fuel, then energy, then spirit`() {
        assertEquals(TitanVital.FUEL, TitanRules.alertFor(TitanVitals(10, 10, 10)))
        assertEquals(TitanVital.ENERGY, TitanRules.alertFor(TitanVitals(50, 10, 10)))
    }

    // ─── Time passing ───────────────────────────────────────────────────────────────────

    @Test
    fun `fuel decays four an hour, on the clock`() {
        val resting = rest(TitanVitals(fuel = 50, energy = 0, spirit = 50), at = 0)

        val later = TitanRules.advance(resting, to = HOUR, seed = 1)

        assertEquals(46, later.vitals.fuel)
        assertEquals(HOUR, later.updatedAt)
    }

    @Test
    fun `decay never takes fuel below the floor, at 11 or at 10`() {
        val at11 = TitanRules.advance(rest(TitanVitals(11, 0, 50), at = 0), to = 3 * HOUR, seed = 1)
        val at10 = TitanRules.advance(rest(TitanVitals(10, 0, 50), at = 0), to = 3 * HOUR, seed = 1)
        val belowFloor = TitanRules.advance(rest(TitanVitals(4, 0, 50), at = 0), to = 3 * HOUR, seed = 1)

        assertEquals(TitanRules.FUEL_FLOOR, at11.vitals.fuel)
        assertEquals(TitanRules.FUEL_FLOOR, at10.vitals.fuel)
        assertEquals("a restored value below the floor is not raised by decay", 4, belowFloor.vitals.fuel)
    }

    @Test
    fun `resting refills energy, and the titan gets up when it is full`() {
        val resting = rest(TitanVitals(fuel = 80, energy = 90, spirit = 50), at = 0)

        val after = TitanRules.advance(resting, to = 2 * TitanRules.TICK_MILLIS, seed = 1)

        assertTrue("it rested to full and moved on, was ${after.activity}", after.activity != TitanActivity.REST)
    }

    @Test
    fun `an activity ends at its own time and the next one is paid for`() {
        val walking = TitanRules.choose(idle(TitanVitals(80, 80, 50)), TitanActivity.WALK, now = 0)
            .let { (it as ChooseActivityResult.Chosen).state }

        val after = TitanRules.advance(walking, to = walking.endsAt!!, seed = 3)

        assertEquals(walking.endsAt, after.startedAt)
        assertEquals(walking.vitals.spirit + TitanActivity.WALK.spiritOnFinish, after.vitals.spirit)
        assertEquals(walking.vitals.energy - after.activity.energyCost, after.vitals.energy)
    }

    @Test
    fun `a tired titan rests rather than choosing something to do`() {
        val walking = TitanRules.choose(idle(TitanVitals(80, 20, 50)), TitanActivity.WALK, now = 0)
            .let { (it as ChooseActivityResult.Chosen).state }

        val after = TitanRules.advance(walking, to = walking.endsAt!!, seed = 3)

        assertEquals(TitanActivity.REST, after.activity)
    }

    @Test
    fun `the titan never chooses a place it does not have, or a spar, or a meal`() {
        val places = setOf(TitanPlace.TRACK)
        repeat(200) { seed ->
            val state = TitanRules.advance(TitanRules.starting(0), to = 6 * HOUR, seed = seed.toLong(), places)
            val chosen = state.activity
            assertTrue("$chosen at seed $seed", chosen.place == null || chosen.place in places)
            assertTrue(chosen != TitanActivity.SPAR && chosen != TitanActivity.EAT)
        }
    }

    @Test
    fun `a meal returns to what the titan was doing, without paying for it again`() {
        val eating = TitanState(
            vitals = TitanVitals(60, 50, 50),
            activity = TitanActivity.EAT,
            startedAt = 0,
            endsAt = TitanActivity.EAT.duration!!.inWholeMilliseconds,
            resumeActivity = TitanActivity.TRAIN,
            updatedAt = 0,
        )

        val after = TitanRules.advance(eating, to = eating.endsAt!!, seed = 1)

        assertEquals(TitanActivity.TRAIN, after.activity)
        assertEquals(50, after.vitals.energy)
        assertNull(after.resumeActivity)
    }

    // ─── Determinism ────────────────────────────────────────────────────────────────────

    @Test
    fun `the same state, interval and seed give the same answer`() {
        val start = TitanRules.starting(START)

        assertEquals(
            TitanRules.advance(start, to = START + DAY, seed = 42),
            TitanRules.advance(start, to = START + DAY, seed = 42),
        )
    }

    @Test
    fun `advancing in two steps is advancing in one, wherever the split falls`() {
        val random = Random(7)
        repeat(300) {
            val seed = random.nextLong()
            val start = TitanRules.starting(START + random.nextLong(TitanRules.TICK_MILLIS))
            val end = start.updatedAt + random.nextLong(3 * DAY)
            val split = start.updatedAt + random.nextLong(end - start.updatedAt + 1)

            val whole = TitanRules.advance(start, to = end, seed = seed)
            val halves = TitanRules.advance(TitanRules.advance(start, to = split, seed = seed), to = end, seed = seed)

            assertEquals("split at $split of ${start.updatedAt}..$end, seed $seed", whole, halves)
        }
    }

    @Test
    fun `a split on a tick that is also an activity's end loses neither`() {
        val endsOnTick = TitanState(
            vitals = TitanVitals(50, 50, 50),
            activity = TitanActivity.WALK,
            startedAt = 0,
            endsAt = TitanRules.TICK_MILLIS,
            resumeActivity = null,
            updatedAt = 0,
        )

        val after = TitanRules.advance(endsOnTick, to = TitanRules.TICK_MILLIS, seed = 1)

        assertEquals("the tick on the end's moment still decays fuel", 49, after.vitals.fuel)
        assertEquals(TitanRules.TICK_MILLIS, after.startedAt)
    }

    @Test
    fun `a backwards clock changes nothing`() {
        val state = TitanRules.advance(TitanRules.starting(START), to = START + HOUR, seed = 1)

        assertSame(state, TitanRules.advance(state, to = START, seed = 1))
        assertSame(state, TitanRules.advance(state, to = state.updatedAt, seed = 1))
    }

    @Test
    fun `a ten-year gap returns a valid state without a throw`() {
        val after = TitanRules.advance(TitanRules.starting(START), to = START + 10 * 365 * DAY, seed = 9)

        assertEquals(START + 10 * 365 * DAY, after.updatedAt)
        assertEquals(TitanRules.FUEL_FLOOR, after.vitals.fuel)
    }

    // ─── The user's override ────────────────────────────────────────────────────────────

    @Test
    fun `choosing an affordable activity starts it and charges for it`() {
        val result = TitanRules.choose(idle(TitanVitals(80, 80, 50)), TitanActivity.RUN, now = 5_000)

        val state = (result as ChooseActivityResult.Chosen).state
        assertEquals(TitanActivity.RUN, state.activity)
        assertEquals(65, state.vitals.energy)
        assertEquals(5_000L, state.startedAt)
        assertEquals(5_000L + TitanActivity.RUN.duration!!.inWholeMilliseconds, state.endsAt)
    }

    @Test
    fun `an unaffordable or place-locked activity is refused with its reason`() {
        val tired = idle(TitanVitals(80, 10, 50))

        assertEquals(ChooseActivityResult.NotAffordable(15), TitanRules.choose(tired, TitanActivity.RUN, now = 0))
        assertEquals(
            ChooseActivityResult.PlaceLocked(TitanPlace.POOL),
            TitanRules.choose(
                idle(TitanVitals(80, 80, 50)),
                TitanActivity.SWIM,
                now = 0,
                places = setOf(TitanPlace.TRACK),
            ),
        )
    }

    @Test
    fun `a spar and a meal are not started from what next, and the current activity is a no-op`() {
        val state = idle(TitanVitals(80, 80, 50))

        assertEquals(ChooseActivityResult.NotChoosable, TitanRules.choose(state, TitanActivity.SPAR, now = 0))
        assertEquals(ChooseActivityResult.NotChoosable, TitanRules.choose(state, TitanActivity.EAT, now = 0))
        assertEquals(ChooseActivityResult.Chosen(state), TitanRules.choose(state, TitanActivity.IDLE, now = 0))
    }

    @Test
    fun `resting can always be chosen, even with no energy`() {
        val result = TitanRules.choose(idle(TitanVitals(80, 0, 50)), TitanActivity.REST, now = 0)

        assertEquals(TitanActivity.REST, (result as ChooseActivityResult.Chosen).state.activity)
        assertNull(result.state.endsAt)
    }

    // ─── Power and storage keys ─────────────────────────────────────────────────────────

    @Test
    fun `power is twice the level plus a tenth of the stats`() {
        assertEquals(48, TitanRules.power(level = 14, stats = PetStats(strength = 120, endurance = 50, mobility = 30)))
        assertEquals(2, TitanRules.power(level = 1, stats = PetStats.ZERO))
        assertEquals(
            2 * 100 + 3 * PetStats.MAX / 10,
            TitanRules.power(level = 100, stats = PetStats(PetStats.MAX, PetStats.MAX, PetStats.MAX)),
        )
    }

    @Test
    fun `the activity and place keys are the ones a stored state names`() {
        assertEquals(
            listOf("idle", "walk", "run", "swim", "train", "spar", "rest", "eat"),
            TitanActivity.entries.map { it.key },
        )
        assertEquals(listOf("track", "rack", "pool", "ring"), TitanPlace.entries.map { it.key })
    }

    @Test
    fun `an activity key this build does not know reads as idle`() {
        assertEquals(TitanActivity.SWIM, TitanActivity.fromKey("swim"))
        assertEquals(TitanActivity.IDLE, TitanActivity.fromKey("fly"))
    }

    @Test
    fun `a newly hatched titan starts idle on the documented vitals`() {
        val start = TitanRules.starting(START)

        assertEquals(TitanVitals.START, start.vitals)
        assertEquals(TitanActivity.IDLE, start.activity)
        assertEquals(START, start.updatedAt)
    }

    private fun idle(vitals: TitanVitals) =
        TitanState(vitals, TitanActivity.IDLE, startedAt = 0, endsAt = DAY, resumeActivity = null, updatedAt = 0)

    private fun rest(
        vitals: TitanVitals,
        at: Long,
    ) = TitanState(vitals, TitanActivity.REST, startedAt = at, endsAt = null, resumeActivity = null, updatedAt = at)

    private companion object {
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
        const val START = 1_788_603_600_000L
    }
}
