package com.opengym.core.domain.superset

import com.opengym.core.domain.validation.ValidationResult

/**
 * The rules for supersets — two or more exercises performed back to back, with the
 * rest taken after the group rather than between its members.
 *
 * ### What a grouping is
 *
 * Everything here works on a **grouping**: one nullable group id per exercise, in
 * position order. `null` means the exercise stands alone; a shared non-null id means
 * those exercises are one superset. Callers hold the exercises, not the grouping, and
 * hand over `exercises.map { it.supersetGroup }` — a session's rows
 * ([com.opengym.core.domain.model.SessionExercise]), a template's
 * ([com.opengym.core.domain.model.TemplateExercise]), a draft's
 * ([com.opengym.core.domain.model.ActiveExercise]) or a list about to be written
 * ([com.opengym.core.domain.model.TemplateExerciseDraft]) all reduce to the same list
 * of ids, and the rules are the same for all four.
 *
 * That indirection is the point: the four types share no supertype, three of them
 * number positions differently — stored ones carry a `position`, drafts take theirs
 * from list order — and none of that changes what makes a superset well-formed.
 *
 * **The list must be in position order.** Nothing here can check that, since a
 * grouping has no positions of its own; it only has indices. A caller reading rows
 * back gets them ordered (`SessionRepository.getExercises`, `getExercises` on a
 * template), and one holding an unordered list sorts before mapping.
 *
 * ### The rules
 *
 * - **A group has at least [MIN_MEMBERS] members.** One exercise performed on its own
 *   is not a superset, whatever it used to be part of, so a group that drops to a
 *   single member is dissolved rather than kept — [normalize] does that.
 * - **A group is contiguous.** Its members occupy consecutive positions, so a
 *   non-member can never be interleaved between them. This is the rule a reorder has
 *   to respect, and the one [normalize] cannot repair: moving an exercise out from
 *   between two members is a decision about the workout, not a tidy-up.
 * - **Group ids are ordinals within one session or template**, dense and numbered from
 *   0 in position order, which is what lets a screen render them as `SS A`, `SS B` and
 *   a member's index within its group as the `1` in `SS A1`. They mean nothing across
 *   sessions and are not stable across edits — [normalize] renumbers.
 * - **Rest is due after the last member**, not between members. See [isRestDueAfter];
 *   the rest timer itself is unchanged, what changes is when it is asked to start.
 *
 * Nothing in the schema holds any of this: `superset_group` is a nullable integer
 * column and the database will accept anything, exactly as with the enum-like columns
 * and the set bounds (see PERSISTENCE.md). These functions are where the rules live.
 */
object Supersets {

    /** Fewer than two exercises back to back is not a superset, it is an exercise. */
    const val MIN_MEMBERS = 2

    /**
     * The members of each group in [grouping], as indices into it.
     *
     * Keyed by group id in the order the groups first appear, with each group's indices
     * ascending — so a UI can walk the result in the order it renders. Exercises that
     * stand alone are absent; a grouping with no supersets maps to an empty result.
     */
    fun groups(grouping: List<Int?>): Map<Int, List<Int>> {
        val members = LinkedHashMap<Int, MutableList<Int>>()
        grouping.forEachIndexed { index, group ->
            if (group != null) members.getOrPut(group) { mutableListOf() }.add(index)
        }
        return members
    }

    /**
     * Whether [grouping] satisfies both structural rules: every group has at least
     * [MIN_MEMBERS] members, and every group occupies consecutive positions.
     *
     * Reports every violation in one [ValidationResult.Invalid], matching
     * [com.opengym.core.domain.validation.SetValidator]: a caller repairing a list
     * should see all of what is wrong with it, not one problem per attempt.
     *
     * Says nothing about the *numbering*. A grouping of `[3, 3, null]` is valid —
     * sparse or out-of-order ids describe the same supersets — and [normalize] is what
     * makes the numbering canonical before it is stored.
     */
    fun validate(grouping: List<Int?>): ValidationResult {
        val violations = groups(grouping).mapNotNull { (group, indices) ->
            when {
                indices.size < MIN_MEMBERS ->
                    "superset $group has ${indices.size} exercise, a superset needs at least $MIN_MEMBERS"

                indices.last() - indices.first() + 1 != indices.size ->
                    "superset $group is split by another exercise; its members must be next to each other"

                else -> null
            }
        }

        return if (violations.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(violations.joinToString("; "))
        }
    }

