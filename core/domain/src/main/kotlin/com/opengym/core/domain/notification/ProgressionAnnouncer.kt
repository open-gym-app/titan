package com.opengym.core.domain.notification

import com.opengym.core.domain.model.SessionProgression

/**
 * Tells the user what a finished workout earned them, when they were not there to watch.
 *
 * ### It announces; it does not decide
 *
 * Every fact it can say is already on the [SessionProgression] it is handed — the streak it
 * saved, the protections left, the collectible that dropped. Nothing here reads a repository,
 * calls a use case or recomputes a figure, and that is a rule rather than a coincidence: the
 * cascade produced those numbers *inside* the writes that persisted them, so a second reading
 * could disagree with what the session actually paid. A fact that is not on the result is not
 * announced; that is a gap in the cascade and belongs where the cascade is.
 *
 * ### Only when the user is not looking
 *
 * The session summary already celebrates both of these — the loot drop with a card of its own,
 * the streak save with a banner — so posting a notification about something somebody is
 * watching happen is the fastest way to get a channel muted. The gate is the *process*
 * lifecycle, the same one `BackgroundRestTimer` uses to decide whether an expiring rest is
 * announced or left to the in-app alert; the implementation shares that reading rather than
 * taking its own.
 *
 * The case this exists for is the one where there is no summary screen at all: a workout closed
 * out from the idle nudge's "Finish now" with the app dead, which is #178's whole point.
 *
 * ### One notification, not two
 *
 * A session that saves a streak *and* drops loot says both in one. There are at most two facts,
 * so a notification group would be scaffolding around a two-line body — and two separate posts
 * for one finished workout is the shape that reads as an app talking to itself.
 *
 * Implemented in `:core:data`. Nothing about channels, wording or plural rules is decided here.
 */
interface ProgressionAnnouncer {

    /**
     * Says whatever [progression] is worth saying, or nothing at all.
     *
     * Nothing is the ordinary answer, and by a wide margin: most sessions neither spend a
     * protection nor pay out a drop, and every session finished with the app on screen is
     * silent whatever it earned.
     */
    suspend fun announce(progression: SessionProgression)
}
