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