    /**
     * [grouping] in its canonical form: groups left with fewer than [MIN_MEMBERS]
     * members dissolved back to standalone, and the survivors renumbered densely from
     * 0 in position order.
     *
     * This is the repair a *removal* needs. Deleting one of two exercises in a superset
     * leaves a group of one, which is not a superset and must not be stored as one — so
     * the caller drops the exercise and normalises, rather than having to notice what
     * its removal did to the group it was in.
     *
     * The renumbering is what keeps ids meaningful as ordinals: dissolving group 0
     * would otherwise leave the next superset labelled `SS B` with no `SS A` above it.
     * It also means ids are **not stable across an edit** — nothing may store a group
     * id and expect to find the same exercises under it later.
     *
     * A non-contiguous group is left exactly as it is. Repairing one means deciding what
     * the workout now is, which this cannot do on the caller's behalf: [validate] is what
     * refuses it, and [tear] is what a caller who *has* made that decision applies.
     */
    fun normalize(grouping: List<Int?>): List<Int?> {
        val surviving = groups(grouping)
            .filterValues { it.size >= MIN_MEMBERS }
            .keys
            // Renumbered by first appearance, which `groups` already orders by.
            .withIndex()
            .associate { (ordinal, group) -> group to ordinal }

        return grouping.map { group -> group?.let(surviving::get) }
    }

    /**
     * [grouping] with every group cut back to the run of consecutive positions it still
     * occupies, then normalised.
     *
     * The repair a *reorder* needs, and the counterpart of [normalize]'s refusal to
     * guess: an exercise dragged out from between two members leaves a group whose
     * members are no longer next to each other, and the user has already said what
     * should happen to it by moving it. Each group is therefore cut at its gaps — every
     * maximal run of consecutive members becomes a group of its own — and [normalize]
     * then dissolves the runs left with a single member and renumbers what survives.
     *
     * Two shapes of edit, one rule:
     *
     * - **A member dragged away from its group.** The exercise that left is a run of one
     *   and stands alone; a pair it left dissolves entirely, while a three it was in the
     *   middle of closes up — the two that remain are neighbours now — and stays a
     *   superset.
     * - **A non-member dropped into the middle of a group.** The group is cut in two at
     *   the exercise that landed in it. What is left either side survives as a superset
     *   if two or more members are still together, and dissolves if not.
     *
     * A grouping that is already well formed comes back exactly as [normalize] would
     * leave it, so a caller does not have to know which of the two it is holding.
     */
    fun tear(grouping: List<Int?>): List<Int?> {
        var fresh = (grouping.filterNotNull().maxOrNull() ?: -1) + 1
        val cut = grouping.toMutableList()

        groups(grouping).forEach { (group, indices) ->
            // The first run keeps the group's own id; every run after a gap takes a new
            // one, above every id in use, which normalize renumbers into its positional
            // ordinal — or dissolves, where the run turned out to be one exercise.
            var run = group
            indices.zipWithNext().forEach { (previous, member) ->
                if (member != previous + 1) run = fresh++
                cut[member] = run
            }
        }

        return normalize(cut)
    }

    /**
     * [grouping] with the exercises at [indices] made into one superset, normalised.
     *
     * The new group takes the positions it is given, and anything those exercises were
     * part of before is left to [normalize] — a group robbed of all but one member
     * dissolves on the way out.
     *
     * Refused, with the reason in an `IllegalArgumentException`, when the result would
     * not be a well-formed grouping:
     *
     * - fewer than [MIN_MEMBERS] indices, or an index outside [grouping];
     * - indices that are not consecutive, which would ask for a superset split by an
     *   exercise that is not in it;
     * - a group *elsewhere* left non-contiguous, which is what grouping the middle of
     *   an existing superset does to the members on either side. The refusal is
     *   deliberate: the caller wanted a new group, not a silent reshuffle of the old
     *   one, and the way to get there is to dissolve it first.
     *
     * Refusing rather than throwing because the caller is a screen where the user
     * selected the rows: an unselectable combination is something to report, not a bug.
     */
    fun form(
        grouping: List<Int?>,
        indices: Set<Int>,
    ): Result<List<Int?>> {
        if (indices.size < MIN_MEMBERS) {
            return failure("a superset needs at least $MIN_MEMBERS exercises, ${indices.size} given")
        }
        val outOfRange = indices.filterNot { it in grouping.indices }
        if (outOfRange.isNotEmpty()) {
            return failure("no exercise at position ${outOfRange.sorted().joinToString()}")
        }
        val ordered = indices.sorted()
        if (ordered.last() - ordered.first() + 1 != ordered.size) {
            return failure("a superset's exercises must be next to each other, got ${ordered.joinToString()}")
        }

        // Above every existing id, so the new group cannot merge with one it was not
        // asked to; normalize then renumbers it into its positional ordinal anyway.
        val fresh = (grouping.filterNotNull().maxOrNull() ?: -1) + 1
        val formed = normalize(grouping.mapIndexed { index, group -> if (index in indices) fresh else group })

        return when (val result = validate(formed)) {
            is ValidationResult.Valid -> Result.success(formed)
            is ValidationResult.Invalid -> failure(result.reason)
        }
    }

