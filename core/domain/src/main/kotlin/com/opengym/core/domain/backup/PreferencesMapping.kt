package com.opengym.core.domain.backup

import com.opengym.core.domain.backup.model.PlateStockPayload
import com.opengym.core.domain.backup.model.PreferencesPayload
import com.opengym.core.domain.model.FitnessLevel
import com.opengym.core.domain.model.OneRepMaxFormula
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.plate.PlateInventory
import com.opengym.core.domain.plate.PlateStock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [UserPreferences] as it is written into an export.
 *
 * A total function: every field of the model has a representation, so nothing is lost and
 * nothing has to be guessed on the way back.
 */
fun UserPreferences.toPayload(): PreferencesPayload =
    PreferencesPayload(
        unitSystem = unitSystem.name,
        theme = theme.name,
        defaultRestDurationSeconds = defaultRestDuration.inWholeSeconds,
        barWeight = barWeight,
        trainingGoal = trainingGoal.name,
        oneRepMaxFormula = oneRepMaxFormula.name,
        onboardingCompleted = onboardingCompleted,
        plateInventory = plateInventory?.stock?.map { PlateStockPayload(weight = it.weight, pairs = it.pairs) },
        fitnessLevel = fitnessLevel.name,
    )

/**
 * The settings this payload describes, with anything unreadable replaced by its default.
 *
 * ### Degrading rather than refusing, and why that is right here
 *
 * The rest of the importer is strict: a dangling foreign key refuses the whole document. This
 * is deliberately the opposite, and it is the rule
 * `com.opengym.core.domain.repository.UserPreferencesRepository`'s emission contract already
 * sets for the store itself — an enum name this build does not have, a rest of zero seconds, a
 * plate rack that breaks [PlateInventory]'s own invariants all read back as the documented
 * default rather than as an error.
 *
 * The asymmetry is what the two halves are worth. A row is a record of something that happened
 * and losing it is losing the user's training; a setting is a single-valued fact they can
 * change in two taps. Refusing a whole restore because a downgraded build does not recognise
 * `ThemePreference.AMOLED` would trade a history for a colour scheme.
 */
fun PreferencesPayload.toPreferences(): UserPreferences =
    UserPreferences(
        unitSystem = unitSystem.asEnumOr(UnitSystem.METRIC),
        theme = theme.asEnumOr(ThemePreference.SYSTEM),
        defaultRestDuration = defaultRestDurationSeconds.asRestDurationOrNull()
            ?: UserPreferences.DEFAULT_REST_DURATION,
        barWeight = barWeight.takeIf { it > 0 } ?: UserPreferences.DEFAULT_BAR_WEIGHT,
        plateInventory = plateInventory?.toInventoryOrNull(),
        trainingGoal = trainingGoal.asEnumOr(TrainingGoal.STRENGTH),
        // Null is a file from before the question existed, and it means the same thing an
        // unrecognised name does: this user has never given an answer this build can use.
        fitnessLevel = fitnessLevel.asEnumOr(FitnessLevel.BEGINNER),
        oneRepMaxFormula = oneRepMaxFormula.asEnumOr(OneRepMaxFormula.EPLEY),
        onboardingCompleted = onboardingCompleted,
    )

/**
 * The rack these rows describe, or **null** if they do not describe one.
 *
 * Null is the value that means "the user has never said", so falling back to it is falling back
 * to the standard rack for their unit system — which is a better answer for an unreadable
 * inventory than an empty one, since an empty inventory is a barbell with no plates to load.
 * [PlateInventory.of] sorts and de-duplicates, so an out-of-order or repeated denomination is
 * repaired rather than refused; what is left to fail is a weight or a pair count outside the
 * bounds [PlateStock] holds, and a rack of more sizes than [PlateInventory.MAX_DENOMINATIONS].
 */
private fun List<PlateStockPayload>.toInventoryOrNull(): PlateInventory? =
    try {
        PlateInventory.of(map { PlateStock(weight = it.weight, pairs = it.pairs) })
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * These many seconds as a rest [UserPreferences] would accept, or **null** if it would not.
 *
 * Both ends have to be checked, and the far one is the less obvious: [Duration] saturates
 * rather than overflowing, so a hand-edited or corrupted `default_rest_duration_seconds` past
 * what it can hold comes back as [Duration.INFINITE], which the model's `require` refuses. An
 * exception there would escape [toPreferences] — `ImportUseCase` types only
 * `BackupConstraintException` and `IOException` — and it would do so *after* the tables have
 * already been replaced, killing the restore over a rest timer. Falling back is the whole rule
 * this half of the mapping follows.
 */
private fun Long.asRestDurationOrNull(): Duration? =
    takeIf { it >= MIN_REST_SECONDS }?.seconds?.takeIf { it.isFinite() }

/**
 * The constant of [T] named by this string, or [fallback] when this build has no such constant.
 *
 * Nullable on purpose: a key that arrived after the first documents were written is absent
 * from older files, and absent means the same thing an unrecognised name does. One fallback
 * for both keeps the two answers from ever drifting apart.
 */
private inline fun <reified T : Enum<T>> String?.asEnumOr(fallback: T): T =
    enumValues<T>().firstOrNull { it.name == this } ?: fallback

/**
 * The shortest rest [UserPreferences] will accept. Spelled here because a payload holding zero
 * has to be caught *before* the constructor sees it: the model throws on it, and an import that
 * threw over a setting would be an import that lost a history over a rest timer. The longest is
 * not a constant — it is whatever [Duration] can still represent finitely, see
 * [asRestDurationOrNull].
 */
private const val MIN_REST_SECONDS = 1L
