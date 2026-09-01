package com.opengym.feature.session.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.EmptyState
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.NotesCard
import com.opengym.core.ui.component.SectionHeading
import com.opengym.core.ui.component.SessionNameDialog
import com.opengym.core.ui.component.SessionStatGrid
import com.opengym.core.ui.component.TemplateNameDialog
import com.opengym.core.ui.format.formatAsClock
import com.opengym.core.ui.format.formatSessionDate
import com.opengym.core.ui.format.formatTimeOfDay
import com.opengym.core.ui.theme.LocalReducedMotion
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.session.R
import com.opengym.feature.session.summary.component.AchievementsUnlockedCard
import com.opengym.feature.session.summary.component.ExerciseBreakdownCard
import com.opengym.feature.session.summary.component.NewRecordsCard
import com.opengym.feature.session.summary.component.SessionMusclesCard
import com.opengym.feature.session.summary.component.XpCelebrationCard
import kotlin.time.Duration.Companion.seconds

/**
 * The session recap, wired to its ViewModel.
 *
 * @param onExit the way off this screen. What that *means* is the mode's: ending the session
 *   flow in one and popping back to the session list in the other, which is a distinction
 *   `:app` makes and this screen does not need to know about.
 */
@Composable
fun SessionSummaryRoute(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionSummaryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SessionSummaryScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onExit = onExit,
        modifier = modifier,
    )
}

/**
 * What the workout amounted to.
 *
 * ### The same screen, twice
 *
 * Drawn once the moment a session ends, and again whenever that session is opened from
 * history. Everything below the header is identical — the stats, the records, the muscles, the
 * breakdown, the note — because it is the same session and there is no honest reason for it to
 * read differently a week later. [SessionSummaryMode] carries the two things that do differ:
 * what the eyebrow says, and how the user gets out.
 *
 * ### Where the accent is spent
 *
 * Three places, and they are the three things worth looking at: the eyebrow that says the
 * session is done, the records card's border and figures, and DONE. The stat grid, the muscle
 * bars' secondary tone and every rule on the screen are deliberately quiet around them — a
 * summary where four cards compete is one where the PR does not stand out, which is the one
 * thing the user came back to see.
 *
 * @param onExit the way off the screen. See [SessionSummaryRoute].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSummaryScreen(
    state: SessionSummaryUiState,
    onEvent: (SessionSummaryEvent) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val message = state.message?.let { stringResource(it.labelRes()) }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onEvent(SessionSummaryEvent.MessageShown)
        }
    }

    state.nameDraft?.let { draft ->
        SessionNameDialog(
            name = draft,
            onNameChange = { onEvent(SessionSummaryEvent.NameChanged(it)) },
            onConfirm = { onEvent(SessionSummaryEvent.NameConfirmed) },
            onDismiss = { onEvent(SessionSummaryEvent.NameEditCancelled) },
            isSaving = state.isSavingName,
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { SummaryTopBar(mode = state.mode, onBack = onExit) },
        bottomBar = {
            // Not while the note is being edited. The editor carries its own Save and Cancel,
            // and a pinned bar under them puts a second Save on the screen — the one that ends
            // the flow — directly below the one the user is reaching for. The note is a modal
            // moment inside the screen; its own two buttons are the only ones that should be
            // reachable during it.
            //
            // Otherwise always, including while the reads are in flight and for a session that
            // could not be loaded at all. **DONE needs no data**: the just-finished mode has no
            // back arrow, because there is nothing behind it, so DONE is the only way off the
            // screen — and gating it on content would leave the user on a blank or broken page
            // with no exit but the system gesture. "Save as template" is the half that does
            // need the session, and it is absent until there is one.
            if (state.notesDraft == null) {
                SummaryActionBar(
                    showSaveAsTemplate = state.canSaveAsTemplate,
                    canSaveAsTemplate = !state.isSavingTemplate,
                    showDone = state.mode == SessionSummaryMode.JUST_FINISHED,
                    onSaveAsTemplate = { onEvent(SessionSummaryEvent.SaveAsTemplateRequested) },
                    onDone = onExit,
                )
            }
        },
    ) { innerPadding ->
        when {
            // Nothing at all while the reads are in flight. The session is one query away and
            // a spinner that appears for two frames is worse than a beat of nothing.
            state.isLoading -> Unit

            state.isMissing -> MissingSession(contentPadding = innerPadding)

            else -> SummaryContent(state = state, onEvent = onEvent, contentPadding = innerPadding)
        }
    }

    if (state.templateName != null) {
        TemplateNameDialog(
            name = state.templateName,
            onNameChange = { onEvent(SessionSummaryEvent.TemplateNameChanged(it)) },
            onConfirm = { onEvent(SessionSummaryEvent.SaveAsTemplateConfirmed) },
            onDismiss = { onEvent(SessionSummaryEvent.SaveAsTemplateDismissed) },
            title = stringResource(R.string.summary_template_title),
            confirmLabel = stringResource(R.string.summary_template_save),
            category = state.templateCategory,
            onCategoryChange = { onEvent(SessionSummaryEvent.TemplateCategoryChanged(it)) },
            // What is about to be saved. "Save as template" on a screen full of figures is
            // genuinely ambiguous about whether the weights come with it, and they do.
            body = stringResource(R.string.summary_template_body),
            error = stringResource(R.string.summary_template_name_taken)
                .takeIf { state.templateNameTaken },
            isSaving = state.isSavingTemplate,
        )
    }
}

/**
 * The bar, which in the just-finished mode is empty space and nothing else.
 *
 * There is no back arrow there, and that is the point: the session has been closed out and its
 * screens popped, so there is nothing behind this one to go back *to*. An arrow that could only
 * mean "leave" would be a second, quieter DONE at the far corner of the screen from the real
 * one. The space stays, because the status-bar inset has to come from somewhere and a headline
 * starting under the clock is worse than a gap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummaryTopBar(
    mode: SessionSummaryMode,
    onBack: () -> Unit,
) {
    TopAppBar(
        // Untitled on purpose. The session's name is the headline directly below it, set
        // large and centred, and repeating it in the bar would be the same words twice in
        // fifty vertical pixels.
        title = {},
        navigationIcon = {
            if (mode == SessionSummaryMode.HISTORICAL) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.summary_back),
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

/**
 * The recap itself: a header, then a section per thing the session amounted to.
 *
 * One lazy item per section rather than one holding all of them. A summary is taller than a
 * phone by the second card, and the sections below the fold — a dozen-exercise breakdown, a
 * muscle diagram that draws two figures on a canvas — are composed and measured as they are
 * scrolled to rather than all at once on the frame a workout ends.
 */
