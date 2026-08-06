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
- Version 1 has not shipped yet, so it may still be edited in place with the JSON regenerated. Once a version is released, it is frozen: correct it with a new version, never by editing it.

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

- **Enum-like text columns** (`exercise_type`, `discipline`, `equipment_type`, `mechanic_type`, `difficulty_level`, `set_type`, `pr_type`) hold uppercase identifiers validated by the domain layer. Room cannot express SQL `CHECK` constraints, so the database will accept anything - an importer must validate them itself.
- **`workout_set.completed_at` is NOT NULL** even for a set that is not finished; callers write `0` as a placeholder and flag it with `is_completed = 0`. Analytics filter on `is_completed = 1`, so placeholders never reach an aggregate - but a row with `is_completed = 1` and `completed_at = 0` would be counted at epoch 1970.
- **`workout_session.completed_at` is nullable**; a session in progress is identified by `is_in_progress = 1`, and resume picks the highest `last_active_at`.
- **`workout_set` value bounds** - `reps` in 1-10,000, `weight` in 0-10 t (0-10,000,000,000 mg), `duration` in 1-864,000 s (10 days), `distance` in 0-10,000 km (0-1,000,000,000 cm), `rest_after` at least 0 - are checked by `SetValidator` in the domain layer, and nothing in the schema holds them. `SetValidator` is a check a caller runs, not a gate the write path forces it through: anything that persists a set - a use case, an importer, a restore - has to call it first or the bounds simply do not hold for those rows. A NULL dimension is legal and is not a bound violation. The ceilings catch a stray digit or a unit mix-up; they are not meant to be plausible limits, so multi-day ultras and sled pushes fit under them. The widest set they admit is 1e14 milligram-reps of volume, which leaves `SUM(weight * reps)` two orders of magnitude inside a signed 64-bit range over a 100-set session.
- **Which dimensions a set must fill in depends on `exercise.exercise_type`**, so it cannot be read off the `workout_set` row alone: a `STRENGTH` set records `reps`, an `ENDURANCE` set a `duration` with `distance` optional, an `ISOMETRIC` set a `duration`. `SetValidator` only applies this to rows with `is_completed = 1` - a planned set is stored with its dimensions still empty. Filling the *other* dimensions is never refused; a timed squat set and a loaded carry are both legitimate.

### Identity and uniqueness

| Table | Natural key | Note |
| --- | --- | --- |
| `exercise` | `(name, equipment_type, machine_brand)` unique | Keeps PRs separate per machine brand. SQLite treats NULLs as distinct, so rows with a NULL brand do **not** collide with each other |
| `muscle_group` | `name` unique | 17 seeded values |
| `tag` | `name` unique | 6 seeded system tags, plus user tags |
| `session_exercise` | `(session_id, position)` unique | Reordering must not transiently duplicate a position |
| `template_exercise` | `(template_id, position)` unique | Same |

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

## Hot paths and their indexes

| Query | Depends on |
| --- | --- |
| `WorkoutSessionDao.getActive` | `workout_session(is_in_progress)`, `(last_active_at)` |
| `WorkoutSessionDao.getAll` / `getAllPaginated` | `workout_session(created_at)` |
| `WorkoutSetDao.getHistoryByExercise` (+ paginated) | `session_exercise(exercise_id)`, `workout_set(session_exercise_id)`, `(completed_at)` |
| `WorkoutSetDao.getVolumeByWeek` / `getVolumeByMuscleGroup` | `workout_set(completed_at)`, `exercise_muscle_group(muscle_group_id)` |
| `PersonalRecordDao.getRecentRecords` | `personal_record(achieved_at)` |
| `PersonalRecordDao.getByExercise` | `personal_record(exercise_id, achieved_at)` |
| `BodyWeightLogDao.getAll` / `getLatest` | `body_weight_log(created_at)` |

`LargeHistoryBenchmarkTest` seeds a power-user history (10k sessions, ~30k session exercises, 100k sets, 10k personal records) and asserts each of these stays under 500 ms. The budget is generous on purpose: it catches a dropped index turning a join quadratic, not micro-regressions. Unbounded reads (`getHistoryByExercise`, `getAll`) keep a `PagingSource` variant - screens should use it and leave the `Flow<List<...>>` form to charts and exports.

## Implications for backup and import/export

- The export envelope must carry the schema version it was written at. An import reads it and refuses (or up-converts) anything it does not understand - the Room migration ladder only upgrades a *database file*, not a JSON document.
- Match on natural keys and remap ids, as above.
- Reference data (7 muscle groups, 6 system tags) already exists on the target install; import must resolve it by name rather than inserting duplicates.
- Units are integers and timestamps are epoch-millisecond UTC, so no locale or precision conversion belongs in the serializer; converting to kilograms, pounds or local time is a UI concern.
- Restoring by replacing the database file is only safe when the file's version is one the app can migrate; prefer a row-level import that goes through Room.
