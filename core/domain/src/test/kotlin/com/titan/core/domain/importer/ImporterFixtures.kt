package com.titan.core.domain.importer

import com.titan.core.domain.backup.ImportSource
import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.MuscleGroup
import com.titan.core.domain.model.UnitSystem
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * What every parser's tests share: the sample exports under `src/test/resources/importer/`, the
 * contexts a parse runs in, the engine's own way of running a parser, and the catalogue and muscle
 * groups a fresh install has, to plan a parsed history against.
 *
 * One app's samples live in a directory of their own (`importer/strong/`, `importer/hevy/`,
 * `importer/fitnotes/`), so a parser's tests can also offer it another app's export and expect it
 * to be turned away.
 */
object ImporterFixtures {

    /** A user in UTC who chose kilograms: the context most tests want. */
    val METRIC_UTC = ImportContext(zone = ZoneOffset.UTC, unitSystem = UnitSystem.METRIC)

    /** The same user, had they chosen pounds and miles. */
    val IMPERIAL_UTC = ImportContext(zone = ZoneOffset.UTC, unitSystem = UnitSystem.IMPERIAL)

    /** A context in [zone], by its IANA id. */
    fun context(
        zone: String,
        unitSystem: UnitSystem = UnitSystem.METRIC,
    ) = ImportContext(zone = ZoneId.of(zone), unitSystem = unitSystem)

    /** The sample at `importer/[path]` in the test resources, opened afresh on every read. */
    fun resource(path: String): ImportSource {
        val location = "/importer/$path"
        checkNotNull(ImporterFixtures::class.java.getResource(location)) { "no test resource at $location" }
        return ImportSource { checkNotNull(ImporterFixtures::class.java.getResourceAsStream(location)) }
    }

    /** [text] as the UTF-8 bytes of a file, for the cases too small to be worth one. */
    fun text(text: String): ImportSource = bytes(text.toByteArray(Charsets.UTF_8))

    /** [bytes] as a file. */
    fun bytes(bytes: ByteArray): ImportSource = ImportSource { bytes.inputStream() }

    /**
     * What [parser] answers for [source], run as the engine runs it — through [parseSafely],
     * under [MAX_SOURCE_BYTES] — so a parser that throws is seen failing the way a user would see
     * it rather than as an exception in the test.
     */
    fun read(
        parser: HistoryParser,
        source: ImportSource,
        context: ImportContext = METRIC_UTC,
    ): ParseResult =
        when (val outcome = parser.parseSafely(source, context, MAX_SOURCE_BYTES)) {
            is ParseOutcome.Read -> outcome.result
            else -> throw AssertionError("the engine refused the file before the parser answered: $outcome")
        }

    /** The history [parser] reads from [source], failing the test if it reads none. */
    fun parsed(
        parser: HistoryParser,
        source: ImportSource,
        context: ImportContext = METRIC_UTC,
    ): ImportedHistory =
        when (val result = read(parser, source, context)) {
            is ParseResult.Parsed -> result.history
            else -> throw AssertionError("expected a parsed history, got $result")
        }

    /**
     * Every exercise the seed puts on a fresh install, as `exercises.sql` in `:core:data` writes
     * it, with its real id, name, logging type and equipment.
     *
     * Read from the generated SQL itself rather than copied, so a parser's mapping table is
     * tested against the catalogue that ships: a catalogue update that renames or re-equips an
     * exercise a table points at fails that table's test instead of quietly matching nothing.
     */
    val catalogue: List<Exercise> by lazy(::readCatalogue)

    /**
     * Every muscle group the seed puts on a fresh install, read from `exercises.sql` like
     * [catalogue], so a parser's table of muscle names is tested against the names that ship.
     */
    val muscleGroups: List<MuscleGroup> by lazy(::readMuscleGroups)

    /**
     * The device a fresh install is: the seeded catalogue and muscle groups, nothing logged, and
     * [nowMillis].
     */
    fun freshInstall(nowMillis: Long) =
        ImportBaseline(
            exercises = catalogue,
            muscleGroups = muscleGroups,
            sessionStarts = emptySet(),
            measurementTimes = emptySet(),
            templates = emptyList(),
            nowMillis = nowMillis,
        )

    /**
     * The seed, from this module's directory, which is where Gradle and an IDE both run a test
     * from. `core/domain/build.gradle.kts` declares it as the tests' input.
     */
    private val SEED = File("../data/src/main/assets/seed/exercises.sql")

    /** One exercise row's leading columns: `(id, 'name', 'TYPE', 'DISCIPLINE', 'EQUIPMENT'`. */
    private val EXERCISE_ROW = Regex("\\((\\d+), '((?:[^']|'')*)', '([A-Z_]+)', '[A-Z_]+', '([A-Z_]+)'")
    private val DECLARED_COUNT = Regex("-- (\\d+) exercises")

    /** One muscle group row: `(id, 'NAME')`. */
    private val MUSCLE_GROUP_ROW = Regex("\\((\\d+), '([A-Z_]+)'\\)")

    private fun readCatalogue(): List<Exercise> {
        val sql = SEED.readText()
        val rows = sql.lineSequence()
            .filter { it.startsWith("INSERT OR IGNORE INTO exercise (") }
            .flatMap { EXERCISE_ROW.findAll(it) }
            .map { row ->
                val columns = row.groupValues
                Exercise(
                    id = columns[1].toLong(),
                    name = columns[2].replace("''", "'"),
                    type = ExerciseType.valueOf(columns[3]),
                    equipmentType = EquipmentType.valueOf(columns[4]),
                )
            }
            .toList()
        val declared = DECLARED_COUNT.find(sql)?.groupValues?.get(1)?.toInt()
        check(rows.size == declared) { "read ${rows.size} exercises from $SEED, which declares $declared" }
        return rows
    }

    private fun readMuscleGroups(): List<MuscleGroup> {
        val rows = SEED.readText().lineSequence()
            .filter { it.startsWith("INSERT OR IGNORE INTO muscle_group (") }
            .flatMap { MUSCLE_GROUP_ROW.findAll(it) }
            .map { row -> MuscleGroup(id = row.groupValues[1].toLong(), name = row.groupValues[2]) }
            .toList()
        check(rows.isNotEmpty()) { "read no muscle groups from $SEED" }
        return rows
    }
}
