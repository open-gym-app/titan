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

### Files and packages

Each class lives in a package that matches its layer and module:

```text
com.opengym.core.domain.usecase.LogSessionUseCase
com.opengym.core.data.repository.SessionRepositoryImpl
com.opengym.core.data.local.dao.SessionDao
```

## Pull requests

- **One concern per PR** - keep scope tight so the diff stays readable.
- **CI must be green** before requesting a review (lint, tests, build).
- **1 approval required** to merge.
- **Squash & merge** - all commits on the branch are squashed into one on `main`. The squash commit message must follow the Conventional Commits format above.
- Delete the branch after merging.
