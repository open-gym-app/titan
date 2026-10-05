# Contributing

## Licence

Titan is licensed under the [GNU General Public License v3.0](LICENSE). By submitting a
pull request, you agree that your contribution is provided under the same licence.

## Branch naming

```text
<type>/<short-description>
```

Use the same types as commit messages. Examples:

```text
feat/user-login
fix/crash-on-startup
ci/add-release-workflow
refactor/session-repository
```

## Commit messages

Follow [Conventional Commits](https://www.conventionalcommits.org/).

```text
<type>(<scope>): <description>
```

| Type | When to use |
| ------ | ------------- |
| `feat` | New feature |
| `fix` | Bug fix |
| `refactor` | Code change that is neither a feature nor a fix |
| `test` | Adding or updating tests |
| `ci` | CI/CD changes |
| `build` | Build system or dependency changes |
| `style` | Formatting, lint fixes (no logic change) |
| `docs` | Documentation only |
| `chore` | Anything else that doesn't affect production code |

`scope` is optional and refers to the affected module (e.g. `fix(data): ...`, `feat(ui): ...`).

The description must be lowercase and must not end with a period.

## Naming conventions

### Classes

| Construct | Pattern | Example |
| --- | --- | --- |
| Entity | `<Noun>` | `Session`, `Exercise`, `WorkoutSet` |
| Repository interface | `<Noun>Repository` | `SessionRepository` |
| Repository implementation | `<Noun>RepositoryImpl` | `SessionRepositoryImpl` |
| DAO | `<Noun>Dao` | `SessionDao` |
| Use case | `<Verb><Noun>UseCase` | `LogSessionUseCase`, `GetExercisesUseCase` |
| ViewModel | `<Screen>ViewModel` | `SessionViewModel`, `ExerciseLibraryViewModel` |

```kotlin
// ✅
class LogSessionUseCase
class SessionRepositoryImpl : SessionRepository

// ❌
class SessionManager       // ambiguous - is this a repository? a use case?
class SessionRepo          // abbreviation, inconsistent with the rest
class CreateSession        // missing UseCase suffix
```

### Screens

Every screen is **two** composables, never one.

```kotlin
// Stateful. Wires the ViewModel and nothing else - no layout lives here.
@Composable
fun ExerciseLibraryRoute(
    onExerciseClick: (exerciseId: Long) -> Unit,
    viewModel: ExerciseLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ExerciseLibraryScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onExerciseClick = onExerciseClick,
    )
}

// Stateless. Takes a state object and emits events; knows nothing about Hilt,
// ViewModels or navigation.
@Composable
fun ExerciseLibraryScreen(
    state: ExerciseLibraryUiState,
    onEvent: (ExerciseLibraryEvent) -> Unit,
    onExerciseClick: (exerciseId: Long) -> Unit,
) { /* ... */ }
```

This is a **convention, not a suggestion**, because the split is what makes the rest of
the testing strategy possible:

- `XScreen` can be driven from a Compose test with a hand-built state object, with no
  ViewModel, no Hilt graph and no database behind it.
- `XScreen` can carry `@Preview`s for its interesting states — empty, loading, error, full
  — which is what the screenshot tests render.
- A state that is awkward to construct by hand is a signal the UI state model is wrong,
  and the split surfaces that at authoring time rather than at test time.

Only `XRoute` may take a `viewModel` parameter, and it must default to `hiltViewModel()`
so the composable stays callable from a preview or a test. `XScreen` must never reference
a ViewModel type.

The `@Serializable` navigation type that reaches this screen is `XDestination`, never
`XRoute` — see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#routes-are-type-safe) for why
sharing the name silently renders an empty screen.

### Using the design system

A screen should contain **no `Color(...)`, no `TextStyle(...)` and no `dp` literal for
standard rhythm.** All three come from `:core:ui`'s theme package.

```kotlin
Text(
    text = "THIS WEEK",                                     // eyebrows are uppercased by the caller
    style = TitanTheme.typography.eyebrow,                // ours: eyebrow, numeric*
    color = MaterialTheme.colorScheme.onSurfaceVariant,     // Material: colour, shape, the type roles
    modifier = Modifier.padding(TitanTheme.spacing.md),
)
```

- **Material's own tokens stay on `MaterialTheme`** — `colorScheme`, `shapes`, and the
  Material type roles. There is no wrapper for them; a second way to reach the same value
  is a second thing to keep in step.
- **`TitanTheme.x` is only for what Material has no slot for**: the spacing scale, the
  `eyebrow` and `numeric`/`numericLarge`/`numericSmall` roles, `plateColors` and
  `sessionChrome`.

  `sessionChrome` is the one token set that does **not** invert with the scheme: the active
  workout runs under a solid near-black bar in light *and* dark, because a workout is a mode —
  the bottom bar is hidden and the back gesture does not simply leave — and a header built from
  Material's `inverse*` slots would be near-white half the time. See `theme/TitanSessionChrome.kt`;
  every pair in it is held to WCAG AA by the same test that holds the two schemes.

  `plateColors` is an *ordinal* ramp — the heaviest size on the user's rack is the accent and
  each lighter one steps down through the neutrals — rather than the competition colour code,
  which would put four saturated hues on a screen in a design that has one accent on purpose.
  Every tone carries the label colour that reads on it; see `theme/PlateColors.kt`.
- **Reach for a component before a style.** `core/ui/.../component/` holds the primitives the
  tokens are meant to be used through — `Eyebrow` (which uppercases for you), `SectionHeading`
  (the eyebrow with the rule that makes it read as a section break), `TitanCard` and
  `HairlineDivider` (the bordered surface and the rule inside it), `TitanBadge`. A screen
  that draws its own bordered `Surface` has restated four arguments that will drift.

  The same package holds the shared *workout* components, which are not primitives but whole
  widgets several screens are built from. All of them are stateless and hoisted — state in,
  events out — and none makes a repository or use-case call:

  | Component | Draws | Takes |
  | --- | --- | --- |
  | `SetLogRow` | a set as a row of fields — two value fields chosen by the exercise's logging mode (weight and reps, distance and time, or a hold's time and added weight), RPE, set type, tick, inline validation, and what the same set did last time. The set being typed *and* every set already logged against a workout that can still be changed | text for the fields, because a half-typed number is not a number, an `ExerciseType` and a `DistanceUnit`, plus an optional `onCommit`, an optional `PreviousSet` and an optional `footer` |
  | `HoldStopwatch` | the start/stop control under a hold's entry row, and its reading while it runs. Stopping it fills the time field; it never logs the set | the set number and the elapsed `Duration`, or null when stopped |
  | `RpePicker` | the rating cell as a menu of the 6–10 scale in halves, plus the way back to unrated | the stored rating (× 10), or null |
  | `SetTypePicker` | the leading cell as a menu of standard / warm-up / drop / failure / interval | a `SetType` |
  | `SwipeToDelete` | a row thrown away by dragging it clear off to the left, with the action a screen reader gets instead | what to delete, and what to call it |
  | `SessionHeaderBar` | the workout's dark bar: name, running clock, pause, discard, END, and — on a line of its own above the progress rail — the rest countdown as a pill and a bar | a name, an elapsed `Duration`, a `SessionProgress`, an optional `RestTimerState` |
  | `SessionProgressRail` | the same header's one-sliver-per-exercise progress | a `SessionProgress` |
  | `SessionNameDialog` | what to call a workout, before it starts, during it and after | the field's text, hoisted |
  | `DiscardSessionDialog` | the question a workout is thrown away through, with the count of what it costs in it. Asked from the workout itself and from Home's resume banner | how many sets go with it |
  | `TemplateNameDialog` | what to call a *template*, and what it trains — saving one out of a workout, renaming one, retitling one in the editor | the name and the category, hoisted, plus the refusal to draw under the field |
  | `TemplateCategoryPicker` | the six answers to "what does this train", `None` among them | a `TemplateCategory?` |
  | `ChipSection` | an eyebrow over a wrapping row of chips — a closed vocabulary shown whole rather than scrolled | a label and the chips |
  | `LoggedSetRow` | a set that has already been written, formatted and unwritable — a finished session's recap, a preview. Its tick takes the set back out of the count where a screen allows that | `WorkoutSet` or `ActiveSet`, plus an optional `onCompletedChange` |
  | `SetLogHeader` | the column labels above either of the above — `KG REPS`, `KM TIME` or `TIME KG` | the same `ExerciseType` and `DistanceUnit` the rows were given |
  | `SetTargetLine` | what the next set should be and why — `Target 82.5 kg × 8 · +2.5 kg` over the rule in a sentence — as one button that fills the entry row's fields. `SetLogRow` draws it under the row and the same figures as the fields' placeholders when handed a `target`; `formattedFigures` and `reasonText` are the two strings on their own, for the exercise detail's card | `LoadSuggestion` |
  | `ExerciseListItem` / `ExerciseCard` | name, equipment and muscles, image, "last performed" | `Exercise` + `List<ExerciseMuscleGroupLink>` |
  | `ExerciseImage` / `ExerciseHeroImage` | the bundled photo as a thumbnail and as a 3:2 banner, over the initial it falls back to | `Exercise` |
  | `RestTimerSheet` | the rest bottom sheet's body: countdown, −15s/+15s, pause, skip | `RestTimerState` |
  | `RestCountdown` / `RestTimerIndicator` | the same countdown alone, and as a header pill — the pill takes its two colour pairs from `RestTimerDefaults`, so it can sit on the session chrome as well as on the scheme | `RestTimerState` |
  | `RestProgressBar` | how far through a rest is, as one continuous bar. Shared by the sheet and the header so the two cannot disagree, and continuous where `SessionProgressRail` beside it is segmented, because they count different things | `RestTimerState`, plus its colours and thickness |
  | `RestExpiryAlert` | nothing. It buzzes and plays the device's notification tone when a rest **that was running while it was on screen** reaches zero — not for one restored as finished, and not for one that was skipped | `RestTimerState` |
  | `StatTile` / `StatChip` | one figure as a box, and as an inline pill | a label and a formatted value |
  | `SessionStatGrid` / `SessionStatChips` | the summary's four-up grid, and its one-line form | `SessionStats` |
  | `MetaGrid` | the facts about a thing, two label-and-value cells to a row inside one card | `List<MetaEntry>`, built with `listOfNotNull` |
  | `MuscleContributionList` / `MuscleContributionRow` | the muscle breakdown as bars: a name, its volume, and how it compares | `List<MuscleContribution>`, built with `muscleContributions` |
  | `NotesCard` | the user's own remark on a thing, read as prose and edited in place | the note, the draft, and the prompt shown when there is none |
  | `TemplateRow` | a saved template: its initial, name, `8 ex · ~1h 15m` and category chip, with a slot for whatever the screen puts on the right. Dims and disarms when `enabled` is false | `TemplateSummary` |
  | `SessionRow` | a logged session: its day rail, name, PR badge and `52m · 12 sets · 4,820 kg` | a name, an instant, a duration and two totals |
  | `TrainingCalendar` | a month as a heatmap, trained days shaded against the month's heaviest | a `YearMonth` and a `TrainingDay` per day |
  | `WeekStrip` | the same row without the month around it: seven days, the trained ones filled | a `WeekDay` per day, in the order they are drawn |
  | `TitanTabRow` | the wireframes' section tabs: mono labels, accent underline, ruled off | labels and the selected index |
  | `PrBadge` / `PrValue` | the personal-record tag, and the record's own figure. `formattedValue` writes it as a phrase (`92 kg`, `5:27 /km`, `30.0 km/h`, `1:30 · +20 kg`); `formattedFigure` and `recordUnit` take the two apart, for a `StatTile` that sets them in different type roles. A best pace is drawn in its exercise's `PaceStyle` — a pace, a swim's pace per 100, or a speed — which the repository fills in on every record it reads. `PersonalRecordEntry.formattedImprovement` is what it beat, signed (`+12 kg`, `−0:17 /km`) | the record's label (`PersonalRecord.label()`) / `PersonalRecord` |
  | `PersonalRecordRow` | the same record as a row of its own: the kind as an eyebrow, the date under it, the figure in the accent on the end, with slots for a badge beside the kind and a line under the figure. The list form of `PrValue`, shared by the exercise's bests and the records list | the three pieces already formatted, plus the sentence the whole row is read as |
  | `TitanSearchField` | the in-place search box, with its clear button | the query, hoisted |
  | `TitanFilterChip` / `TitanFilterChipRow` | a filter as a capsule, and a scrolling row of them | a label and a selected flag |
  | `SelectableOption` | one choice in a list where exactly one is taken, as a card: a radio, the name, and a mono line saying what the option means. What a question asked *as a screen* looks like | a label, an optional detail, and a selected flag |
  | `StepProgress` | how far through a numbered flow the user is, as one filled rail | the step and how many there are |
  | `EmptyState` | the "nothing yet" and "nothing matches" page, with an optional way out | a title, a line of prose, an action |
  | `ReorderableItem` / `Modifier.reorderable` / `DragHandle` | a list whose items are picked up by holding one, dragged, and dropped by letting go; the glyph that marks a row as movable | the list, hoisted, plus `onMove` per crossing and `onCommit` per gesture |
  | `CollapsedReorderRow` | what every item of such a list shrinks to while one is held: its place, its name, one fact and its superset | a position and a name |
  | `SupersetLink` | the join in the gap between two exercises, where a superset is made and unmade | whether the two are already one |
  | `SupersetBadge` | the `SS A1` an exercise in a superset wears | the marker from `supersetLabels` |
  | `TitanValueField` | the app's numeric cell: one figure in a filled box, with an optional caption under it and an optional placeholder in it — a suggested figure that is drawn while the box is empty, never typed for the user and never announced | the text, hoisted, and an error |
  | `BodyDiagram` | the front and back figures with the worked muscles highlighted | `Collection<MuscleGroup>` |
  | `BarbellDiagram` | a loaded bar, plates to scale and mirrored, stamped with their weights | `List<PlateCount>` + the rack's sizes |
  | `PlateLegend` | the same loading as a list of what to carry over from the rack | `List<PlateCount>` + the rack's sizes |
  | `LineSeriesChart` | a series as a line with the area under it filled and the latest point emphasised — a trend, scaled to its own floor and ceiling | `List<ChartEntry>` + what the series is called |
  | `BarSeriesChart` | the same series as bars, measured against zero, with the single highest one in the accent | `List<ChartEntry>` + what the series is called |
  | `ProgressRing` | a fraction as a gauge: a track, an accent arc, and a slot for the figure inside it | a `0f..1f` progress, its two labels, and the content |
  | `ChartAxisLabels` | the sparse period labels under a chart (`FEB · MAR · APR`), each over the marks it covers | what `chartAxisLabels` returned |
  | `ChartPlot` | one of the two plots with both of its axes and a tooltip on it: the figures down the left, the periods along the bottom, and what one mark is worth when it is touched | the series, its `ChartScale`, and a formatter for the figures |
  | `ChartCard` | the same, in a bordered surface under its own eyebrow — the shape every chart on a reading screen takes, and the half that remembers which mark is open | a label, the series and its `ChartScale` |
  | `ChangeChip` | `↑ 18%` beside a hero figure — the accent on a rise, neutral on a fall | a `ChangeDirection` and an unsigned magnitude |

  `SetLogRow`, `LoggedSetRow` and `PlannedSetRow` are one table in three states — the set being
  typed, the set that is a record, and the set that has not happened yet — and they share their
  column grid, which is the point of the component: the weight a template plans and the weight it
  becomes are the same cell, at the same width, in the same order. **Which two value cells the
  grid holds is the exercise's logging mode** (`SetValueFields`): weight and reps for `STRENGTH`,
  distance and time for `ENDURANCE`, time and an added weight for `ISOMETRIC`. Always two, in the
  same two cells, so a table of runs lines up under a table of presses and every grid constant
  holds for all three. A time is typed as `M:SS` or `H:MM:SS` — the decimal pad's full stop
  stands in for the colon — and parsed by `parseClockSeconds`; a distance is typed in the unit
  `distanceEntryUnit` picks — kilometres or miles, for every exercise, a pool length as `0.025` —
  and the caller parses with the same unit it labelled the field with. A caller has to pass the same
  `showPrevious` to `SetLogRow`, `LoggedSetRow` and `SetLogHeader` or the labels stop sitting over
  the cells they name; `PlannedSetRow` has its own header (`PlannedSetHeader`) because it drops
  two columns a plan cannot fill in — the previous performance, and the rating.

  **There is no editor.** A logged set is corrected in the row it is already in: on a screen that
  can still be written to, every row of the table is a `SetLogRow` with the values in its fields,
  and what writes one is *leaving* it — `onCommit`, raised when focus goes from the row or the
  keyboard's DONE is pressed. There used to be a third component for this, opened by tapping a
  row, drawing the same cells at the same widths with a tick and a CANCEL under them; it bought a
  footer and cost a tap before a lifter could touch their own numbers. Only one row on a card
  carries the accent, and it is the one the *next* set goes in — `isActive` is not "the row being
  edited", because on this table that is any of them.

  The two pickers are *cells* of that grid rather than blocks under it: the rating is picked from
  the cell that shows the rating and the type from the cell that shows the type, so neither costs
  the page any height. That is deliberate and hard-won — the rating scale used to be nine buttons
  drawn open inside the table, which pushed the sets a rating is judged against off the screen.
  Deleting a set is `SwipeToDelete` on the row for the mirror-image reason: a destructive action
  should be the hardest gesture on a row, not the one nearest the thumb.

  The reorder pair is the one with rules attached. The gesture goes on the **list**
  (`Modifier.reorderable`) and never on the items, because the items move: a handler on the row
  under the finger is detached the moment that row changes places, mid-drag. It reports every
  crossing and the *end* of the gesture separately, so a caller persists once per drag rather
  than once per row crossed. It offers "move up" / "move down" as accessibility actions, because
  a drag is a gesture TalkBack cannot perform — a list that can be reordered must be reorderable
  without one. And the list is expected to **change shape while an item is held**, collapsing to
  one line per item: that is what makes the pick-up unmistakable, and what removes the need for
  auto-scrolling at the edges.

  **A feature must not reimplement one of these.** If one is close but not right, widen it
  here rather than forking it into a feature — a second set row is how two screens end up
  disagreeing about what a warm-up looks like.

  **The charts are `:core:ui`'s and never a feature's.** Five M5 surfaces plot a series, and
  three of them separately carry the acceptance criterion *"values reachable via TalkBack, not
  image-only"* — which is one semantics problem solved once here, or four times in feature code,
  inconsistently. Every chart clears the semantics over its canvas and carries a single sentence
  built from its own series: the shape, the range and the latest value. A feature that draws its
  own plot has also decided, silently, what a flat series and an empty one look like; here those
  are one rule (`ChartStats`, `MIN_PLOTTABLE_ENTRIES`) and a series with fewer than two marks
  draws *nothing plottable* rather than a chart of one bar.

  Values reach a chart **already formatted or already canonical** — `ChartEntry` carries the
  number to plot *and* the string to read out — so the same component draws kilograms and pounds
  without reading `LocalUnitSystem`. Unit conversion stays in the formatters below.

  `ChartCard` is where most of them are actually drawn, and it takes the series **once** for
  every part of the drawing that needs it: both axes have to be given the same entries the plot
  was, or the months sit over marks they do not cover and the figures name a band nothing was
  drawn against. A screen that lays out its own eyebrow, plot and axis has taken that back on.

  Which chart is a `ChartScale` rather than a slot, and that is the price of the value axis: a
  rail of figures has to know whether the band runs from the series' own floor (a line) or from
  zero (bars), and a tooltip has to know where mark seven sits. `ChartGeometry` answers both
  from the same three inputs the drawing uses, so a gridline, a bubble's leader and the point it
  points at are the same pixel by construction. A chart drawn without a value axis is a chart
  whose shape says a lifter is going up and whose card never says up from what to what — pass
  `formatValue` unless a hero figure above the plot already answers it.

