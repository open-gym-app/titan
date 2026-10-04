package com.titan.core.domain.model

import com.titan.core.domain.timer.RestTimerSnapshot
import com.titan.core.domain.timer.StopwatchSnapshot
import kotlinx.serialization.descriptors.SerialDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import kotlin.time.Duration.Companion.seconds

/**
 * The document a session that has only just started encodes to, written out in full.
 *
 * Not a round trip, on purpose: every other test here would still pass if a field were
 * renamed on both sides at once, and this one would not. It is the format's spelling,
 * and changing it means bumping [ActiveSessionFormat.VERSION].
 */
private const val GOLDEN_DOCUMENT =
    """{"version":1,"session":{"sessionId":1,"startedAt":1700000000000,"pausedDuration":"PT0S",""" +
        """"pausedAt":null,"exercises":[],"currentExerciseIndex":0,"restTimer":{"type":"idle"},""" +
        """"stopwatch":null}}"""

/**
 * The same, for a draft with every field populated — which is the one that pins the
 * *nested* spellings.
 *
 * [GOLDEN_DOCUMENT] cannot: its `exercises` is empty, so renaming a field of
 * [ActiveExercise], [ActiveSet] or [com.titan.core.domain.timer.RestTimerSnapshot],
 * or changing one of the timer's `@SerialName` discriminators, changes the stored format
 * without moving a character of it. The parity tests do not catch that either — they
 * compare declared field names against serial names, and a rename moves both at once.
 * This constant is the only thing standing between a rename and every draft on every
 * device in the field decoding to nothing.
 */
private const val GOLDEN_MAXIMAL_DOCUMENT =
    """{"version":1,"session":{"sessionId":7,"startedAt":1700000000000,"pausedDuration":"PT1M35S",""" +
        """"pausedAt":1700000600000,"exercises":[{"exerciseId":12,"sessionExerciseId":34,""" +
        """"supersetGroup":0,"notes":"belt on from set 3","sets":[{"id":56,"reps":5,"weight":102500000,"duration":45,""" +
        """"distance":20000,"heartRateAvg":132,"heartRateMax":168,"restAfter":90,"rpe":85,""" +
        """"setType":"DROP_SET","isCompleted":true,"completedAt":1700000120000,"notes":"last one moved slow"},""" +
        """{"id":null,"reps":8,"weight":null,"duration":null,"distance":null,"heartRateAvg":null,""" +
        """"heartRateMax":null,"restAfter":null,"rpe":null,"setType":"NORMAL","isCompleted":false,""" +
        """"completedAt":null,"notes":null}]},{"exerciseId":13,"sessionExerciseId":35,""" +
        """"supersetGroup":0,"notes":null,""" +
        """"sets":[]}],"currentExerciseIndex":1,"restTimer":{"type":"running","deadline":1700000700000,""" +
        """"total":"PT1M30S"},"stopwatch":{"sessionExerciseId":35,"startedAt":1700000500000}}}"""

/**
 * [GOLDEN_MAXIMAL_DOCUMENT] as it was written before the hold stopwatch joined the draft
 * (#M7-17). Adding a field with a default needs no [ActiveSessionFormat.VERSION] bump only if a
 * document without it still decodes — which is what this pins.
 */
private const val PRE_STOPWATCH_DOCUMENT =
    """{"version":1,"session":{"sessionId":7,"startedAt":1700000000000,"pausedDuration":"PT1M35S",""" +
        """"pausedAt":1700000600000,"exercises":[{"exerciseId":12,"sessionExerciseId":34,""" +
        """"supersetGroup":0,"notes":"belt on from set 3","sets":[{"id":56,"reps":5,"weight":102500000,"duration":45,""" +
        """"distance":20000,"heartRateAvg":132,"heartRateMax":168,"restAfter":90,"rpe":85,""" +
        """"setType":"DROP_SET","isCompleted":true,"completedAt":1700000120000,"notes":"last one moved slow"},""" +
        """{"id":null,"reps":8,"weight":null,"duration":null,"distance":null,"heartRateAvg":null,""" +
        """"heartRateMax":null,"restAfter":null,"rpe":null,"setType":"NORMAL","isCompleted":false,""" +
        """"completedAt":null,"notes":null}]},{"exerciseId":13,"sessionExerciseId":35,""" +
        """"supersetGroup":0,"notes":null,""" +
        """"sets":[]}],"currentExerciseIndex":1,"restTimer":{"type":"running","deadline":1700000700000,""" +
        """"total":"PT1M30S"}}}"""

