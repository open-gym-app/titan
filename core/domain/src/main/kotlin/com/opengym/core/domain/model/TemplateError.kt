package com.opengym.core.domain.model

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
    data class DuplicateName(val name: String) : TemplateError(
        "a template named \"$name\" already exists",
    )

    /** The template being updated, deleted or applied is not in the repository. */
    data class NotFound(val templateId: Long) : TemplateError(
        "template $templateId does not exist",
    )
}
