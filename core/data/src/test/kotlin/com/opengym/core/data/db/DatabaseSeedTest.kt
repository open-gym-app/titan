package com.opengym.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opengym.core.domain.model.DifficultyLevel
import com.opengym.core.domain.model.Discipline
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.MechanicType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the onCreate seed populates the bundled exercise catalogue and the
 * reference data the schema requires.
 *
 * The catalogue is generated (`make seed`), so these assertions are about its
 * *shape* - taxonomy coverage, link invariants, enum validity - rather than its
 * exact contents, which would otherwise churn on every upstream refresh. The
 * spot checks are the exception: they pin a handful of movements that any
 * exercise dataset worth seeding has to contain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DatabaseSeedTest {

    private lateinit var db: OpenGymDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, OpenGymDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(OpenGymDatabase.foreignKeysCallback)
            .addCallback(OpenGymDatabase.singleRowProgressCallback)
            .addCallback(OpenGymDatabase.seedCallback(context.assets))
            .build()
    }

    @After
    fun tearDown() = db.close()

    // --- reference data ---------------------------------------------------

    @Test
    fun `seed inserts the seventeen muscle groups`() =
        runBlocking {
            val names = db.muscleGroupDao().getAll().first().map { it.name }.toSet()
            assertEquals(
                setOf(
                    "ABDOMINALS", "ABDUCTORS", "ADDUCTORS", "BICEPS", "CALVES", "CHEST",
                    "FOREARMS", "GLUTES", "HAMSTRINGS", "LATS", "LOWER_BACK", "MIDDLE_BACK",
                    "NECK", "QUADRICEPS", "SHOULDERS", "TRAPS", "TRICEPS",
                ),
                names,
            )
        }

    @Test
    fun `seed inserts the six system tags and marks them system`() =
        runBlocking {
            val systemTags = db.tagDao().getSystemTags().first().map { it.name }.toSet()
            assertEquals(
                setOf("PUSH", "PULL", "STATIC", "CARRY", "COMPOUND", "ISOLATION"),
                systemTags,
            )
        }

    // --- catalogue --------------------------------------------------------

    @Test
    fun `seed inserts a baseline exercise library`() {
        assertTrue("expected at least 80 exercises", countRows("exercise") >= 80)
    }

    @Test
    fun `every exercise has exactly one primary muscle group`() {
        assertEquals(
            0,
            count(
                """
                SELECT COUNT(*) FROM (
                    SELECT exercise_id FROM exercise_muscle_group
                    GROUP BY exercise_id HAVING SUM(is_primary) != 1
                )
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `every exercise is linked to at least one muscle group`() {
        assertEquals(
            0,
            count(
                """
                SELECT COUNT(*) FROM exercise e WHERE NOT EXISTS (
                    SELECT 1 FROM exercise_muscle_group m WHERE m.exercise_id = e.id
                )
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `every muscle group has at least one exercise`() =
        runBlocking {
            val linked = distinctValues("SELECT DISTINCT muscle_group_id FROM exercise_muscle_group")
            val all = db.muscleGroupDao().getAll().first().map { it.id.toString() }.toSet()
            assertEquals(emptySet<String>(), all - linked)
        }

    @Test
    fun `every equipment type has at least one exercise`() {
        assertEquals(
            emptySet<String>(),
            EquipmentType.entries.map { it.name }.toSet() -
                distinctValues("SELECT DISTINCT equipment_type FROM exercise"),
        )
    }

    @Test
    fun `every discipline has at least one exercise`() {
        assertEquals(
            emptySet<String>(),
            Discipline.entries.map { it.name }.toSet() -
                distinctValues("SELECT DISTINCT discipline FROM exercise"),
        )
    }

    @Test
    fun `discipline survives the fold into exercise_type`() {
        // Five disciplines share ExerciseType.STRENGTH, so the column only earns
        // its place if it still separates them afterwards.
        assertTrue(
            "discipline should distinguish rows that exercise_type flattens",
            count(
                """
                SELECT COUNT(DISTINCT discipline) FROM exercise
                WHERE exercise_type = '${ExerciseType.STRENGTH.name}'
                """.trimIndent(),
            ) > 1,
        )
    }

    @Test
    fun `enum-like columns only hold values the domain layer knows`() {
        assertEquals(
            emptySet<String>(),
            distinctValues("SELECT DISTINCT exercise_type FROM exercise") -
                ExerciseType.entries.map { it.name }.toSet(),
        )
        assertEquals(
            emptySet<String>(),
            distinctValues("SELECT DISTINCT discipline FROM exercise WHERE discipline IS NOT NULL") -
                Discipline.entries.map { it.name }.toSet(),
        )
        assertEquals(
            emptySet<String>(),
            distinctValues("SELECT DISTINCT mechanic_type FROM exercise WHERE mechanic_type IS NOT NULL") -
                MechanicType.entries.map { it.name }.toSet(),
        )
        assertEquals(
            emptySet<String>(),
            distinctValues("SELECT DISTINCT difficulty_level FROM exercise WHERE difficulty_level IS NOT NULL") -
                DifficultyLevel.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `every exercise icon points at a bundled image asset`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bundled = context.assets.list("seed/images").orEmpty().toSet()
        val referenced = distinctValues("SELECT DISTINCT icon FROM exercise WHERE icon IS NOT NULL")
        assertTrue("expected icons to be seeded", referenced.isNotEmpty())
        assertEquals(
            emptySet<String>(),
            referenced.map { it.removePrefix("seed/images/") }.toSet() - bundled,
        )
    }

    // --- spot checks ------------------------------------------------------

    @Test
    fun `known exercises are seeded with their muscle group links`() =
        runBlocking {
            val expected = mapOf(
                "Barbell Squat" to "QUADRICEPS",
                "Barbell Deadlift" to "LOWER_BACK",
                "Barbell Bench Press - Medium Grip" to "CHEST",
                "Pullups" to "LATS",
                "Barbell Curl" to "BICEPS",
            )
            expected.forEach { (name, primaryMuscle) ->
                val exercise = db.exerciseDao().searchByPartialName(name).first()
                    .singleOrNull { it.name == name }
                assertNotNull("$name is missing from the seed", exercise)

                val links = db.exerciseDao().getMuscleGroupLinks(exercise!!.id).first()
                assertTrue("$name has no muscle groups", links.isNotEmpty())
                // getMuscleGroupLinks orders is_primary first.
                val primary = links.first()
                assertEquals("wrong primary muscle for $name", primaryMuscle, primary.muscleGroupName)
                assertTrue("$name's first link should be flagged primary", primary.isPrimary)
            }
        }

    @Test
    fun `barbell squat carries its full attribute set`() =
        runBlocking {
            val squat = db.exerciseDao().searchByPartialName("Barbell Squat").first()
                .single { it.name == "Barbell Squat" }
            assertEquals(ExerciseType.STRENGTH.name, squat.exerciseType)
            assertEquals(Discipline.STRENGTH.name, squat.discipline)
            assertEquals(EquipmentType.BARBELL.name, squat.equipmentType)
            assertEquals(MechanicType.COMPOUND.name, squat.mechanicType)
            assertEquals(DifficultyLevel.BEGINNER.name, squat.difficultyLevel)
            assertTrue("instructions should be seeded", !squat.instructions.isNullOrBlank())
            assertEquals(
                setOf("PUSH", "COMPOUND"),
                db.exerciseDao().getTags(squat.id).first().map { it.name }.toSet(),
            )
        }

    // --- idempotency ------------------------------------------------------

    @Test
    fun `replaying the seed inserts no duplicates`() {
        val before = listOf("exercise", "muscle_group", "tag", "exercise_muscle_group", "exercise_tag")
            .associateWith { countRows(it) }

        val context = ApplicationProvider.getApplicationContext<Context>()
        DatabaseSeed.insertInto(db.openHelper.writableDatabase, context.assets)

        assertEquals(before, before.keys.associateWith { countRows(it) })
    }

    // --- helpers ----------------------------------------------------------

    private fun countRows(table: String): Int = count("SELECT COUNT(*) FROM $table")

    private fun count(query: String): Int =
        db.query(query, emptyArray()).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun distinctValues(query: String): Set<String> =
        db.query(query, emptyArray()).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
}
