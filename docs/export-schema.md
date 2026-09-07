# Export schema

The JSON document Open Gym writes when you back a device up, and reads when you restore one.

It is the only copy of a user's training history that leaves the phone, so this file is the
specification rather than a description: the format is pinned by tests against it, and a change
here that is not a change to those tests is a change nobody made.

| Artifact | Path |
| --- | --- |
| The document, and the version | `core/domain/src/main/kotlin/com/opengym/core/domain/backup/model/ExportDocument.kt` |
| One data class per table | `.../backup/model/BackupRow.kt` |
| The tables, and the order they restore in | `.../backup/model/BackupTable.kt` |
| Reading, writing and the `Json` configuration | `.../backup/ExportFormat.kt`, `.../backup/ExportWriter.kt` |
| What an import refuses | `.../backup/BackupValidator.kt`, `.../backup/model/ImportError.kt` |
| The use cases | `.../usecase/ExportUseCase.kt`, `.../usecase/ImportUseCase.kt` |
| The row-level store | `core/data/src/main/kotlin/com/opengym/core/data/repository/BackupRepositoryImpl.kt` |
| Round trip, idempotency and schema-drift tests | `core/data/src/test/kotlin/com/opengym/core/data/db/Backup*.kt`, `ExportSchemaTest.kt` |

## The shape

```jsonc
{
  "schema_version": 1,
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
  "user_progress":         [ … ],
  "achievement":           [ … ],
  "quest_completion":      [ … ],
  "collected_item":        [ … ]
}
```

**Every key above is present in every document**, empty arrays included, so `jq 'keys'` returns
the same answer for a fresh install as for a power user. `ExportSchemaTest` asserts that this
list is exactly the tables `OpenGymDatabase` declares.

### Keys are the schema's names

A top-level array key is a **SQLite table name**. A row's keys are that table's **column
names**, in the order the table declares them, and a nullable column is written as `null`
rather than omitted. So a row in an export and a row in
`core/data/schemas/com.opengym.core.data.db.OpenGymDatabase/1.json` can be read side by side
with no translation — which is the point, and is what `ExportSchemaTest` holds, column for
column. Units are the schema's too: milligrams, centimetres, seconds, epoch-millisecond UTC
timestamps, `0`/`1` booleans. See [PERSISTENCE.md](PERSISTENCE.md#storage-assumptions).

`preferences` is keyed by the **Preferences DataStore key names** for the same reason —
`default_rest_duration_seconds`, not `defaultRestDuration`.

## What is in it, and what is not

Every table in the schema is exported. Three of those choices have a defensible alternative and
are recorded here so they are not re-litigated:

- **`personal_record` is exported, not re-derived.** Re-running `PrDetectionUseCase` over
  imported history would produce a *plausible* record set rather than *the user's*: the
  detection rules can change between the build that wrote the file and the one that reads it,
  and a restore that silently rewrites somebody's PR history is a worse failure than a slightly
  larger file. This is a deliberate departure from PERSISTENCE.md's note that an export *may*
  omit it.
- **`session_xp_claim` and `session_loot_claim` are exported.** They are what makes the
  post-session cascade idempotent. Dropping them would let every restored session be paid XP
  again and rolled for loot again the next time anything touched it. `BackupIdempotencyTest`
  runs the real cascade over a restored session and asserts it awards nothing — and, as a
  control, that deleting the claim rows makes it pay twice.
- **`preferences` is exported**, and restored **last**. A restore is meant to reproduce a
  device, and the unit system in particular changes how every stored weight is *read back*;
  applying it after the rows means the app re-formats once, at the end, rather than mid-write.

**Not exported:** the in-progress session draft (`InProgressSessionRepository`). An interrupted
workout is device state, not history — importing one would drop the user into a live session
that never happened on this phone. The rows it overlaps *are* exported, so nothing logged is
lost. An import clears whatever draft the target device holds, because a draft naming a session
id from before the wipe points at data that no longer exists.

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
`ImportError.IoFailure`.

## What an import refuses

Four typed failures, as `ImportError`. Every one but the last leaves the database **exactly** as
it was.

| Error | When | State afterwards |
| --- | --- | --- |
| `MalformedJson` | Not JSON, or not shaped like a document — a missing required column, a string where a number belongs, a truncated file | Untouched |
| `UnsupportedSchemaVersion` | `schema_version` is not this build's | Untouched |
| `ConstraintViolation` | A foreign key naming a row the file does not contain, two rows sharing a primary key or a unique index, a `user_progress` that is not the single row the table admits | Untouched. `BackupValidator` decides it before anything is cleared; on the rarer path where SQLite decides it instead, the transaction rolls back and every table is still untouched, but the live session and its draft — cleared before the transaction opens, deliberately — stay cleared |
| `IoFailure` | A store other than the database could not be read or written — the file itself, the draft on the way in, the settings on the way out | Untouched if it was the file or the draft; rows restored and settings not if it was the settings, which is the only partial outcome and the reason it is reported rather than swallowed |

`ConstraintViolation` is decided by checking the document **against itself, before the wipe**,
which is possible only because import replaces: the file is the whole database afterwards, so
every reference in it must resolve inside it. The transaction is still there and still catches a
constraint the validator does not model, but it is the backstop rather than the mechanism.

### What is deliberately *not* refused

Every invariant PERSISTENCE.md says lives in the domain rather than in the schema:
`SetValidator`'s value bounds, dense set numbering, the superset rules,
`BodyMeasurementValidator`'s all-null rule, and whether an enum-like column holds a name this
build recognises.

That is not laziness. A backup is *the user's own data coming back*, and PERSISTENCE.md is
explicit that a row naming an achievement or a collectible from a newer version is a record
rather than a corruption, which a restore must preserve. Refusing a file because a set carries
an RPE this build would not have written would make an export unrestorable on the very build
that wrote it, the day a bound moved.

Settings are the one place that degrades rather than refusing: an enum name this build does not
have, a rest of zero seconds, a plate rack that breaks its own invariants all read back as the
documented default. A row is a record of something that happened; a setting is a fact the user
can change in two taps.

## Version history and the compatibility rule

**A reader accepts its own version and refuses every other one, older included.**

| `schema_version` | Shipped in | Changes |
| --- | --- | --- |
| 1 | unreleased | Initial format: 19 tables, plus `preferences` |

Refusing older files is the rule rather than an oversight. A v1 reader handed a v2 file cannot
know which columns moved; a v2 reader handed a v1 file would have to invent the values v1 never
stored. Either is a silent, permanent corruption of the only copy of somebody's training
history. When an older version does become readable, it will be because an up-converter was
written for it and listed in the table above.

`ExportFormat.decode` reads `schema_version` **before** interpreting anything else, so a file
from a newer build is refused for the reason a user can act on rather than incidentally, by the
decoder tripping over a table it has never heard of.

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

## Checking a file by hand

```sh
jq empty export.json                                    # it parses
jq 'keys_unsorted' export.json                          # the envelope, then the 19 tables
jq '.workout_set | length' export.json                  # how many sets it carries
jq '.schema_version' export.json                        # which reader will accept it
jq '.workout_set[0] | keys_unsorted' export.json         # a row's columns, in the table's order
```