@Composable
private fun SummaryContent(
    state: SessionSummaryUiState,
    onEvent: (SessionSummaryEvent) -> Unit,
    contentPadding: PaddingValues,
) {
    val header = state.header ?: return
    val stats = state.stats ?: return

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = SECTION_HEADER) {
            SummaryHeaderBlock(
                mode = state.mode,
                header = header,
                durationSeconds = stats.durationSeconds,
                onRename = { onEvent(SessionSummaryEvent.NameEditRequested) },
            )
        }

        item(key = SECTION_STATS) {
            SummarySection {
                SessionStatGrid(
                    stats = stats,
                    modifier = Modifier.fillMaxWidth(),
                    // The duration is already in the header two lines up, so the fourth tile
                    // carries the figure that has nowhere else to go.
                    musclesWorked = state.musclesWorked,
                )
            }
        }

        // The payoff, above the records rather than below them: the XP figure is what the
        // screen is *for* in this mode, and a celebration the user has to scroll to has already
        // failed to celebrate. Null in the historical mode and whenever the session earned
        // nothing — see [SummaryCelebration], which is absent rather than zeroed.
        val celebration = state.celebration
        if (celebration != null) {
            item(key = SECTION_CELEBRATION) {
                SummarySection {
                    Section(title = stringResource(R.string.summary_xp_earned)) {
                        XpCelebrationCard(celebration = celebration)
                    }
                }
            }

            if (celebration.achievements.isNotEmpty()) {
                item(key = SECTION_ACHIEVEMENTS) {
                    SummarySection {
                        Section(title = stringResource(R.string.summary_achievements)) {
                            AchievementsUnlockedCard(achievements = celebration.achievements)
                        }
                    }
                }
            }
        }

        // Absent, not empty. A session that beat nothing is the ordinary case, and a card
        // saying so is the screen telling the user what they failed to do.
        if (state.records.isNotEmpty()) {
            item(key = SECTION_RECORDS) {
                SummarySection {
                    Section(
                        title = stringResource(
                            if (state.mode == SessionSummaryMode.HISTORICAL) {
                                R.string.summary_records_historical
                            } else {
                                R.string.summary_records
                            },
                        ),
                        accented = state.mode == SessionSummaryMode.JUST_FINISHED,
                    ) {
                        NewRecordsCard(records = state.records)
                    }
                }
            }
        }

        if (state.muscles.isNotEmpty()) {
            item(key = SECTION_MUSCLES) {
                SummarySection {
                    Section(title = stringResource(R.string.summary_muscles)) {
                        SessionMusclesCard(
                            contributions = state.muscles,
                            primary = state.primaryMuscles,
                            secondary = state.secondaryMuscles,
                        )
                    }
                }
            }
        }

        if (state.exercises.isNotEmpty()) {
            item(key = SECTION_EXERCISES) {
                SummarySection {
                    Section(
                        title = stringResource(R.string.summary_exercises),
                        trailing = { Eyebrow(text = state.exercises.size.toString()) },
                    ) {
                        ExerciseBreakdownCard(exercises = state.exercises)
                    }
                }
            }
        }

        item(key = SECTION_NOTES) {
            SummarySection {
                Section(title = stringResource(R.string.summary_notes)) {
                    NotesCard(
                        notes = state.notes,
                        draft = state.notesDraft,
                        isSaving = state.isSavingNotes,
                        emptyPrompt = stringResource(R.string.summary_notes_empty),
                        fieldLabel = stringResource(R.string.summary_notes_label),
                        onEdit = { onEvent(SessionSummaryEvent.NoteEditRequested) },
                        onDraftChange = { onEvent(SessionSummaryEvent.NoteChanged(it)) },
                        onSave = { onEvent(SessionSummaryEvent.NoteSaved) },
                        onCancel = { onEvent(SessionSummaryEvent.NoteEditCancelled) },
                    )
                }
            }
        }
    }
}

