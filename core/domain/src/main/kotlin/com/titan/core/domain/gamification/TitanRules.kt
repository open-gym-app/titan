package com.titan.core.domain.gamification

import com.titan.core.domain.model.Collectible
import com.titan.core.domain.model.FeedEffect
import com.titan.core.domain.model.PetStats
import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.model.TitanPlace
import com.titan.core.domain.model.TitanState
import com.titan.core.domain.model.TitanVital
import com.titan.core.domain.model.TitanVitals
import kotlin.random.Random

/**
 * How the titan's state moves, as pure functions over named rates.
 *
 * ### Time passes without a worker
 *
 * There is no background job and no alarm. A state is stored with the moment it describes, and
 * [advance] carries it to "now" when something reads it.
 *
 * ### Ticks are on the clock, not on the read
 *
 * Decay and refill happen at **absolute** quarter-hour boundaries ([TICK_MILLIS] since the
 * epoch), and an activity ends at its own stored time. So the events between two moments do not
 * depend on when anybody looked: advancing a→c is exactly advancing a→b then b→c, for any b,
 * which is what lets the dock and the tab read the same titan at different moments and agree.
 *
 * ### The titan picks for itself
 *
 * When an activity ends, the titan chooses its next one from where it can go. Nobody chooses for
 * it. A hungry titan only rests or idles until it is fed. The draw is seeded by the caller's seed
 * **and the moment of the choice**, so the same state and time give the same answer on every
 * read.
 *
 * The rates are starting defaults; the canvas gives costs but no rates.
 */
object TitanRules {

    /** How often decay and refill happen: every quarter hour, on the clock. */
    const val TICK_MILLIS: Long = 15 * 60 * 1000L

    /** Fuel lost per tick. Four an hour: a full titan gets hungry in about a day. */
    const val FUEL_PER_TICK = 1

    /** Decay never takes fuel below this. The titan just rests more. No death, no guilt. */
    const val FUEL_FLOOR = 10

    /** Spirit is lost once every this many ticks: one an hour. */
    const val SPIRIT_DECAY_EVERY_TICKS = 4

    /** Under this much fuel the titan is too hungry to exercise: it rests or idles until fed. */
    const val HUNGRY_BELOW = TitanVitals.LOW_BELOW

    /** The state a newly hatched titan starts in at [now]. */
    fun starting(now: Long): TitanState = start(TitanVitals.START, TitanActivity.IDLE, at = now, resume = null)

    /**
     * [state] as read from storage, made safe to advance at [now].
     *
     * The reader's half of #256's rule. A moment in the future — a clock that was ahead, a
     * hand-edited file — is pulled back to [now], and an activity cannot be due to end later than
     * its own duration after it began, so a hostile `ends_at` cannot freeze the titan for years.
     */
    fun repaired(
        state: TitanState,
        now: Long,
    ): TitanState {
        val startedAt = state.startedAt.coerceAtMost(now)
        val longest = state.activity.duration?.inWholeMilliseconds
        return state.copy(
            startedAt = startedAt,
            endsAt = if (longest == null) {
                null
            } else {
                (state.endsAt ?: (startedAt + longest)).coerceIn(
                    startedAt,
                    startedAt + longest,
                )
            },
            updatedAt = state.updatedAt.coerceIn(startedAt, now),
        )
    }

    /**
     * The single vital to raise an alert for: the **lowest**, and only if it is low.
     *
     * Ties resolve in [TitanVital]'s declaration order — fuel, then spirit — which
     * is also the order of what the user can do about them.
     */
    fun alertFor(vitals: TitanVitals): TitanVital? = TitanVital.entries.minBy { vitals[it] }.takeIf { vitals.isLow(it) }

    /**
     * [state] carried forward to [to].
     *
     * A [to] at or before the state's own moment — a clock set back, a read from the past —
     * returns the state as it is: time does not run backwards here, and nothing is undone.
     *
     * @param seed what the titan's own choices are drawn from, mixed with each choice's moment.
     * @param places where the titan can go.
     */
    fun advance(
        state: TitanState,
        to: Long,
        seed: Long,
        places: Set<TitanPlace> = TitanPlace.STARTING,
    ): TitanState {
        var current = state
        while (true) {
            val now = current.updatedAt
            val nextTick = (Math.floorDiv(now, TICK_MILLIS) + 1) * TICK_MILLIS
            val end = current.endsAt?.coerceAtLeast(now)
            current = when {
                end != null && end < nextTick && end <= to -> finish(current, at = end, seed, places)
                nextTick <= to -> tick(current, at = nextTick)
                else -> return if (to > now) current.copy(updatedAt = to) else current
            }
        }
    }

