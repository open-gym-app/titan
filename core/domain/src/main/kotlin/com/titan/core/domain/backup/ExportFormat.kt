package com.titan.core.domain.backup

import com.titan.core.domain.backup.model.AchievementRow
import com.titan.core.domain.backup.model.BackupRow
import com.titan.core.domain.backup.model.BackupTable
import com.titan.core.domain.backup.model.BodyMeasurementRow
import com.titan.core.domain.backup.model.CollectedItemRow
import com.titan.core.domain.backup.model.ExerciseMuscleGroupRow
import com.titan.core.domain.backup.model.ExerciseRow
import com.titan.core.domain.backup.model.ExerciseTagRow
import com.titan.core.domain.backup.model.ExportDocument
import com.titan.core.domain.backup.model.GymRow
import com.titan.core.domain.backup.model.ImportError
import com.titan.core.domain.backup.model.MuscleGroupRow
import com.titan.core.domain.backup.model.PersonalRecordRow
import com.titan.core.domain.backup.model.PreferencesPayload
import com.titan.core.domain.backup.model.QuestCompletionRow
import com.titan.core.domain.backup.model.SessionExerciseRow
import com.titan.core.domain.backup.model.SessionLootClaimRow
import com.titan.core.domain.backup.model.SessionXpClaimRow
import com.titan.core.domain.backup.model.TagRow
import com.titan.core.domain.backup.model.TemplateExerciseRow
import com.titan.core.domain.backup.model.TemplateSetRow
import com.titan.core.domain.backup.model.UserProgressRow
import com.titan.core.domain.backup.model.WorkoutSessionRow
import com.titan.core.domain.backup.model.WorkoutSetRow
import com.titan.core.domain.backup.model.WorkoutTemplateRow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonObject
import java.io.IOException

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
 * [decode] streams the document once for `schema_version` alone, with every other key skipped,
 * and only then decodes it against *this* build's model. A future document is therefore refused
 * for the reason it should be — the version — rather than incidentally, by the decoder tripping
 * over a table it does not recognise. Two passes over the bytes rather than one over a parsed
 * tree, because a tree of a 50,000-set history is heap this app does not have; see [decode].
 *
 * The two failures are distinct values here ([ImportError.UnsupportedSchemaVersion] against
 * [ImportError.MalformedJson]) where `ActiveSessionFormat` collapses both to null, and the
 * difference is what the two documents are worth: a draft is disposable and a backup is the
 * only copy, so "written by a newer version" has to be a sentence the user can act on.
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
            is GymRow -> json.encodeToString(GymRow.serializer(), row)
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
     * The document [source] holds, or the reason it was refused.
     *
     * ### Two passes, and no tree
     *
     * [source] is opened twice: once to read [VERSION_KEY] with every other key skipped, and —
     * only if that version is this build's — once more to decode the document itself. Both
     * passes stream, so at no point does the *text* exist as a value; the parser sees 16 KiB of
     * it at a time and the document being built is the only thing that grows.
     *
     * The obvious alternative is one pass into a `JsonElement` tree, read the version off it,
     * then decode the document from the tree. It was that, and the cost is the reason it is not
     * any more: a tree is a `JsonObject` of `JsonArray`s of `JsonObject`s of boxed
     * `JsonPrimitive`s, several times the size of the text it came from, held *at the same
     * time* as the decoded document — and this app commits to a 50,000-set history on a phone
     * that may have 2 GB of RAM. The second pass buys that back for one more sequential read of
     * a file, which is I/O the import was going to do anyway and no heap at all.
     *
     * Reading the version first is not an optimisation and cannot be dropped: a document from a
     * newer build has to be refused *for its version*, which means learning the version before
     * anything is decoded against this build's model. See the class doc.
     *
     * @return [Decoded.Success] with the parsed document, [Decoded.Refused] with
     *   [ImportError.UnsupportedSchemaVersion] when the version is not this build's,
     *   [ImportError.MalformedJson] when the bytes are not a document at all, or
     *   [ImportError.IoFailure] when they could not be read.
     */
    fun decode(source: ImportSource): Decoded =
        try {
            when (val version = source.readVersion()) {
                null -> Decoded.Refused(ImportError.MalformedJson("no `$VERSION_KEY` in the document"))

                ExportDocument.SCHEMA_VERSION -> Decoded.Success(source.readDocument())

                else -> Decoded.Refused(
                    ImportError.UnsupportedSchemaVersion(found = version, supported = ExportDocument.SCHEMA_VERSION),
                )
            }
        } catch (failure: IllegalArgumentException) {
            // Covers every way the text can fail to be a document at once: `SerializationException`
            // *is* an `IllegalArgumentException`, and it is what the decoder throws for JSON that
            // is not an object, a version that is not a number, a missing column and a truncated
            // file alike. All of them mean the same thing to the caller.
            Decoded.Refused(ImportError.MalformedJson(failure.message.orEmpty()))
        } catch (failure: IOException) {
            // The bytes could not be read — a revoked content URI, a removed SD card. Not a
            // malformed document: they never arrived, so nothing can be said about their shape,
            // and the two are different sentences for a screen to show.
            Decoded.Refused(ImportError.IoFailure(failure.message.orEmpty()))
        }

    /**
     * The version this source's document claims, or null if it does not claim one.
     *
     * [VersionEnvelope] declares that one field and nothing else, so `ignoreUnknownKeys` makes
     * the whole rest of the document — every table, every row — a value the lexer walks past
     * without building anything from. The pass costs a read of the file and a few dozen bytes
     * of heap.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun ImportSource.readVersion(): Int? =
        open().use { json.decodeFromStream(VersionEnvelope.serializer(), it) }.schemaVersion

    /** The whole document, streamed off a second opening of the same bytes. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun ImportSource.readDocument(): ExportDocument =
        open().use { json.decodeFromStream(ExportDocument.serializer(), it) }

    /**
     * The document as the version pass sees it: one integer, and permission to ignore the rest.
     *
     * A class rather than a hand-rolled scan of the first few bytes, because the key is not
     * promised to come first. This build's exports put it there and every file the app has ever
     * written begins with it, but a document is a file a user can hand-edit, and a reader that
     * refused a re-serialised backup for holding its keys in another order would be refusing it
     * for nothing. The decoder finds the key wherever it is, at the cost of walking to it.
     */
    @Serializable
    private class VersionEnvelope(@SerialName(VERSION_KEY) val schemaVersion: Int? = null)

    /** What [decode] answers: a document, or the typed reason there is not one. */
    sealed interface Decoded {

        /** The [source][decode] held a document at this build's schema version. */
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
