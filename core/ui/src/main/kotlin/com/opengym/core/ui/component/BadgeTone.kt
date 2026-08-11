package com.opengym.core.ui.component

/**
 * What a badge is *for*, which decides what it costs.
 *
 * The accent is the app's only signal (CONTRIBUTING.md § Using the design system), so a badge
 * that spends it is claiming the user should look at this before anything else on the screen.
 * Naming the tones after their meaning rather than their colour is what makes that a decision
 * at the call site instead of a colour picked because it looked good in one place.
 */
enum class BadgeTone {

    /** A personal record, a live timer, a streak still running: look here first. */
    ACCENT,

    /** A fact worth marking but not worth interrupting for — a set type, a "soon". */
    NEUTRAL,

    /** Something lost or overdue. Uses the error role, so it is never merely decorative. */
    CRITICAL,
}