/**
 * The draft can be written down and read back as the same thing.
 *
 * The round trip is asserted on a draft with *every* field populated and no field on
 * its default ([maximalSession]), because a round trip over a sparse value passes just
 * as happily when a field is dropped — the null it decodes to is the null it started
 * with. The parity tests go further and compare the encoded shape against the classes'
 * declared fields by reflection, so a field that stops being serialized fails here even
 * if no test ever reads it.
 *
 * The model's own behaviour is in [ActiveSessionTest], the deadline semantics in
 * [com.titan.core.domain.timer.RestTimerSnapshotTest].
 */
class ActiveSessionSerializationTest {

    @Test
    fun `a fully populated draft survives a round trip unchanged`() {
        val session = maximalSession()

        val restored = ActiveSessionFormat.decode(ActiveSessionFormat.encode(session))

        assertEquals(session, restored)
    }

    @Test
    fun `a draft on every default survives a round trip unchanged`() {
        val session = minimalSession()

        val restored = ActiveSessionFormat.decode(ActiveSessionFormat.encode(session))

        assertEquals(session, restored)
    }

    @Test
    fun `every declared field of the draft is serialized`() {
        assertFieldParity(ActiveSession::class.java, ActiveSession.serializer().descriptor)
    }

    @Test
    fun `every declared field of an exercise is serialized`() {
        assertFieldParity(ActiveExercise::class.java, ActiveExercise.serializer().descriptor)
    }

    @Test
    fun `every declared field of a set is serialized`() {
        assertFieldParity(ActiveSet::class.java, ActiveSet.serializer().descriptor)
    }

    @Test
    fun `every declared field of a persisted rest timer is serialized`() {
        assertFieldParity(
            RestTimerSnapshot.Running::class.java,
            RestTimerSnapshot.Running.serializer().descriptor,
        )
        assertFieldParity(
            RestTimerSnapshot.Paused::class.java,
            RestTimerSnapshot.Paused.serializer().descriptor,
        )
    }

    @Test
    fun `each rest timer case round trips to its own case`() {
        val snapshots = listOf(
            RestTimerSnapshot.Idle,
            RestTimerSnapshot.Running(deadline = STARTED_AT + 90_000, total = 90.seconds),
            RestTimerSnapshot.Paused(remaining = 45.seconds, total = 90.seconds),
            RestTimerSnapshot.Finished,
        )

        val restored = snapshots.map { snapshot ->
            val session = minimalSession().copy(restTimer = snapshot)
            ActiveSessionFormat.decode(ActiveSessionFormat.encode(session))?.restTimer
        }

        assertEquals(snapshots, restored)
    }

    @Test
    fun `every set type round trips to itself`() {
        val restored = SetType.entries.map { type ->
            val session = minimalSession().copy(
                exercises = listOf(ActiveExercise(exerciseId = 1, sets = listOf(ActiveSet(setType = type)))),
            )
            ActiveSessionFormat.decode(ActiveSessionFormat.encode(session))
                ?.exercises?.single()?.sets?.single()?.setType
        }

        assertEquals(SetType.entries.toList(), restored)
    }

    @Test
    fun `exercise and set order survives the round trip`() {
        val session = maximalSession()

        val restored = ActiveSessionFormat.decode(ActiveSessionFormat.encode(session))

        assertEquals(
            session.exercises.map { it.exerciseId },
            restored?.exercises?.map { it.exerciseId },
        )
        assertEquals(session.exercises[0].sets, restored?.exercises?.get(0)?.sets)
    }

