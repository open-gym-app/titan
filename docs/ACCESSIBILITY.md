# Accessibility

What the app promises a user of TalkBack, a large system font or a thumb, how the promise is
checked on every build, and what the pass on a real device found. This is the record #40 asked
for; the *Device pass* section is filled in by whoever last ran it, on the device they ran it
on.

## What is checked on every build

`make test` runs the Accessibility Test Framework — the library behind Google's Accessibility
Scanner — over **every `@Preview` in every Compose module**, before the preview is captured
for its screenshot. A preview that the Scanner would flag on a phone fails the build here. The
plumbing is `:core:testing`'s `AccessibleComposePreviewTester`, named as the Roborazzi
plugin's tester in `AndroidComposeConventionPlugin`; a screen test that drives the UI into a
state no preview shows calls `assertAccessible()` (or `assertAllRootsAccessible()`, with a
dialog up) from the same module.

| Check | What it catches | Severity |
| --- | --- | --- |
| `TouchTargetSizeCheck` | a tappable element announced under 48 × 48 dp | error |
| `SpeakableTextPresentCheck` | a control with no label a screen reader can say | error |
| `DuplicateSpeakableTextCheck` | two controls announced identically | warning, logged |
| `DuplicateClickableBoundsCheck` | two clickables on the same pixels | error |
| `TraversalOrderCheck` | a focus order that loops or skips | error |
| `TextContrastCheck`, `ImageContrastCheck` | contrast below WCAG AA on the rendered pixels | error |
| `EditableContentDescCheck`, `RedundantDescriptionCheck`, `ClassNameCheck`, `LinkPurposeUnclearCheck`, `TextSizeCheck`, `UnexposedTextCheck` | the rest of the `LATEST` preset | as ATF rates them |

ATF's *errors* fail the test; its *warnings* are printed. That is the Scanner's own line, and
the acceptance criterion is zero errors. Note the two things the run needs, both already set
by the convention plugin: Robolectric on SDK 34 or later, and native graphics.

A few things worth knowing before reading a failure:

- **Compose reports a control's touch bounds grown to 48dp — into room nothing else holds.**
  Siblings are settled from last to first, so a 32dp control four dp from its neighbour is
  announced as 36dp wide whatever modifier it carries. The fix is always layout: a gap, a
  padding, or a minimum size. `MIN_TOUCH_TARGET` in `:core:ui`'s theme is the token, and
  `SetLogRow`'s note on its columns is the worked example of spending width on clearances
  rather than on wider cells.
- **A preview shorter than its content produces findings about the preview.** A row cut to a
  13dp sliver at the bottom edge reads as a 13dp target with no label. Give a catalogue
  preview a `heightDp` that shows the whole catalogue; give a list screen with a bottom bar a
  height that puts the bar under the content, as the template editor does. On a device the same
  half-row under a `Scaffold` bottom bar is also flagged, at that scroll position — scroll a
  list so no row is cut before you scan it.
- **The check runs when the capture does:** under `recordRoborazziDebug` and
  `verifyRoborazziDebug` (`make test`, `make screenshots`, CI), and not under a bare
  `./gradlew test`, which never composes a preview. Same rule as the screenshots.

Beyond ATF, the per-component semantics tests in `:core:ui` hold the specific promises below.

## What the app promises

### Charts and the body diagram

`LineSeriesChart`, `BarSeriesChart` and `BodyDiagram` are drawn shapes. Each is **one
node** with a sentence for a description — "Cumulative volume, Feb to Apr. Latest 108,400 kg.
Highest 108,400 kg in Apr, lowest 40,200 kg in Feb." / "Body diagram. Worked directly: Chest,
Shoulders. Also worked: Triceps." — and `clearAndSetSemantics` over the canvas, so twelve
marks are not twelve swipes. Decided once in `:core:ui` (`chartDescription`,
`BodyDiagram`) and asserted by `ChartSemanticsTest` and `BodyDiagramTest`. `ProgressRing`
follows the same rule with its fraction.

### Live regions — the things that change without a tap

A value that updates silently is invisible to TalkBack. Three are announced, each as a
**polite** live region, each at a cadence chosen so the region does not talk over the screen:

| What | Where | Says | How often | Test |
| --- | --- | --- | --- | --- |
| The rest countdown | the sheet's status eyebrow (`RestCountdown`); the header pill (`RestTimerIndicator`) once the sheet is swiped away | "Resting, 1:30 left" — the time left rounded **up** to the quarter-minute; "Rest paused, 1:23 left"; "Rest complete" | four times a minute, never on the exact second | `RestTimerSheetTest` |
| The set counter | `SessionStatChips(announces = true)` on the active session | "5 sets · 2,800 kg", as one node — the chips' own words in the chips' own order | once per set logged | `StatTest` |
| Exercise progress | `SessionProgressRail` | "3 of 8 exercises done" | once per exercise finished | (rail description in `SessionHeaderBar`) |
| Import / export progress | `BackupScreen`'s `ProgressRow` | "Exporting… 40%" | in tens of a percent — at most ten times a run, not once per page of rows | `BackupScreenTest` |

