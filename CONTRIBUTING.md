# Contributing

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
|------|-------------|
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
|---|---|---|
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
    style = OpenGymTheme.typography.eyebrow,                // ours: eyebrow, numeric*
    color = MaterialTheme.colorScheme.onSurfaceVariant,     // Material: colour, shape, the type roles
    modifier = Modifier.padding(OpenGymTheme.spacing.md),
)
```

- **Material's own tokens stay on `MaterialTheme`** — `colorScheme`, `shapes`, and the
  Material type roles. There is no wrapper for them; a second way to reach the same value
  is a second thing to keep in step.
- **`OpenGymTheme.x` is only for what Material has no slot for**: the spacing scale, and
  the `eyebrow` and `numeric`/`numericLarge`/`numericSmall` roles.
- **Reach for a component before a style.** `core/ui/.../component/` holds the primitives the
  tokens are meant to be used through — `Eyebrow` (which uppercases for you), `OpenGymCard`
  and `HairlineDivider` (the bordered surface and the rule inside it), `OpenGymBadge`. A
  screen that draws its own bordered `Surface` has restated four arguments that will drift.

  The same package holds the shared *workout* components, which are not primitives but whole
  widgets several screens are built from. All of them are stateless and hoisted — state in,
  events out — and none makes a repository or use-case call:

  | Component | Draws | Takes |
  | --- | --- | --- |
  | `SetLogRow` | the weight/reps/RPE entry row, with set type, tick and inline validation | text for the fields, because a half-typed number is not a number |
  | `LoggedSetRow` | a set that has already been written, formatted | `WorkoutSet` or `ActiveSet` |
  | `SetLogHeader` | the column labels above either of the above | — |
  | `ExerciseListItem` / `ExerciseCard` | name, equipment and muscles, image, "last performed" | `Exercise` + `List<ExerciseMuscleGroupLink>` |
  | `RestTimerSheet` | the rest bottom sheet's body: countdown, −15s/+15s, pause, skip | `RestTimerState` |
  | `RestCountdown` / `RestTimerIndicator` | the same countdown alone, and as a header pill | `RestTimerState` |
  | `StatTile` / `StatChip` | one figure as a box, and as an inline pill | a label and a formatted value |
  | `SessionStatGrid` / `SessionStatChips` | the summary's four-up grid, and its one-line form | `SessionStats` |
  | `PrBadge` | the personal-record tag | `PrType` |
  | `BodyDiagram` | the front and back figures with the worked muscles highlighted | `Collection<MuscleGroup>` |

  **A feature must not reimplement one of these.** If one is close but not right, widen it
  here rather than forking it into a feature — a second set row is how two screens end up
  disagreeing about what a warm-up looks like.
- **Domain enums are labelled once, in `:core:ui`.** `SetType.label()`, `EquipmentType.labelOrNull()`,
  `PrType.label()` and `MuscleGroup.displayName()` live in `component/DomainLabels.kt`, because
  `:core:domain` is a plain Kotlin library that has no business knowing what a string resource
  is, and because more than one feature renders each of them.
- **Never format a number by hand, and never parse one by hand either.**
  `core/ui/.../format/` turns the stored milligrams, centimetres and `Duration`s into what the
  user reads, and reads what they type back into the same canonical units:

  | Quantity | Stored as | Read with | Typed back with |
  | --- | --- | --- | --- |
  | Weight | milligrams | `formatWeight`, `formatWeightValue` | `parseWeightMilligrams` |
  | Volume | milligram-reps | `formatVolume`, `formatVolumeValue` | — (computed, never typed) |
  | Distance | centimetres | `formatDistance`, `formatDistanceValue` | `parseDistanceCentimetres` |
  | Duration | seconds | `formatAsClock` (countdown), `formatAsElapsed` (session) | — |

  The unit system is ambient (`LocalUnitSystem`), so it is a value to read, not a parameter to
  thread through five composables: call the overload that takes the canonical value alone and
  a change to the setting repaints every number on screen without the screen being revisited.
  Everything goes through `NumberFormat`, so decimal and thousands separators are the device's
  — `"$value kg"` is wrong in half of Europe before it is anything else.
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

### Files and packages

Each class lives in a package that matches its layer and module:

```text
com.opengym.core.domain.usecase.LogSessionUseCase
com.opengym.core.data.repository.SessionRepositoryImpl
com.opengym.core.data.local.dao.SessionDao
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
from the `opengym.android.compose` convention plugin, so a module's build file adds nothing.

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
            OpenGymTheme {
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
  `createAndroidComposeRule<HiltTestActivity>()`; `:app`'s `OpenGymAppNavigationTest` is the
  worked example, and `HiltTestActivity` lives in `app/src/debug`. Prefer not needing it: drive
  the stateless half.
- Do **not** add `@Config(sdk = ...)`. The level is pinned once for every module by the
  generated `robolectric.properties`; a per-class override drifts the day it moves.
- Wrap the screen in `OpenGymTheme` so it composes against the colours and typography the
  app actually uses.

### How to add a screenshot test

Usually you do not write one. Add a `@Preview` per interesting state — empty, loading,
error, full — and the Roborazzi plugin generates a Robolectric test for it. Previews are
scanned per module, scoped to that module's namespace.

```kotlin
@Preview(name = "Exercise library - empty", showBackground = true)
@Composable
fun ExerciseLibraryScreenEmptyPreview() {
    OpenGymTheme {
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

### What these tests do not cover

Robolectric runs real composition, measure and layout, so it catches render crashes,
infinite-constraint errors, missing `CompositionLocal`s, recomposition loops and semantics
failures. It does **not** faithfully reproduce window insets, real font metrics at large
font scales, TalkBack behaviour or IME behaviour. Those belong to the accessibility pass on
a real device (#40), not here — a green screenshot suite is not an accessibility result.

## Pull requests

- **One concern per PR** - keep scope tight so the diff stays readable.
- **CI must be green** before requesting a review (lint, tests, build, coverage).
- **1 approval required** to merge.
- **Squash & merge** - all commits on the branch are squashed into one on `main`. The squash commit message must follow the Conventional Commits format above.
- Delete the branch after merging.
