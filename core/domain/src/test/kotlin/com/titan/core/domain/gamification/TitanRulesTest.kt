package com.titan.core.domain.gamification

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
        assertThrows(IllegalArgumentException::class.java) { TitanVitals(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { TitanVitals(0, 101) }
        assertEquals(TitanVitals(0, 100), TitanVitals.clamped(-5, 1_000))
    }

    @Test
    fun `every write is clamped into the range`() {
        val vitals = TitanVitals(fuel = 95, spirit = 3)

        assertEquals(100, vitals.plus(TitanVital.FUEL, 40).fuel)
        assertEquals(0, vitals.plus(TitanVital.SPIRIT, -20).spirit)
        assertEquals(100, vitals.plus(TitanVital.SPIRIT, Int.MAX_VALUE).spirit)
    }

    @Test
    fun `low is below 25`() {
        assertTrue(TitanVitals(24, 100).isLow(TitanVital.FUEL))
        assertTrue(!TitanVitals(25, 100).isLow(TitanVital.FUEL))
    }

    @Test
    fun `only the lowest vital under 25 raises an alert`() {
        assertEquals(TitanVital.FUEL, TitanRules.alertFor(TitanVitals(fuel = 22, spirit = 24)))
        assertEquals(TitanVital.SPIRIT, TitanRules.alertFor(TitanVitals(fuel = 60, spirit = 18)))
        assertNull(TitanRules.alertFor(TitanVitals(fuel = 25, spirit = 25)))
    }

    @Test
    fun `a tie resolves fuel, then spirit`() {
        assertEquals(TitanVital.FUEL, TitanRules.alertFor(TitanVitals(10, 10)))
    }

    // ─── Time passing ───────────────────────────────────────────────────────────────────

    @Test
    fun `fuel decays four an hour, on the clock`() {
        val resting = rest(TitanVitals(fuel = 50, spirit = 50), at = 0)

        val later = TitanRules.advance(resting, to = HOUR, seed = 1)

        assertEquals(46, later.vitals.fuel)
        assertEquals(HOUR, later.updatedAt)
    }

    @Test
    fun `decay never takes fuel below the floor, at 11 or at 10`() {
        val at11 = TitanRules.advance(rest(TitanVitals(11, 50), at = 0), to = 3 * HOUR, seed = 1)
        val at10 = TitanRules.advance(rest(TitanVitals(10, 50), at = 0), to = 3 * HOUR, seed = 1)
        val belowFloor = TitanRules.advance(rest(TitanVitals(4, 50), at = 0), to = 3 * HOUR, seed = 1)

        assertEquals(TitanRules.FUEL_FLOOR, at11.vitals.fuel)
        assertEquals(TitanRules.FUEL_FLOOR, at10.vitals.fuel)
        assertEquals("a restored value below the floor is not raised by decay", 4, belowFloor.vitals.fuel)
    }

    @Test
    fun `an activity ends at its own time and the titan picks the next itself`() {
        val walking = start(TitanActivity.WALK, TitanVitals(80, 50))

        val after = TitanRules.advance(walking, to = walking.endsAt!!, seed = 3)

        assertEquals(walking.endsAt, after.startedAt)
        assertEquals(walking.vitals.spirit + TitanActivity.WALK.spiritOnFinish, after.vitals.spirit)
        assertTrue(after.activity.selfChosen)
    }

    @Test
    fun `a hungry titan only rests or idles until it is fed`() {
        repeat(200) { seed ->
            val walking = start(TitanActivity.WALK, TitanVitals(fuel = 20, spirit = 50))

            val after = TitanRules.advance(walking, to = walking.endsAt!!, seed = seed.toLong())

            assertTrue(
                "${after.activity} at seed $seed",
                after.activity == TitanActivity.REST || after.activity == TitanActivity.IDLE,
            )
        }
    }

    @Test
    fun `a fed titan does everything over time, with nobody choosing for it`() {
        val done = mutableSetOf<TitanActivity>()
        var state = TitanRules.starting(0).copy(vitals = TitanVitals(100, 50))
        repeat(400) {
            state = TitanRules.advance(
                state.copy(vitals = TitanVitals(100, 50)),
                to = state.updatedAt + 10 * 60_000L,
                seed = 5,
            )
            done += state.activity
        }

        assertTrue(
            done.containsAll(
                listOf(
                    TitanActivity.WALK,
                    TitanActivity.RUN,
                    TitanActivity.SWIM,
                    TitanActivity.TRAIN,
                    TitanActivity.REST,
                ),
            ),
        )
    }

    @Test
    fun `the titan never chooses a place it does not have, or a meal`() {
        val places = setOf(TitanPlace.TRACK)
        repeat(200) { seed ->
            val state = TitanRules.advance(TitanRules.starting(0), to = 6 * HOUR, seed = seed.toLong(), places)
            val chosen = state.activity
            assertTrue("$chosen at seed $seed", chosen.place == null || chosen.place in places)
            assertTrue(chosen != TitanActivity.EAT)
        }
    }

    @Test
    fun `a meal returns to what the titan was doing`() {
        val eating = TitanState(
            vitals = TitanVitals(60, 50),
            activity = TitanActivity.EAT,
            startedAt = 0,
            endsAt = TitanActivity.EAT.duration!!.inWholeMilliseconds,
            resumeActivity = TitanActivity.TRAIN,
            updatedAt = 0,
        )

        val after = TitanRules.advance(eating, to = eating.endsAt!!, seed = 1)

        assertEquals(TitanActivity.TRAIN, after.activity)
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
            vitals = TitanVitals(50, 50),
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

    // ─── A stored state read back ───────────────────────────────────────────────────────

    @Test
    fun `a state from the future is pulled back to now`() {
        val future = TitanState(TitanVitals(50, 50), TitanActivity.WALK, 9_000, 9_500, null, updatedAt = 9_000)

        val repaired = TitanRules.repaired(future, now = 1_000)

        assertEquals(1_000L, repaired.startedAt)
        assertEquals(1_000L, repaired.updatedAt)
        assertEquals("an end within the activity's length of the new start is kept", 9_500L, repaired.endsAt)
    }

    @Test
    fun `an end far past the activity's own length is held to it`() {
        val frozen = TitanState(TitanVitals(50, 50), TitanActivity.RUN, 0, Long.MAX_VALUE / 2, null, 0)

        assertEquals(TitanActivity.RUN.duration!!.inWholeMilliseconds, TitanRules.repaired(frozen, now = 1_000).endsAt)
    }

    @Test
    fun `a timed activity with no end gets one`() {
        val noEnd = TitanState(TitanVitals(50, 50), TitanActivity.TRAIN, 0, null, null, 0)

        assertEquals(TitanActivity.TRAIN.duration!!.inWholeMilliseconds, TitanRules.repaired(noEnd, now = 1).endsAt)
    }

    @Test
    fun `a state that is already sound is left as it is`() {
        val sound = TitanRules.starting(START)

        assertEquals(sound, TitanRules.repaired(sound, now = START + HOUR))
    }

    // ─── Feeding ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a feed's preview is its fuel and the spirit every food adds, held at the top`() {
        val state = idle(TitanVitals(fuel = 22, spirit = 55))

        val effect = TitanRules.previewFeed(state, Collectibles.STEAK)

        assertEquals(TitanVitals(fuel = 62, spirit = 60), effect.after)
        assertEquals(40, effect.change(TitanVital.FUEL))
        assertEquals(listOf(TitanVital.FUEL, TitanVital.SPIRIT), effect.moved)
        assertEquals(100, TitanRules.previewFeed(idle(TitanVitals(90, 99)), Collectibles.STEAK).after.fuel)
    }

    @Test
    fun `feeding applies exactly the preview and stops the titan to eat`() {
        val training = start(TitanActivity.TRAIN, TitanVitals(22, 55))

        val (fed, effect) = TitanRules.feed(training, Collectibles.STEAK, now = 1_000)

        assertEquals(TitanRules.previewFeed(training, Collectibles.STEAK), effect)
        assertEquals(effect.after, fed.vitals)
        assertEquals(TitanActivity.EAT, fed.activity)
        assertEquals(TitanActivity.TRAIN, fed.resumeActivity)
        val back = TitanRules.advance(fed, to = fed.endsAt!!, seed = 1)
        assertEquals("after the meal it goes back to the rack", TitanActivity.TRAIN, back.activity)
    }

    @Test
    fun `a second feed mid-meal goes back to what came before the first`() {
        val training = start(TitanActivity.TRAIN, TitanVitals(22, 55))
        val (once, _) = TitanRules.feed(training, Collectibles.OATS, now = 1_000)

        val (twice, _) = TitanRules.feed(once, Collectibles.OATS, now = 2_000)

        assertEquals(TitanActivity.TRAIN, twice.resumeActivity)
    }

    @Test
    fun `a trophy cannot be fed`() {
        assertThrows(IllegalArgumentException::class.java) {
            TitanRules.previewFeed(idle(TitanVitals(22, 55)), Collectibles.IRON_CROWN)
        }
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
            listOf("idle", "walk", "run", "swim", "train", "rest", "eat"),
            TitanActivity.entries.map { it.key },
        )
        assertEquals(
            listOf("track", "rack", "pool", "ring", "sauna", "trail", "dojo", "summit"),
            TitanPlace.entries.map { it.key },
        )
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

    /** [activity] started at 0 on [vitals], as the titan's own choice would. */
    private fun start(
        activity: TitanActivity,
        vitals: TitanVitals,
    ) = TitanState(
        vitals,
        activity,
        startedAt = 0,
        endsAt = activity.duration!!.inWholeMilliseconds,
        resumeActivity = null,
        updatedAt = 0,
    )

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
