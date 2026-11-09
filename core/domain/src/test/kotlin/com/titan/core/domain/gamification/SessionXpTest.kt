package com.titan.core.domain.gamification

import com.titan.core.domain.model.PersonalRecord
import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.model.XpEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionXpTest {

    // ─── Which sets pay ─────────────────────────────────────────────────────────────────

    @Test
    fun `a completed working set with reps pays`() {
        assertTrue(SessionXp.isPaidSet(set(reps = 5)))
    }

    @Test
    fun `a set that was never completed does not pay`() {
        assertFalse(SessionXp.isPaidSet(set(reps = 5, isCompleted = false)))
    }

    @Test
    fun `a warm-up does not pay`() {
        assertFalse(SessionXp.isPaidSet(set(reps = 5, setType = SetType.WARMUP)))
    }

    @Test
    fun `a set ticked off with nothing logged on it does not pay`() {
        assertFalse(SessionXp.isPaidSet(set()))
        assertFalse(SessionXp.isPaidSet(set(reps = 0, duration = 0, distance = 0)))
    }

    @Test
    fun `a hold or a run pays on its duration or its distance`() {
        assertTrue(SessionXp.isPaidSet(set(duration = 45)))
        assertTrue(SessionXp.isPaidSet(set(distance = 500_000)))
    }

    @Test
    fun `every set type but the warm-up pays`() {
        val paying = SetType.entries.filter { it != SetType.WARMUP }

        assertEquals(paying, paying.filter { SessionXp.isPaidSet(set(reps = 8, setType = it)) })
    }

    // ─── What a session earns ───────────────────────────────────────────────────────────

    @Test
    fun `a session with the minimum of working sets pays the session and each set`() {
        val events = SessionXp.eventsFor(sets(SessionXp.MIN_SETS_FOR_SESSION_XP), emptyList())

        assertEquals(
            listOf(XpEvent.SessionFinished) + List(SessionXp.MIN_SETS_FOR_SESSION_XP) { XpEvent.SetLogged },
            events,
        )
    }

    @Test
    fun `a session short of the minimum pays for its sets and not for itself`() {
        val events = SessionXp.eventsFor(sets(SessionXp.MIN_SETS_FOR_SESSION_XP - 1), emptyList())

        assertFalse(XpEvent.SessionFinished in events)
        assertEquals(SessionXp.MIN_SETS_FOR_SESSION_XP - 1, events.count { it == XpEvent.SetLogged })
    }

    @Test
    fun `a session of warm-ups and empty ticks earns nothing`() {
        val sets = List(10) { set(reps = 5, setType = SetType.WARMUP) } + List(10) { set() }

        assertEquals(emptyList<XpEvent>(), SessionXp.eventsFor(sets, emptyList()))
    }

    @Test
    fun `a session pays for no more sets than the cap`() {
        val events = SessionXp.eventsFor(sets(SessionXp.MAX_PAID_SETS * 4), emptyList())

        assertEquals(SessionXp.MAX_PAID_SETS, events.count { it == XpEvent.SetLogged })
    }

    @Test
    fun `no session can pay more for showing up than the ceiling`() {
        val events = SessionXp.eventsFor(sets(1_000), emptyList())

        val earned = events.sumOf(XpCalculator::xpFor)

        assertEquals(XpCalculator.XP_PER_SESSION + SessionXp.MAX_PAID_SETS * XpCalculator.XP_PER_SET, earned)
    }

    // ─── Which records pay ──────────────────────────────────────────────────────────────

    @Test
    fun `each beaten record pays its own type`() {
        val events = SessionXp.eventsFor(emptyList(), listOf(record(PrType.MAX_WEIGHT), record(PrType.MAX_REPS)))

        assertEquals(listOf(XpEvent.PrBroken(PrType.MAX_WEIGHT), XpEvent.PrBroken(PrType.MAX_REPS)), events)
    }

    @Test
    fun `records pay without any working set`() {
        val events = SessionXp.eventsFor(emptyList(), listOf(record(PrType.MAX_WEIGHT)))

        assertEquals(listOf<XpEvent>(XpEvent.PrBroken(PrType.MAX_WEIGHT)), events)
    }

    @Test
    fun `a session pays for no more records than the cap`() {
        val records = List(SessionXp.MAX_PAID_RECORDS * 3) { record(PrType.MAX_REPS) }

        val events = SessionXp.eventsFor(emptyList(), records)

        assertEquals(SessionXp.MAX_PAID_RECORDS, events.size)
    }

    @Test
    fun `the order is the session, then the sets, then the records`() {
        val events = SessionXp.eventsFor(sets(3), listOf(record(PrType.MAX_WEIGHT)))

        assertEquals(
            listOf(
                XpEvent.SessionFinished,
                XpEvent.SetLogged,
                XpEvent.SetLogged,
                XpEvent.SetLogged,
                XpEvent.PrBroken(PrType.MAX_WEIGHT),
            ),
            events,
        )
    }

    private fun sets(count: Int): List<WorkoutSet> = List(count) { set(reps = 5) }

    private fun set(
        reps: Int? = null,
        duration: Long? = null,
        distance: Long? = null,
        setType: SetType = SetType.NORMAL,
        isCompleted: Boolean = true,
    ): WorkoutSet =
        WorkoutSet(
            sessionExerciseId = 1,
            setNumber = 1,
            reps = reps,
            duration = duration,
            distance = distance,
            setType = setType,
            completedAt = 1,
            isCompleted = isCompleted,
            createdAt = 1,
            updatedAt = 1,
        )

    private fun record(type: PrType): PersonalRecord =
        PersonalRecord(exerciseId = 1, workoutSetId = 1, type = type, achievedAt = 1)
}
