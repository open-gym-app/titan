# Export schema

The JSON document Titan writes when you back a device up, and reads when you restore one.

It is the only copy of a user's training history that leaves the phone, so this file is the
specification rather than a description: the format is pinned by tests against it, and a change
here that is not a change to those tests is a change nobody made.

| Artifact | Path |
| --- | --- |
| The document, and the version | `core/domain/src/main/kotlin/com/titan/core/domain/backup/model/ExportDocument.kt` |
| One data class per table | `.../backup/model/BackupRow.kt` |
| The tables, and the order they restore in | `.../backup/model/BackupTable.kt` |
| Reading, writing and the `Json` configuration | `.../backup/ExportFormat.kt`, `.../backup/ExportWriter.kt` |
| What an import refuses | `.../backup/BackupValidator.kt`, `.../backup/model/ImportError.kt` |
| The use cases | `.../usecase/ExportUseCase.kt`, `.../usecase/ImportUseCase.kt` |
| The row-level store | `core/data/src/main/kotlin/com/titan/core/data/repository/BackupRepositoryImpl.kt` |
| Round trip, idempotency and schema-drift tests | `core/data/src/test/kotlin/com/titan/core/data/db/Backup*.kt`, `ExportSchemaTest.kt` |

## The shape

```jsonc
{
  "schema_version": 2,
  "exported_at": 1788686970491,        // epoch milliseconds, UTC
  "app_version": "1.0-debug",          // diagnostic only; nothing reads it back
  "preferences": { … },                // the settings store — see below
  "muscle_group":          [ … ],      // one array per table, in restore order
  "tag":                   [ … ],
  "exercise":              [ … ],
  "exercise_muscle_group": [ … ],
  "exercise_tag":          [ … ],
  "workout_template":      [ … ],
  "template_exercise":     [ … ],
  "template_set":          [ … ],
  "workout_session":       [ … ],
  "session_exercise":      [ … ],
  "workout_set":           [ … ],
  "personal_record":       [ … ],
  "body_measurement":      [ … ],
  "session_xp_claim":      [ … ],
  "session_loot_claim":    [ … ],
  "session_pet_claim":     [ … ],
  "user_progress":         [ … ],
  "achievement":           [ … ],
  "quest_completion":      [ … ],
  "collected_item":        [ … ],
  "pet":                   [ … ],
  "pet_state":             [ … ],
  "pet_loadout":           [ … ],
  "pet_feed":              [ … ],
  "food_seen":             [ … ],
  "pet_unlock":            [ … ]
}
```

**Every key above is present in every document**, empty arrays included, so `jq 'keys'` returns
the same answer for a fresh install as for a power user. `ExportSchemaTest` asserts that this
list is exactly the tables `TitanDatabase` declares.

### Keys are the schema's names

