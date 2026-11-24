# Import CSV format

The spreadsheet Titan reads when you import a history from an app it has no importer for. Convert
your data into it, with a spreadsheet or a script, and import it as a plain CSV file.

This file is the specification, version 1. `PlainCsvParser` is held to it by tests, including one
that imports the two example files below, so a change here that is not a change to those tests is
a change nobody made.

| Artifact | Path |
| --- | --- |
| Example workout file | [`docs/import-csv-workouts.csv`](import-csv-workouts.csv) |
| Example body measurement file | [`docs/import-csv-measurements.csv`](import-csv-measurements.csv) |
| The parser | `core/domain/src/main/kotlin/com/titan/core/domain/importer/plain/` |
| Its tests and fixtures | `core/domain/src/test/kotlin/.../importer/plain/`, `core/domain/src/test/resources/importer/plain/` |
| What every import does with what a parser reads | `.../importer/ImportPlanner.kt`; [PERSISTENCE.md](PERSISTENCE.md#importing-from-another-app) |

## The shape

Two kinds of file, one per import. A workout file has **one row per set**, the long format every
spreadsheet and most apps can produce:

```csv
workout_date,workout_name,exercise,set_type,reps,weight,weight_unit
2026-11-02 18:30,Push,Bench Press (Barbell),warmup,10,40,kg
2026-11-02 18:30,Push,Bench Press (Barbell),,5,80,kg
2026-11-02 18:30,Push,Bench Press (Barbell),,5,80,kg
2026-11-02 18:30,Push,Triceps Pushdown,,12,25,kg
2026-11-06,Legs,Barbell Squat,,5,100,kg
```

A body measurement file has **one row per measurement**:

```csv
measurement_date,weight,body_fat,waist
2026-10-01,82.4,18.5,86
2026-11-01,81.2,17.6,84
```

The first row is the header. Titan recognises a workout file by its `workout_date` and `exercise`
columns, and a measurement file by its `measurement_date` column; a file with neither is "not a
plain CSV file". A file with both kinds of column is read as a workout file.

### Columns are matched loosely

- **Case and spacing do not matter.** Any run of spaces, hyphens or other punctuation reads as one
  underscore, so `Workout Date`, `workout-date` and `WORKOUT_DATE` are all `workout_date`.
- **Order does not matter**, and every column but the required ones can be left out.
- **Columns this page does not define are ignored**, so a spreadsheet's own columns can stay.
- **A unit can go in the header**, in parentheses or after an underscore: `weight (kg)`,
  `weight_lb`, `distance (mi)`, `waist (in)`. A parenthesis after a column that has no unit is
  ignored: `rpe (1-10)` is `rpe`. A parenthesis that names a unit Titan does not read, such as
  `weight (stone)`, makes the whole file unreadable, because no figure under it could be read.

The file itself can be separated by commas, semicolons, tabs or `|`, start with a byte order mark,
use CRLF or LF line endings, and write decimals with a point or a comma (`62,5`). Text with the
separator or a line break in it goes in double quotes, as every spreadsheet writes it.

## Workout files

| Column | Required | What it holds |
| --- | --- | --- |
| `workout_date` | **yes** | When the workout started: a date, or a date and a time. See [Dates and times](#dates-and-times) |
| `workout_time` | | The start time, when it is kept apart from the date: `18:30`, `6:30 PM` |
| `workout_name` | | `Push`, `Legs`, `Morning run` |
| `workout_duration` | | How long the workout lasted. A bare number is **minutes**; see [Durations](#durations) |
| `workout_notes` | | Notes on the whole workout |
| `workout_calories` | | Energy burned, in kilocalories |
| `workout_rating` | | How it went, `1` to `5` |
| `exercise` | **yes** | The exercise's name. See [Exercise names](#exercise-names) |
| `equipment` | | What it was performed with. See [Exercise names](#exercise-names) |
| `muscle` | | The main muscle it works, for an exercise Titan does not know yet |
| `exercise_notes` | | Notes on the exercise in this workout |
| `superset` | | Any label: rows of one workout with the same label, one after the other, are one superset |
| `set_type` | | `normal` (or blank), `warmup`, `drop`, `failure`, `interval` |
| `reps` | | Repetitions |
| `weight` | | The load, in `weight_unit`. A bodyweight set leaves it blank or writes `0` |
| `weight_unit` | | `kg` or `lb` |
| `duration` | | How long the set lasted. A bare number is **seconds** |
| `distance` | | How far, in `distance_unit` |
| `distance_unit` | | `m`, `km`, `mi` or `ft` |
| `rpe` | | Rate of perceived exertion, `1` to `10`, halves allowed (`8.5`) |
| `rest` | | The rest after the set. A bare number is **seconds** |
| `avg_heart_rate` | | Average heart rate over the set, in beats per minute |
| `max_heart_rate` | | Highest heart rate during the set |
| `notes` | | Notes on the set |

### Workouts

A workout is **every row with the same start**: the date and time in `workout_date`, or the date
at `workout_time`, or noon on the date when the row gives no time. Rows of one workout can be
anywhere in the file. The `workout_*` columns describe the workout, so repeat them on each of its
rows or fill them on one; Titan reads the first value it finds. Each different `workout_notes` is
kept.

- **Give two workouts on the same day a time.** Without one they both start at noon and are read as
  one workout.
- **Importing the same file again adds nothing.** A workout that starts at the same moment as one
  already in Titan is skipped as a duplicate, and noon is the same moment every time.
- **A workout dated today with no time** starts at noon. Imported before noon, it would start in
  the future and is skipped; import again in the afternoon to add it.
- **No `workout_duration`?** Titan estimates two minutes a set.

### Exercises within a workout

Consecutive rows naming the same exercise are one exercise of the workout, its sets in row order.
An exercise you come back to later in the workout is listed twice, as you did it.

**Supersets.** Give the exercises of a superset the same `superset` label (`A`, `1`, anything) on
consecutive rows. Inside a superset, rows are gathered by exercise, so writing it set by set is
fine:

```csv
workout_date,exercise,superset,reps,weight
2026-11-02 18:30,Incline Dumbbell Press,A,10,26
2026-11-02 18:30,Side Lateral Raise,A,15,10
2026-11-02 18:30,Incline Dumbbell Press,A,10,26
2026-11-02 18:30,Side Lateral Raise,A,15,10
```

is two exercises of two sets each, performed as one superset. A label on one exercise alone is no
superset.

### Sets

A row is a set when it has at least one of `reps`, `weight`, `duration` or `distance`. A row with
none of them still names its exercise, which is imported with no sets: that is how to bring an
exercise you have not trained yet.

Write the columns the exercise is logged with: reps and a weight for a lift, reps alone for a
bodyweight movement, a duration for a hold such as a plank, a distance and a duration for cardio.
Titan works out how each exercise is logged from its sets.

`set_type` also accepts `working`, `warm up`, `drop set`, `dropset` and the letters `W`, `D` and
`F`. Anything else is a normal set.

**Assisted exercises.** Titan has no column for the help an assistance machine or a band gives. A
negative `weight`, and the weight of an exercise whose name says it is assisted (`Assisted
Pull-Up`, `Pull Up (Assisted)`), are not imported; the reps are.

### Exercise names

Each exercise is matched to one already in Titan by name, ignoring case, accents and punctuation,
and created as a custom exercise when nothing matches. A name from Titan's own exercise library
always matches; open the exercise in Titan to see its exact name.

Two common naming styles from other apps are understood as well:

- **The equipment in parentheses after the name**, as Strong and Hevy write it: `Bench Press
  (Barbell)`, `Lateral Raise (Dumbbell)`. Many of their names map onto Titan's library.
- **The equipment first**: `Barbell Squat`, `Dumbbell Curl`, `Cable Crossover`.

`equipment` says the equipment outright: `barbell`, `dumbbell`, `machine`, `smith machine`,
`cable`, `kettlebell`, `band`, `bodyweight`, `ez bar`, `medicine ball`, `exercise ball`,
`foam roll` or `other`. Titan only matches an exercise of the same equipment, and a custom one is
created with it. A value not in that list is ignored.

`muscle` is used only when the exercise is created: `abdominals`, `abductors`, `adductors`,
`biceps`, `calves`, `chest`, `forearms`, `glutes`, `hamstrings`, `lats`, `lower back`,
`middle back`, `neck`, `quadriceps`, `shoulders`, `traps` or `triceps`. An exercise already in
Titan keeps what it has.

## Body measurement files

| Column | Required | What it holds |
| --- | --- | --- |
| `measurement_date` | **yes** | When it was measured: a date, or a date and a time |
| `measurement_time` | | The time, when it is kept apart from the date |
| `weight` | | Body weight, in `weight_unit` |
| `weight_unit` | | `kg` or `lb` |
| `body_fat` | | Body fat in percent: `18.5` or `18.5%` |
| `neck`, `shoulders`, `chest`, `waist`, `hips`, `arm`, `forearm`, `thigh`, `calf` | | Circumferences, in `length_unit`. One figure per limb: measure the same side each time |
| `length_unit` | | `cm`, `mm` or `in`, for every circumference on the row |
| `notes` | | Notes on the measurement |

Each row is one measurement. Leave blank whatever you did not measure. Dates and times read as a
workout's do, so give two measurements on one day a time, or only the first is imported.

## Units

Every unit can be given in three places. Titan uses the first it finds:

1. **In the cell**, for a weight only: `80kg`, `176 lb`.
2. **In the row's unit column**: `weight_unit`, `distance_unit`, `length_unit`.
3. **In the column's header**: `weight (kg)`, `distance_km`, `waist (in)`.

With none of them, a figure is in your unit system in Titan's settings: kilograms, kilometres and
centimetres, or pounds, miles and inches.

| Kind | Units, as written in a cell or header |
| --- | --- |
| Weight | `kg`, `kgs`, `kilogram(s)`; `lb`, `lbs`, `pound(s)` |
| Distance | `m`, `metre(s)`, `meter(s)`; `km`, `kilometre(s)`, `kilometer(s)`; `mi`, `mile(s)`; `ft`, `foot`, `feet` |
| Circumference | `mm`, `cm` and their long forms; `in`, `inch`, `inches` |
| Duration (header only) | `s`, `sec`, `seconds`; `min`, `minutes`; `h`, `hr`, `hours` |

### Durations

`workout_duration`, `duration` and `rest` take any of:

- **A bare number**, counted in the header's unit when it has one: `duration (min)`. Without one,
  `workout_duration` is in minutes and `duration` and `rest` are in seconds.
- **A clock**: `1:05:00` is an hour and five minutes. A clock with two parts is minutes and
  seconds, so write an hour and five minutes as `1:05:00`, never `1:05`.
- **Units written out**: `1h 5m`, `65 min`, `45 s`.

## Dates and times

**ISO 8601 is the safest choice**, and every spreadsheet can write it:

| Written | Read as |
| --- | --- |
| `2026-11-02` | 2 November 2026, at noon (see [Workouts](#workouts)) |
| `2026-11-02 18:30`, `2026-11-02T18:30:00` | 18:30 on that day, in your phone's time zone |
| `2026-11-02 00:00`, `2026-11-02T00:00:00` | as `2026-11-02`: midnight is how a spreadsheet writes a day with no time |
| `2026-11-02T18:30:00+01:00`, `2026-11-02T17:30:00Z` | that moment, wherever it was |
| `2026/11/02` | as `2026-11-02` |

A spreadsheet often saves dates its own way when it writes a CSV, and those are read too:
`02/11/2026`, `2.11.26`, `11/2/2026 6:30 PM`. Whether `02/11` is the 2nd of November or the 11th
of February is decided by the file as a whole: a date such as `25/11/2026` can only be day first,
and `11/25/2026` only month first. A file whose dates never settle it, or settle it both ways,
has those dates unread. Write ISO dates to be sure.

Times are 24-hour (`18:30`, `18:30:00`) or 12-hour (`6:30 PM`). A `workout_time` or
`measurement_time` is used instead of any time in the date column. Exactly midnight in the date
column is read as no time, so a file saved again by a spreadsheet imports as it did before; to
date something at midnight, put `00:00` in `workout_time` or give the date its offset
(`2026-11-02T00:00:00+01:00`).

## What is not in the format

- **Templates and routines.** A plan is not history, and plans have no shape every app and
  spreadsheet shares: ranges of reps, percentages of a maximum, progressions. Titan's templates
  take a minute to build, and the app ships with editable defaults.
- **Personal records.** Titan works them out from the imported sets, together with XP, streaks,
  achievements and quests, so they follow its own rules.
- **Progress photos**, which a CSV cannot hold.
- **Elevation and steps on a set**, which few spreadsheets record. They may be added as optional
  columns in a later version without changing how a version 1 file reads.
- **Left and right limbs apart.** Titan records one figure per limb.

## What happens to a file with mistakes

| Mistake | What happens |
| --- | --- |
| No `workout_date` and `exercise`, and no `measurement_date` | The file is refused as not a plain CSV file |
| A header unit Titan does not read (`weight (stone)`) | The file is refused, naming the column |
| A row whose date does not read, or with no exercise | The row is skipped |
| A set whose reps, weight, duration or distance is not a number, or whose unit is not one Titan reads | The set is skipped and counted in the import's report |
| An RPE, a heart rate, a rest, calories or a rating that is not a number | Left out; the set or workout is kept |
| A set Titan does not allow, such as a thousand-tonne squat | The set is skipped and counted, as for any import |
| An RPE outside 1 to 10, a rating outside 1 to 5 | Left out; the set or workout is kept |
| No row with a date and an exercise that read | The file is refused |
| A quote that is never closed | The file is refused, naming the line |

## Versions

This is version 1. A later version adds optional columns and accepts more spellings; it never reads
a version 1 file differently. A change that would have to is a new format with its own required
columns.
