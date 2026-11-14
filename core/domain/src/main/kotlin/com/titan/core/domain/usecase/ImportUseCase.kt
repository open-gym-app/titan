package com.titan.core.domain.usecase

import com.titan.core.domain.backup.BackupConstraintException
import com.titan.core.domain.backup.BackupLock
import com.titan.core.domain.backup.BackupValidator
import com.titan.core.domain.backup.ExportFormat
import com.titan.core.domain.backup.ImportSource
import com.titan.core.domain.backup.model.ExportDocument
import com.titan.core.domain.backup.model.ImportError
import com.titan.core.domain.backup.model.ImportProgress
import com.titan.core.domain.backup.model.PreferencesPayload
import com.titan.core.domain.backup.toPreferences
import com.titan.core.domain.model.SessionState
import com.titan.core.domain.notification.ReminderScheduler
import com.titan.core.domain.repository.BackupRepository
import com.titan.core.domain.repository.InProgressSessionRepository
import com.titan.core.domain.repository.SessionStateRepository
import com.titan.core.domain.repository.TemplateDraftRepository
import com.titan.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
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
 * argument in full. Telling the user before the wipe happens is the backup screen's
 * confirmation; this refuses nothing on their behalf.
 *
 * ### The order, and what each step guarantees
 *
 * 1. **Parse and check the version.** [ExportFormat] streams the file for `schema_version`
 *    before it interprets anything else, so a file from a newer build is refused for the reason
 *    it should be rather than by the decoder tripping over a table it does not know. It reads
 *    no more than [ExportFormat.MAX_DOCUMENT_BYTES].
 * 2. **Validate the document against itself.** Every foreign key, primary key and unique index
 *    in the file, the ranges the format defines for ids, timestamps and set numbering, and the
 *    single session in progress, checked while the database is still untouched — possible only
 *    because import replaces, so the file is the whole database and every reference in it must
 *    resolve inside it. This is what makes [ImportError.ConstraintViolation] mean "and nothing
 *    happened".
 * 3. **Take the two locks.** [BackupLock] first, so no export, weekly backup or delete-all runs
 *    between here and the settings; then [SessionLifecycleLock], so no session starts, finishes
 *    or resumes between the session state being reset and the rows it named being replaced.
 *    Parsing and validating happen before either, so a large file does not hold up a backup
 *    while it is being read.
 * 4. **Forget the live session and the two drafts.** All three name row ids from before the
 *    wipe — the workout in progress, and the template somebody was halfway through writing. The
 *    session state goes first because screens are collecting it, then the draft files, exactly
 *    as `LocalDataRepositoryImpl.deleteAll` orders them and for the same reason. Before the
 *    transaction rather than after it, which costs something in one case: a constraint the
 *    validator did not model would roll the rows back and leave the drafts cleared anyway. That
 *    is the cheaper mistake — `InProgressSessionRepository` is explicit that losing a draft
 *    costs a place and never a set — where clearing *after* the commit would leave a window in
 *    which a process death left a draft pointing into a database that had been replaced under
 *    it. The files cannot join the transaction, so one of the two windows has to exist, and
 *    this is the one that loses nothing logged. A draft store that cannot be cleared stops the
 *    restore here, as [ImportError.LiveStateNotCleared].
 * 5. **Wipe and insert, in one transaction.** [BackupRepository.replaceAll] owns it. A failure
 *    anywhere in it rolls the whole thing back, so there is no state where the database is
 *    empty and the import has given up.
 * 6. **Apply the settings, last, and bring the reminder alarms into line.** The unit system
 *    changes how every stored weight is *read*, so applying it after the rows means the app
 *    re-themes and re-formats once, at the end, rather than mid-write. Applying them starts
 *    from [UserPreferencesRepository.clear], which turns the workout reminder off — the
 *    reminder is device state and not in the document — so [ReminderScheduler.sync] follows,
 *    or the phone would keep buzzing for a reminder its settings report as off — and a sync
 *    that fails on its own is [ImportError.RemindersNotRescheduled], not a settings failure.
 *    This step is not cancellable: the rows are already committed, and a restore abandoned between them and
 *    the settings is one nobody would be told about.
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
 * Neither draft — neither is in the document, and step 4 clears whatever the target device
 * held. An interrupted workout and a half-written template are device state rather than
 * history: importing one would drop the user into a live session that never happened on this
 * phone, or into an editor for a template id the file has given a different name to. The rows
 * they overlapped are restored like any others, so nothing logged or saved is lost.
 *
 * @property backupRepository the transaction: the wipe and every insert.
 * @property userPreferencesRepository the settings, restored last.
 * @property inProgressSessionRepository the workout draft, cleared before the wipe.
 * @property templateDraftRepository the template draft, cleared with it.
 * @property sessionStateRepository the live session state, set to [SessionState.Idle] first.
 * @property reminderScheduler the reminder alarms, re-synced once the settings are written.
 * @property backupLock held from the first write to the last, against exports and delete-all.
 * @property sessionLock held across the session reset and the replace, against transitions.
 */
