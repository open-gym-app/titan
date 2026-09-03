package com.opengym.core.domain.model

/**
 * Something the user did that experience points are awarded for.
 *
 * One variant per **source** of XP, and the whole reason this is a type rather than an `Int`
 * passed to a repository: what a set is worth is a tuning decision made in one place
 * ([com.opengym.core.domain.gamification.XpCalculator]) rather than at every call site that
 * happens to know a set was logged. A caller says what happened; it does not say what that
 * is worth.
 *
 * ### One event per qualifying action
 *
 * A session with ten sets and one broken record emits [SessionFinished] once, [SetLogged]
 * ten times and [PrBroken] once per record actually broken — not a single bundled event
 * carrying counts. Bundling would make "how much is a set worth" a sum computed by whoever
 * assembled the bundle, and would make the level-up answer ([XpAward.leveledUp]) a property
 * of a batch rather than of the moment a threshold was crossed, which is what #27's
 * celebration is triggered by.
 *
 * The flip side is that emitting the same action twice awards twice: nothing here
 * deduplicates, because two sets logged a second apart are genuinely two events and this
 * type cannot tell them from one set counted twice. Not emitting a duplicate is the
 * emitter's job — see `AchievementTriggerUseCase` (#34), which owes exactly that for
 * [AchievementUnlocked], and the `AchievementDao.insertIfAbsent` it is built on.
 *
 * ### Adding a source later
 *
 * A sealed interface, so a new source (M8's daily challenges, M5-11's quests) is a new
 * variant and a new constant beside the existing ones. Nothing that *emits* or *carries* an
 * event changes; the one place a new variant must be handled is
 * [com.opengym.core.domain.gamification.XpCalculator.xpFor], where the compiler demands it
 * rather than letting a new source silently be worth nothing.
 */
sealed interface XpEvent {

    /** One set logged, warm-up or not — the work of showing up, priced per set. */
    data object SetLogged : XpEvent

    /** A session finished. Emitted once, on the finish, never per exercise. */
    data object SessionFinished : XpEvent

    /**
     * A personal record broken, one event per [PrType] the session actually beat.
     *
     * A session that beats its weight, rep and volume records for one exercise emits three
     * of these, because three records fell. [prType] carries which, so a future tuning pass
     * can price an estimated 1RM differently from a heaviest lift without changing this
     * type — today they are all worth the same.
     */
    data class PrBroken(val prType: PrType) : XpEvent

    /**
     * An achievement unlocked.
     *
     * A source in its own right, deliberately: an achievement earned *for* finishing a
     * session is worth its own award on top of that session's, and folding it into
     * [SessionFinished] would make the two impossible to tune apart. #34 decides which
     * achievements exist and when they unlock; this only prices the unlock.
     *
     * [achievementId] is the stable string key the achievement is defined and stored under
     * (#34's data-defined set, #35's `AchievementEntity.achievementId`) rather than a row id,
     * so an event survives the row being written and can be emitted before it.
     */
    data class AchievementUnlocked(val achievementId: String) : XpEvent

    /**
     * A quest completed, for the period it was completed in.
     *
     * ### Why this one carries its own price
     *
     * Every other variant is priced by a constant in
     * [com.opengym.core.domain.gamification.XpCalculator] because every event of that kind is
     * worth the same. Quests are not: the reward is part of what defines a quest — a month of
     * training pays more than a good week — and it lives in the catalogue beside the target it
     * is the price of, where a tuning pass can see the two together.
     *
     * The alternative is [com.opengym.core.domain.gamification.XpCalculator.xpFor] reading the
     * catalogue to look the reward up by id, which would give that object a dependency and a
     * failure mode ("what is an id nobody defines worth?") in exchange for nothing. So the
     * price travels on the event and the calculator stays the pure arithmetic it says it is.
     *
     * @property questId the stable catalogue key, for the same reason
     *   [AchievementUnlocked.achievementId] is one: it survives the row being written and can
     *   be emitted before it.
     * @property xp what this quest pays, copied off
     *   [com.opengym.core.domain.model.Quest.xpReward] at the moment of completion.
     */
    data class QuestCompleted(
        val questId: String,
        val xp: Int,
    ) : XpEvent
}
