package com.titan.core.domain.gamification

import com.titan.core.domain.model.Achievement

/**
 * How hard a milestone was to reach, relative to the others measured the same way.
 *
 * ### Derived from the catalogue, never written down
 *
 * A tier is not a field on [Achievement] and there is no table of ids to metals anywhere. It is
 * read off the ladder the milestone belongs to — see [tier] — which keeps the property
 * [Achievements] exists for: adding "five hundred sessions" is one line in the catalogue, and it
 * becomes the ladder's new gold without a second edit here, in the theme, or on any screen.
 *
 * A hand-written map would have been the obvious thing and would have broken exactly that. The
 * milestone below the new top rung would have kept its gold, and nothing would have failed — the
 * app would simply have had two golds on the same ladder until somebody noticed.
 *
 * ### Three, because a medal has three
 *
 * Bronze, silver, gold is the one ranking a reader already knows without a legend, which is worth
 * more here than a scale that could describe a longer ladder precisely. A four-rung metric puts
 * two milestones in silver; that is the correct answer, not a gap — they are both "past the start
 * and not at the end".
 */
enum class AchievementTier {

    /** The first rung of a ladder — the milestone that marks having started. */
    BRONZE,

    /** Anything between the ends: the user is into the metric and not at the end of it. */
    SILVER,

    /** The last rung a metric offers, and the only one that means there is nothing above it. */
    GOLD,
}

/**
 * Which metal this milestone is worth, judged against every other milestone on its own metric.
 *
 * The rank is positional rather than absolute: a streak of seven days is the *first* rung of the
 * streak ladder and so is bronze, even though seven of anything is a larger number than the one
 * session that earns bronze on the session ladder. Comparing thresholds across metrics would be
 * comparing days with workouts, which is why the ladder is filtered to [Achievement.metric] first.
 *
 * A **ladder of one is bronze**, not gold. `first_record` is the only record milestone this build
 * ships, and calling it the top of its ladder would give a brand-new user a gold medal for their
 * first session's first PR — the loudest reward in the set, for the easiest thing in it. A first
 * is a first; when a five-record milestone joins the catalogue, this one stays bronze and the new
 * entry takes the gold, with no edit here.
 *
 * @param catalogue the ladder to rank against. Defaulted to [Achievements.ALL] — the parameter is
 *   for tests, which build a ladder of known shape rather than restating the shipped one.
 *   An achievement absent from [catalogue] ranks bronze: it is off the ladder entirely, and the
 *   quietest metal is the right answer for something nothing else can be compared to.
 */
fun Achievement.tier(catalogue: List<Achievement> = Achievements.ALL): AchievementTier {
    val ladder = catalogue.asSequence()
        .filter { it.metric == metric }
        .map { it.threshold }
        .distinct()
        .sorted()
        .toList()

    return when (ladder.indexOf(threshold)) {
        // Absent, or the bottom rung, or the only rung. See the note above on ladders of one.
        -1, 0 -> AchievementTier.BRONZE

        ladder.lastIndex -> AchievementTier.GOLD

        else -> AchievementTier.SILVER
    }
}