/**
 * The screen margin and the gap to the next section, applied once per lazy item.
 *
 * Half the section gap top and bottom rather than a full gap on one side: consecutive items
 * then sit a full gap apart whichever of them is present, so a session with no records does
 * not close up differently from one with them.
 */
@Composable
private fun SummarySection(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.padding(
            horizontal = OpenGymTheme.spacing.screen,
            vertical = OpenGymTheme.spacing.md,
        ),
        content = content,
    )
}

/**
 * What was done, when, and for how long — centred, and ruled off from the recap under it.
 *
 * Centred because this is the only screen in the app that opens on a *result* rather than on a
 * list, and the wireframes draw it that way: a title on the left edge reads as the top of a
 * page of content, and this is a headline.
 *
 * The window is two mono lines rather than one, and that is a measurement rather than a
 * preference: `Mon 28 Apr 2026 · 10:14 → 11:06 · 52:24` is thirty-seven characters, and a
 * 360dp screen minus its margins does not hold that at any size worth reading. Split, the day
 * leads and the clock follows it, which is also the order somebody asks the two questions in.
 */
@Composable
private fun SummaryHeaderBlock(
    onRename: () -> Unit,
    mode: SessionSummaryMode,
    header: SummaryHeader,
    durationSeconds: Long,
) {
    val window = stringResource(
        R.string.summary_meta,
        stringResource(
            R.string.summary_time_range,
            formatTimeOfDay(header.startedAt),
            formatTimeOfDay(header.finishedAt),
        ),
        durationSeconds.seconds.formatAsClock(),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = OpenGymTheme.spacing.screen,
                end = OpenGymTheme.spacing.screen,
                bottom = OpenGymTheme.spacing.xxl,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        Eyebrow(
            text = stringResource(
                if (mode == SessionSummaryMode.HISTORICAL) {
                    R.string.summary_logged
                } else {
                    R.string.summary_complete
                },
            ),
            // The accent, in the just-finished mode only. Nothing was achieved by opening a
            // session from a list, and colouring the label as though something had is exactly
            // the celebration the historical view is meant not to have.
            color = if (mode == SessionSummaryMode.HISTORICAL) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        val rename = stringResource(R.string.summary_rename)
        Text(
            // A session built by hand and never named is genuinely unnamed; the line under it
            // is what identifies it, so this stands in rather than inventing a title.
            text = header.name ?: stringResource(R.string.summary_unnamed),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // The title is the affordance, in both modes: a workout is often named after it is
            // over — the recap is where a user finds out what it turned into — and a session
            // logged months ago is as renameable as the one that just ended.
            modifier = Modifier
                .clickable(onClick = onRename)
                .semantics { onClick(label = rename, action = null) },
        )
        Text(
            text = formatSessionDate(header.startedAt),
            style = OpenGymTheme.typography.numeric,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            text = window,
            style = OpenGymTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** A [SectionHeading] and the block it introduces, at the gap the design gives that pair. */
@Composable
private fun Section(
    title: String,
    accented: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg)) {
        SectionHeading(
            text = title,
            color = if (accented) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            trailing = trailing,
        )
        content()
    }
}

/**
 * The two things the user can still do, pinned.
 *
 * Pinned rather than at the end of the list for the reason the new-session flow's START bar is:
 * a twelve-exercise session would put the way out below the fold of the thing it is a way out
 * of.
 *
 * Both halves are conditional, and neither is ever drawn disabled-for-good. A session with no
 * performed exercises has nothing to save as a template, so the action is *absent* rather than
 * greyed — a button that can never be pressed is a question the user is left holding. In the
 * historical mode there is no DONE, because the back arrow is the exit; a session opened from
 * the list with nothing to save therefore has no bar at all, and the divider goes with it.
 *
 * @param showSaveAsTemplate whether there is anything to save.
 * @param canSaveAsTemplate whether it can be pressed *right now*, which is false only while a
 *   save is in flight.
 * @param showDone whether this mode owns the way out.
 */
@Composable
private fun SummaryActionBar(
    showSaveAsTemplate: Boolean,
    canSaveAsTemplate: Boolean,
    showDone: Boolean,
    onSaveAsTemplate: () -> Unit,
    onDone: () -> Unit,
) {
    if (!showSaveAsTemplate && !showDone) return

    Column(
        modifier = Modifier
            // Opaque, and outside the inset padding so it covers that too. A `Scaffold`'s
            // bottom bar draws no ground of its own, and the recap scrolls *under* this one —
            // without a fill, an exercise row shows through the buttons.
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(OpenGymTheme.spacing.screen),
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
        ) {
            if (showSaveAsTemplate) {
                OutlinedButton(
                    onClick = onSaveAsTemplate,
                    enabled = canSaveAsTemplate,
                    shape = MaterialTheme.shapes.small,
                    // The wider half of an uneven split: "Save as template" is three words and
                    // DONE is one, and an even one ellipsises the label that needs reading
                    // while leaving white space around the one that does not.
                    modifier = Modifier.weight(TEMPLATE_BUTTON_WEIGHT),
                ) {
                    Text(
                        text = stringResource(R.string.summary_save_template),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (showDone) {
                Button(
                    onClick = onDone,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(R.string.summary_done),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * A session id that no longer resolves.
 *
 * Reachable in the historical mode — a row opened from the list, deleted from another screen
 * behind it — and not in the other, where the session was written moments ago. It is drawn in
 * both because "cannot happen" is not a thing a screen should crash to prove.
 */
@Composable
private fun MissingSession(contentPadding: PaddingValues) {
    EmptyState(
        glyph = "◷",
        title = stringResource(R.string.summary_missing_title),
        description = stringResource(R.string.summary_missing_body),
        modifier = Modifier.padding(contentPadding),
    )
}

/** The sentence a message is shown as. */
private fun SessionSummaryMessage.labelRes(): Int =
    when (this) {
        SessionSummaryMessage.NAME_SAVE_FAILED -> R.string.summary_error_rename
        SessionSummaryMessage.NOTES_SAVE_FAILED -> R.string.summary_error_notes
        SessionSummaryMessage.TEMPLATE_SAVED -> R.string.summary_template_saved
        SessionSummaryMessage.TEMPLATE_SAVE_FAILED -> R.string.summary_error_template
    }

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Session summary", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun SessionSummaryScreenPreview() {
    PreviewScreen(previewState())
}

@Preview(name = "Session summary - dark", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun SessionSummaryScreenDarkPreview() {
    PreviewScreen(previewState(), theme = ThemePreference.DARK)
}

@Preview(name = "Session summary - nothing beaten", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun SessionSummaryScreenNoRecordsPreview() {
    PreviewScreen(previewState(records = emptyList(), notes = null))
}

/*
 * The payoff, in its two shapes (#111).
 *
 * Both are captured under reduced motion, which is what makes them *references* rather than
 * frames: the count, the bar and the confetti all land immediately there, so a screenshot shows
 * the state the screen settles on instead of whichever millisecond the recorder happened to
 * catch. See `LocalReducedMotion`, and `ConfettiBurst` for why the burst is absent rather than
 * frozen.
 */

@Preview(name = "Session summary - xp earned", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun SessionSummaryScreenCelebrationPreview() {
    CompositionLocalProvider(LocalReducedMotion provides true) {
        PreviewScreen(previewState(celebration = previewCelebration()))
    }
}

@Preview(name = "Session summary - level up", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun SessionSummaryScreenLevelUpPreview() {
    CompositionLocalProvider(LocalReducedMotion provides true) {
        PreviewScreen(previewState(celebration = previewLevelUpCelebration()))
    }
}

@Preview(name = "Session summary - historical", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun SessionSummaryScreenHistoricalPreview() {
    PreviewScreen(previewState(mode = SessionSummaryMode.HISTORICAL))
}

@Preview(name = "Session summary - editing the note", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun SessionSummaryScreenNoteEditPreview() {
    PreviewScreen(previewState(notesDraft = "Felt strong on bench. Bumped to 80 kg × 5 for the top set."))
}

@Preview(name = "Session summary - saving a template", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun SessionSummaryScreenTemplatePreview() {
    PreviewScreen(previewState(templateName = "Push Day · Upper"))
}

@Preview(name = "Session summary - no such session", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun SessionSummaryScreenMissingPreview() {
    PreviewScreen(SessionSummaryUiState(mode = SessionSummaryMode.HISTORICAL, isLoading = false))
}

@Composable
private fun PreviewScreen(
    state: SessionSummaryUiState,
    theme: ThemePreference = ThemePreference.LIGHT,
) {
    OpenGymTheme(theme = theme) {
        SessionSummaryScreen(state = state, onEvent = {}, onExit = {})
    }
}

/**
 * A `@Preview` renders at the device's height and silently clips a screen with a pinned bar at
 * the bottom, so the captures are given a phone's worth of height explicitly.
 */
private const val FULL_SCREEN_HEIGHT_DP = 900

/**
 * How the pinned bar splits between its two actions. See the note at the call site — three
 * words against one, so the split is not even either.
 */
private const val TEMPLATE_BUTTON_WEIGHT = 1.4f

/*
 * Stable keys for the lazy items.
 *
 * Sections come and go with the session — a recap with no records has one fewer — and without
 * keys the list identifies its items by index, so the muscle card would inherit the records
 * card's state on a session that beat nothing. It also keeps the note field's focus and
 * scroll position across the recomposition a saved note causes.
 */
private const val SECTION_HEADER = "header"
private const val SECTION_STATS = "stats"
private const val SECTION_CELEBRATION = "celebration"
private const val SECTION_ACHIEVEMENTS = "achievements"
private const val SECTION_RECORDS = "records"
private const val SECTION_MUSCLES = "muscles"
private const val SECTION_EXERCISES = "exercises"
private const val SECTION_NOTES = "notes"