    /**
     * [grouping] with the exercises at [index] and `index + 1` performed back to back.
     *
     * The pairwise form of [form], and the one a screen without a reorder can actually
     * offer: the only thing a user can ask for that is guaranteed to stay contiguous is
     * "join these two neighbours". Whatever either side already belongs to comes with
     * it — joining the last member of `A` to the exercise after it *extends* `A` rather
     * than tearing a member out of it, and joining two existing groups merges them —
     * which is what makes a three-exercise superset reachable by tapping twice.
     *
     * Refused when [index] or its successor is outside [grouping]: the exercise at the
     * end of a session has nothing to be joined to, and a screen that draws no control
     * there never asks. The other refusals are [form]'s.
     */
    fun join(
        grouping: List<Int?>,
        index: Int,
    ): Result<List<Int?>> {
        if (index !in grouping.indices || index + 1 !in grouping.indices) {
            return failure("nothing to join at position $index of ${grouping.size} exercises")
        }
        return form(grouping, membersAt(grouping, index) + membersAt(grouping, index + 1))
    }

    /**
     * [grouping] with the exercise at [index] no longer performed back to back with the
     * one after it, normalised.
     *
     * Breaks *one link* rather than dissolving the whole group, which is what a control
     * sitting between two exercises means: splitting `A = [0, 1, 2]` after position 1
     * leaves `[0, 1]` a superset and `2` standing alone. Everything from the break
     * onwards keeps being one group, so a split of a three-member group cannot leave
     * two orphans; a tail of one dissolves in [normalize] like any other.
     *
     * Total, unlike [join]: a link that is not there is nothing to refuse, so an
     * exercise that stands alone, the last exercise of a session and an index past the
     * end all answer with the grouping normalised and otherwise unchanged.
     */
    fun split(
        grouping: List<Int?>,
        index: Int,
    ): List<Int?> {
        val group = grouping.getOrNull(index)
        if (group == null || grouping.getOrNull(index + 1) != group) return normalize(grouping)

        val fresh = (grouping.filterNotNull().maxOrNull() ?: -1) + 1
        return normalize(
            grouping.mapIndexed { at, member -> if (at > index && member == group) fresh else member },
        )
    }

    /**
     * The whole group the exercise at [index] belongs to, or just itself when it stands
     * alone — the set [join] has to preserve to be an extension rather than a theft.
     */
    private fun membersAt(
        grouping: List<Int?>,
        index: Int,
    ): Set<Int> {
        val group = grouping[index] ?: return setOf(index)
        return groups(grouping)[group]?.toSet() ?: setOf(index)
    }

    /**
     * Whether a rest belongs after the exercise at [index] of [grouping].
     *
     * True for an exercise that stands alone and for the last member of a superset;
     * false between members, which is the whole of what a superset changes about how a
     * session is paced. A caller finishing an exercise asks this before starting
     * [com.opengym.core.domain.timer.RestTimerController] — the timer itself has no
     * notion of grouping and needs none.
     *
     * An [index] past the end answers true: there is nothing after it to run into, and
     * an empty session asking about its (legal) index 0 must get an answer rather than
     * an exception.
     *
     * Reads the *next* position rather than looking the group up, so it costs nothing
     * on a long list — which is sound exactly because members are contiguous. On a
     * grouping [validate] would refuse, the answer is as meaningless as the grouping.
     */
    fun isRestDueAfter(
        grouping: List<Int?>,
        index: Int,
    ): Boolean {
        val group = grouping.getOrNull(index) ?: return true
        return grouping.getOrNull(index + 1) != group
    }

    private fun failure(reason: String): Result<List<Int?>> = Result.failure(IllegalArgumentException(reason))
}
