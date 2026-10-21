package com.titan.core.domain.superset

import com.titan.core.domain.validation.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The superset rules, over the grouping alone.
 *
 * Every case here is written as the list of group ids in position order, which is
 * what [Supersets] takes — a session's rows, a template's, and an in-progress draft
 * all reduce to that, so testing the four separately would be testing `map` three
 * extra times.
 */
class SupersetsTest {

    private fun reasonOf(grouping: List<Int?>): String =
        (Supersets.validate(grouping) as ValidationResult.Invalid).reason

    private fun failureOf(result: Result<List<Int?>>): String =
        requireNotNull(result.exceptionOrNull()).message.orEmpty()

    // ---- forming ----------------------------------------------------------

    @Test
    fun `forming a group makes the chosen exercises one superset`() {
        val formed = Supersets.form(listOf(null, null, null), setOf(1, 2)).getOrThrow()

        assertEquals(listOf(null, 0, 0), formed)
    }

    @Test
    fun `a formed group takes the next ordinal after the groups above it`() {
        // Numbered by position, not by when it was formed: the first superset in the
        // list is always 0, which is what lets a screen label them A, B, C downwards.
        val formed = Supersets.form(listOf(0, 0, null, null), setOf(2, 3)).getOrThrow()

        assertEquals(listOf(0, 0, 1, 1), formed)
    }

    @Test
    fun `forming above an existing group renumbers it downwards`() {
        val formed = Supersets.form(listOf(null, null, 0, 0), setOf(0, 1)).getOrThrow()

        assertEquals(listOf(0, 0, 1, 1), formed)
    }

    @Test
    fun `three exercises can be one superset`() {
        val formed = Supersets.form(listOf(null, null, null), setOf(0, 1, 2)).getOrThrow()

        assertEquals(listOf(0, 0, 0), formed)
    }

    @Test
    fun `forming over part of an existing group dissolves what it leaves behind`() {
        // The pair at 1-2 loses one member to the new group; one exercise is not a
        // superset, so what is left goes back to standing alone rather than staying a
        // group of one.
        val formed = Supersets.form(listOf(null, 0, 0, null), setOf(0, 1)).getOrThrow()

        assertEquals(listOf(0, 0, null, null), formed)
    }

    @Test
    fun `a single exercise cannot be a superset`() {
        val result = Supersets.form(listOf(null, null), setOf(0))

        assertTrue(result.isFailure)
        assertTrue(failureOf(result).contains("at least ${Supersets.MIN_MEMBERS}"))
    }

    @Test
    fun `forming rejects a non-contiguous selection`() {
        val result = Supersets.form(listOf(null, null, null), setOf(0, 2))

        assertTrue(result.isFailure)
        assertTrue(failureOf(result).contains("next to each other"))
    }

    @Test
    fun `forming rejects a selection that would split an existing group`() {
        // Grouping 1 and 2 out of the four-strong group at 0-3 would leave 0 and 3 in
        // one superset with two exercises between them, which is not a state the rest
        // of the rules can express.
        val result = Supersets.form(listOf(0, 0, 0, 0), setOf(1, 2))

        assertTrue(result.isFailure)
        assertTrue(failureOf(result).contains("next to each other"))
    }

    @Test
    fun `forming rejects a position the list does not have`() {
        val result = Supersets.form(listOf(null, null), setOf(1, 2))

        assertTrue(result.isFailure)
        assertTrue(failureOf(result).contains("no exercise at position 2"))
    }

    @Test
    fun `forming leaves the list it was given alone`() {
        val original = listOf<Int?>(null, null, null)

        Supersets.form(original, setOf(0, 1)).getOrThrow()

        assertEquals(listOf(null, null, null), original)
    }

    // ---- joining a pair ---------------------------------------------------

    @Test
    fun `joining two exercises that stand alone makes them a superset`() {
        val joined = Supersets.join(listOf(null, null, null), index = 1).getOrThrow()

        assertEquals(listOf(null, 0, 0), joined)
    }

    @Test
    fun `joining the last member of a group to the next exercise extends the group`() {
        // The whole point of the pairwise form: the exercise at 1 comes with the group it
        // is in, so a third exercise joins the superset instead of tearing 0 out of it.
        val joined = Supersets.join(listOf(0, 0, null), index = 1).getOrThrow()

        assertEquals(listOf(0, 0, 0), joined)
    }

    @Test
    fun `joining across two groups merges them`() {
        val joined = Supersets.join(listOf(0, 0, 1, 1), index = 1).getOrThrow()

        assertEquals(listOf(0, 0, 0, 0), joined)
    }

    @Test
    fun `joining a standalone exercise to the group below it extends that group`() {
        val joined = Supersets.join(listOf(null, 0, 0), index = 0).getOrThrow()

        assertEquals(listOf(0, 0, 0), joined)
    }