- **`supersetLabels` turns a grouping into `A1`/`A2`/`B1`,** and lives in `:core:ui` for the same
  reason the enum labels below do: `Supersets` in `:core:domain` decides what a group *is*, and
  what it is *called* is presentation. Three screens draw the same marker over the same grouping
  — the review panel before a workout, the workout itself, and template authoring — so a second
  implementation is how two of them end up disagreeing about what the third exercise is called.
- **Domain enums are labelled once, in `:core:ui`**, because `:core:domain` is a plain Kotlin
  library that has no business knowing what a string resource is, and because more than one
  feature renders each of them. Two files, split by subject:

  | File | Labels |
  | --- | --- |
  | `component/DomainLabels.kt` | `SetType.label()` / `markerOrNull()`, `PrType.label()` (a kind on its own, as a filter names it: `Fastest`) and `PersonalRecord.label()` (a record, as its exercise reads it: `Best pace`, `Top speed`), `MuscleGroup.displayName()`, `TemplateCategory.label()` |
  | `component/ExerciseLabels.kt` | `EquipmentType.labelOrNull()`, `ExerciseType.label()` / `trackedLabel()`, `MechanicType.label()`, `DifficultyLevel.label()`, `Discipline.label()` |
  | `component/PreferenceLabels.kt` | `ThemePreference.label()` / `detailOrNull()`, `UnitSystem.label()` / `detail()`, `TrainingGoal.label()` / `detail()` |

  `PreferenceLabels.kt` is the same rule applied to the *stored settings*, and it is where they
  live because two screens ask for them: onboarding asks for the training goal and the unit
  system on first launch, and settings offers all three for the rest of the app's life. Each
  label is paired with a `detail` saying what the value means, because `Auto` says nothing
  without "Follows your device" and `Hypertrophy` means little to somebody three weeks in.

  `ExerciseType.label()` and `ExerciseType.trackedLabel()` are the same fact at two lengths —
  `Weight & reps` for a chip, `Weight, reps, RPE` where there is room to say what a set
  records. `Discipline.label()` is *not* the same question: five disciplines are all logged
  as weight and reps, so neither can be derived from the other.