Only one rest region is live at a time: the pill announces only while the sheet is not up
(`restTimerAnnounces = !state.isRestSheetOpen`), or the same quarter-minute would be said
from two windows.

### State, in words

`stateDescription` is what TalkBack says *first*, before the label, and it is the same two
words on every instance rather than whatever a reader infers from a flag:

| Control | Says | Test |
| --- | --- | --- |
| `SelectableOption` (onboarding goal cards, settings pickers' dialogs) | "Selected" / "Not selected" | `SelectableOptionTest` |
| `PreferenceRow` (a settings picker) | its current value — "Auto, Theme, button"; the drawn value is cleared from the tree so it is said once | `PreferenceRowTest`, `SettingsScreenTest` |
| `CompletionToggle` (the set tick) | "Completed" / "Not completed", label "Set 3" | `SetLogRowTest` |

`PreferenceInfoRow` — the Settings "Font scale" row, the version — is not a control: it reads
as text and offers no tap, asserted by `SettingsScreenTest` and `PreferenceRowTest`.

A `PreferenceRow` with `enabled = false` — *Restore default templates* when none is missing —
is announced as disabled, through `clickable(enabled = false)` rather than through dimming
alone, and its supporting line says why it does nothing. Asserted by `PreferenceRowTest` and
`SettingsScreenTest`.

### A control answers to the words drawn on it

Every node that can be pressed has a spoken name that contains the text visible on it (WCAG
2.5.3, *Label in Name*): the `END` button is "End the session", not "Finish the session"; the
join between two exercises is "Superset. Perform Bench press and Cable fly back to back", with
the pill's own word first; a session row opens "2:38 PM, PR, personal record, …", the record
straight after the name the way the badge sits beside it; the quest board's link is
"Achievements, everything you have unlocked: 2 of 6". Two readers depend on it. A Voice Access
user says what they can read, and a name that paraphrases the label is a control they cannot
reach. Accessibility Scanner's `UnexposedTextCheck` holds every node to the same rule — the
OCR'd text, case and punctuation stripped, must be a substring of the spoken text — so a control
that fails it on a phone is a finding, not a style. `SupersetLinkTest`, `SessionsComponentsTest`,
`StatTest` and the screen tests hold the names.

A merged node that *replaces* its children's semantics (`clearAndSetSemantics`) replaces their
click too. A control merged that way re-supplies the action inside the block — `SupersetLink`
and `RestTimerIndicator` are the pattern — or puts the `clickable` *before* the merge in the
modifier chain, as `SessionRow` does. A label with no action under it is a node TalkBack reaches
and cannot activate; `SupersetLinkTest` drives the semantics action, not a tap, because a tap
passes over exactly that.

### Touch targets

Every control is announced at 48 × 48 dp or more, held by ATF's `TouchTargetSizeCheck` on
every preview. The notable decisions:

- **The set table** (`SetLogRow`, `PlannedSetRow`): the set-type picker, the RPE picker and
  the tick keep their drawn 28 / 32 / 32 dp; the grid carries two clearances instead
  (`INDEX_CLEARANCE`, `TOGGLE_CLEARANCE`) and gives up its 8dp side padding to pay for them.
  A timed exercise's fields (time, distance, added weight) take the same two cells the weight
  and reps do, so they inherit the same widths; each names its set and, for a distance, its unit
  ("Distance in km, set 2"), because the header that says so is hidden from a screen reader.
- **The hold stopwatch** (`HoldStopwatch`): a full-width button under the row, floored at 48dp
  tall. Its reading is drawn and not announced — a live region a second for the length of a
  plank is noise — and the stop control speaks it instead: "Stop timer, set 2, 0:47 elapsed".
- **Filter chips** (`TitanFilterChip`): drawn at 30dp inside a 48dp
  `minimumInteractiveComponentSize`, as Material's own chips are. Chip sections got 18dp of
  air between lines out of it.
- **The workout header** (`SessionHeaderBar`): the three 36dp controls sit 12dp apart; the
  rename target is the whole name-and-clock block, merged into one node, rather than a 13dp
  eyebrow.
- **The weekday picker** wraps to two lines (`M T W T` / `F S S`) wherever seven 48dp cells
  do not fit — which is every phone in portrait.
- **Bottom sheets** pass `SheetDragHandle`, Material's 32dp handle inside a 48dp box.
- **Preference rows** have a 48dp floor.

### Font scale

Every screen has a `fontScale = 2f` preview — "*Screen* - twice the font" — recorded under
`src/test/screenshots` like any other reference, so a change that starts clipping or
overlapping text at 2× is an image diff in the pull request. Home's `HomeScreenLargeFontPreview`
was the pattern; the rest follow it. Robolectric's font metrics are not a phone's (see
*What this does not cover*), so these are the regression guard, and the device walk below is
the result.

## Accepted deviations

Each is scoped to one check on one component, by test tag, in
`AccessibilityChecks.ACCEPTED_DEVIATIONS`; nothing else is suppressed. Adding one means adding
to this list.

| Component | Finding | Why it is accepted |
| --- | --- | --- |
| `TrainingCalendar` day cells (`calendar:day`) | `TouchTargetSizeCheck`: ~40dp wide | A month is seven columns and a phone's card gives the row about 300dp; seven 48dp targets need 372. There is no layout that fixes it short of a calendar with fewer days in a week. A trained day's tap filters the session list to that day, which the date filter reaches at full size, so the cell keeps the shortcut at the size the grid allows. Every cell still announces its date and session count. |

### Scanner warnings accepted on the device

ATF's *errors* are the gate; these are its *warnings*, seen on the device pass and left as they
are, each for a reason. Every one is on a node that cannot be pressed, or that a screen reader
reaches through the context around it.

| Finding | Where | Why it is accepted |
| --- | --- | --- |
| `UnexposedTextCheck`: the spoken sentence paraphrases the drawn text | `LevelHeader` ("Level 14, 91 points to the next level" for `LVL 14 · 240 / 331 XP`), the summary's `ExerciseBreakdownCard` rows ("2 sets of 8, 50 kg max" for `2 × 8 · 50 kg max`), `RecentAttemptsTable` rows | None of these can be pressed, so no Voice Access command targets them, and the sentence says what the abbreviations mean — "LVL" and "×" read aloud are letters and a symbol. WCAG 2.5.3 is a rule for controls, and every control follows it (above). |
| `UnexposedTextCheck`: the rest pill shows `1:28` and says "Resting, 1:30 left" | `RestTimerIndicator` | The pill is a live region announced to the nearest quarter-minute above the remainder — four times a minute rather than sixty — and the description *is* what the region speaks. The exact figure is the sheet's digits, one tap away. See *Live regions*. |
| `DuplicateSpeakableTextCheck`: "Weight, set 1" under exercise 1 and under exercise 2 | `SetLogRow`'s cells | The exercise heading is read immediately before its table in traversal order, so the context is never more than one swipe back; naming the exercise on five cells of every set would make each control a sentence. Voice Access disambiguates duplicates with its numbered overlays. |
| `DuplicateSpeakableTextCheck`: two `FIRST TIME` badges, two `KG` unit tags | `SessionExerciseCard`, `ExerciseProgressionScreen` | Neither can be pressed, and each is the same fact about a different card it sits on. |

## What this does not cover

Robolectric runs real composition, measure and layout, and ATF reads the real accessibility
tree, so a missing label, an undersized target, a duplicate announcement or a broken traversal
order is caught on the JVM exactly as on a phone. It does **not** faithfully reproduce:

- window insets — edge-to-edge, content under the system bars;
- real font metrics at large scales, and the IME;
- TalkBack itself: its focus order heuristics, what it says for a role, how it queues live
  regions.

Those are what the device pass is for. A green build here is the floor under it, not the
result.

## Device pass

Run it with the app's own targets, in this order, on a physical device on adb:

```sh
make a11y-scan               # installs the debug build, switches Accessibility Scanner on, opens the app
make a11y-talkback           # TalkBack on, for the linear-navigation walk
make a11y-font-scale         # system font scale 2.0 (SCALE=1.5 for the middle step)
make a11y-reset              # everything off, font scale back to 1.0
```

The inventory is every destination in the navigation graph — all twenty:

| Slice | Screens |
| --- | --- |
| Onboarding | Onboarding (5 steps) |
| Home | Home |
| History | Sessions, Template editor, Exercise library, Exercise detail, Plate calculator, Exercise picker |
| Session | New session, Active session, Session summary |
| Progression | Stats, Personal records, Exercise progression, Quest log, Achievements, Body measurements |
| Settings | Settings, Backup & restore, Reminders |

For each: Scanner — tap its button, expect zero errors (the calendar's day cells are the
accepted exception above); TalkBack — swipe right through every element and confirm every
action is reached in reading order; 2× — walk the screen and look for clipped or overlapping
text. Then, with TalkBack on and the screen face down: start a session, log a set, rest, log
another, finish. The rest timer should speak four times a minute and once when it ends; the set
counter once per set.

### Record

| | |
| --- | --- |
| Device | _not yet run on this branch_ |
| Android version | |
| TalkBack version | |
| Accessibility Scanner version | |
| Date | |
| Scanner errors | |
| TalkBack walk | |
| 2× font scale | |
| Deviations found | |

Fill the table in and commit it with the pass. The JVM gate above is what keeps the result
true between passes.