    @Test
    fun `joining two exercises already performed back to back changes nothing`() {
        val joined = Supersets.join(listOf(0, 0), index = 0).getOrThrow()

        assertEquals(listOf(0, 0), joined)
    }

    @Test
    fun `the last exercise has nothing to join`() {
        val result = Supersets.join(listOf(null, null), index = 1)

        assertTrue(result.isFailure)
        assertTrue(failureOf(result).contains("nothing to join"))
    }

    @Test
    fun `joining leaves the list it was given alone`() {
        val original = listOf<Int?>(null, null)

        Supersets.join(original, index = 0).getOrThrow()

        assertEquals(listOf(null, null), original)
    }

    // ---- splitting a link -------------------------------------------------

    @Test
    fun `splitting a pair leaves both exercises standing alone`() {
        val split = Supersets.split(listOf(0, 0), index = 0)

        assertEquals(listOf(null, null), split)
    }

    @Test
    fun `splitting a three-member group leaves a pair and a single`() {
        // The link is what was tapped, not the group: 0 and 1 are still performed back to
        // back, and only 2 comes out.
        val split = Supersets.split(listOf(0, 0, 0), index = 1)

        assertEquals(listOf(0, 0, null), split)
    }

    @Test
    fun `splitting the head of a three-member group leaves the pair below it intact`() {
        val split = Supersets.split(listOf(0, 0, 0), index = 0)

        assertEquals(listOf(null, 0, 0), split)
    }

    @Test
    fun `splitting renumbers the groups that survive`() {
        val split = Supersets.split(listOf(0, 0, 1, 1), index = 0)

        assertEquals(listOf(null, null, 0, 0), split)
    }

    @Test
    fun `splitting where there is no link normalises and changes nothing else`() {
        assertEquals(listOf(null, 0, 0), Supersets.split(listOf(null, 0, 0), index = 0))
        assertEquals(listOf(0, 0, null), Supersets.split(listOf(0, 0, null), index = 2))
        assertEquals(listOf(0, 0), Supersets.split(listOf(0, 0), index = 7))
    }

    // ---- dissolving -------------------------------------------------------

    @Test
    fun `a group that drops to one member dissolves`() {
        // What removing one of a pair leaves behind: the survivor still carries the
        // group id, and normalising is what says it is no longer in a superset.
        assertEquals(listOf(null, null), Supersets.normalize(listOf(null, 0)))
    }

    @Test
    fun `dissolving one group renumbers the ones that survive`() {
        // Group 0 is gone, so the pair below it becomes the first superset in the
        // list — otherwise the only superset on screen would be labelled B.
        assertEquals(listOf(null, 0, 0), Supersets.normalize(listOf(0, 1, 1)))
    }

    @Test
    fun `normalizing numbers surviving groups by position`() {
        assertEquals(listOf(0, 0, 1, 1), Supersets.normalize(listOf(4, 4, 2, 2)))
    }

    @Test
    fun `normalizing a grouping already in canonical form changes nothing`() {
        val canonical = listOf(0, 0, null, 1, 1)

        assertEquals(canonical, Supersets.normalize(canonical))
    }

    @Test
    fun `normalizing leaves a non-contiguous group to be refused`() {
        // Repairing this means moving an exercise, which is a decision about the
        // workout — validate says so rather than normalize guessing.
        val split = listOf(0, null, 0)

        assertEquals(split, Supersets.normalize(split))
        assertFalse(Supersets.validate(split).isValid)
    }

    @Test
    fun `a list with no supersets normalizes to itself`() {
        assertEquals(listOf(null, null), Supersets.normalize(listOf(null, null)))
        assertEquals(emptyList<Int?>(), Supersets.normalize(emptyList()))
    }

    // ---- tearing ----------------------------------------------------------

    @Test
    fun `a pair separated by a reorder dissolves`() {
        // What dragging one member of a pair away from the other leaves behind: two runs of
        // one exercise, and one exercise is not a superset.
        assertEquals(listOf(null, null, null), Supersets.tear(listOf(0, null, 0)))
    }

    @Test
    fun `the half of a group still performed back to back survives`() {
        // A three cut after its second member: the first two are still neighbours, the third
        // is on its own.
        assertEquals(listOf(0, 0, null, null), Supersets.tear(listOf(0, 0, null, 0)))
    }

    @Test
    fun `a group that closed up over a departed member stays one group`() {
        // The member that left is elsewhere; the two that remain are neighbours now, so there
        // is no gap to cut and nothing to renumber.
        assertEquals(listOf(null, 0, 0), Supersets.tear(listOf(null, 0, 0)))
    }

    @Test
    fun `what survives a tear is renumbered down the workout`() {
        assertEquals(listOf(0, 0, 1, 1), Supersets.tear(listOf(1, 1, 0, 0)))
    }