class ImportUseCase(
    private val backupRepository: BackupRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val inProgressSessionRepository: InProgressSessionRepository,
    private val templateDraftRepository: TemplateDraftRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val reminderScheduler: ReminderScheduler,
    private val backupLock: BackupLock = BackupLock.SHARED,
    private val sessionLock: SessionLifecycleLock = SessionLifecycleLock.SHARED,
) {

    /**
     * Restores the device from [document], an export file this will open and read itself.
     *
     * An [ImportSource] rather than the text, for the reason [ImportSource] gives: the file is
     * tens of megabytes at the scale the app commits to, and a `String` of it is twice that in
     * heap, held for as long as the caller holds it — on top of the document being built. The
     * bytes are streamed instead, so the *parse* costs nothing that lasts.
     *
     * What is still held whole is the decoded [ExportDocument], and that asymmetry with the
     * export side is the validation: whether a `workout_set`'s parent exists cannot be answered
     * until every `session_exercise` in the file has been seen, so the document has to be in
     * memory *before* the wipe. Streaming that too would mean either validating nothing or
     * wiping first and discovering the problem afterwards, and the second is how a restore eats
     * a training history.
     *
     * A `channelFlow` rather than a `flow`, because [BackupRepository.replaceAll] reports its
     * progress from inside the database transaction — a different coroutine context, which a
     * plain flow's `emit` refuses.
     */
    operator fun invoke(document: ImportSource): Flow<ImportProgress> =
        channelFlow {
            send(ImportProgress.Validating)
            send(restore(document))
        }

    /** Reads, validates and applies [source], answering with the run's terminal progress. */
    private suspend fun ProducerScope<ImportProgress>.restore(source: ImportSource): ImportProgress {
        val parsed = when (val decoded = ExportFormat.decode(source)) {
            is ExportFormat.Decoded.Refused -> return ImportProgress.Failed(decoded.error)
            is ExportFormat.Decoded.Success -> decoded.document
        }
        val violation = BackupValidator.validate(parsed)
        if (violation != null) return ImportProgress.Failed(violation)
        return backupLock.exclusively {
            replace(parsed) ?: withContext(NonCancellable) { applySettings(parsed) }
        }
    }

    /**
     * Clears the device's live state and replaces every table, answering with the failure that
     * stopped it or **null** once the transaction has committed.
     *
     * The wait for the session lock can be cancelled; what runs under it cannot. A cancelled
     * collector still stops the write — its channel is closed, so the next progress `send`
     * throws inside the transaction and rolls it back — but the commit itself returns through
     * the repository's `withContext`, which would otherwise answer a caller cancelled at that
     * moment with a [kotlinx.coroutines.CancellationException] *after* the rows were in. That
     * is the one place a cancellation could skip the settings, so it is not allowed to land.
     */
    private suspend fun ProducerScope<ImportProgress>.replace(document: ExportDocument): ImportProgress? =
        sessionLock.withTransition {
            withContext(NonCancellable) { forgetLiveState() ?: replaceRows(document) }
        }

    /**
     * Ends the live session and discards both drafts, answering with the failure that stopped
     * it or **null**.
     *
     * Its own [ImportError.LiveStateNotCleared] rather than [ImportError.IoFailure], whose
     * sentence is "nothing was changed": the session state is reset before the drafts are
     * cleared, so a draft store that fails has already watched the live workout end.
     */
    private suspend fun forgetLiveState(): ImportProgress? =
        try {
            sessionStateRepository.update(SessionState.Idle)
            inProgressSessionRepository.clear()
            templateDraftRepository.clear()
            null
        } catch (failure: IOException) {
            ImportProgress.Failed(ImportError.LiveStateNotCleared(failure.message.orEmpty()))
        }

    /**
     * Wipes and refills every table in one transaction, answering with the failure that rolled
     * it back or **null** once it has committed.
     *
     * The one `catch` is the whole of what [BackupRepository.replaceAll] is allowed to throw: a
     * [BackupConstraintException] for a constraint [BackupValidator] did not model. Anything
     * else is a programming error and is deliberately left to propagate — a `catch (Throwable)`
     * here would report a null-pointer bug to the user as a corrupt backup file.
     */
    private suspend fun ProducerScope<ImportProgress>.replaceRows(document: ExportDocument): ImportProgress? {
        val rowsTotal = document.rowCount
        return try {
            backupRepository.replaceAll(document) { table, rowsWritten ->
                // `send` rather than `emit`: this runs inside the database transaction, on the
                // dispatcher that owns it, and a plain flow would refuse a value produced there.
                send(ImportProgress.Writing(table = table, rowsWritten = rowsWritten, rowsTotal = rowsTotal))
            }
            null
        } catch (failure: BackupConstraintException) {
            // Only reachable after [forgetLiveState] has run, so the live state is gone.
            ImportProgress.Failed(
                ImportError.ConstraintViolation(failure.message.orEmpty(), liveStateCleared = true),
            )
        }
    }

    /**
     * Writes the document's settings over the device's and re-syncs the reminder alarms, once
     * the rows are committed.
     *
     * Every failure here is caught, not only the [IOException] the store documents, and that
     * is the one place this class does it. By now the rows are in, so whatever went wrong the
     * true sentence is about what came after them, and letting an unexpected exception through
     * would have the screen report the whole restore as a file that could not be read.
     * Cancellation is the exception, as everywhere.
     *
     * The alarms are re-synced whether or not the settings landed: the reset before the setters
     * may have, and the alarms follow what is stored now. A settings failure is reported over an
     * alarms one, because it is the one the user has to go and put right; an alarms failure
     * alone is its own error, since the settings it would otherwise blame are exactly the
     * document's.
     */
    private suspend fun applySettings(document: ExportDocument): ImportProgress {
        val settingsFailure = attempt { restorePreferences(document.preferences) }
        val alarmsFailure = attempt { reminderScheduler.sync() }
        val rows = document.rowCount
        return when {
            settingsFailure != null -> ImportProgress.Failed(
                ImportError.SettingsNotRestored(rowsRestored = rows, reason = settingsFailure.message.orEmpty()),
            )

            alarmsFailure != null -> ImportProgress.Failed(
                ImportError.RemindersNotRescheduled(rowsRestored = rows, reason = alarmsFailure.message.orEmpty()),
            )

            else -> ImportProgress.Complete(rowsWritten = rows)
        }
    }

    /** What [block] threw, or null. Cancellation is re-thrown rather than answered. */
    private suspend fun attempt(block: suspend () -> Unit): Throwable? =
        runCatching { block() }.exceptionOrNull()?.also { if (it is CancellationException) throw it }

    /**
     * Puts every setting back, having first reset them all.
     *
     * [UserPreferencesRepository.clear] before the setters, rather than the setters
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
        userPreferencesRepository.setFitnessLevel(preferences.fitnessLevel)
        userPreferencesRepository.setCustomRepRange(preferences.customRepRange)
        userPreferencesRepository.setCustomIncrement(preferences.customIncrement)
        userPreferencesRepository.setOneRepMaxFormula(preferences.oneRepMaxFormula)
        userPreferencesRepository.setWarmupSuggestions(preferences.warmupSuggestions)
        userPreferencesRepository.setAutoOpenRestTimer(preferences.autoOpenRestTimer)
        userPreferencesRepository.setWeeklyWorkoutGoal(preferences.weeklyWorkoutGoal)
        userPreferencesRepository.setOnboardingCompleted(preferences.onboardingCompleted)
    }
}
