package com.titan.core.domain.model

/**
 * Every way a workout template write can be refused.
 *
 * Mirrors [SessionError]: the use cases return these inside a [Result] rather than
 * throwing, so a caller can exhaustively `when` over the failure and render it
 * against the offending form field. They extend [IllegalArgumentException] — unlike
 * [SessionError], every one of these is a statement about the *arguments* handed in,
 * not about a state machine the caller cannot see — so `getOrThrow()` still behaves
 * the way a Kotlin caller expects.
 */
sealed class TemplateError(message: String) : IllegalArgumentException(message) {

    /** The name was blank, so the template would be unnameable in a list. */
    data object EmptyName : TemplateError(
        "a template must have a name",
    )

    /** A template with no exercises plans nothing, so it is refused rather than stored. */
    data object NoExercises : TemplateError(
        "a template must have at least one exercise",
    )

    /**
     * Another template already carries this name, compared case-insensitively.
     * [name] is the *requested* name, trimmed — not the name of the template it
     * collided with, whose casing may differ.
     */
    data class DuplicateName(val name: String) :
        TemplateError(
            "a template named \"$name\" already exists",
        )

    /**
     * The superset grouping of the exercise list is not one that can be stored:
     * a superset is split by an exercise that is not in it. [reason] comes from
     * [com.titan.core.domain.superset.Supersets.validate] and names every offending
     * group, so it can be shown as-is.
     *
     * A group left with a single member is *not* this error — that is what removing
     * one of two exercises does, and it dissolves on the way in rather than being
     * refused. Only a grouping that needs the exercises moved to repair gets here.
     */
    data class InvalidSuperset(val reason: String) : TemplateError(reason)

    /** The template being updated, deleted or applied is not in the repository. */
    data class NotFound(val templateId: Long) :
        TemplateError(
            "template $templateId does not exist",
        )
}
