package com.titan.core.domain.gamification

/**
 * What a pet may be called.
 *
 * One pure rule, read by the rename dialog (to say why a name is refused), by the repository (to
 * refuse it anyway) and by the reader of a restored row (to drop a name that fails it).
 */
object PetNickname {

    /** The longest a nickname can be, in characters as the user counts them. */
    const val MAX_LENGTH = 24

    /**
     * Checks [raw] and answers with the name to store.
     *
     * Surrounding whitespace is trimmed first and is not a reason to refuse: a trailing space
     * from a keyboard's autocomplete is not something the user typed on purpose.
     */
    fun validate(raw: String): PetNicknameResult {
        val trimmed = raw.trim()
        return when {
            trimmed.isEmpty() -> PetNicknameResult.Blank
            trimmed.any(Char::isISOControl) -> PetNicknameResult.ControlCharacters
            trimmed.codePointCount(0, trimmed.length) > MAX_LENGTH -> PetNicknameResult.TooLong
            else -> PetNicknameResult.Valid(trimmed)
        }
    }

    /** [raw] as a stored nickname, or null if it fails the rule — the reader's degrade. */
    fun orNull(raw: String?): String? = raw?.let { (validate(it) as? PetNicknameResult.Valid)?.nickname }
}

/** The answer to "may a pet be called this". */
sealed interface PetNicknameResult {

    /** Yes, as [nickname] — the input with its surrounding whitespace removed. */
    data class Valid(val nickname: String) : PetNicknameResult

    /** Nothing but whitespace. */
    data object Blank : PetNicknameResult

    /** Longer than [PetNickname.MAX_LENGTH]. */
    data object TooLong : PetNicknameResult

    /** Holds a control character — a newline, a tab, a NUL. */
    data object ControlCharacters : PetNicknameResult
}
