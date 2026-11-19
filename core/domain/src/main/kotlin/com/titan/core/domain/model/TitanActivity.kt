package com.titan.core.domain.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A place in the titan's habitat. "Places unlock activities. Streaks, distance and sparring open
 * new ones."
 *
 * Four are owned from hatch and enable the activities that need them; the other four unlock with
 * the user's training and, **once earned, are kept**: a streak can break, the sauna does not
 * close. What the four later places enable is not decided yet (#290), so today they enable
 * nothing — [enables] is derived from [TitanActivity.place], so giving one an activity is a line
 * in that table.
 *
 * @property key the stable id a stored unlock names. Pinned by a test.
 */
enum class TitanPlace(
    override val key: String,
    override val condition: UnlockCondition,
) : Unlockable {
    TRACK("track", UnlockCondition.FromHatch),
    RACK("rack", UnlockCondition.FromHatch),
    POOL("pool", UnlockCondition.FromHatch),
    RING("ring", UnlockCondition.FromHatch),
    SAUNA("sauna", UnlockCondition.StreakWeeks(weeks = 8)),
    TRAIL("trail", UnlockCondition.DistanceKm(kilometres = 100)),
    DOJO("dojo", UnlockCondition.SparsWon(count = 10)),

    /** "Legend rank": the user's `LevelTitles.LEGEND`, from level 100. */
    SUMMIT("summit", UnlockCondition.Level(level = 100)),
    ;

    /** The activities that happen here. */
    val enables: List<TitanActivity> get() = TitanActivity.entries.filter { it.place == this }

    companion object {

        /** The places every titan has from hatch. */
        val STARTING: Set<TitanPlace> = entries.filter { it.condition == UnlockCondition.FromHatch }.toSet()

        /** The places a set of stored unlock keys and the starting ones add up to. */
        fun owned(unlocked: Set<String>): Set<TitanPlace> = STARTING + entries.filter { it.key in unlocked }
    }
}

/**
 * Something the titan does, as data: what it costs, where it happens, how long it lasts and what
 * finishing it is worth.
 *
 * @property key the stable id a stored state names. **Part of the storage contract**, pinned by a
 *   test; an unknown key reads back as [IDLE] (see [fromKey]).
 * @property energyCost what starting it takes from energy. Zero for the ones that cost nothing.
 * @property place where it happens, or null for anywhere.
 * @property duration how long it lasts, or null for [REST], which lasts until energy is full.
 * @property spiritOnFinish what finishing it adds to spirit.
 * @property selfChosen whether the titan picks it on its own. [SPAR] is resolved against a rival
 *   the user picks (#292) and [EAT] is entered by a feed (#289), so neither is.
 */
enum class TitanActivity(
    val key: String,
    val energyCost: Int,
    val place: TitanPlace?,
    val duration: Duration?,
    val spiritOnFinish: Int,
    val selfChosen: Boolean,
) {
    IDLE("idle", energyCost = 0, place = null, duration = 10.minutes, spiritOnFinish = 0, selfChosen = true),
    WALK(
        "walk",
        energyCost = 5,
        place = TitanPlace.TRACK,
        duration = 12.minutes,
        spiritOnFinish = 3,
        selfChosen = true,
    ),
    RUN("run", energyCost = 15, place = TitanPlace.TRACK, duration = 8.minutes, spiritOnFinish = 2, selfChosen = true),
    SWIM(
        "swim",
        energyCost = 12,
        place = TitanPlace.POOL,
        duration = 20.minutes,
        spiritOnFinish = 2,
        selfChosen = true,
    ),
    TRAIN(
        "train",
        energyCost = 10,
        place = TitanPlace.RACK,
        duration = 15.minutes,
        spiritOnFinish = 2,
        selfChosen = true,
    ),
    SPAR(
        "spar",
        energyCost = 20,
        place = TitanPlace.RING,
        duration = 5.minutes,
        spiritOnFinish = 0,
        selfChosen = false,
    ),
    REST("rest", energyCost = 0, place = null, duration = null, spiritOnFinish = 0, selfChosen = true),
    EAT("eat", energyCost = 0, place = null, duration = 2.minutes, spiritOnFinish = 0, selfChosen = false),
    ;

    companion object {

        /** The activity [key] names, or [IDLE] for a key this build does not know. */
        fun fromKey(key: String): TitanActivity = entries.firstOrNull { it.key == key } ?: IDLE
    }
}

/**
 * The titan's whole state at one moment: its vitals and what it is doing.
 *
 * Times are epoch milliseconds, UTC, the way every stored instant in this app is.
 *
 * @property startedAt when the current activity began.
 * @property endsAt when it will end, or null for [TitanActivity.REST], which ends when energy is
 *   full.
 * @property resumeActivity what to go back to once [TitanActivity.EAT] ends, if anything.
 * @property updatedAt the moment this state describes.
 */
data class TitanState(
    val vitals: TitanVitals,
    val activity: TitanActivity,
    val startedAt: Long,
    val endsAt: Long?,
    val resumeActivity: TitanActivity?,
    val updatedAt: Long,
)

/** The answer to the user asking the titan to do something else. */
sealed interface ChooseActivityResult {

    /** Done, as [state]. Asking for what it is already doing is done and changes nothing. */
    data class Chosen(val state: TitanState) : ChooseActivityResult

    /** It costs [needs] energy and the titan has less. */
    data class NotAffordable(val needs: Int) : ChooseActivityResult

    /** It happens at [place], which the titan does not have. */
    data class PlaceLocked(val place: TitanPlace) : ChooseActivityResult

    /** Not something the user starts from "What next": a spar has its own screen, and eating is a feed. */
    data object NotChoosable : ChooseActivityResult

    /** There is no such pet. */
    data object NotFound : ChooseActivityResult
}

/**
 * What a write to the titan's state decided.
 *
 * @property state what to store, or null to store nothing.
 * @property result what to hand back to the caller.
 */
data class TitanChange<R>(
    val state: TitanState?,
    val result: R,
)
