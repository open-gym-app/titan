package com.opengym.core.domain.backup

import com.opengym.core.domain.backup.model.AchievementRow
import com.opengym.core.domain.backup.model.BackupRow
import com.opengym.core.domain.backup.model.BackupTable
import com.opengym.core.domain.backup.model.BodyMeasurementRow
import com.opengym.core.domain.backup.model.CollectedItemRow
import com.opengym.core.domain.backup.model.ExerciseMuscleGroupRow
import com.opengym.core.domain.backup.model.ExerciseRow
import com.opengym.core.domain.backup.model.ExerciseTagRow
import com.opengym.core.domain.backup.model.ExportDocument
import com.opengym.core.domain.backup.model.ImportError
import com.opengym.core.domain.backup.model.MuscleGroupRow
import com.opengym.core.domain.backup.model.PersonalRecordRow
import com.opengym.core.domain.backup.model.PreferencesPayload
import com.opengym.core.domain.backup.model.QuestCompletionRow
import com.opengym.core.domain.backup.model.SessionExerciseRow
import com.opengym.core.domain.backup.model.SessionLootClaimRow
import com.opengym.core.domain.backup.model.SessionXpClaimRow
import com.opengym.core.domain.backup.model.TagRow
import com.opengym.core.domain.backup.model.TemplateExerciseRow
import com.opengym.core.domain.backup.model.TemplateSetRow
import com.opengym.core.domain.backup.model.UserProgressRow
import com.opengym.core.domain.backup.model.WorkoutSessionRow
import com.opengym.core.domain.backup.model.WorkoutSetRow
import com.opengym.core.domain.backup.model.WorkoutTemplateRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The wire format of an [ExportDocument]: one [Json] configuration, one row encoder, and one
 * decoder that reads the version before it interprets anything else.
 *
 * Defined in `:core:domain` so the layer that stores a backup does not get to invent a format,
 * exactly as `ActiveSessionFormat` is for the in-progress draft. Nothing here knows about
 * files, content URIs or SQLite.
 *
 * ### Reading the version first
 *
 * [decode] parses the document with every field left as raw JSON, checks `schema_version`, and
 * only then decodes against *this* build's model. A future document is therefore refused for
 * the reason it should be — the version — rather than incidentally, by the decoder tripping
 * over a table it does not recognise. The two failures are distinct values here
 * ([ImportError.UnsupportedSchemaVersion] against [ImportError.MalformedJson]) where
 * `ActiveSessionFormat` collapses both to null, and the difference is what the two documents
 * are worth: a draft is disposable and a backup is the only copy, so "written by a newer
 * version" has to be a sentence the user can act on.
 *
 * ### Why `ignoreUnknownKeys` is on for a format this strict
 *
 * It is not a licence to read a newer document — the version check has already refused that.
 * It is tolerance *within* one version, for the case the version rule deliberately allows: a
 * nullable column added to a table does not bump [ExportDocument.SCHEMA_VERSION], so a file
 * written by a slightly newer build of the same version must still read here, and the column
 * this build has never heard of must be skipped rather than fatal.
 */
object ExportFormat {

    /**
     * The configuration every document is written and read with.
     *
     * `encodeDefaults` is on so every field is written even when it holds its default, which is
     * what makes the document's top-level key set constant: nineteen table keys are present in
     * every file, empty arrays included, so `jq 'keys'` on any export gives the same answer and
     * docs/export-schema.md can state the shape without qualifying it. `explicitNulls` is on
     * for the same reason one column further down — a null `machine_brand` is written as
     * `null`, not omitted, so a row in the file has exactly the columns the table has.
     */
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    /** The keys of the envelope: everything in the document that is not one of the tables. */
    private val tableKeys: Set<String> = BackupTable.entries.mapTo(mutableSetOf()) { it.tableName }