    @Test
    fun `a well-formed grouping is left as normalize would leave it`() {
        val grouping = listOf<Int?>(null, 2, 2, null, 5, 5)

        assertEquals(Supersets.normalize(grouping), Supersets.tear(grouping))
    }

    @Test
    fun `tearing a workout with no supersets in it changes nothing`() {
        assertEquals(listOf(null, null, null), Supersets.tear(listOf(null, null, null)))
    }

    @Test
    fun `what tearing produces is always a valid grouping`() {
        // Two groups interleaved beyond repair — the one shape `validate` refuses — comes back
        // as a grouping the rest of the rules can work on.
        assertTrue(Supersets.validate(Supersets.tear(listOf(0, 1, 0, 1, 0))).isValid)
    }

    @Test
    fun `tearing leaves the list it was given alone`() {
        val original = listOf<Int?>(0, null, 0)

        Supersets.tear(original)

        assertEquals(listOf(0, null, 0), original)
    }

    // ---- validating -------------------------------------------------------

    @Test
    fun `a contiguous group of two or more is valid`() {
        assertTrue(Supersets.validate(listOf(null, 0, 0, null, 1, 1, 1)).isValid)
    }

    @Test
    fun `a list with no supersets is valid`() {
        assertTrue(Supersets.validate(listOf(null, null, null)).isValid)
        assertTrue(Supersets.validate(emptyList()).isValid)
    }

    @Test
    fun `sparse and out-of-order ids are valid`() {
        // Numbering is normalize's business; validate only judges the shape, so a
        // grouping read back from rows written by an older edit is not refused.
        assertTrue(Supersets.validate(listOf(9, 9, null, 3, 3)).isValid)
    }

    @Test
    fun `a group of one is refused`() {
        assertTrue(reasonOf(listOf(0, null)).contains("superset 0 has 1 exercise"))
    }

    @Test
    fun `a group split by a non-member is refused`() {
        assertTrue(reasonOf(listOf(0, null, 0)).contains("superset 0 is split"))
    }

    @Test
    fun `two interleaved groups are both refused`() {
        // Every violation in one message, as SetValidator does: a caller repairing a
        // list should see all of what is wrong with it.
        val reason = reasonOf(listOf(0, 1, 0, 1))

        assertTrue(reason.contains("superset 0 is split"))
        assertTrue(reason.contains("superset 1 is split"))
    }

    // ---- membership -------------------------------------------------------

    @Test
    fun `groups report their members in position order`() {
        assertEquals(
            mapOf(0 to listOf(1, 2), 1 to listOf(4, 5)),
            Supersets.groups(listOf(null, 0, 0, null, 1, 1)),
        )
    }

    @Test
    fun `groups are keyed in the order they first appear`() {
        // So a screen can walk the result top to bottom, whatever the ids happen to be.
        assertEquals(listOf(7, 2), Supersets.groups(listOf(7, 7, 2, 2)).keys.toList())
    }

    @Test
    fun `a list with no supersets has no groups`() {
        assertTrue(Supersets.groups(listOf(null, null)).isEmpty())
    }

    // ---- rest attribution -------------------------------------------------

    @Test
    fun `rest is due after a standalone exercise`() {
        assertTrue(Supersets.isRestDueAfter(listOf(null, null), 0))
    }

    @Test
    fun `no rest is due between the members of a superset`() {
        assertFalse(Supersets.isRestDueAfter(listOf(0, 0), 0))
    }

    @Test
    fun `rest is due after the last member of a superset`() {
        // The whole of what a superset changes about pacing: the pair at 0-1 runs back
        // to back, and the rest lands once, after the second.
        val grouping = listOf(0, 0, null)

        assertFalse(Supersets.isRestDueAfter(grouping, 0))
        assertTrue(Supersets.isRestDueAfter(grouping, 1))
        assertTrue(Supersets.isRestDueAfter(grouping, 2))
    }

    @Test
    fun `rest is due after the last member of a superset of three`() {
        val grouping = listOf(0, 0, 0)

        assertEquals(listOf(false, false, true), grouping.indices.map { Supersets.isRestDueAfter(grouping, it) })
    }

    @Test
    fun `rest is due between two supersets that touch`() {
        assertTrue(Supersets.isRestDueAfter(listOf(0, 0, 1, 1), 1))
    }

    @Test
    fun `rest is due after the last exercise of the session`() {
        assertTrue(Supersets.isRestDueAfter(listOf(0, 0), 1))
    }

    @Test
    fun `an index past the end has nothing to run into`() {
        // A session with no exercises still has a legal current index of 0, so this is
        // asked and must answer rather than throw. See ActiveSession.
        assertTrue(Supersets.isRestDueAfter(emptyList(), 0))
        assertTrue(Supersets.isRestDueAfter(listOf(null), 4))
    }

    @Test
    fun `the reason of a valid grouping is null`() {
        assertNull(Supersets.validate(listOf(0, 0)).reasonOrNull())
    }
}