- **Never format a number by hand, and never parse one by hand either.** A date is one of
  these: `28/04/2026` is the fourth of the twenty-eighth to an American reader before it is
  anything else. `core/ui/.../format/` turns the stored milligrams, centimetres, `Duration`s
  and epoch millis into what the user reads, and reads what they type back into the same
  canonical units:

  | Quantity | Stored as | Read with | Typed back with |
  | --- | --- | --- | --- |
  | Weight | milligrams | `formatWeight`, `formatWeightValue` | `parseWeightMilligrams` |
  | Volume | milligram-reps | `formatVolume`, `formatVolumeValue` | — (computed, never typed) |
  | Distance | centimetres | `formatDistance`, `formatDistanceValue` | `parseDistanceCentimetres` |
  | Pace or speed | whole nanoseconds per centimetre (`paceOf`: a best pace's duration over its distance, never a `Double`) | `formatPaceFigure` and `paceUnit` (`5:27` `/km`, `1:45` `/100 m`, `30.0` `km/h`, by `PaceStyle`), `formatPaceChange` (the difference of two, as shown) — integer arithmetic throughout, through `DistanceUnit`'s exact tenths of a millimetre | — (computed, never typed) |
  | Duration | seconds | `formatAsClock` (countdown), `formatAsElapsed` (running session), `formatAsLength` (a finished one, `52m`), `formatAsTotal` (a period's worth, `32h`), `formatAsEstimate` (`~55m`) | — |
  | Date and time | epoch millis UTC | `formatSessionDate` (`Tue, Apr 28, 2026`), `formatDayAndMonth` (`Apr 28`, a table's date column), `formatTimeOfDay` (`10:14`), `formatMonthAndYear` (`April 2026`), `formatMonthAbbreviation` (`Apr`, a chart's axis), `formatDayOfMonth` / `formatWeekday` / `weekdayInitials` (a calendar's parts) | — (picked, never typed) |
  | A bare count | an `Int` or a `Long` | `formatCount` (`12`, `2,140`) | — |
  | A whole set | milligrams and a count | `formatSet` (`80 kg × 5`, `12 reps`, or **null** where there is neither), and `formatSetCompact` (`80×5`) for the one cell too narrow to hold the long form | — (typed field by field) |
  | A change | a ratio or a difference | `formatPercentChange` (`18%`, unsigned — the arrow beside it is the sign), `formatMagnitude` (`3.2`, `0.4`) | — (computed, never typed) |

  The unit system is ambient (`LocalUnitSystem`), so it is a value to read, not a parameter to
  thread through five composables: call the overload that takes the canonical value alone and
  a change to the setting repaints every number on screen without the screen being revisited.

  Everything goes through ICU, on the far side of two seams: `NumberFormats.kt` for the digits
  and `MeasureFormats.kt` for the unit beside them. `"$value kg"` is wrong three ways over —
  the separator is the device's, the symbol is translated (`in` is `po` in French and `pol.`
  in Brazilian Portuguese), and what sits between them is a narrow no-break space in some
  locales and nothing at all in others. The same goes for a date: `formatSessionDate` asks
  CLDR for a *skeleton* — the fields it wants — and lets the locale decide their order, so the
  weekday lands where that language puts it rather than always in front.
- **A parser answers "is this a number", not "is this allowed".** `parseWeightMilligrams`
  returns null only for malformed input; range is `SetValidator`'s, because it is the one that
  can say *which* bound was missed. A form does `parse ?: "not a number"`, then validates.
- **Rounding is reporting, not deciding.** The formatters never snap a value onto a plate
  increment — a 20 kg bar reads `44.09 lb` and not `45 lb`, because the user has to be able to
  load what they read. `roundToPlateIncrement` is for the things that *choose* a weight.

`core/ui/src/test/.../format/FormatCatalog.kt` renders every formatter in both unit systems;
open its previews rather than guessing what a weight or a rest looks like.

- **Every number the user reads is a numeric role.** Weights, reps, durations, dates,
  counts — mono, with tabular figures, so a running timer does not shift the layout.
- The accent (`colorScheme.primary`) marks *the thing that matters right now* — a PR, the
  active set, the running timer, the primary action. Spending it on ordinary chrome is what
  stops it working everywhere else.

`core/ui/src/test/.../theme/ThemeCatalog.kt` renders every token in both schemes; open its
previews rather than guessing what a token looks like.

### Strings

**Every word a user reads is a resource.** Nothing about this is optional: `HardcodedText` is
an error in the shared `lint {}` block, and `./gradlew lint` fails on a violation rather than
warning about one. Lint only reaches XML layouts, and this app has none — so the rules below
are what stands between a Compose screen and a string a translator cannot reach.

- **Where a string lives.** In the module that draws it, except for anything a *shared*
  component says: a `contentDescription` written at the call site would be written once per
  screen and drift four ways, so it belongs to the component, in `:core:ui`. A label for a
  domain enum that two screens both show — a `SetType`, a `TemplateCategory`, a
  `ThemePreference` — goes in `:core:ui`'s `DomainLabels.kt` or `PreferenceLabels.kt` for the
  same reason, so two screens cannot describe one stored value differently. `SettingsLabels.kt`
  in `:feature:settings` records where the line falls and why.
- **Naming.** `<screen-or-component>_<what-it-is>`, lowercase with underscores:
  `set_log_column_reps`, `backup_export_failed`, `quest_log_streak_personal_best`. A
  description a screen reader gets ends in `_description`. Labels are written in sentence case
  even where the design draws them uppercase — `Eyebrow` and `TitanBadge` uppercase for the
  caller, so a translator is never asked to reproduce a typographic treatment.
- **A count takes a `<plurals>`, always.** Not `"$n sets"`, and not a `<string>` with a `%1$d`
  in front of a noun: `1 sets` is the first set of every workout reading wrong in English, and
  English is the language with the *fewest* forms to get wrong. Reach for `pluralStringResource`
  and pass the count twice — once to choose the form, once to fill the placeholder.

  The count that chooses the form is the one the noun belongs to, which is not always the
  first placeholder. `%1$d of %2$d exercises done` agrees with the total, not with what is
  done.

  A chip that draws the figure and its word in two different colours still needs the plural:
  see `stat_sets_short`, a plural with no placeholder in it at all.
- **A translatable string is never concatenated.** If a sentence is assembled from pieces, the
  assembly is a resource too, with positional arguments — `%1$s` and `%2$s` — because word
  order is not universal and neither is what goes between the pieces. `:core:ui` carries the
  joins everything shares: `figure_with_unit`, `spoken_separator`, `badge_with_qualifier`,
  `title_and_body_description`. Use one rather than a `+ " "`.

  The same rule covers the punctuation: a separator that reaches the screen is a resource
  (`exercise_meta_separator`, `plate_list_separator`), not a `private const val " · "`.
- **Right-to-left is a claim the manifest already makes.** `supportsRtl="true"`, so use
  `start`/`end` and never `left`/`right`, `placeRelative` and never `place`, and check any
  `Canvas`: raw pixels mirror nothing on their own. `LevelProgressBar` reads `layoutDirection`
  to decide which end it fills from, and `ChartGeometry` takes one so a chart's marks, its
  month labels and its taps agree about which way time runs.
- **Check the work before a translator does.** The debug build carries the two pseudolocales
  (`isPseudoLocalesEnabled`), which is the cheapest way to find a truncated label and a
  left-anchored row:

  ```bash
  adb shell settings put global debug.force_rtl 1   # mirror every screen
  adb shell settings put global debug.force_rtl 0   # and back
  ```

  The pseudolocales are picked in Developer options → *Languages*, which lists `en-XA`
  (accented, about 30% longer) and `ar-XB` (bidi) once a debug build is installed. Walk every
  screen under both: a label that fits in English and nowhere else is a defect a translator
  should not be the one to find.
- **A new string lands in four files.** The app ships in French, Spanish and Brazilian
  Portuguese, one `values-<locale>/strings.xml` per module beside `values/`, and the words
  each language holds itself to are in [docs/TRANSLATION.md](docs/TRANSLATION.md). Add the
  translations in the same section as the base string, with a `many` form on any plural, and
  run `make translations-check`: it reports every key a locale is missing, and CI runs it on
  every PR as a warning. A string you cannot translate is left out of the locale files, not
  pasted in English — the check keeps it visible until the locale's next pass.

### Files and packages

Each class lives in a package that matches its layer and module:

```text
com.titan.core.domain.usecase.LogSessionUseCase
com.titan.core.data.repository.SessionRepositoryImpl
com.titan.core.data.local.dao.SessionDao
```

## Test coverage

`:core:domain` carries coverage floors enforced by JaCoCo. It is the only module that
does: it is pure business logic with no Android framework escape hatches, so an
uncovered line there is a rule nobody ran rather than platform glue that a JVM test
cannot reach.

| Floor | Scope | Minimum |
| --- | --- | --- |
| Line | the whole module | 95% |
| Line | each package except `model` | 95% |
| Branch | each package except `model` | 75% |

```sh
./gradlew :core:domain:check                          # runs the tests and the floors
open core/domain/build/reports/jacoco/test/html/index.html
```

CI runs the same check and uploads the report as the `core-domain-coverage` artifact on
every run, with a three-day retention to keep artifact storage bounded. On a red build it
says which lines went unexercised.

### What the floors do not measure

They are a **ratchet against regression, not a quality bar.** Read them as "nothing large
went untested in this PR", never as "the domain is tested".

- **A line is not a rule.** Line coverage says a statement executed, not that its outcome
  was asserted on. The per-package branch floor is the stricter half of the pair, which is
  why it exists — but a covered branch is still only a branch that ran.
- **`model` is held to the module line floor and nothing else.** JaCoCo counts the branches
  in the `equals`/`hashCode` Kotlin generates for a data class, and on a wide one that is
  most of what it counts — `ActiveSet` alone contributes 72 missed branches. A branch floor
  over that package would measure how many fields the models carry, not how well they are
  tested, so the package is excluded rather than the floor lowered to accommodate it.
- **The module floor alone would not catch much.** At ~630 lines, 95% leaves room for a
  whole use case nobody tested. That is what the per-package floors are for, and why a new
  package starts life having to clear them rather than being averaged into a passing
  module.

Raise a floor when the module has moved past it and stayed there. Lowering one needs a
reason in the PR description.

## Compose tests

**There is no emulator, and there is no `androidTest/` source set.** Compose tests are JVM
unit tests in `src/test`, run by Robolectric, and they come in two kinds:

| Kind | Asks | Lives in |
| --- | --- | --- |
| Behaviour | does it render this state, and emit this event? | a test class you write |
| Screenshot | does it still *look* the same? | mostly a `@Preview`, captured for you |

Both are picked up by `make test` and by CI's `unit-test` job. **Use `make test`, not
`./gradlew test`:** Roborazzi decides whether to compare from the task graph, so a plain
`./gradlew test` runs the screenshot tests and captures nothing — they pass without
verifying anything. `verifyRoborazziDebug` has to be in the same invocation, which is what
`make test` and CI both do. Everything they need —
`ui-test-junit4`, Robolectric, Roborazzi, the SDK pin, `isIncludeAndroidResources` — comes
from the `titan.android.compose` convention plugin, so a module's build file adds nothing.

`:core:ui`'s `example/` package is a worked copy of both patterns against a screen written
to the two-composable convention above. Start there.

```sh
make test                 # behaviour tests + screenshot verification
make screenshots          # re-record the references after an intended visual change
make screenshots-verify   # verify screenshots, without re-recording (runs the tests too)
```

### How to write a screen test

Drive the **stateless** `XScreen(state, onEvent)` half with a hand-built `XUiState`. No
ViewModel, no Hilt graph, no Room — that is the whole point of the split.

```kotlin
@RunWith(RobolectricTestRunner::class)
class ExerciseLibraryScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<ExerciseLibraryEvent>()

    @Test
    fun `emits ExerciseClicked with the exercise that was tapped`() {
        composeTestRule.setContent {
            TitanTheme {
                ExerciseLibraryScreen(
                    state = ExerciseLibraryUiState(exercises = listOf(benchPress)),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Bench press").performClick()

        assertEquals(listOf(ExerciseLibraryEvent.ExerciseClicked(benchPress.id)), events)
    }
}
```

- Use `createComposeRule()`, not `createAndroidComposeRule<Activity>()` — a stateless screen
  needs no activity, and asking for one drags in a manifest and a theme.
- **A test that composes an `XRoute` needs Hilt**, because `hiltViewModel()` resolves its
  factory through the activity and a plain `ComponentActivity` is not an entry point. That is
  `@HiltAndroidTest` + `@Config(application = HiltTestApplication::class)` +
  `createAndroidComposeRule<HiltTestActivity>()`; `:app`'s `TitanAppNavigationTest` is the
  worked example, and `HiltTestActivity` lives in `app/src/debug`. Prefer not needing it: drive
  the stateless half.
- Do **not** add `@Config(sdk = ...)`. The level is pinned once for every module by the
  generated `robolectric.properties`; a per-class override drifts the day it moves.
- Wrap the screen in `TitanTheme` so it composes against the colours and typography the
  app actually uses.

### How to add a screenshot test

Usually you do not write one. Add a `@Preview` per interesting state — empty, loading,
error, full — and the Roborazzi plugin generates a Robolectric test for it. Previews are
scanned per module, scoped to that module's namespace.

```kotlin
@Preview(name = "Exercise library - empty", showBackground = true)
@Composable
fun ExerciseLibraryScreenEmptyPreview() {
    TitanTheme {
        ExerciseLibraryScreen(state = ExerciseLibraryUiState(), onEvent = {})
    }
}
```

Then `make screenshots` and commit the new PNGs under the module's `src/test/screenshots`.

Write a capture by hand only for what a preview cannot express — a state reached by
*driving* the UI, or a single node when the screen around it is noisy:

```kotlin
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // required; the legacy renderer records a blank image
class ExerciseLibraryScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `captures the expanded filter sheet`() {
        composeTestRule.setContent { /* ... */ }
        composeTestRule.onNodeWithText("Filters").performClick()
        composeTestRule.onRoot().captureRoboImage("ExerciseLibraryScreen_filters.png")
    }
}
```

The path is resolved against the module's `src/test/screenshots` — see
`roborazzi.record.filePathStrategy` in `gradle.properties`.

### How to re-record references

Reference images are committed under `src/test/screenshots`, so a visual change shows up as
an image diff in the pull request. That is the review mechanism: **a re-recorded reference
is a change someone has to look at.**

```sh
make screenshots     # ./gradlew recordRoborazziDebug -Proborazzi.cleanupOldScreenshots=true
git add -A '*/src/test/screenshots'
```

- Re-record only when the change is intended, and say so in the PR description. Re-recording
  to make a red build green is how a regression gets committed.
- `cleanupOldScreenshots` deletes references with no matching test, which is what you want
  after deleting or renaming a preview. It also means **recording a filtered subset of tests
  deletes everything else** — always record the whole module.
- **CI does not upload the diffs** — artifact storage is the scarce resource on a free-tier
  private repository, and a screenshot diff is cheap to reproduce. The failing test names the
  reference file; run `make screenshots-verify` locally and open the matching
  `*_compare.png` under `<module>/build/outputs/roborazzi`, which is a reference / diff /
  actual triptych of what changed.

### Accessibility checks, for free

Every generated preview test also runs the Accessibility Test Framework — the checks behind
Google's Accessibility Scanner — over the composed preview before capturing it, and fails on
what the Scanner would report as an error: a tap target under 48dp, a control with nothing to
say, two controls that say the same thing. Nothing to add for a new screen beyond the preview it
already needs. A test that drives a screen somewhere no preview goes calls
`assertAccessible()` (or `assertAllRootsAccessible()` with a dialog up) from `:core:testing`.
Every screen also carries a `fontScale = 2f` preview, so text that clips at twice the size is
an image diff. docs/ACCESSIBILITY.md has the promises, the one accepted deviation, and how to
read a `TouchTargetSizeCheck` finding — the short version is that the fix is layout, never a
modifier.

### What these tests do not cover

Robolectric runs real composition, measure and layout, so it catches render crashes,
infinite-constraint errors, missing `CompositionLocal`s, recomposition loops and semantics
failures, and the accessibility checks above read the real semantics tree. It does **not**
faithfully reproduce window insets, real font metrics at large font scales, TalkBack behaviour
or IME behaviour. Those belong to the accessibility pass on a real device — `make a11y-scan`,
`make a11y-talkback`, `make a11y-font-scale`, recorded in docs/ACCESSIBILITY.md by
`make a11y-record` — not here. A
green screenshot suite is a floor under an accessibility result, not the result.

### A window size a component test never sees

Every module's own tests run at Robolectric's default 320×470dp/mdpi window unless the test
overrides it, `:core:ui` included. `:app` does not: `app/src/test/resources/robolectric.properties`
drives the whole shell at a real phone's size (`w411dp-h891dp-xxhdpi`), because Home's actions
sit below the fold at the default and a tap injected before a settling scroll lands where the
button used to be. That gap is invisible almost everywhere — but Material3's `AlertDialog` sizes
itself from its content's *intrinsic* width, and at this window size and density a text field
inside one (measured by real `StaticLayout` line-breaking, unlike a `Text`) can answer that
question in a way that depends on the width it is asked about, so the two measurements chase
each other and composition never settles. Not on a device — a frame or two more of layout there
is invisible — and not in a component test either, since none of them run at this window size.
It surfaced once, in `EndToEndJourneyTest` (#187): the one test that assembles the whole app,
which is the one place that runs at the size where it happens. `TemplateNameDialog` and
`SessionNameDialog` both hit it and both now use `TitanDialog` instead of `AlertDialog` — see
its own doc for the fix, which is sizing the dialog from the window's constraints rather than
from its content, so there is nothing left to chase. If a new dialog with a text field in it
ever needs the same shape, reach for `TitanDialog` rather than `AlertDialog` from the start.

## Pull requests

- **One concern per PR** - keep scope tight so the diff stays readable.
- **CI must be green** before requesting a review (lint, tests, build, coverage).
- **Reword what your PR makes untrue.** A comment, doc or string that still waits on an issue the PR closes (`until #54`) should say what the code does now. `make issue-refs-check PR=<number>` lists them.
- **1 approval required** to merge.
- **Squash & merge** - all commits on the branch are squashed into one on `main`. The squash commit message must follow the Conventional Commits format above.
- Delete the branch after merging.
