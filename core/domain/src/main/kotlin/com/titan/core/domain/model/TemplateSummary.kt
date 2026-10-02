package com.titan.core.domain.model

import kotlin.time.Duration

/**
 * A saved template as a list row shows it: `Push Day · Upper — 8 ex · ~55m — STRENGTH`.
 *
 * [WorkoutTemplate] holds none of the two figures on purpose, and this is where they
 * come from instead — see
 * [com.titan.core.domain.usecase.GetTemplateSummariesUseCase], which reads them off
 * the template's exercises. Both go stale the moment a template is edited, so neither
 * is stored and both are recomputed from the rows that decide them.
 *
 * A summary, not the template: it carries no exercises, because the two screens that
 * render it — the new-session picker (#25) and the templates tab (#24) — draw a row and
 * not a body. Starting from one reads the template properly, through
 * [com.titan.core.domain.usecase.ApplyTemplateUseCase].
 *
 * @property exerciseCount how many exercises the template lays out, supersets counted
 *   as their members rather than as one. It is what the user is about to perform.
 * @property estimatedDuration the pacing figure from
 *   [com.titan.core.domain.template.TemplateDuration], accurate to about the nearest
 *   five minutes — which is why every rendering of it carries a `~`.
 */
data class TemplateSummary(
    val template: WorkoutTemplate,
    val exerciseCount: Int,
    val estimatedDuration: Duration,
)
