package com.titan.core.ui.component

import com.titan.core.domain.superset.Supersets

/**
 * The `A1`, `A2`, `B1` marker for each position of [grouping], or null where the exercise
 * stands alone.
 *
 * The letter is the group's ordinal and the digit is the member's place within it, which is what
 * makes a superset readable as a unit in a list — `A1` and `A2` are one thing, `B1` is the next.
 *
 * ### Why it lives in `:core:ui` rather than in `:core:domain`
 *
 * `Supersets` decides what a group *is*; this decides what it is *called*, and a name is a
 * presentation fact. It is here rather than in a feature because three screens draw the same
 * marker over the same grouping — the review panel before a workout starts, the workout itself,
 * and template authoring — and a second implementation is how two of them end up disagreeing
 * about what the third exercise is called.
 *
 * @param grouping must already be normalised, per [Supersets.normalize]: the letters are read
 *   off dense ids numbered from 0, so a gap would leave a `B` with no `A` above it. Normalising
 *   is the caller's because a grouping is normalised on the way *into* the store as well, and
 *   doing it twice in one pass would hide a caller that had skipped it.
 *
 * @return one entry per position of [grouping], in the same order.
 */
fun supersetLabels(grouping: List<Int?>): List<String?> {
    val labels = arrayOfNulls<String>(grouping.size)

    Supersets.groups(grouping).forEach { (group, indices) ->
        val name = groupName(group)
        indices.forEachIndexed { member, index -> labels[index] = "$name${member + 1}" }
    }

    return labels.toList()
}

/**
 * The name of the group at each position of [grouping] — the `A` of `A1` — or null where the
 * exercise stands alone.
 *
 * Beside [supersetLabels] rather than recovered from one, because a caller that needs the group
 * on its own has only two ways to get it and one of them is wrong: reading the letters off the
 * label is a parse of a display string whose format lives here, and it has no answer at all for
 * the numeric fallback below, where every character is a digit and the parse comes back empty.
 *
 * @param grouping normalised, on the same terms as [supersetLabels].
 *
 * @return one entry per position of [grouping], in the same order. Members of one group all
 *   carry the same name, which is the point of it.
 */
fun supersetGroupNames(grouping: List<Int?>): List<String?> {
    val names = arrayOfNulls<String>(grouping.size)

    Supersets.groups(grouping).forEach { (group, indices) ->
        val name = groupName(group)
        indices.forEach { index -> names[index] = name }
    }

    return names.toList()
}

/**
 * What one group is called.
 *
 * Groups past the twenty-sixth fall back to their number. Nothing can produce a session with
 * twenty-seven supersets in it, but a letter that silently became `[` is worse than a digit.
 */
private fun groupName(group: Int): String =
    if (group < ALPHABET_SIZE) ('A' + group).toString() else (group + 1).toString()

private const val ALPHABET_SIZE = 26
