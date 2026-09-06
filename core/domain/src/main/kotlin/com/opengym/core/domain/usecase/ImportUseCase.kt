package com.opengym.core.domain.usecase

import com.opengym.core.domain.backup.BackupConstraintException
import com.opengym.core.domain.backup.BackupValidator
import com.opengym.core.domain.backup.ExportFormat
import com.opengym.core.domain.backup.model.ExportDocument
import com.opengym.core.domain.backup.model.ImportError
import com.opengym.core.domain.backup.model.ImportProgress
import com.opengym.core.domain.backup.model.PreferencesPayload
import com.opengym.core.domain.backup.toPreferences
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.repository.BackupRepository
import com.opengym.core.domain.repository.InProgressSessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.io.IOException

/**
 * Restores a device from an export document: parse, validate, wipe, insert, then settings.
 *
 * ### Replace, not merge
 *
 * Afterwards the database holds exactly what the document holds. A template the user made
 * yesterday and that is not in the file is gone; an exercise they added is gone unless the file
 * carries it. That is the whole semantics, and it is a choice rather than a limitation — a
 * merge needs a conflict rule per table, and two devices that both started from the same seeded
 * exercise library collide on ids that mean different rows. docs/export-schema.md has the
 * argument in full. Telling the user before the wipe happens is #38's job; this refuses
 * nothing on their behalf.
 *
 * ### The order, and what each step guarantees
 *
 * 1. **Parse and check the version.** [ExportFormat] reads `schema_version` before it
 *    interprets anything else, so a file from a newer build is refused for the reason it should
 *    be rather than by the decoder tripping over a table it does not know.
 * 2. **Validate the document against itself.** Every foreign key, primary key and unique index
 *    in the file, checked while the database is still untouched — possible only because import
 *    replaces, so the file is the whole database and every reference in it must resolve inside
 *    it. This is what makes [ImportError.ConstraintViolation] mean "and nothing happened".
 * 3. **Forget the live session and its draft.** Both name row ids from before the wipe. The
 *    session state goes first because screens are collecting it, then the draft file, exactly
 *    as `LocalDataRepositoryImpl.deleteAll` orders them and for the same reason. Before the
 *    transaction rather than after it, which costs something in one case: a constraint the
 *    validator did not model would roll the rows back and leave the draft cleared anyway. That
 *    is the cheaper mistake — `InProgressSessionRepository` is explicit that losing a draft
 *    costs a place and never a set — where clearing *after* the commit would leave a window in
 *    which a process death left a draft pointing into a database that had been replaced under
 *    it.
 * 4. **Wipe and insert, in one transaction.** [BackupRepository.replaceAll] owns it. A failure
 *    anywhere in it rolls the whole thing back, so there is no state where the database is
 *    empty and the import has given up.
 * 5. **Apply the settings, last.** The unit system changes how every stored weight is *read*,
 *    so applying it after the rows means the app re-themes and re-formats once, at the end,
 *    rather than mid-write.
 *
 * ### Nothing escapes untyped
 *
 * The returned flow carries its own failures as [ImportProgress.Failed] and completes normally.
 * Every refusal is a sentence a screen has to show the user, so handing a ViewModel a
 * `Throwable` to translate would be handing it an easy way to get that wrong. A collector that
 * reaches the end has seen either [ImportProgress.Complete] or [ImportProgress.Failed].
 *
 * ### What is not restored
 *
 * The in-progress session draft — it is not in the document, and step 3 clears whatever the
 * target device held. An interrupted workout is device state rather than history: importing one
 * would drop the user into a live session that never happened on this phone. The rows it
 * overlapped are restored like any others, so nothing logged is lost.
 *
 * @property backupRepository the transaction: the wipe and every insert.
 * @property userPreferencesRepository the settings, restored last.
 * @property inProgressSessionRepository the draft, cleared before the wipe.
 * @property sessionStateRepository the live session state, set to [SessionState.Idle] first.
 */
