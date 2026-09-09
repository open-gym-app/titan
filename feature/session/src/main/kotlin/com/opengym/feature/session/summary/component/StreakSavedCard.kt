package com.opengym.feature.session.summary.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.opengym.core.ui.component.BadgeTone
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.OpenGymBadge
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.StreakShields
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.session.R
import com.opengym.feature.session.summary.SummaryStreakSave

/**
 * What a spent streak protection looks like: the streak that survived, and what is left of the
 * month's allowance.
 *
 * ### Two facts, in the order they matter
 *
 * The good news first — the streak is intact and this is what it now stands at — and the cost
 * under it. That order is deliberate: the card exists to stop a user finding out about the
 * mechanic by running out of it, but the moment it appears is the moment they were expecting
 * to have *lost* a streak, and leading with the bill would land as a penalty. The allowance
 * line is quiet type on the same card rather than a second one, because it is a footnote to
 * the headline and not an event of its own.
 *
 * ### The marks and the words say the same thing, once
 *
 * `StreakShields` is drawn beside the line that counts them, so its own description is cleared
 * here — a reader who heard "two of three streak protections left this month" and then "2 left
 * this month" would be told twice. The marks are what a *sighted* reader takes in at a glance;
 * the sentence is what everybody else gets, and neither is decoration for the other.
 *
 * Which makes the line beside them the one that has to carry the whole fact, and `2 LEFT THIS
 * MONTH` does not: it names neither the allowance it is two *of* nor the thing there are two
 * of, both of which the marks are drawing for everybody else. So the eyebrow speaks the full
 * sentence — see [Eyebrow]'s `contentDescription`. The drawn words stay short because they sit
 * in a row with the marks on a 360dp screen, and the spoken ones do not have to.
 *
 * ### It is not accented
 *
 * The badge takes `primaryContainer` and nothing else on the card does. The just-finished recap
 * already spends the accent on the XP figure, which is what the screen is *for*; a second
 * accented block under it would make the payoff a page of highlights with no highest one. See
 * `OpenGymPlateColors` for the argument the whole system is built on.
 *
 * The caller draws nothing when there is no save to report, which is nearly every session —
 * see [SummaryStreakSave].
 */
@Composable
internal fun StreakSavedCard(
    save: SummaryStreakSave,
    modifier: Modifier = Modifier,
) {
    OpenGymCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(OpenGymTheme.spacing.card),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OpenGymBadge(
                    text = stringResource(R.string.summary_streak_saved_badge),
                    tone = BadgeTone.ACCENT,
                    // The sentence beside it already says the streak was saved.
                    modifier = Modifier.clearAndSetSemantics { },
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.summary_streak_saved_headline,
                        save.streakDays,
                        save.streakDays,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StreakShields(
                    remaining = save.protectionsRemaining,
                    allowance = save.allowance,
                    // Silent: the line to the right is the same sentence, and a reader must
                    // not hear it twice. See the note above.
                    contentDescription = null,
                )
                val exhausted = save.protectionsRemaining == 0
                Eyebrow(
                    // Zero is its own string rather than a `zero` plural, which English never
                    // selects — and "none left" is worth saying in words.
                    text = if (exhausted) {
                        stringResource(R.string.summary_streak_protections_none)
                    } else {
                        pluralStringResource(
                            R.plurals.summary_streak_protections_left,
                            save.protectionsRemaining,
                            save.protectionsRemaining,
                        )
                    },
                    // The whole sentence, marks included, for the reader the marks are silent
                    // for. See the note above.
                    contentDescription = if (exhausted) {
                        pluralStringResource(
                            R.plurals.summary_streak_protections_none_description,
                            save.allowance,
                            save.allowance,
                        )
                    } else {
                        // The noun agrees with the allowance rather than with what is left of
                        // it: the phrase is "of N protections", and "1 of 1 protections" is the
                        // shape that reads wrong.
                        pluralStringResource(
                            R.plurals.summary_streak_protections_left_description,
                            save.allowance,
                            save.protectionsRemaining,
                            save.allowance,
                        )
                    },
                )
            }
        }
    }
}
