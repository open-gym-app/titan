package com.titan.core.domain.gamification

import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.random.RandomSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodDropTest {

    @Test
    fun `the drop grows with effort, from one item to three`() {
        val food = FoodDrop()

        assertEquals(0, food.sizeFor(0))
        assertEquals(1, food.sizeFor(1))
        assertEquals(1, food.sizeFor(FoodDrop.SECOND_ITEM_AT - 1))
        assertEquals(2, food.sizeFor(FoodDrop.SECOND_ITEM_AT))
        assertEquals(2, food.sizeFor(FoodDrop.THIRD_ITEM_AT - 1))
        assertEquals(3, food.sizeFor(FoodDrop.THIRD_ITEM_AT))
        assertEquals(FoodDrop.MAX_ITEMS, food.sizeFor(1_000))
    }

    @Test
    fun `a fixed sequence draws exact food from the pantry`() {
        val numbers = ArrayDeque(listOf(0, 3))
        val food = FoodDrop(random = { numbers.removeFirst() })

        assertEquals(
            listOf(Collectibles.STEAK, Collectibles.EGGS),
            food.roll(List(FoodDrop.SECOND_ITEM_AT) { set() }),
        )
    }

    @Test
    fun `warm-ups and sets beyond the cap do not raise the drop`() {
        val food = FoodDrop(random = { 0 })

        val warmUps = food.roll(List(30) { set(type = SetType.WARMUP) } + set())
        val pastTheCap = food.roll(List(SessionXp.MAX_PAID_SETS + 30) { set() })

        assertEquals(1, warmUps.size)
        assertEquals(food.sizeFor(SessionXp.MAX_PAID_SETS), pastTheCap.size)
    }

    @Test
    fun `a session of no paid set draws nothing and spends no number`() {
        val food = FoodDrop(random = RandomSource { error("nothing should be drawn") })

        assertTrue(food.roll(listOf(set(completed = false), set(type = SetType.WARMUP))).isEmpty())
    }

    @Test
    fun `an empty pantry pays nothing`() {
        assertTrue(FoodDrop(random = { 0 }, pantry = emptyList()).roll(listOf(set())).isEmpty())
    }

    private fun set(
        type: SetType = SetType.NORMAL,
        completed: Boolean = true,
    ) = WorkoutSet(
        sessionExerciseId = 1,
        setNumber = 1,
        reps = 5,
        setType = type,
        isCompleted = completed,
        completedAt = 0,
        createdAt = 0,
        updatedAt = 0,
    )
}
