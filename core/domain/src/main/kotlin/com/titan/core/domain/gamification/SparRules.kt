package com.titan.core.domain.gamification

import com.titan.core.domain.model.Pet
import com.titan.core.domain.model.PetStats
import com.titan.core.domain.model.PowerSnapshot
import com.titan.core.domain.model.SparBout
import com.titan.core.domain.model.SparRival
import com.titan.core.domain.model.SparRivalKind
import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.model.TitanState
import com.titan.core.domain.model.TitanVital
import com.titan.core.domain.random.RandomSource
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/**
 * Sparring against past selves: who is on offer, the odds, and what a bout does.
 *
 * Sparring pays the titan only — energy out, spirit in. It never reaches XP, quests or drops.
 *
 * ### Weeks are UTC weeks
 *
 * A snapshot is filed under the Monday, 00:00 UTC, of the moment it was taken. A UTC week rather
 * than the device's keeps the filing the same on every device a backup is restored to; at worst
 * a session late on a Sunday files under the next week.
 */
object SparRules {

    /** What a bout costs in energy. */
    const val ENERGY_COST = 20

    /** Spirit for a win. */
    const val SPIRIT_ON_WIN = 8

    /** Spirit for a loss: a close one still counts for something. */
    const val SPIRIT_ON_LOSS = 3

    /** The winner of a bout takes this many rounds. */
    const val ROUNDS_TO_WIN = 3

    /** The lowest and highest odds a bout is ever offered at. */
    const val MIN_CHANCE = 5
    const val MAX_CHANCE = 95

    private const val WEEK_MILLIS = 7 * 24 * 60 * 60 * 1000L
    private const val EVEN_CHANCE = 50
    private const val CHANCE_PER_POWER = 2.2

    /** The Monday, 00:00 UTC, of the week [at] falls in. */
    fun weekStart(at: Long): Long =
        Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** `clamp(50 + (power − rivalPower) × 2.2, 5, 95)`, as a whole percentage. */
    fun winChance(
        power: Int,
        rivalPower: Int,
    ): Int {
        val raw = EVEN_CHANCE + (power.toLong() - rivalPower) * CHANCE_PER_POWER
        return raw.coerceIn(MIN_CHANCE.toDouble(), MAX_CHANCE.toDouble()).roundToInt()
    }

    /** The pet's power now. */
    fun powerOf(pet: Pet): Int = TitanRules.power(pet.level, pet.stats)

    /**
     * The past selves on offer to [pet] at [now], in the order the screen lists them.
     *
     * Each is the latest snapshot at or before its week; a week before any snapshot but after
     * the hatch is the newly hatched titan, which always starts at the first level with no
     * stats. A rival from before the titan existed is not offered, the first week is not
     * offered while it is still this week, and two rivals rebuilt from the same week are one.
     */
    fun rivals(
        pet: Pet,
        now: Long,
        snapshots: List<PowerSnapshot>,
        bouts: List<SparBout>,
    ): List<SparRival> {
        val thisWeek = weekStart(now)
        val hatchWeek = weekStart(pet.hatchedAt.toEpochMilli())
        val power = powerOf(pet)
        val offered = mutableSetOf<Long>()

        return SparRivalKind.entries.mapNotNull { kind ->
            val target = kind.weeksAgo?.let { thisWeek - it * WEEK_MILLIS } ?: hatchWeek
            if (target < hatchWeek || target >= thisWeek || !offered.add(target)) return@mapNotNull null
            val past = snapshots.filter { it.weekStart <= target }.maxByOrNull { it.weekStart }
            val level = past?.level ?: XpCalculator.FIRST_LEVEL
            val rivalPower = past?.power ?: TitanRules.power(XpCalculator.FIRST_LEVEL, PetStats.ZERO)
            SparRival(
                kind = kind,
                weekStart = target,
                level = level,
                power = rivalPower,
                winChance = winChance(power, rivalPower),
                beaten = bouts.any { it.won && it.kind == kind.key && it.rivalWeek == target },
            )
        }
    }

    /**
     * One bout against [rival], decided from [random]: whether it is won, at its offered odds,
     * then the loser's rounds.
     *
     * @return won, rounds won, rounds lost.
     */
    fun fight(
        rival: SparRival,
        random: RandomSource,
    ): Triple<Boolean, Int, Int> {
        val won = random.nextInt(PERCENT) < rival.winChance
        val loserRounds = random.nextInt(ROUNDS_TO_WIN)
        return if (won) Triple(true, ROUNDS_TO_WIN, loserRounds) else Triple(false, loserRounds, ROUNDS_TO_WIN)
    }

    /** [state] after a bout at [now]: the energy spent, the spirit earned, and the titan sparring. */
    fun afterBout(
        state: TitanState,
        won: Boolean,
        now: Long,
    ): TitanState {
        val vitals = state.vitals
            .plus(TitanVital.ENERGY, -ENERGY_COST)
            .plus(TitanVital.SPIRIT, if (won) SPIRIT_ON_WIN else SPIRIT_ON_LOSS)
        return TitanState(
            vitals = vitals,
            activity = TitanActivity.SPAR,
            startedAt = now,
            endsAt = now + TitanActivity.SPAR.duration!!.inWholeMilliseconds,
            resumeActivity = null,
            updatedAt = now,
        )
    }

    private const val PERCENT = 100
}
