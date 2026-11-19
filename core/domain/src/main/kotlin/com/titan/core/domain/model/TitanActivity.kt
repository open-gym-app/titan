package com.titan.core.domain.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A place in the titan's habitat.
 *
 * Four are owned from hatch and enable the activities that need them; the other four come with an
 * achievement or drop on a level-up, and **once owned, are kept**. What the four later places
 * enable is not decided yet (#290), so today they enable nothing — [enables] is derived from
 * [TitanActivity.place], so giving one an activity is a line in that table.
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
    SAUNA("sauna", UnlockCondition.Achievement("four_week_streak")),
    TRAIL("trail", UnlockCondition.Achievement("twelve_week_streak")),
    DOJO("dojo", UnlockCondition.LevelUpDrop),

    SUMMIT("summit", UnlockCondition.Achievement("hundred_sessions")),
    ;

    /** The activities that happen here. */
    val enables: List<TitanActivity> get() = TitanActivity.entries.filter { it.place == this }

    companion object {

        /** The places every titan has from hatch. */
        val STARTING: Set<TitanPlace> = entries.filter { it.condition == UnlockCondition.FromHatch }.toSet()

        /** The places the titan has, given the level-up drops stored and the achievements held. */
        fun owned(
            dropped: Set<String>,
            achievements: Set<String>,
        ): Set<TitanPlace> = entries.filter { it.isOwned(dropped, achievements) }.toSet()
    }
}

/**
 * Something the titan does, as data: where it happens, how long it lasts and what finishing it is
 * worth. **The titan picks every one itself**; the user never chooses for it.
 *
 * @property key the stable id a stored state names. **Part of the storage contract**, pinned by a
 *   test; an unknown key reads back as [IDLE] (see [fromKey]).
 * @property place where it happens, or null for anywhere.
 * @property duration how long it lasts.
 * @property spiritOnFinish what finishing it adds to spirit.
 * @property selfChosen whether the titan picks it on its own. [EAT] is entered by a feed (#289),
 *   so it is not.
 */
enum class TitanActivity(
    val key: String,
    val place: TitanPlace?,
    val duration: Duration?,
    val spiritOnFinish: Int,
    val selfChosen: Boolean,
) {
    IDLE("idle", place = null, duration = 10.minutes, spiritOnFinish = 0, selfChosen = true),
    WALK(
        "walk",
        place = TitanPlace.TRACK,
        duration = 12.minutes,
        spiritOnFinish = 3,
        selfChosen = true,
    ),
    RUN("run", place = TitanPlace.TRACK, duration = 8.minutes, spiritOnFinish = 2, selfChosen = true),
    SWIM(
        "swim",
        place = TitanPlace.POOL,
        duration = 20.minutes,
        spiritOnFinish = 2,
        selfChosen = true,
    ),
    TRAIN(
        "train",
        place = TitanPlace.RACK,
        duration = 15.minutes,
        spiritOnFinish = 2,
        selfChosen = true,
    ),
    REST("rest", place = null, duration = 30.minutes, spiritOnFinish = 0, selfChosen = true),
    EAT("eat", place = null, duration = 2.minutes, spiritOnFinish = 0, selfChosen = false),
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
 * @property endsAt when it will end, or null for a state restored without one.
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
