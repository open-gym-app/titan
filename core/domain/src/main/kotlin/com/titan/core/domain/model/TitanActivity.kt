package com.titan.core.domain.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A habitat: a background the titan's stage can be drawn on. **Purely visual** — the titan does
 * the same things wherever it is drawn.
 *
 * Four are owned from hatch; the other four come with an achievement or drop on a level-up, and
 * **once owned, are kept**. The user picks one of the owned ones for each titan; it is stored in
 * the titan's loadout under [LOADOUT_SLOT].
 *
 * @property key the stable id a stored unlock and a stored choice name. Pinned by a test.
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

    companion object {

        /** The loadout slot the chosen habitat is stored under, beside the gear slots. */
        const val LOADOUT_SLOT = "habitat"

        /** What the stage is drawn on until the user picks something else. */
        val DEFAULT: TitanPlace = TRACK

        /** The habitat [key] names, or null for a key this build does not know. */
        fun byKey(key: String?): TitanPlace? = entries.firstOrNull { it.key == key }

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
 * Something the titan does, as data: how long it lasts and what finishing it is worth. **The
 * titan picks every one itself**, and how long it keeps at it, so the user never chooses for it.
 *
 * @property key the stable id a stored state names. **Part of the storage contract**, pinned by a
 *   test; an unknown key reads back as [IDLE] (see [fromKey]).
 * @property shortest the least time it lasts.
 * @property duration the most time it lasts. Each time the titan starts it, the length is drawn
 *   between [shortest] and this.
 * @property spiritOnFinish what finishing it adds to spirit.
 * @property exercise whether it is exercise, which a hungry titan does not do.
 * @property selfChosen whether the titan picks it on its own. [EAT] is entered by a feed (#289),
 *   so it is not.
 */
enum class TitanActivity(
    val key: String,
    val shortest: Duration,
    val duration: Duration?,
    val spiritOnFinish: Int,
    val exercise: Boolean,
    val selfChosen: Boolean,
) {
    IDLE("idle", 1.minutes, 20.minutes, spiritOnFinish = 0, exercise = false, selfChosen = true),
    WALK("walk", 3.minutes, 25.minutes, spiritOnFinish = 3, exercise = true, selfChosen = true),
    RUN("run", 2.minutes, 15.minutes, spiritOnFinish = 2, exercise = true, selfChosen = true),
    SWIM("swim", 4.minutes, 30.minutes, spiritOnFinish = 2, exercise = true, selfChosen = true),
    TRAIN("train", 3.minutes, 25.minutes, spiritOnFinish = 2, exercise = true, selfChosen = true),
    REST("rest", 5.minutes, 45.minutes, spiritOnFinish = 0, exercise = false, selfChosen = true),
    EAT("eat", 2.minutes, 2.minutes, spiritOnFinish = 0, exercise = false, selfChosen = false),
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
