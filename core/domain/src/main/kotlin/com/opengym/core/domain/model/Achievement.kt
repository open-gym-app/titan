package com.opengym.core.domain.model

/**
 * One milestone the user can unlock, as **data**: a metric, and how much of it is enough.
 *
 * The whole point of the shape. An achievement set written as branching logic —
 * `if (sessions == 1) unlock("first_session")` — grows a code path per milestone, and every
 * one of those paths is a place to forget the idempotency the unlock needs. Here, adding
 * "500 sessions" is a line in [com.opengym.core.domain.gamification.Achievements] and no new
 * behaviour at all: [isUnlockedBy] already knows how to evaluate it, and
 * [com.opengym.core.domain.usecase.AchievementTriggerUseCase] already knows how to unlock it
 * once. Only a genuinely new *kind* of condition costs code, and that cost is one constant in
 * [AchievementMetric] plus one branch in [AchievementProgress.valueOf], where the compiler
 * demands it.
 *
 * @property id the stable key the unlock is stored under (`AchievementEntity.achievementId`,
 *   #35) and carried on [XpEvent.AchievementUnlocked]. **Part of the storage contract**: a
 *   rename orphans every row already written under the old spelling, so it is pinned by a
 *   test in the same way the persisted enum names are.
 * @property metric what the milestone is measured in.
 * @property threshold how much of [metric] unlocks it — always at least one, since a
 *   milestone nobody has to reach is not a milestone.
 */
data class Achievement(
    val id: String,
    val metric: AchievementMetric,
    val threshold: Int,
) {

    init {
        require(threshold >= 1) { "an achievement threshold must be at least 1, was $threshold for $id" }
    }

    /**
     * Whether [progress] has reached this milestone.
     *
     * Greater-or-equal rather than equal: a user can pass a threshold without ever being
     * measured exactly on it — a session finished while the app was killed, an achievement
     * added to the catalogue years after the user cleared its bar — and an equality test
     * would leave those permanently unreachable. It also means this answers **true** for an
     * achievement that is already unlocked, which is why "already unlocked" is a question
     * about what is stored rather than one this can answer; see
     * [com.opengym.core.domain.usecase.AchievementTriggerUseCase].
     */
    fun isUnlockedBy(progress: AchievementProgress): Boolean = progress.valueOf(metric) >= threshold
}