    /**
     * [row] as a JSON object, encoded against its own concrete serializer.
     *
     * The `when` is the price of not marking [BackupRow] `@Serializable`: a polymorphic
     * serializer would add a `type` discriminator to every row, in a format whose claim is that
     * a row has exactly the columns its table has. It is exhaustive over a sealed interface, so
     * a row type added without a branch here does not compile.
     */
    fun encodeRow(row: BackupRow): String =
        when (row) {
            is MuscleGroupRow -> json.encodeToString(MuscleGroupRow.serializer(), row)
            is TagRow -> json.encodeToString(TagRow.serializer(), row)
            is ExerciseRow -> json.encodeToString(ExerciseRow.serializer(), row)
            is ExerciseMuscleGroupRow -> json.encodeToString(ExerciseMuscleGroupRow.serializer(), row)
            is ExerciseTagRow -> json.encodeToString(ExerciseTagRow.serializer(), row)
            is WorkoutTemplateRow -> json.encodeToString(WorkoutTemplateRow.serializer(), row)
            is TemplateExerciseRow -> json.encodeToString(TemplateExerciseRow.serializer(), row)
            is TemplateSetRow -> json.encodeToString(TemplateSetRow.serializer(), row)
            is WorkoutSessionRow -> json.encodeToString(WorkoutSessionRow.serializer(), row)
            is SessionExerciseRow -> json.encodeToString(SessionExerciseRow.serializer(), row)
            is WorkoutSetRow -> json.encodeToString(WorkoutSetRow.serializer(), row)
            is PersonalRecordRow -> json.encodeToString(PersonalRecordRow.serializer(), row)
            is BodyMeasurementRow -> json.encodeToString(BodyMeasurementRow.serializer(), row)
            is SessionXpClaimRow -> json.encodeToString(SessionXpClaimRow.serializer(), row)
            is SessionLootClaimRow -> json.encodeToString(SessionLootClaimRow.serializer(), row)
            is UserProgressRow -> json.encodeToString(UserProgressRow.serializer(), row)
            is AchievementRow -> json.encodeToString(AchievementRow.serializer(), row)
            is QuestCompletionRow -> json.encodeToString(QuestCompletionRow.serializer(), row)
            is CollectedItemRow -> json.encodeToString(CollectedItemRow.serializer(), row)
        }

    /**
     * The document's envelope — everything before the first table — as an *unclosed* JSON
     * object, ready for [ExportWriter] to append table arrays to.
     *
     * Built by encoding a real, table-less [ExportDocument] and dropping its table keys, rather
     * than by writing `{"schema_version":` as a literal. The envelope's key names and the
     * document's are then the same strings by construction, so renaming a field in
     * [ExportDocument] cannot leave a streamed export that the document's own decoder rejects.
     * `ExportFormatTest` pins that agreement anyway, from the other direction.
     */
    internal fun encodeEnvelope(
        exportedAt: Long,
        appVersion: String,
        preferences: PreferencesPayload,
    ): String {
        val skeleton = ExportDocument(
            exportedAt = exportedAt,
            appVersion = appVersion,
            preferences = preferences,
        )
        val encoded = json.encodeToJsonElement(ExportDocument.serializer(), skeleton).jsonObject
        val envelope = JsonObject(encoded.filterKeys { it !in tableKeys })
        return json.encodeToString(JsonObject.serializer(), envelope).removeSuffix("}")
    }

    /**
     * The document [text] holds, or the reason it was refused.
     *
     * @return [Decoded.Success] with the parsed document, [Decoded.Refused] with
     *   [ImportError.UnsupportedSchemaVersion] when the version is not this build's, or
     *   [ImportError.MalformedJson] when the text is not a document at all.
     */
    fun decode(text: String): Decoded =
        try {
            // Parsed once into a tree, then interpreted from the tree, rather than parsed twice
            // — the version has to be read before the document is decoded against this build's
            // model, and re-reading tens of megabytes of JSON to learn one integer is not a
            // price worth paying for one fewer local.
            val element = json.parseToJsonElement(text)
            when (val version = element.jsonObject[VERSION_KEY]?.jsonPrimitive?.int) {
                null -> Decoded.Refused(ImportError.MalformedJson("no `$VERSION_KEY` in the document"))

                ExportDocument.SCHEMA_VERSION ->
                    Decoded.Success(json.decodeFromJsonElement(ExportDocument.serializer(), element))

                else -> Decoded.Refused(
                    ImportError.UnsupportedSchemaVersion(found = version, supported = ExportDocument.SCHEMA_VERSION),
                )
            }
        } catch (failure: IllegalArgumentException) {
            // Covers every way this can fail at once: `SerializationException` *is* an
            // `IllegalArgumentException`, and so is what `jsonObject`/`jsonPrimitive` throw
            // when the text is JSON but not an object, or holds a version that is not a
            // number. All of them mean the same thing to the caller — this is not a document.
            Decoded.Refused(ImportError.MalformedJson(failure.message.orEmpty()))
        }

    /** What [decode] answers: a document, or the typed reason there is not one. */
    sealed interface Decoded {

        /** [text][decode] held a document at this build's schema version. */
        data class Success(val document: ExportDocument) : Decoded

        /** It did not, and this is why. */
        data class Refused(val error: ImportError) : Decoded
    }

    /**
     * The envelope key the version is read from, before anything else in the document is
     * interpreted. Pinned as a constant rather than spelled inline because it is the one key
     * whose name is load-bearing across versions: every future reader has to find it in a
     * document written by a build it knows nothing else about.
     */
    private const val VERSION_KEY = "schema_version"
}