class ImportUseCase(
    private val backupRepository: BackupRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val inProgressSessionRepository: InProgressSessionRepository,
    private val sessionStateRepository: SessionStateRepository,
) {

    /**
     * Restores the device from [document], the whole text of an export file.
     *
     * Takes a `String` rather than a stream, unlike the export side, and the asymmetry is the
     * validation: whether a `workout_set`'s parent exists cannot be answered until every
     * `session_exercise` in the file has been seen, so the document has to be whole in memory
     * *before* the wipe. Streaming the parse would mean either validating nothing or wiping
     * first and discovering the problem afterwards, and the second is how a restore eats a
     * training history.
     *
     * A `channelFlow` rather than a `flow`, because [BackupRepository.replaceAll] reports its
     * progress from inside the database transaction — a different coroutine context, which a
     * plain flow's `emit` refuses.
     */
    operator fun invoke(document: String): Flow<ImportProgress> =
        channelFlow {
            send(ImportProgress.Validating)
            send(restore(document))
        }

    /** Parses, validates and applies [text], answering with the run's terminal progress. */
    private suspend fun ProducerScope<ImportProgress>.restore(text: String): ImportProgress {
        val parsed = when (val decoded = ExportFormat.decode(text)) {
            is ExportFormat.Decoded.Refused -> return ImportProgress.Failed(decoded.error)
            is ExportFormat.Decoded.Success -> decoded.document
        }
        val violation = BackupValidator.validate(parsed)
        if (violation != null) return ImportProgress.Failed(violation)
        return write(parsed)
    }

    /**
     * Clears the device's live state, replaces every table, and applies the settings.
     *
     * The two `catch`es are the whole of what the layers below are allowed to throw:
     * [BackupConstraintException] for a constraint [BackupValidator] did not model, and
     * [IOException] for a settings write that could not land. Anything else is a programming
     * error and is deliberately left to propagate — a `catch (Throwable)` here would report a
     * null-pointer bug to the user as a corrupt backup file.
     */
    private suspend fun ProducerScope<ImportProgress>.write(document: ExportDocument): ImportProgress {
        val rowsTotal = document.rowCount
        return try {
            sessionStateRepository.update(SessionState.Idle)
            inProgressSessionRepository.clear()
            backupRepository.replaceAll(document) { table, rowsWritten ->
                // `send` rather than `emit`: this runs inside the database transaction, on the
                // dispatcher that owns it, and a plain flow would refuse a value produced there.
                send(ImportProgress.Writing(table = table, rowsWritten = rowsWritten, rowsTotal = rowsTotal))
            }
            restorePreferences(document.preferences)
            ImportProgress.Complete(rowsWritten = rowsTotal)
        } catch (failure: BackupConstraintException) {
            ImportProgress.Failed(ImportError.ConstraintViolation(failure.message.orEmpty()))
        } catch (failure: IOException) {
            ImportProgress.Failed(ImportError.IoFailure(failure.message.orEmpty()))
        }
    }

    /**
     * Puts every setting back, having first reset them all.
     *
     * [UserPreferencesRepository.clear] before the eight setters, rather than the setters
     * alone: replace means replace, and a setting added to the store later and forgotten here
     * would otherwise survive an import as a value from the device being overwritten. Resetting
     * first makes the omission show up as a default rather than as somebody else's setting.
     *
     * A null [PreferencesPayload.plateInventory] is written *as* null, deliberately: it means
     * "the user has never chosen a rack, derive the standard one for their unit system", and
     * skipping the call would leave the target device's rack in place.
     */
    private suspend fun restorePreferences(payload: PreferencesPayload) {
        val preferences = payload.toPreferences()
        userPreferencesRepository.clear()
        userPreferencesRepository.setUnitSystem(preferences.unitSystem)
        userPreferencesRepository.setTheme(preferences.theme)
        userPreferencesRepository.setDefaultRestDuration(preferences.defaultRestDuration)
        userPreferencesRepository.setBarWeight(preferences.barWeight)
        userPreferencesRepository.setPlateInventory(preferences.plateInventory)
        userPreferencesRepository.setTrainingGoal(preferences.trainingGoal)
        userPreferencesRepository.setOneRepMaxFormula(preferences.oneRepMaxFormula)
        userPreferencesRepository.setOnboardingCompleted(preferences.onboardingCompleted)
    }
}
