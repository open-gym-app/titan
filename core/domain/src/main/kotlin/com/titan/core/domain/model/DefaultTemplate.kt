package com.titan.core.domain.model

/**
 * One of the templates the app ships with, as it would be written today: named in the
 * device's current language, its exercises resolved to the ids this database gave the seeded
 * catalogue.
 *
 * Not a stored row. The defaults are inserted into `workout_template` when the database is
 * created and are ordinary templates from then on — the user renames, edits and deletes them
 * like any other. This is the *recipe* for one, which is what restoring a deleted default
 * needs: [com.titan.core.domain.usecase.RestoreDefaultTemplatesUseCase] compares [name]
 * against the templates that exist and creates the ones that do not.
 *
 * @property name in the language the device is in now, which is not necessarily the one the
 *   database was created in. A default seeded as "Legs" and looked for after a switch to
 *   French is looked for as "Jambes".
 * @property exercises in order, ready for [com.titan.core.domain.usecase.CreateTemplateUseCase].
 */
data class DefaultTemplate(
    val name: String,
    val category: TemplateCategory,
    val exercises: List<TemplateExerciseDraft>,
)
