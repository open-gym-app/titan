package com.titan.core.data.db.projection

import androidx.room.Embedded
import androidx.room.Relation
import com.titan.core.data.db.entity.TemplateExerciseEntity
import com.titan.core.data.db.entity.TemplateSetEntity

/**
 * One template exercise and the sets it plans, as one read.
 *
 * A template line is not readable without its sets — the whole of what it plans is in
 * them — so every read of `template_exercise` in this codebase goes through this rather
 * than through the bare entity. Room satisfies it with two queries per call and joins
 * them in memory, which is what `@Relation` is for; the alternative is a query per
 * exercise from the caller.
 *
 * **[sets] comes back unordered.** `@Relation` promises the right rows and says nothing
 * about their order, so the mapper sorts on `set_number` — the numbering is the plan's
 * order, and a template drawn with its drop set in the middle would be the wrong plan.
 *
 * It doubles as the write carrier for the same pairing: the DAO takes a list of these
 * to store a template body, since the sets of an exercise cannot be written before the
 * exercise row they hang off exists. Two near-identical types for the two directions
 * would be one more place for the pairing to drift.
 */
data class TemplateExerciseWithSets(
    @Embedded val exercise: TemplateExerciseEntity,
    @Relation(parentColumn = "id", entityColumn = "template_exercise_id")
    val sets: List<TemplateSetEntity> = emptyList(),
)