    @Test
    fun `the document carries the format version outside the session`() {
        val document = ActiveSessionFormat.encode(maximalSession())

        assertTrue(
            "expected a version field at the top level, was $document",
            document.startsWith("""{"version":${ActiveSessionFormat.VERSION},"session":{"""),
        )
    }

    @Test
    fun `a draft written at another format version is not read`() {
        val document = ActiveSessionFormat.encode(maximalSession())
            .replace(""""version":${ActiveSessionFormat.VERSION}""", """"version":${ActiveSessionFormat.VERSION + 1}""")

        assertNull(ActiveSessionFormat.decode(document))
    }

    @Test
    fun `malformed json decodes to no draft rather than throwing`() {
        assertNull(ActiveSessionFormat.decode(""))
        assertNull(ActiveSessionFormat.decode("{"))
        assertNull(ActiveSessionFormat.decode("""{"version":1}"""))
        assertNull(ActiveSessionFormat.decode("not json at all"))
    }

    @Test
    fun `a draft whose values break an invariant decodes to no draft`() {
        val document = ActiveSessionFormat.encode(maximalSession())
            .replace(""""currentExerciseIndex":1""", """"currentExerciseIndex":9""")

        assertNull(ActiveSessionFormat.decode(document))
    }

    @Test
    fun `an unknown field does not make a draft unreadable`() {
        val document = ActiveSessionFormat.encode(minimalSession())
            .replace(""""sessionId":1""", """"sessionId":1,"favouriteColour":"blue"""")

        assertEquals(minimalSession(), ActiveSessionFormat.decode(document))
    }

    @Test
    fun `defaults are written rather than omitted`() {
        val document = ActiveSessionFormat.encode(minimalSession())

        assertTrue("""expected pausedAt in $document""", document.contains(""""pausedAt":null"""))
        assertTrue("""expected exercises in $document""", document.contains(""""exercises":[]"""))
        assertTrue("""expected restTimer in $document""", document.contains(""""restTimer""""))
    }

    @Test
    fun `the document is the shape this contract promised`() {
        assertEquals(GOLDEN_DOCUMENT, ActiveSessionFormat.encode(minimalSession()))
    }

    @Test
    fun `a populated document spells every nested field the way it always has`() {
        assertEquals(GOLDEN_MAXIMAL_DOCUMENT, ActiveSessionFormat.encode(maximalSession()))
    }

    @Test
    fun `a draft written before the stopwatch existed decodes with none running`() {
        assertEquals(maximalSession().copy(stopwatch = null), ActiveSessionFormat.decode(PRE_STOPWATCH_DOCUMENT))
    }

    @Test
    fun `every declared field of a persisted stopwatch is serialized`() {
        assertFieldParity(StopwatchSnapshot::class.java, StopwatchSnapshot.serializer().descriptor)
    }

    @Test
    fun `a document written against the pinned spelling is still readable`() {
        assertEquals(maximalSession(), ActiveSessionFormat.decode(GOLDEN_MAXIMAL_DOCUMENT))
    }

    @Test
    fun `a foreign version is refused even when this build cannot parse the session it carries`() {
        val document = """{"version":${ActiveSessionFormat.VERSION + 1},"session":{"shape":"from another build"}}"""

        assertNull(ActiveSessionFormat.decode(document))
    }

    @Test
    fun `the encoded draft is stable across encodings`() {
        val session = maximalSession()

        assertEquals(ActiveSessionFormat.encode(session), ActiveSessionFormat.encode(session))
    }

    /**
     * Asserts the serialized shape of [type] names exactly the fields the class
     * declares — no `@Transient` quietly dropping one, no field the encoder invents.
     *
     * Java reflection rather than `kotlin-reflect`: the backing fields of a data class
     * are its primary-constructor properties, computed properties have none, and the
     * module does not otherwise need the dependency.
     */
    private fun assertFieldParity(
        type: Class<*>,
        descriptor: SerialDescriptor,
    ) {
        val declared = type.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .sorted()
        val serialized = (0 until descriptor.elementsCount).map(descriptor::getElementName).sorted()

        assertEquals("${type.simpleName} does not serialize every field it declares", declared, serialized)
    }
}
