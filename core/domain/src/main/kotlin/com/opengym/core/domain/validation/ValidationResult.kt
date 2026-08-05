package com.opengym.core.domain.validation

/**
 * Outcome of a domain validation rule.
 *
 * Deliberately not a [Result] or a bare `Boolean`: a caller rendering a form needs
 * the *reason* a value was refused, and an exhaustive `when` over this sealed class
 * is what makes forgetting the failure branch a compile error.
 */
sealed class ValidationResult {

    /** The value satisfies every rule. */
    data object Valid : ValidationResult()

    /**
     * The value was refused. [reason] is human-readable and names the offending
     * field, its bound, and the value seen, so it can be shown as-is; several
     * violations of the same value are joined into one string.
     */
    data class Invalid(val reason: String) : ValidationResult() {
        init {
            require(reason.isNotBlank()) { "an Invalid result must carry a reason" }
        }
    }

    /** True when this is [Valid]. Shorthand for call sites that only branch on success. */
    val isValid: Boolean get() = this is Valid

    /** The refusal reason, or null when [Valid]. */
    fun reasonOrNull(): String? = (this as? Invalid)?.reason
}
