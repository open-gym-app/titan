# Persistence

How the Room schema in `:core:data` is versioned, and the assumptions any consumer of it - analytics, backup, import/export - is allowed to rely on.

## Where the schema lives

| Artifact | Path |
| --- | --- |
| Entities | `core/data/src/main/kotlin/com/opengym/core/data/db/entity/` |
| DAOs | `core/data/src/main/kotlin/com/opengym/core/data/db/dao/` |
| Database + migrations | `core/data/src/main/kotlin/com/opengym/core/data/db/OpenGymDatabase.kt` |
| Reference data + catalogue seeding | `core/data/src/main/kotlin/com/opengym/core/data/db/DatabaseSeed.kt` |
| Generated seed assets | `core/data/src/main/assets/seed/` |
| Seed generator | `tools/seed/generate_seed.py` (`make seed`) |
| Exported schema JSON | `core/data/schemas/com.opengym.core.data.db.OpenGymDatabase/<version>.json` |
| Migration + DAO tests | `core/data/src/test/kotlin/com/opengym/core/data/db/` |

KSP exports one JSON file per schema version (`room.schemaLocation` in `core/data/build.gradle.kts`). Those files are **committed** and wired in as `test`/`androidTest` assets, which is what lets the migration harness build a historical database from its own spec.
Deleting or hand-editing a released version's JSON removes the only record of what shipped.

## Migration strategy

The database is deliberately **not** built with `fallbackToDestructiveMigration`. A version that has no path to the current schema throws at startup rather than silently wiping a user's training history - offline-first means the device is the only copy.

### Checklist for a schema change

1. Change the entities.
2. Bump `version` in `@Database` (`OpenGymDatabase.kt`).
3. Add a `Migration` covering the bump to `OpenGymDatabase.MIGRATIONS`.
4. Build once so KSP exports `schemas/<new version>.json`, and commit it.
5. Add a `MigrationFixture` for the version you migrate *from* in `DatabaseMigrationTest`: it seeds rows through raw SQL at the old version and asserts they survive the upgrade.

`DatabaseMigrationTest` enforces every step - it reads the current version and the exported schema list at runtime, so it never needs editing for a new version, only a fixture:

| Test | Fails when |
| --- | --- |
| `every schema version up to the current one is exported` | step 4 was skipped |
| `the current schema matches its exported spec` | entities drifted from the committed JSON |
| `migrations form an unbroken path from version 1 to the current version` | step 3 was skipped, or a gap between two bumps |
| `every exported version migrates up to the current schema` | a migration produces a schema Room does not accept |
| `every migration preserves the data seeded at its start version` | step 5 was skipped, or a migration loses data |
| `opening a database at an unmigrated version fails instead of wiping it` | a destructive fallback gets added |

### Writing the migration itself

- Foreign keys are **not enforced inside the migration transaction**: `PRAGMA foreign_keys = ON` is applied by `OpenGymDatabase.foreignKeysCallback` in `onOpen`, which Room runs *after* migrations. A migration that rebuilds a table (SQLite cannot alter constraints in place) should finish with `PRAGMA foreign_key_check` to prove it did not orphan any child row.
- `DatabaseSeed` runs from `seedCallback.onCreate`, which fires **only on a fresh install**. New reference data (a muscle group, a system tag) must therefore be inserted by the migration *and* present in the seed, otherwise existing installs never get it. Use `INSERT OR IGNORE` in both, keyed on the unique `name`.
- Version 1 has not shipped yet, so it may still be edited in place with the JSON regenerated - which is how `workout_session.name`, the two `superset_group` columns and `workout_template.category` were added, rather than as a v2 with a migration nothing would ever have run. Once a version is released, it is frozen: correct it with a new version, never by editing it. Editing a version some *other* version migrates from is off the table from the first bump either way - `MIGRATION_FIXTURES` seeds that version's tables by raw SQL, and rewriting the schema underneath it turns a data-loss test into a syntax error.

## Seed data

