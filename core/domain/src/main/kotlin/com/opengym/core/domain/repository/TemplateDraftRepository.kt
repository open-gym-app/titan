package com.opengym.core.domain.repository

import com.opengym.core.domain.model.TemplateDraft
import com.opengym.core.domain.model.TemplateDraftFormat

/**
 * The one template being written, saved so that a half-built plan survives the process that was
 * building it being killed.
 *
 * At most one draft exists at a time, because the editor is one screen and the user is in one
 * place: there is no id parameter anywhere here, and a draft is a slot rather than a collection.
 * Which template the slot is about is [TemplateDraft.templateId], and a caller that opens the
 * editor on a *different* template ignores what it finds — see [load].
 *
 * ### How this differs from [TemplateRepository]
 *
 * That one owns the stored templates and is what SAVE writes to. This is what the editor writes
 * to *between* saves. The two do not overlap the way the session's draft overlaps its rows: a
 * template that has never been saved has no rows at all, which is exactly why this exists — the
 * work is nowhere else until the user presses the button.
 *
 * ### Contract
 *
 * - [load] returns **null** when no draft exists — nothing has been saved, [clear] was called,
 *   or what was stored cannot be read by this build. All three mean the same thing to a caller,
 *   and none of them is an error.
 * - [save] **overwrites** whatever draft was there, atomically. There is no merge and no append;
 *   the last write wins, whole. The atomicity is a real requirement rather than a formality:
 *   this is written as the user types, so a kill landing inside a write is not a remote
 *   possibility, and the failure mode is a template lost to half a JSON object.
 * - [clear] is what a finished editor calls — a save that landed, or a draft the user threw
 *   away. A draft left behind by a screen that has closed would be restored over the *next*
 *   template the user opens, which is the one way this feature could cost work rather than save
 *   it.
 *
 * ### Where the implementation lives
 *
 * In `:core:data`, and nothing about the storage medium is decided here — only the document, by
 * [TemplateDraftFormat].
 */
interface TemplateDraftRepository {

    /**
     * Replaces the stored draft with [draft], atomically: a reader concurrent with this call, or
     * one that comes back after the process died in the middle of it, sees either the whole
     * previous draft or the whole new one — never half of either, and never nothing.
     */
    suspend fun save(draft: TemplateDraft)

    /**
     * The stored draft, or **null** when there is none to restore.
     *
     * A draft that *is* returned may be about a template the caller is not editing, or about one
     * that has since been deleted. Deciding that is the caller's: this knows what was saved, not
     * what the screen asking is for.
     */
    suspend fun load(): TemplateDraft?

    /** Discards the draft. Doing this when there is none is not an error. */
    suspend fun clear()
}
