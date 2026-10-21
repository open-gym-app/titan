package com.titan.core.data.db

import android.content.Context
import android.content.res.Configuration
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.titan.core.data.R
import com.titan.core.data.mapper.TemplateExerciseMapper
import com.titan.core.data.mapper.TemplateMapper
import com.titan.core.data.repository.DefaultTemplateCatalogueImpl
import com.titan.core.data.repository.TemplateRepositoryImpl
import com.titan.core.domain.model.DifficultyLevel
import com.titan.core.domain.model.Discipline
import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.MechanicType
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.TemplateCategory
import com.titan.core.domain.model.TemplateExercise
import com.titan.core.domain.superset.Supersets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

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

    private lateinit var db: TitanDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TitanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(TitanDatabase.foreignKeysCallback)
            .addCallback(TitanDatabase.singleRowProgressCallback)
            .addCallback(TitanDatabase.seedCallback(context))
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
    fun `seed inserts the nine system tags and marks them system`() =
        runBlocking {
            val systemTags = db.tagDao().getSystemTags().first().map { it.name }.toSet()
            assertEquals(
                // DYNAMIC arrived with the warm-up suggestion (#214): it is what tells a leg
                // swing from a seated hamstring stretch, and the generator derives it from the
                // upstream `category` and `force` fields. SPEED and SWIM arrived with the timed
                // records (#225): how a cardio movement reads its best pace.
                setOf("PUSH", "PULL", "STATIC", "CARRY", "COMPOUND", "ISOLATION", "DYNAMIC", "SPEED", "SWIM"),
                systemTags,
            )
        }

    @Test
    fun `the speed tag marks the rides, the rows and the skate, and nothing that is not cardio`() =
        runBlocking {
            val tagged = distinctValues(
                """
                SELECT e.name FROM exercise e
                INNER JOIN exercise_tag et ON et.exercise_id = e.id
                INNER JOIN tag t ON t.id = et.tag_id
                WHERE t.name = 'SPEED'
                """,
            )
            assertEquals(
                setOf("Bicycling", "Bicycling, Stationary", "Recumbent Bike", "Rowing, Stationary", "Skating"),
                tagged,
            )
            // A run is the untagged case, and reads a pace per kilometre.
            assertEquals(
                0,
                count(
                    """
                    SELECT COUNT(*) FROM exercise_tag et
                    INNER JOIN exercise e ON e.id = et.exercise_id
                    INNER JOIN tag t ON t.id = et.tag_id
                    WHERE t.name IN ('SPEED', 'SWIM') AND e.name = 'Running, Treadmill'
                    """,
                ),
            )
        }

    @Test
    fun `the dynamic tag marks movements a warm-up can be built from`() =
        runBlocking {
            // Every tagged exercise is stretching or plyometrics, so nothing a warm-up card
            // could suggest is a bench press — and there is more than one of them to pick from.
            val disciplines = distinctValues(
                """
                SELECT DISTINCT e.discipline FROM exercise e
                INNER JOIN exercise_tag et ON et.exercise_id = e.id
                INNER JOIN tag t ON t.id = et.tag_id
                WHERE t.name = 'DYNAMIC'
                """,
            )
            assertEquals(setOf("STRETCHING", "PLYOMETRICS"), disciplines)
            assertTrue(
                "expected a catalogue of dynamic movements to pick from",
                count(
                    """
                    SELECT COUNT(*) FROM exercise_tag et
                    INNER JOIN tag t ON t.id = et.tag_id WHERE t.name = 'DYNAMIC'
                    """,
                ) >= 20,
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
    fun `replaying the seed inserts no duplicate catalogue rows`() {
        // The catalogue only. The default templates are the seed's one step that is not
        // idempotent, by design — see the section below.
        val before = listOf("exercise", "muscle_group", "tag", "exercise_muscle_group", "exercise_tag")
            .associateWith { countRows(it) }

        DatabaseSeed.insertInto(db.openHelper.writableDatabase, context())

        assertEquals(before, before.keys.associateWith { countRows(it) })
    }

    // --- default templates ------------------------------------------------

    @Test
    fun `a fresh database holds every default template, named in the device's language`() =
        runBlocking {
            val stored = templates().getAll().first()

            // Seventeen, pinned, so a default that quietly fails to resolve fails here and not
            // only in the reference check below. #213's table also lists a Run and a Swim; the
            // catalogue has no outdoor run or freestyle swim to build them over, so they are
            // not defined.
            assertEquals(17, DefaultTemplates.all.size)
            assertEquals(
                DefaultTemplates.all.map { context().getString(it.nameRes) }.sorted(),
                stored.map { it.name },
            )
            assertEquals(
                TemplateCategory.HYPERTROPHY,
                stored.single { it.name == "Legs" }.category,
            )
            // `icon` is left for the row's initial — see `TemplateRow`.
            assertTrue(stored.all { it.icon == null })
        }

    @Test
    fun `every exercise a default names resolves to one seeded exercise`() {
        val unresolved = DefaultTemplates.all
            .flatMap { it.exercises }
            .map { it.key }
            .distinct()
            .filter { key ->
                count(
                    """
                    SELECT COUNT(*) FROM exercise
                    WHERE name = '${key.name.replace("'", "''")}' AND equipment_type = '${key.equipmentType.name}'
                    AND machine_brand IS NULL AND is_custom = 0
                    """,
                ) != 1
            }

        assertEquals("every reference should resolve to exactly one row", emptyList<SeedExerciseKey>(), unresolved)
    }

    @Test
    fun `every default is stored as the catalogue would create it`() =
        runBlocking {
            val repository = templates()
            val stored = repository.getAll().first().associateBy { it.name }
            val defaults = DefaultTemplateCatalogueImpl(context(), db.exerciseDao(), Dispatchers.Unconfined).getAll()

            assertEquals(DefaultTemplates.all.size, defaults.size)
            for (default in defaults) {
                val name = default.name.current
                val template = checkNotNull(stored[name]) { "$name was not seeded" }
                assertEquals(default.category, template.category)
                assertEquals(
                    name,
                    default.exercises.map { listOf(it.exerciseId, it.sets, it.supersetGroup) },
                    repository.getExercises(template.id).first()
                        .map { listOf(it.exerciseId, it.sets, it.supersetGroup) },
                )
            }
        }

    @Test
    fun `the defaults plan the sets the issue lays out`() =
        runBlocking {
            // Reps only, and a weight on nothing: a default cannot know the user's loads.
            assertEquals(listOf(4, 3, 3, 3, 3, 3), exercisesOf("Push").map { it.sets.size })
            assertEquals(listOf(8, 10, 10, 15, 12, 10), exercisesOf("Push").map { it.sets.first().reps })
            assertTrue(
                templates().getAllExercises().first().flatMap { it.sets }.all { it.weight == null },
            )

            // Three supersets of two, each resting after the pair.
            assertEquals(listOf(0, 0, 1, 1, 2, 2), exercisesOf("Arms").map { it.supersetGroup })

            // One superset of five, four interval sets each.
            val hiit = exercisesOf("HIIT")
            assertEquals(List(5) { 0 }, hiit.map { it.supersetGroup })
            assertTrue(hiit.all { exercise -> exercise.sets == List(4) { PlannedSet(setType = SetType.INTERVAL) } })

            // A warm-up, five intervals and a steady piece, none of them with a figure planned.
            assertEquals(
                listOf(SetType.WARMUP) + List(5) { SetType.INTERVAL } + SetType.NORMAL,
                exercisesOf("Row").single().sets.map { it.setType },
            )
            assertEquals(
                listOf(PlannedSet(setType = SetType.WARMUP), PlannedSet()),
                exercisesOf("Bike").single().sets,
            )

            // A hold plans no reps.
            assertEquals(List(3) { PlannedSet() }, exercisesOf("Abs").first().sets)
        }

    @Test
    fun `every default's supersets are in the form the template use cases store`() =
        runBlocking {
            for (spec in DefaultTemplates.all) {
                val grouping = spec.exercises.map { it.supersetGroup }
                assertEquals(grouping, Supersets.normalize(grouping))
                assertTrue(Supersets.validate(grouping).reasonOrNull() == null)
            }
        }

    @Test
    fun `seeding a cleared database writes the defaults once, not twice`() =
        runBlocking {
            db.clearAllTables()

            DatabaseSeed.insertInto(db.openHelper.writableDatabase, context())

            assertEquals(DefaultTemplates.all.size, countRows("workout_template"))
        }

    @Test
    fun `the defaults are named in the language the database is created in`() =
        runBlocking {
            db.clearAllTables()
            val french = context().createConfigurationContext(
                Configuration(context().resources.configuration).apply { setLocale(Locale.FRENCH) },
            )

            DatabaseSeed.insertInto(db.openHelper.writableDatabase, french)

            val names = templates().getAll().first().map { it.name }
            assertTrue(names.containsAll(listOf("Jambes", "Pectoraux", "Étirements et mobilité")))
            assertTrue("Legs" !in names)
            assertEquals(french.getString(R.string.default_template_legs), "Jambes")
        }

    @Test
    fun `a custom exercise sharing a seeded name does not capture a default's reference`() =
        runBlocking {
            val seededSquat = exercisesOf("Legs").first().exerciseId
            // The seeded row's name and equipment and no brand — which the unique index lets
            // through, since SQLite treats two NULLs as distinct.
            val custom = db.exerciseDao().insert(
                exercise(name = "Barbell Squat", equipmentType = "BARBELL").copy(isCustom = true),
            )
            db.openHelper.writableDatabase.execSQL("DELETE FROM workout_template")

            DatabaseSeed.insertDefaultTemplates(db.openHelper.writableDatabase, context().resources)

            assertNotEquals(custom, exercisesOf("Legs").first().exerciseId)
            assertEquals(seededSquat, exercisesOf("Legs").first().exerciseId)
        }

    // --- helpers ----------------------------------------------------------

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun templates() =
        TemplateRepositoryImpl(db.workoutTemplateDao(), TemplateMapper(), TemplateExerciseMapper())

    private suspend fun exercisesOf(name: String): List<TemplateExercise> {
        val repository = templates()
        val template = repository.getAll().first().single { it.name == name }
        return repository.getExercises(template.id).first()
    }

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
