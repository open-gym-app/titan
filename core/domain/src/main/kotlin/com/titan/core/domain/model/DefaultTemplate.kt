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
 * @property exercises in order, ready for [com.titan.core.domain.usecase.CreateTemplateUseCase].
 */
data class DefaultTemplate(
    val name: DefaultTemplateName,
    val category: TemplateCategory,
    val exercises: List<TemplateExerciseDraft>,
)

/**
 * What a default is called, in the language it would be written in now and in every language
 * the app ships.
 *
 * Both, because the language a database was seeded in is not necessarily the one the device
 * is in when a default is looked for. A default seeded as "Legs" on an English phone is still
 * there after a switch to French, though its French name "Jambes" is nowhere in the list; it is
 * [everyLanguage] that recognises it, and [current] that names one restored now.
 *
 * @property current in the device's current language: what a restored default is called and
 *   what the restore dialog lists.
 * @property everyLanguage the name in each language the app ships, [current] among them even
 *   when the device's language is not one of those (a pseudolocale, say).
 */
data class DefaultTemplateName(
    val current: String,
    val everyLanguage: Set<String>,
) {

    /** A name the app ships in only one language, or a test's. */
    constructor(name: String) : this(name, setOf(name))
}
