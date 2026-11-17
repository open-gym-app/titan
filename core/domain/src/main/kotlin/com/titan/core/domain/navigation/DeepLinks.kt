package com.titan.core.domain.navigation

/**
 * The app's own address space: the `titan://` URIs anything outside the app uses to point at
 * a screen inside it.
 *
 * ### Why this is in the domain
 *
 * Because two layers that cannot see each other have to agree on it, exactly. `:core:data`
 * *writes* these URIs onto the notifications it posts; `:app` *registers* them on the
 * navigation graph and in the manifest. A string literal on each side is a link that opens
 * nothing the day one of them is edited, and it fails silently — a tap that does nothing.
 * `:core:domain` is the only module both can reach.
 *
 * It is not a domain concept, and it is not pretending to be one. It is a constant with two
 * consumers, kept where both can read the same copy.
 *
 * ### The shape
 *
 * `titan://<feature>/<screen>/<id>`, which is what Navigation Compose's own
 * `navDeepLink(basePath = …)` builds for a route type whose arguments are required: the base
 * path, then one path segment per argument in declaration order. [ACTIVE_SESSION] is that base
 * path and [activeSession] is the same URI with the id filled in, so the two cannot drift.
 */
object DeepLinks {

    /** The scheme every one of these carries. Declared on the launcher activity's filter too. */
    const val SCHEME: String = "titan"

    /** The host the session flow's links sit under — the manifest filter matches on it. */
    const val SESSION_HOST: String = "session"

    /** The host the progression screens sit under. Matched by the same filter. */
    const val PROGRESSION_HOST: String = "progression"

    /** The host the settings screens sit under. Matched by the same filter. */
    const val SETTINGS_HOST: String = "settings"

    /**
     * Setting a workout up: `titan://session/new`.
     *
     * Where a scheduled reminder lands, and the only one of these URIs that points at a screen
     * for something that does not exist yet. A constant rather than a function even though
     * `NewSessionDestination` has two arguments: both are defaulted, so the base path *is* the
     * whole route, and neither of them is a reminder's to choose — a nudge to train opens the
     * flow at the top and lets the user say what they are doing.
     */
    const val NEW_SESSION: String = "$SCHEME://$SESSION_HOST/new"

    /**
     * The workout in progress, without its id: `titan://session/active`.
     *
     * Handed to `navDeepLink<ActiveSessionDestination>(basePath = …)`, which appends the
     * `sessionId` segment itself.
     */
    const val ACTIVE_SESSION: String = "$SCHEME://$SESSION_HOST/active"

    /** The workout in progress: `titan://session/active/42`. */
    fun activeSession(sessionId: Long): String = "$ACTIVE_SESSION/$sessionId"

    /**
     * The quest log: `titan://progression/quests`.
     *
     * A constant rather than a function, because the destination takes no arguments — and
     * therefore the base path and the whole URI are the same string. It is where both of the
     * things a progression notification announces are drawn: the recent loot, and what is left
     * of the month's streak allowance.
     */
    const val QUEST_LOG: String = "$SCHEME://$PROGRESSION_HOST/quests"

    /**
     * The import from another app: `titan://settings/import`.
     *
     * Where both of the import's notifications land — the progress one while it runs, and the
     * line saying it ended — so the report is one tap from the sentence announcing it. A
     * constant, because the destination takes no arguments: the screen reads the import's state
     * from the worker running it, not from the link.
     */
    const val HISTORY_IMPORT: String = "$SCHEME://$SETTINGS_HOST/import"
}