    /** What any food adds to spirit on top of its fuel. */
    const val FEED_SPIRIT = 5

    /**
     * What feeding [food] to [state] at [now] would do, without doing it — the figures the feed
     * sheet shows before the tap. [feed] applies exactly these.
     *
     * @throws IllegalArgumentException if [food] is not a food.
     */
    fun previewFeed(
        state: TitanState,
        food: Collectible,
    ): FeedEffect {
        val fuel = requireNotNull(food.fuel) { "${food.id} is not a food" }
        val after = state.vitals.plus(TitanVital.FUEL, fuel).plus(TitanVital.SPIRIT, FEED_SPIRIT)
        return FeedEffect(before = state.vitals, after = after)
    }

    /**
     * [state] fed [food] at [now]: the effect applied, and the titan stopping to eat before it
     * goes back to what it was doing. A titan already eating goes back to what it was doing
     * before that meal, not to eating.
     */
    fun feed(
        state: TitanState,
        food: Collectible,
        now: Long,
    ): Pair<TitanState, FeedEffect> {
        val effect = previewFeed(state, food)
        val resume = if (state.activity == TitanActivity.EAT) state.resumeActivity else state.activity
        val eating = start(effect.after, TitanActivity.EAT, at = now, resume = resume)
        return eating to effect
    }

    /**
     * The titan's power: one figure, derived from what training has built.
     *
     * Twice its level plus a tenth of its three stats together, derived from training alone.
     */
    fun power(
        level: Int,
        stats: PetStats,
    ): Int {
        val total = stats.strength.toLong() + stats.endurance + stats.mobility
        return (2L * level + total / POWER_STAT_DIVISOR).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun tick(
        state: TitanState,
        at: Long,
    ): TitanState {
        var vitals = state.vitals
        if (vitals.fuel > FUEL_FLOOR) {
            vitals = vitals.plus(TitanVital.FUEL, -FUEL_PER_TICK).let {
                if (it.fuel < FUEL_FLOOR) it.copy(fuel = FUEL_FLOOR) else it
            }
        }
        if (Math.floorMod(Math.floorDiv(at, TICK_MILLIS), SPIRIT_DECAY_EVERY_TICKS.toLong()) == 0L) {
            vitals = vitals.plus(TitanVital.SPIRIT, -1)
        }
        return state.copy(vitals = vitals, updatedAt = at)
    }

    private fun finish(
        state: TitanState,
        at: Long,
        seed: Long,
        places: Set<TitanPlace>,
    ): TitanState {
        val vitals = state.vitals.plus(TitanVital.SPIRIT, state.activity.spiritOnFinish)
        val resume = state.resumeActivity
        if (state.activity == TitanActivity.EAT && resume != null) {
            return start(vitals, resume, at, resume = null)
        }
        return start(vitals, next(vitals, at, seed, places), at, resume = null)
    }

    private fun next(
        vitals: TitanVitals,
        at: Long,
        seed: Long,
        places: Set<TitanPlace>,
    ): TitanActivity {
        val hungry = vitals.fuel < HUNGRY_BELOW
        val open = TitanActivity.entries.filter { activity ->
            activity.selfChosen &&
                (activity.place == null || activity.place in places) &&
                (!hungry || activity.place == null)
        }
        return open[Random(seed xor (at * SEED_MIX)).nextInt(open.size)]
    }

    private fun start(
        vitals: TitanVitals,
        activity: TitanActivity,
        at: Long,
        resume: TitanActivity?,
    ): TitanState =
        TitanState(
            vitals = vitals,
            activity = activity,
            startedAt = at,
            endsAt = activity.duration?.let { at + it.inWholeMilliseconds },
            resumeActivity = resume,
            updatedAt = at,
        )

    private const val POWER_STAT_DIVISOR = 10L

    /** An odd constant, so that consecutive moments seed far-apart generators. */
    private const val SEED_MIX = -0x61c8864680b583ebL
}
