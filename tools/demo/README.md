# Demo dataset

Five months of a made-up lifter's training, as the JSON export a phone writes from Backup &
restore (docs/export-schema.md). The store listing's screenshots are taken from it, so they show
plausible data rather than a developer's real history and can be retaken by anyone.

| File | What it is |
| --- | --- |
| `titan-demo-en-US.json` | The history, as an English phone would have exported it |
| `titan-demo-fr-FR.json`, `-es-ES`, `-pt-BR` | The same history from a French, Spanish or Brazilian Portuguese phone |

The four files hold the same sessions, sets and figures. They differ only in what the seed
names in the device's language — the default templates, and so the sessions started from them —
because a French screenshot taken from the English file would show "Push" and "Legs" on a French
screen.

## The lifter

An intermediate on a push/pull/legs split, three evenings a week from May to the end of
September 2026: 60 sessions from the default templates, about one planned day in ten missed,
and a weekly weigh-in drifting down by a couple of kilograms. Loads and reps are whatever the
app's next-target line suggested before each session, with the odd rep missed, so the
progression is the app's own. Personal records, XP, quests, achievements and loot are not
written by hand: the real post-session cascade derived them from the sets.

The last session is a push day on Monday 28 September. Everything generated from the dataset
treats Wednesday 30 September, 17:40 UTC, as "now" — the next workout in the rotation.

## Regenerating

```sh
make demo-data            # rewrite the four exports
make store-screenshots    # re-capture fastlane/metadata/android/*/images/phoneScreenshots/
```

Both run as Robolectric tests over the real app in `:app` (`app/src/test/.../store/`):

- `DemoDatasetTest` builds the history on a fresh database for each locale (`DemoHistory`) and
  exports it. Without `make`'s flag it runs on every `make test` as a check instead: the
  generator must reproduce each committed file byte for byte, and each file must restore
  through the real import. A change to the schema, the seed, the default templates or any
  engine behind the cascade that moves a row fails there with the command to run.
- `StoreScreenshotTest` restores each file through the import, pins the app's clock to the
  demo's "now", drives the app to six screens and writes them at 1080 × 1920, without alpha.
  It runs only through `make`.

After `make demo-data`, run `make store-screenshots` too and commit both: the screenshots
describe the files.

## Restoring it on a phone

Copy a file to the device and pick it in Settings › Backup & restore › Restore. The restore
**replaces everything on the phone**. Set the device's date to 30 September 2026 first, or
every dated screen will measure from the real date: the week strip empty, the streak gone, the
last workout months ago.