A top-level array key is a **SQLite table name**. A row's keys are that table's **column
names**, in the order the table declares them, and a nullable column is written as `null`
rather than omitted. So a row in an export and a row in
`core/data/schemas/com.titan.core.data.db.TitanDatabase/2.json` can be read side by side
with no translation — which is the point, and is what `ExportSchemaTest` holds, column for
column. Units are the schema's too: milligrams, centimetres, seconds, epoch-millisecond UTC
timestamps, `0`/`1` booleans. See [PERSISTENCE.md](PERSISTENCE.md#storage-assumptions).

`preferences` is keyed by the **Preferences DataStore key names** for the same reason —
`default_rest_duration_seconds`, not `defaultRestDuration`.

### The `preferences` object

| Key | Type | Absent from a file means |
| --- | --- | --- |
| `unit_system` | `UnitSystem` name | *required* |
| `theme` | `ThemePreference` name | *required* |
| `default_rest_duration_seconds` | int64 seconds, `1`–`86400` | *required* |
| `bar_weight_milligrams` | int64 milligrams, `1`–`10000000000` | *required* |
| `training_goal` | `TrainingGoal` name | *required* |
| `one_rep_max_formula` | `OneRepMaxFormula` name | *required* |
| `onboarding_completed` | bool | *required* |
| `plate_inventory_milligrams` | array of `{weight_milligrams, pairs}`, or `null` | `null` — the user never chose a rack |
| `fitness_level` | `FitnessLevel` name | `BEGINNER` — the file predates #M7-11 |
| `custom_rep_range_min`, `custom_rep_range_max` | int32 reps | `8` and `12` — the file predates #M7-15, or holds one end without the other |
| `custom_increment_milligrams` | int64 milligrams, `1`–`10000000000`, or `null` | `null` — the rack's own smallest step |
| `warmup_suggestions` | bool | `true` — the file predates #214 |
| `weekly_workout_goal` | int32 sessions, `1`–`7` | `4` — the file predates the weekly streak, or holds a goal out of range |
| `auto_open_rest_timer` | bool | `true` — the file predates the setting |

*Required* is the decoder's word, not the importer's: a required key that is missing makes the
document `MalformedJson`, where a value the build does not recognise degrades to the setting's
default (see below). The last eight are the keys that may be absent. `plate_inventory_milligrams`
is written as an explicit `null` by this build and has been since the format's first version;
`fitness_level` is written by every build from #M7-11 on, the three CUSTOM-goal keys by every
build from #M7-15 on, `warmup_suggestions` by every build from #214 on, `weekly_workout_goal`
by every build from the weekly streak on, and `auto_open_rest_timer` by every build from the
setting on — each is simply not there in a file from before it.
All read back as the default the table gives, and none bumped `schema_version`, per
[the rule](#when-to-bump).
A settings key added after these takes the same shape: nullable in `PreferencesPayload`,
defaulted on read, listed here.

Not in the object: the notification switches and the reminder schedule. They are device state
— a permission this phone was granted and alarms this phone has scheduled — and an import that
wrote them would draw switches that are on with nothing behind them. A restore resets them to
their defaults, as it resets every setting before writing the file's, and then re-syncs the
reminder alarms (`ReminderScheduler.sync`), so no alarm outlives the reminder it was set for.

## What is in it, and what is not

Every table in the schema is exported. Three of those choices have a defensible alternative and
are recorded here so they are not re-litigated:

- **`personal_record` is exported, not re-derived.** Re-running `PrDetectionUseCase` over
  imported history would produce a *plausible* record set rather than *the user's*: the
  detection rules can change between the build that wrote the file and the one that reads it,
  and a restore that silently rewrites somebody's PR history is a worse failure than a slightly
  larger file. This is a deliberate departure from PERSISTENCE.md's note that an export *may*
  omit it.
- **`session_xp_claim`, `session_loot_claim` and `session_pet_claim` are exported.** They are what makes the
  post-session cascade idempotent. Dropping them would let every restored session be paid XP
  again, rolled for loot again and grow the active pet again the next time anything touched it. `BackupIdempotencyTest`
  runs the real cascade over a restored session and asserts it awards nothing — and, as a
  control, that deleting the claim rows makes it pay twice.
- **`preferences` is exported**, and restored **last**. A restore is meant to reproduce a
  device, and the unit system in particular changes how every stored weight is *read back*;
  applying it after the rows means the app re-formats once, at the end, rather than mid-write.

**The default templates are user rows.** The ones seeded on first launch (#213) carry no marker
and travel in `workout_template` like any template the user wrote. A file from a phone that
deleted one restores without it, and one the user renamed comes back renamed.

**Not exported:** the in-progress session draft (`InProgressSessionRepository`). An interrupted
workout is device state, not history — importing one would drop the user into a live session
that never happened on this phone. The rows it overlaps *are* exported, so nothing logged is
lost. An import clears whatever draft the target device holds, because a draft naming a session
id from before the wipe points at data that no longer exists. For the same reason it takes down
the workout's rest notification and alarm and the idle nudge, which the operating system would
otherwise keep, and deliver, for a session the file may have replaced with another under the
same id.

## Restore semantics: replace, not merge

**An import wipes every table and re-inserts the document.** It is not a merge, and there is no
upsert-by-id path. Afterwards the database holds exactly what the document holds: a template the
user made yesterday and that is not in the file is gone.

The reasoning: a merge needs a conflict rule for every table, and two devices that both started
from the same seeded exercise library collide on ids that mean different rows. Replace has one
rule, is trivially transactional, and is what somebody restoring after a reinstall actually
wants. The cost — no cross-device *combining* of two histories — is a feature we are choosing
not to ship rather than one we are deferring by accident. Telling the user before the wipe
happens is #38's job.

Two consequences worth stating outright:

- **Ids are exported verbatim and restored verbatim.** PERSISTENCE.md says ids are not portable
  and that anything matching rows across installs must match on the natural key and remap —
  that rule is about a merge. Because this replaces, the file's ids are the only ids afterwards
  and every foreign key in it still resolves to the row it was written against.
- **The seeded catalogue comes from the file, not from the seed.** The whole `exercise`,
  `muscle_group` and `tag` content is in the document, so an import does not replay
  `DatabaseSeed` — replaying it would leave every seeded row the *file* does not contain still
  in the table, and "replace" would quietly become "replace, plus whatever this build ships
  with".

### The order

Rows go in in the order the tables are listed above, which is dependency order, and the wipe
runs it backwards. Foreign keys stay **enforced** throughout — no `defer_foreign_keys` — so
referential integrity holds at every point of the transaction rather than only at its commit.
`BackupTableTest` pins the order against PERSISTENCE.md's referential-actions table.

### Atomicity

The wipe and every insert are **one Room transaction**. A failure anywhere in it rolls the whole
thing back, so there is no state where the database is empty and the import has given up. That
is also why the import does not call `LocalDataRepository.deleteAll()`, which is the settings
screen's wipe: it reclaims space with a `VACUUM`, which cannot run inside a transaction, and it
replays the seed afterwards, which this must not do. `BackupRepositoryImpl` explains it at the
class level.

The two steps outside the transaction are the file read, which happens before anything is
touched, and the settings write, which happens after the rows are committed — see
`ImportError.SettingsNotRestored`. Once the rows are committed the settings write cannot be
cancelled: a screen closed at that moment does not leave the history restored and the settings
half-written with nobody told.

### Nothing else runs meanwhile

Three operations take the whole of the user's data at once — an export (the manual one and the
weekly automatic one, which run the same use case), a restore, and "delete all data" — and each
is several steps over more than one store. They hold one process-wide lock, `BackupLock`, for
their whole run, so none of them starts while another is between its steps: an export cannot
read half a restore, nor a restored history beside the settings it is about to replace. A restore
or a wipe started during the weekly backup waits for it to finish.

A restore also holds `SessionLifecycleLock` — the lock every session transition takes — from
resetting the live session to the end of the replace, so a session cannot be started, finished
or resumed against rows that are being swapped out under it. "Delete all data" does the same.

An export reads every table inside **one read transaction**
(`BackupRepository.readConsistently`). Its pages are separate queries, and without it a write
landing between two of them — a session added after `session_exercise` was paged and before
`workout_set` was — would leave the file with sets whose parent it does not hold, which the next
restore refuses. The transaction holds the app's writes back until the export ends, seconds at
the target scale; a set logged in that time is saved when it does. `ExportSnapshotTest` pins it.

## What an import refuses

Eight typed failures, as `ImportError`. All but the last two leave the database **exactly** as
it was.

| Error | When | State afterwards |
| --- | --- | --- |
| `DocumentTooLarge` | The file runs past `ExportFormat.MAX_DOCUMENT_BYTES` (64 MiB) — see [Memory](#memory) | Untouched |
| `MalformedJson` | Not JSON, or not shaped like a document — a missing required column, a string where a number belongs, a truncated file | Untouched |
| `UnsupportedSchemaVersion` | `schema_version` is not this build's, on either of the two reads of the file | Untouched |
| `ConstraintViolation` | A foreign key naming a row the file does not contain, two rows sharing a primary key or a unique index, a `user_progress` that is not the single row the table admits, a value outside [the ranges the format accepts](#the-ranges-a-value-must-be-in) | Untouched. `BackupValidator` decides it before anything is cleared; on the rarer path where SQLite decides it instead, the transaction rolls back and every table is still untouched, but the live session and its draft — cleared before the transaction opens, deliberately — stay cleared, and the error's `liveStateCleared` says so for the screen to tell the user |
| `IoFailure` | The file could not be read, or the device could not take the transaction's writes — out of space, a disk error | Untouched. A file that cannot be read fails before anything is cleared; a device that cannot write fails inside the transaction, which rolls back, and leaves the live session and its draft cleared as for a `ConstraintViolation` SQLite decides, with the same `liveStateCleared` |
| `LiveStateNotCleared` | The live session or one of the two drafts could not be cleared before the transaction | Untouched, but the live session may already have been ended and the workout draft discarded — the session state is reset first and the drafts after it |
| `SettingsNotRestored` | The rows were committed and the settings write after them failed | Rows restored, settings at whatever the store held when it failed — the defaults, if the reset before the setters had landed. A partial outcome, and the reason it is its own error rather than a refusal |
| `RemindersNotRescheduled` | The rows and the settings were restored and the reminder alarms could not be re-synced after them | Rows and settings restored; the alarms still follow the reminder from before the restore. Reported only when the settings landed — a settings failure wins |

The drafts are files and cannot join the transaction, so one of two windows has to exist. Clearing
them before it, as the import does, loses a place in a workout when SQLite refuses what the
validator did not; clearing them after it would leave a process death in between pointing a
draft into a database that had been replaced under it. `ImportUseCaseTest` pins the order.

`ConstraintViolation` is decided by checking the document **against itself, before the wipe**,
which is possible only because import replaces: the file is the whole database afterwards, so
every reference in it must resolve inside it. The transaction is still there and still catches a
constraint the validator does not model, but it is the backstop rather than the mechanism.

### The ranges a value must be in

A few columns have a range that belongs to the format rather than to a rule the domain might
retune. No build of the app has written a value outside them, and each value outside them broke
the app after a restore rather than reading oddly (#256). `BackupValidator` refuses a file that
holds one, and `BackupValidatorRangeTest` pins each range at both of its edges.

| Columns | Accepted | What a value outside it did |
| --- | --- | --- |
| `id` of every table whose id is a rowid: `muscle_group`, `tag`, `exercise`, `workout_template`, `template_exercise`, `template_set`, `workout_session`, `session_exercise`, `workout_set`, `personal_record`, `body_measurement`, `collected_item`, `pet` | `1` to `9007199254740992` (2^53) | Zero is rewritten to a fresh id on insert and strands every reference to it. An in-progress session numbered below one is one the workout draft refuses to be built around. An id near `Long.MAX_VALUE` leaves `AUTOINCREMENT` nothing to assign, so every later insert fails with `SQLITE_FULL`, and the counter survives a restore's wipe; only "Delete all data" resets it. 2^53 is also the largest integer a reader that parses JSON numbers as doubles, `jq` among them, reads exactly. Every foreign key must resolve to one of these ids, so it is in range too |
| `workout_session.completed_at`, `.last_active_at`, `.created_at`, `.updated_at`; `session_exercise.created_at`, `.updated_at`; `workout_set.completed_at`, `.created_at`, `.updated_at`; `personal_record.achieved_at`; `body_measurement.created_at`, `.updated_at`; `user_progress.last_counted_at`, `.protection_consumed_at`; `achievement.unlocked_at`; `quest_completion.period_start`, `.completed_at`; `collected_item.acquired_at`; `pet.hatched_at`; `pet_state.activity_started_at`, `.activity_ends_at`, `.updated_at`; `pet_feed.fed_at`; `pet_unlock.unlocked_at` | `0` to `253402300799999`, the epoch to the last millisecond of 9999, UTC | A session dated before 1970 opened the all-time chart on its day, and the chart allocated a bucket for every week since: at −10^17 ms that is millions of them. `0` stays accepted because it is the placeholder `completed_at` of a planned set |
| `workout_set.set_number`, `template_set.set_number` | `1` and up | Sets are numbered from 1; a set numbered 0 is one no screen can show as "set 0 of 3" |
| `workout_set.rpe` | `null`, or `10` to `100` | The scale is RPE 1 to 10 in tenths, and it does not move |
| `workout_session.is_in_progress` | `1` on at most one row | The app holds one session in progress at a time and an export only ever carries the one it had. A second restored one stayed open for good: resume picks one, and the other is never finished or counted |
| The text columns a primary key or unique index covers: `muscle_group.name`, `tag.name`, `exercise.name`, `.equipment_type`, `.machine_brand`, `achievement.achievement_id`, `quest_completion.quest_id` | Well-formed text: no unpaired surrogate, which JSON can spell as a `\ud800` escape | The validator compares keys as the decoded strings and SQLite's index compares the UTF-8 it stores. SQLite's conversion pairs a lone high surrogate with whatever character follows it, so two names that differ in the file can be one name in the table: they passed the check and failed the insert, after the drafts had been cleared. Well-formed text encodes one to one, so the two comparisons agree. Text no index covers is not refused |

### What is repaired rather than refused

Four kinds of value are brought back into range on the way in, because refusing the whole file
over them would trade a training history for a figure the app can recompute:

- **`user_progress`**, which the XP header, the quest log, the streak step and every XP award
  read. A `total_xp` outside `0` to `XpCalculator.MAX_TOTAL_XP` (10^15) is clamped into it,
  since the level curve overflows an `Int` past about 5.9 × 10^15. A `current_level` outside the
  levels that range buys is replaced by the level the clamped total buys. `current_streak`,
  `longest_streak` and `week_sessions` are clamped to zero and up. `protection_remaining` is
  clamped into `0` to the monthly allowance, and a `protection_period` that is not a `YYYY-MM`
  month becomes `null`. `UserProgressMapper.repaired` does it, and every reader of the row
  applies it again, for a row that got into the table some other way.
- **`pet`**, which the titan's screens read. `xp` is clamped as `total_xp` is, each of
  `strength`, `endurance` and `mobility` into `0` to `PetStats.MAX` (99,999), and a `nickname`
  that fails the naming rule (blank, over 24 characters, or holding a control character) becomes
  `null`; one with surrounding whitespace is trimmed. `PetMapper.repaired` does it, and every
  reader applies it again.
- **`pet_state`**: `fuel` and `spirit` are clamped into `0` to `100`. An `activity` or
  `resume_activity` this build does not know is kept as written and read as idle; a moment in
  the future is pulled back to now by the reader, which is the one that knows what now is.
- **The settings**, per the next section: a `default_rest_duration_seconds` past one day, and a
  `bar_weight_milligrams` or `custom_increment_milligrams` past ten tonnes, read back as the
  default.

### What is deliberately *not* refused

Every invariant PERSISTENCE.md says lives in the domain rather than in the schema:
`SetValidator`'s value bounds, dense set numbering, the superset rules,
`BodyMeasurementValidator`'s all-null rule, an exercise's `default_rest`, and whether an
enum-like column holds a name this build recognises.

That is not laziness. A backup is *the user's own data coming back*, and PERSISTENCE.md is
explicit that a row naming an achievement or a collectible from a newer version is a record
rather than a corruption, which a restore must preserve. Refusing a file because a set carries
a weight this build's `SetValidator` would not accept would make an export unrestorable on the
very build that wrote it, the day a bound moved.

Such a value is the readers' to survive instead, and each of them does. The history and stats
sums cannot overflow (`WorkoutSetDao` explains how). The next-load suggestion judges only sets
inside `SetValidator`'s bounds. An exercise rest outside one second to a day falls back to the
user's default. A pace, a lean mass and an in-memory volume past what a `Long` holds are no
figure, or held at the bound, rather than wrapped. `HostileBackupTest` restores each of these
values, and also writes each straight into its table, then reads it back through the screens'
queries and carries on writing.

Settings are the one place that degrades rather than refusing: an enum name this build does not
have, a rest of zero seconds or of more than a day, a bar weight or a custom increment of
nothing or of more than ten tonnes, a plate rack that breaks its own invariants all read back as
the documented default. A row is a record of something that happened; a setting is a fact the
user can change in two taps.

## Version history and the compatibility rule

**A reader accepts its own version and the older ones it has an up-converter for, and refuses
every other one.**

| `schema_version` | Shipped in | Changes |
| --- | --- | --- |
| 1 | the 1.0.0 release candidates | Initial format: 19 tables, plus `preferences` |
| 2 | unreleased | The Titans milestone: adds `pet`, `pet_state`, `pet_loadout`, `pet_feed`, `food_seen`, `pet_unlock` and `session_pet_claim`, and the nullable `collected_item.session_id`, which a version 1 file restores as `null`. **Reads version 1** through `SchemaUpgrade`: the new arrays are empty, and anything a version 1 file claims under a key its version never had is dropped |

Version 1 has been amended once in place, while unreleased, in a way the rule below would
otherwise call a bump: the streak moved from sessions to weeks, `user_progress` gained
`week_sessions` and `week_goal_met`, and the XP curve was retuned. A file from before that
change is told apart by a `user_progress` row with no `week_sessions`, and `WeeklyStreakUpgrade`
converts it on the way in — the streak counts start again, the retired streak milestones are
dropped, the protection allowance and the level are brought to today's rules. From the first
release on, a change like it is a bump.

Refusing an older file is the default rather than an oversight. A v1 reader handed a v2 file
cannot know which columns moved; a v2 reader handed a v1 file would have to invent the values v1
never stored. Either is a silent, permanent corruption of the only copy of somebody's training
history. An older version is readable only because an up-converter was written for it and
listed in the table above: version 2 only added tables, so its converter has nothing to invent.

Version 2 is amended in place for the length of the Titans milestone — each of its tables joins
this version and its converter — on the condition PERSISTENCE.md states: no release containing
version 2 has been tagged. From the first one that has, a further table is version 3.

`ExportFormat.decode` reads `schema_version` **before** interpreting anything else, so a file
from a newer build is refused for the reason a user can act on rather than incidentally, by the
decoder tripping over a table it has never heard of. It reads the file twice (see
[Memory](#memory)), and checks the version the second read decoded as well: a provider owes
nothing about serving the same bytes twice, and a file whose version changed between the two
reads is refused for it.

### When to bump

Bump when a change would make an existing file restore into something other than what was
exported: a table added or dropped, a column renamed or retyped, a unit changed. **Adding a
nullable column does not need a bump** — it reads back as null on an older file, which is what
the row would have held — and `ignoreUnknownKeys` is on so a file written by a slightly newer
build of the same version still reads.

Every bump owes a row in the table above and, if older files are to keep restoring, an
up-converter listed with it.

## Memory

An export never holds the history. Reads are paged
(`BackupRepository.readPage`, 500 rows at a time) and the document is written incrementally
into an `ExportSink`, so peak memory is one page of rows and the text it encodes into whatever
the history's size. `ExportStreamingTest` exports the 1,000-session / 50,000-set fixture and
asserts no chunk handed to the sink is bigger than a page's worth.

An **import** does hold the decoded document, and that is deliberate: whether a `workout_set`'s
parent exists cannot be answered until every `session_exercise` in the file has been seen, so
the document has to be whole in memory before the wipe. Streaming that too would mean either
validating nothing, or wiping first and discovering the problem afterwards — and the second is
how a restore eats a training history.

It does **not** hold the file's text, and on a 2 GB phone the difference is the feature.
`ImportUseCase` takes an `ImportSource` — something that opens a stream — rather than a
`String`, so the JSON is never a value: the decoder reads it in 16 KiB batches and discards
each one. That means two passes over the bytes, one to read `schema_version` with every other
key skipped and one to decode the document, which is a second sequential read of a file and no
heap at all. The alternative it replaced — parse into a `JsonElement` tree, read the version
off it, decode the document from it — held the text, a tree several times its size and the
document all at once, which at 50,000 sets is hundreds of megabytes on a device that has 2 GB
for everything. `ExportFormatDecodeTest` pins both halves: that the source is opened twice
rather than read once into anything, and that a target-scale document decodes off a file.

**A file is not trusted to be small.** Both reads count the bytes and stop at
`ExportFormat.MAX_DOCUMENT_BYTES`, 64 MiB, refusing the file as `DocumentTooLarge`. A
target-scale history is about 15 MB, so the limit is four times that — some 200,000 sets — and
`ExportFormatDecodeTest` fails if the target scale stops fitting four times over. The decoded
document takes about twice the file's size in heap, so a file past the limit is one no phone
could hold anyway; refusing it by count costs a read and no memory, where decoding it ended in
an out-of-memory error.

## Where a restore reads from

Only from a `content://` document another app's provider serves — what the system picker hands
back. The backup screen refuses a `file://` path, which would be read with the app's own
permissions, and a URI on any of the app's own authorities, which would be the app reading its
own private files through a door meant for others. It checks when the file is picked and again
when the restore is confirmed, since the URI can come back from saved state. The confirmation
names the file in the provider's words, stripped of control and direction-override characters.

## Checking a file by hand

```sh
jq empty export.json                                    # it parses
jq 'keys_unsorted' export.json                          # the envelope, then the 28 tables
jq '.workout_set | length' export.json                  # how many sets it carries
jq '.schema_version' export.json                        # which reader will accept it
jq '.workout_set[0] | keys_unsorted' export.json         # a row's columns, in the table's order
```