The baseline exercise library is **generated, not hand-written**. `tools/seed/generate_seed.py` reads the public-domain [`yuhonas/free-exercise-db`](https://github.com/yuhonas/free-exercise-db) dataset, pinned by commit in `tools/seed/source.lock`, and emits two assets:

| Asset | Contents |
| --- | --- |
| `core/data/src/main/assets/seed/exercises.sql` | 17 muscle groups, 6 system tags, 873 exercises, 2 574 muscle links, 1 630 tag links |
| `core/data/src/main/assets/seed/images/*.webp` | One 400 px photo per exercise, ~8 MB total, referenced by `exercise.icon` |

| Command | Effect |
| --- | --- |
| `make seed` | Regenerate from the pinned commit |
| `make seed-update` | Re-pin `source.lock` to upstream HEAD, then regenerate |
| `make seed-check` | Fail if the committed assets are stale (runs in CI) |

`exercises.sql` is replayed verbatim by `DatabaseSeed.insertInto`. Its format is deliberately parser-free - **every line is blank, a `--` comment, or one complete statement** - which is why instruction paragraphs are re-joined with `char(10)` concatenation instead of embedding raw newlines. Anything editing the generator must preserve that invariant. Every statement is `INSERT OR IGNORE`, so a replay over a populated database is a no-op; `DatabaseSeedTest` asserts this.

Exercise, muscle-group and tag ids are **written explicitly** by the generated SQL rather than autoincremented, because the link rows have to reference them. They therefore follow the ordering of `MUSCLE_GROUPS` / `SYSTEM_TAGS` in the generator and the upstream slug sort for exercises: reordering either constant renumbers rows, so the regenerated SQL must be committed with the change.

### Field mapping

| Column | Upstream field | Note |
| --- | --- | --- |
| `name`, `instructions` | `name`, `instructions[]` | Paragraphs joined with `\n` |
| `exercise_type` | `category` | `ExerciseType` is a *logging mode*: every barbell-shaped discipline folds into `STRENGTH`, `cardio` → `ENDURANCE`, `stretching` → `ISOMETRIC` |
| `discipline` | `category` | 1:1. Keeps what the fold above destroys - five disciplines share `exercise_type = STRENGTH`, so neither column derives the other |
| `equipment_type` | `equipment` | Never NULL - upstream's `other` and a missing value both become `OTHER`, because a NULL would defeat the unique index (SQLite treats NULLs as distinct) |
| `mechanic_type`, `difficulty_level` | `mechanic`, `level` | Direct, nullable |
| `is_unilateral` | *derived* | Inferred from name markers (`One-Arm`, `Single-Leg`, `Alternating`, …); no marker means the `false` default, never a guess |
| `icon` | `images[0]` | Asset-relative path to the bundled WebP |
| `exercise_muscle_group` | `primaryMuscles`, `secondaryMuscles` | Exactly one `is_primary` row per exercise, enforced by the generator and by `DatabaseSeedTest` |
| `exercise_tag` | `force`, `mechanic` | Links to the seeded `PUSH`/`PULL`/`STATIC` and `COMPOUND`/`ISOLATION` system tags |

Every upstream field is imported except the second of each exercise's two photos. `machine_brand`, `default_rest` and `notes` stay NULL rather than being fabricated: all three belong to the user, not the catalogue.

`notes` and `instructions` are easy to confuse and must not be. **`instructions` is the canonical how-to** - imperative, second-person, seeded from upstream ("Stand up with your torso upright while holding a barbell…"). **`notes` is whatever the user writes about the exercise** and is never seeded. A third-person summary of a movement belongs in neither: the dataset has no such field, and duplicating `instructions` under a second name is what the old `description` column invited before it was renamed.

## Storage assumptions

Physical units are integers - no floating point anywhere in the schema, so values round-trip through JSON exactly.

| Concept | Unit | Columns |
| --- | --- | --- |
| Weight | milligrams | `workout_set.weight`, `personal_record.weight`, `personal_record.estimated_1rm`, `template_exercise.default_weight`, `body_weight_log.weight` |
| Distance | centimeters | `workout_set.distance`, `personal_record.distance` |
| Duration and rest | seconds | `workout_set.duration`, `workout_set.rest_after`, `personal_record.duration`, `exercise.default_rest`, `template_exercise.rest_duration` |
| RPE | RPE x 10, range 10-100 | `workout_set.rpe` |
| Timestamps | epoch milliseconds, UTC | every `*_at` column |
| Booleans | INTEGER 0/1 | `is_completed`, `is_in_progress`, `is_primary`, `is_system`, `is_custom`, `is_unilateral` |

Other invariants:

- **Enum-like text columns** (`exercise_type`, `discipline`, `equipment_type`, `mechanic_type`, `difficulty_level`, `set_type`, `pr_type`, `workout_template.category`) hold uppercase identifiers validated by the domain layer. Room cannot express SQL `CHECK` constraints, so the database will accept anything - an importer must validate them itself.
- **`workout_set.completed_at` is NOT NULL** even for a set that is not finished; callers write `0` as a placeholder and flag it with `is_completed = 0`. Analytics filter on `is_completed = 1`, so placeholders never reach an aggregate - but a row with `is_completed = 1` and `completed_at = 0` would be counted at epoch 1970.
- **`workout_session.completed_at` is nullable**; a session in progress is identified by `is_in_progress = 1`, and resume picks the highest `last_active_at`.
- **`workout_session.name` is nullable, and NULL is not the same as `''`.** A session started from a template copies the template's name at apply time - `ApplyTemplateUseCase` returns it on the plan and `StartSessionUseCase` writes it - so renaming the template afterwards does not retitle workouts already logged under the old name, and `template_id` is a record of provenance rather than the source of the title. A session started empty has NULL, meaning genuinely unnamed, which is what the UI's date-derived fallback keys off; a `NOT NULL DEFAULT ''` would make "unnamed" and "named with an empty string" the same row. `WorkoutSession.normalizeName` trims the name it is handed and collapses blank to NULL, and it is the one implementation of that rule — the name is set in three places (`StartSessionUseCase` before the session exists, `RenameSessionUseCase` during it and after it), so the app never writes `''` - but nothing in the *schema* holds that, and an importer or a restore writing directly to the table can, so a consumer that treats blank as unnamed should test for it as well.
- **`session_exercise.superset_group` and `template_exercise.superset_group` are nullable ordinals, and NULL means "not in a superset".** Exercises of one session (or one template) sharing a non-null value are performed back to back as one superset, ordered by their `position`; the value itself is an ordinal within that one list, numbered densely from 0 in position order, which is what a screen renders as the `A` in `SS A1`. It means nothing across sessions and is **not stable across an edit** - nothing may store a group id and expect to find the same exercises under it later. Three rules hold it together and *none of them is in the schema*: a group has at least two members, a group's members occupy consecutive positions, and a rest is attributed after the last member rather than between members. `Supersets` in `:core:domain` is where they live and `TemplateError.InvalidSuperset` is what refuses a template that breaks them, so - as with the enum-like columns and the set bounds - an importer or a restore writing to these tables directly has to check them itself. A group left with one member is not an error but a non-superset: the write path dissolves it back to NULL rather than refusing the list.
- **`workout_template.category` is nullable, and NULL is not `GENERAL`.** It holds one `TemplateCategory` name - the chip a template row shows - and NULL means the template was never given one, which a screen renders as no chip rather than as a chip reading "General". One column rather than a `template_tag` join mirroring `exercise_tag`: a template carries exactly one category, so the join would buy a many-to-many nothing asks for, put a join on the read that lists every template, and add seeded reference rows for an importer to resolve by name. As with every other enum-like column the value is unconstrained by the schema, and a name this build does not know reads back as NULL rather than making the row unreadable.
- **A template's estimated duration is derived on read and has no column, deliberately.** `TemplateDuration` in `:core:domain` computes it from the template's exercises - per exercise, `sets x 45 s` of work plus `sets x` its `rest_duration` (falling back to the user's default rest), minus the final rest of the workout, with a superset's members contributing rest only after the last of them. A stored estimate would be wrong the moment an exercise, a set count or a rest changed, and keeping it true would mean a second write on every edit whose only job is to restate the rows; nothing in an export should carry one either, since an importer can compute it from the `template_exercise` rows it already has. The formula is a pacing figure for a list row, not a measurement - what a session actually took is `workout_session.created_at` to `completed_at`.
- **`workout_set` value bounds** - `reps` in 1-10,000, `weight` in 0-10 t (0-10,000,000,000 mg), `duration` in 1-864,000 s (10 days), `distance` in 0-10,000 km (0-1,000,000,000 cm), `rest_after` at least 0 - are checked by `SetValidator` in the domain layer, and nothing in the schema holds them. `SetValidator` is a check a caller runs, not a gate the write path forces it through: anything that persists a set - a use case, an importer, a restore - has to call it first or the bounds simply do not hold for those rows. A NULL dimension is legal and is not a bound violation. The ceilings catch a stray digit or a unit mix-up; they are not meant to be plausible limits, so multi-day ultras and sled pushes fit under them. The widest set they admit is 1e14 milligram-reps of volume, which leaves `SUM(weight * reps)` two orders of magnitude inside a signed 64-bit range over a 100-set session.
- **`workout_set.set_number` is contiguous from 1 within one `session_exercise`, and nothing in the schema holds that either.** It is read as much as stored - it is the `3` in "set 3 of 4", it orders the table under an exercise, and a previous-performance lookup lines two sessions up on it - so a gap in it is a set the user is told they performed and cannot find. `LogSetUseCase` numbers a new set one past the highest in use, and `DeleteSetUseCase` closes the gap a deletion leaves by renumbering the survivors in one write (`SessionRepository.updateSets`); `RestoreSetUseCase` opens it again when an undo puts a set back at the number it had. There is deliberately **no** unique index on `(session_exercise_id, set_number)`, unlike `session_exercise`'s positions: a renumbering would then have to park its rows out of the way first, which buys nothing here, since the invariant a reader depends on is denseness rather than uniqueness and no index can express that. An importer or a restore writing sets directly has to number them itself.
- **Which dimensions a set must fill in depends on `exercise.exercise_type`**, so it cannot be read off the `workout_set` row alone: a `STRENGTH` set records `reps`, an `ENDURANCE` set a `duration` with `distance` optional, an `ISOMETRIC` set a `duration`. `SetValidator` only applies this to rows with `is_completed = 1` - a planned set is stored with its dimensions still empty. Filling the *other* dimensions is never refused; a timed squat set and a loaded carry are both legitimate.

### Identity and uniqueness

| Table | Natural key | Note |
| --- | --- | --- |
| `exercise` | `(name, equipment_type, machine_brand)` unique | Keeps PRs separate per machine brand. SQLite treats NULLs as distinct, so rows with a NULL brand do **not** collide with each other |
| `muscle_group` | `name` unique | 17 seeded values |
| `tag` | `name` unique | 6 seeded system tags, plus user tags |
| `session_exercise` | `(session_id, position)` unique | Reordering must not transiently duplicate a position - `SessionExerciseDao.rewriteLayout` parks every row it is about to move at a negative position first, in one transaction, so no intermediate state has two exercises on one |
| `template_exercise` | `(template_id, position)` unique | Same |
| `workout_set` | none | `(session_exercise_id, set_number)` is dense within one exercise but is **not** indexed unique - the numbering is the use cases' invariant, per the bullet above, and denseness is what a reader needs |

Every `id` is an autogenerated local rowid. **Ids are not portable across devices or exports** - anything that has to match rows across installs (import/export, backup restore, a future sync) must match on the natural key above and remap ids, never trust an exported id.

### Referential actions

| Child | Parent | On delete |
| --- | --- | --- |
| `exercise_muscle_group` | `exercise` | CASCADE |
| `exercise_muscle_group` | `muscle_group` | RESTRICT |
| `exercise_tag` | `exercise` / `tag` | CASCADE |
| `template_exercise` | `workout_template` | CASCADE |
| `template_exercise` | `exercise` | RESTRICT |
| `workout_session` | `workout_template` | SET NULL |
| `session_exercise` | `workout_session` | CASCADE |
| `session_exercise` | `exercise` | RESTRICT |
| `session_exercise` | `template_exercise` | SET NULL |
| `workout_set` | `session_exercise` | CASCADE |
| `personal_record` | `exercise` / `workout_set` | CASCADE |

RESTRICT is what stops an exercise that history points at from being deleted; the UI must offer archiving instead. A restore therefore has to insert in dependency order: `muscle_group`, `tag`, `exercise`, join tables, `workout_template`, `template_exercise`, `workout_session`, `session_exercise`, `workout_set`, then `personal_record` and `body_weight_log`.

`personal_record` is **derived data** - it cascades away with its source set, and can be recomputed from history by the PR engine. An export may omit it.

## Analytics assumptions

The aggregates in `WorkoutSetDao` and `PersonalRecordDao` run in SQLite so results scale with the number of buckets, not the number of sets. They assume:

- **Volume = `weight x reps`**, in milligram-reps. A set missing `weight` or `reps` (cardio,
  holds) contributes 0 rather than dropping its bucket from the series.
- **Only completed sets count** (`is_completed = 1`).
- **Weeks start Monday, UTC.** `getVolumeByWeek` derives the epoch day as `completed_at / 86400000` and shifts by `+ 3` because epoch day 0 was a Thursday. There is no local-timezone or DST handling: a session logged late on a Sunday evening west of UTC lands in the following week's bucket.
- **Day ranges are half-open** `[startEpochDay, endEpochDayExclusive)` and are expressed in epoch days, converted to milliseconds inside the query.
- **Muscle attribution is per link, not per set.** `getVolumeByMuscleGroup` fans a set out across `exercise_muscle_group`, so `total_volume` credits the full volume to every linked group and intentionally sums to more than the session total. `weighted_volume` applies 100% to a primary link and 50% to a secondary one, with integer division truncating the halved value.
- **`weighted_volume` has a domain twin.** `MuscleVolumeCalculator` in `:core:domain` applies the identical rule to sets already held in memory, for a session or window that has been loaded anyway; the SQL aggregate is for ranges wide enough that materialising the sets would be wasteful. Both halve *each set* before summing, so the truncation matches figure for figure — `MuscleVolumeParityTest` pins that, and an edit to either rule has to change both.
- **Attributed volume is not conserved, and the 17-row taxonomy sharpens that.** A secondary link takes a flat 50% however many secondaries the exercise has, so a 1-primary/9-secondary movement attributes 5.5x the volume actually lifted, against 2x for a bench press. The figures therefore track a muscle against itself over time; they are not a share-of-effort split, and no consumer should render them as parts of a whole.

## Hot paths and their indexes

| Query | Depends on |
| --- | --- |
| `WorkoutSessionDao.getActive` | `workout_session(is_in_progress)`, `(last_active_at)` |
| `WorkoutSessionDao.getAll` / `getAllPaginated` | `workout_session(created_at)` |
| `WorkoutSetDao.getHistoryByExercise` (+ paginated) | `session_exercise(exercise_id)`, `workout_set(session_exercise_id)`, `(completed_at)` |
| `WorkoutSetDao.getVolumeByWeek` / `getVolumeByMuscleGroup` | `workout_set(completed_at)`, `exercise_muscle_group(muscle_group_id)` |
| `PersonalRecordDao.getRecentRecords` | `personal_record(achieved_at)` |
| `PersonalRecordDao.getByExercise` | `personal_record(exercise_id, achieved_at)` |
| `PersonalRecordDao.getHeadlineRecords` | `personal_record(exercise_id, achieved_at)`, **and its query shape** - see below |
| `ExerciseDao.getAllMuscleGroupLinks` | `exercise_muscle_group(exercise_id, muscle_group_id)` (the composite primary key) |
| `BodyWeightLogDao.getAll` / `getLatest` | `body_weight_log(created_at)` |

`LargeHistoryBenchmarkTest` seeds a power-user history (10k sessions, ~30k session exercises, 100k sets, 10k personal records) and asserts each of these stays under 500 ms. The budget is generous on purpose: it catches a dropped index turning a join quadratic, not micro-regressions. Unbounded reads (`getHistoryByExercise`, `getAll`) keep a `PagingSource` variant - screens should use it and leave the `Flow<List<...>>` form to charts and exports.

**An index is not always the whole story.** `getHeadlineRecords` picks one record per exercise, which needs a per-exercise "best" and cannot be served by an index alone: its preference order leads on a `CASE` over `pr_type`, and a sort whose leading key is an expression builds a temporary b-tree however the table is indexed. What decides its cost is therefore *how many times that sort runs*. Written the obvious way - a subquery correlated on the outer row - it runs once per record and measures **1,263 ms** on the fixture above; driven from `SELECT DISTINCT exercise_id` it runs once per exercise and measures **6 ms**. The benchmark is what holds it there, so treat the query's shape as load-bearing rather than stylistic, and re-measure rather than reason about it if you rewrite it.

## The in-progress session draft

The database is not the only thing the app writes. A workout in progress also has a **draft** - `ActiveSession` in `:core:domain`, encoded by `ActiveSessionFormat` as a single JSON document - saved so a process kill does not cost the user their place in the session. It is deliberately outside the schema: it is a snapshot of a screen, not a record of a workout, and it is written far too often to be worth a migration ladder.

- **It overlaps the rows rather than replacing them.** The sets are in `workout_set` either way; the draft is what carries the parts no column holds - the sets half-typed and not yet ticked, how long the session has spent paused, and the rest countdown. Where a draft entry has an id it names the row it came from, so a restore updates rather than duplicates; where the id is null the entry exists only in the draft.
- **`currentExerciseIndex` is vestigial.** It records which exercise the user was on, and the active session screen no longer has an answer: every exercise of the workout is on the page and every one of them takes sets, which is what makes a superset loggable. The screen writes `0` and restores each exercise's half-typed set from that exercise's own trailing incomplete `ActiveSet`. The field stays in the format because removing it is a version bump that buys nothing.
- **Losing it costs a place, never a set.** Every consumer treats an unreadable draft as no draft, which is why `InProgressSessionRepository.load()` returns `null` for a corrupt document as readily as for an empty store, and why nothing here up-converts.
- **The document carries its own version**, outside the session object, for the reason the export envelope does: a draft written by a build that laid the fields out differently is refused rather than misread. Adding a field with a default does not need a bump - `encodeDefaults` and `ignoreUnknownKeys` make that survivable in both directions.
- **It carries the superset grouping too.** `ActiveExercise.supersetGroup` mirrors the column, because the grouping is read on every logged set - it decides whether a rest is due yet (`ActiveSession.isRestDueAfter`) - and an exercise added to a running session may have no row to read it back from. Adding it needed no format bump, per the point above.
- **The rest timer is persisted as an absolute deadline**, epoch millis UTC, never as a remainder. A remainder is stale the instant it is written; a draft reloaded ten minutes later must restore a rest that is *over*. `RestTimerSnapshot` holds the deadline and does the conversion, so `RestTimerState` and `RestTimerController` stay free of any notion of an absolute instant. A paused rest is the exception and is stored as its remainder, which does not go stale. Pausing the *session* is a different question and does not pause the rest: the session clock measures how long the user has been working out, the rest measures how long they have been standing still, and only the first is suspended by a pause.
- **The wall clock, not `elapsedRealtime()`.** `SystemClock.elapsedRealtime()` is the only Android elapsed-time reading that counts device sleep, but it resets to zero on reboot, which makes a persisted deadline meaningless afterwards. For a rest measured in minutes, persisting the wall-clock deadline and letting a reboot read as "the rest is over" is the better trade.
- **What `:core:data` owes on top of the interface:** `save` must be atomic. It is called on every logged set, so a kill landing inside a write is not a remote possibility, and the failure mode is a truncated document. A write-then-rename, a DataStore commit or a single-row transaction all satisfy it; a `File.writeText` over the live document does not.
- **Who writes it.** `ActiveSessionViewModel` is the only writer, and it saves after every
  change — a field edited, a set logged, an exercise opened, a pause. Reading it back is the same
  screen's job on open, and a draft naming a *different* session is left alone rather than
  cleared: at most one session runs at a time, so that means the screen was opened on a session
  it should not have been, and deleting somebody's place in a workout to tidy up is the worse of
  the two mistakes. Clearing it belongs to the two use cases that end a session —
  `FinishSessionUseCase` and `DiscardSessionUseCase` — because the draft's lifetime is the
  session's, and a second closer that forgot it would be a bug nobody sees until the next launch.
- **How it is stored today:** `InProgressSessionRepositoryImpl` writes `in-progress-session.json` in the app's private `filesDir`, via write-then-rename - the bytes go to a `.tmp` sibling, are forced to disk with `FileDescriptor.sync()`, and are then moved onto the live document with `ATOMIC_MOVE`. The scratch file is a sibling rather than a cache-directory file so the move stays a rename within one filesystem; `ATOMIC_MOVE` across mount points degrades into a copy, which is the thing this is avoiding. Concurrent savers serialise on a mutex, since they share the one scratch path. A leftover `.tmp` from a save that was killed mid-write is harmless: nothing reads it, the next save truncates it, and `clear()` removes it.

Restoring on relaunch is *not* alerting. Nothing reads a database while the process is dead, so a rest that ran out while the app was gone comes back `Finished` without ever having rung. Alerting at the deadline needs an OS-scheduled wake-up (`AlarmManager` plus a notification) and is not built yet.

## The user preferences store

The third thing the app writes, after the schema and the draft: the user's settings - `UserPreferences` in `:core:domain`, held in a **Preferences DataStore** at `user-preferences.preferences_pb` in the app's private storage, implemented by `UserPreferencesRepositoryImpl`.

Outside the schema for the same reason the draft is, from the other direction. A setting is not a record of anything that happened; it is a single-valued fact that is read on the way to the first frame of nearly every screen and written a handful of times in a device's life. A one-row table would buy a migration ladder for a document with six scalar fields and put a database read in front of the first render.

| Key | Type | Default |
| --- | --- | --- |
| `unit_system` | `UnitSystem` name | `METRIC` |
| `theme` | `ThemePreference` name | `SYSTEM` |
| `default_rest_duration_seconds` | int64 seconds | 90 |
| `bar_weight_milligrams` | int64 milligrams | 20 000 000 (a 20 kg Olympic bar) |
| `training_goal` | `TrainingGoal` name | `STRENGTH` |
| `onboarding_completed` | bool | `false` |

- **Preferences DataStore rather than Proto.** Proto would add the protobuf plugin, a `.proto` file and a generated-source directory for six scalars, and the typed document it buys is largely spent again: `UserPreferences` is declared in `:core:domain` where the storage layer cannot reach it, so a mapping step exists either way. Preferences also degrade *per key* where a serialized document is all-or-nothing, and `edit` over one key is a narrower write than `updateData` over a whole document - a new setter physically cannot clobber a sibling by forgetting to copy it through.
- **Key names and enum names are a persisted format.** Renaming `unit_system`, or renaming `UnitSystem.METRIC`, silently resets that setting for every existing install. `UserPreferencesTest` pins the enum names for exactly this reason; the keys are string literals in one private object rather than derived from anything a refactor would rename.
- **Units are canonical, as everywhere else.** `bar_weight_milligrams` is milligrams because it is added to and subtracted from set weights, and `unit_system` decides only how a number is *rendered*. Nothing in the store is written in the user's display units - see the note under [Storage assumptions](#storage-assumptions).
- **Every read falls back to the default, three times over.** A key that is absent (a fresh install), a key holding an enum name this build does not have (a downgrade, or a value since renamed), and a key holding a number the model refuses (a non-positive rest or bar weight, which no setter would have written) all produce the documented default rather than an exception, and an unreadable store produces a whole default object. A settings read is on the path to the first frame; it must not be the thing that throws. Rejection happens on the *write* side, where `require` refuses a bad value before it can be stored.
- **Emissions are `StateFlow` semantics over a file.** `observe()` delivers the current settings to every new collector immediately, without a write having to happen, then re-emits on change - conflated, latest-wins, and silent when a write changes nothing. That is the contract `SessionStateRepository` documents, and the screens depend on it: `observe().first()` has to return on a fresh install.
- **The rest duration is stored to the second.** A value finer than that is truncated on the way in, which is the resolution a rest timer is set in anyway - except one that would truncate to *zero*, which is rejected rather than stored, since the reader would then discard it for the default and the setting would silently not take.

The store is not part of the export envelope. Settings describe a device and an install, not a training history, and restoring someone's theme onto a new phone is not what a backup is for.

## Implications for backup and import/export

- The export envelope must carry the schema version it was written at. An import reads it and refuses (or up-converts) anything it does not understand - the Room migration ladder only upgrades a *database file*, not a JSON document.
- Match on natural keys and remap ids, as above.
- Reference data (17 muscle groups, 6 system tags) already exists on the target install; import must resolve it by name rather than inserting duplicates.
- Units are integers and timestamps are epoch-millisecond UTC, so no locale or precision conversion belongs in the serializer; converting to kilograms, pounds or local time is a UI concern.
- Restoring by replacing the database file is only safe when the file's version is one the app can migrate; prefer a row-level import that goes through Room.
