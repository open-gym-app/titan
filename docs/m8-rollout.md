# M8 rollout

M8 adds a gym directory, gym reviews, a template marketplace and Health Connect sync. Each
ships behind its own switch, and every switch defaults to **off**. A user who never opts in
runs exactly what they ran before M8.

## The switches

`M8Feature` in `:core:domain` names them; `FeatureToggles` reads and writes them;
`DataStoreFeatureToggles` stores one boolean each in `files/datastore/feature-toggles.preferences_pb`.

| Switch                  | Gates                                             | Read at                                   |
| ----------------------- | ------------------------------------------------- | ----------------------------------------- |
| `MARKETPLACE_INGESTION` | Loading the bundled marketplace into Room         | `MarketplaceBootstrap.start()`, at launch |
| `HEALTH_CONNECT`        | Writing finished sessions, importing workouts     | `HealthConnectControls`, the sync and import use cases |
| `GYM_DIRECTORY`         | The gym map and nearby search                     | No entry point yet                        |
| `GYM_REVIEWS`           | Reviews and the first check-in / review XP hooks  | No entry point yet                        |
| `MARKETPLACE_SEARCH`    | Searching and ranking marketplace templates       | No entry point yet                        |

The last three have domain code and no UI. Whoever adds their screen reads the switch at the
screen's entry point (the route that would show it), not inside the use case, so the code stays
testable with the switch on.

### Semantics

- **Absent means off.** A fresh install, a cleared store and an unreadable value all read as off.
- **Cleared by the user, not by an update.** An app update never flips a switch.
- **Off is inert, not hidden-but-running.** An off switch means no work, no permission prompt
  and no network: `MarketplaceBootstrap` returns before opening the bundle, and Health Connect
  is not asked for permissions or written to.
- **The switch is the user's.** There is no remote flag. The app has no `INTERNET` permission
  (`NetworkPolicyTest`), so a remote kill-switch cannot exist; a bad release is stopped by
  halting the staged rollout and shipping a build with the default unchanged.
- **Backups do not carry switches.** The `datastore` directory is excluded from backup, so a
  restored install starts with everything off.

## Staged rollout

Releases go out through the Play Console's staged rollout.

1. **5%.** Hold for 48 hours. Watch the crash-free rate and ANR rate against the previous release.
2. **20%.** Hold for 48 hours.
3. **50%**, then **100%**.

Halt at any stage if crash-free users drops by more than 0.5 points against the previous
release, or a regression-checklist item below fails on a device. A halt costs nothing for
users who already have the build: their switches are off and the new code is inert.

## Regression checklist

Run on a build upgraded from the previous release, with no switch touched, then again with
each switch on.

Switches off (the default; this is what almost every user runs):

- [ ] Launch to Home with no extra delay; no marketplace rows in the database.
- [ ] Start a workout from a template, log a set, finish it, reach the recap, and return Home
      (`EndToEndJourneyTest`).
- [ ] No Health Connect permission prompt anywhere, including Settings and Body.
- [ ] The Health Connect row in Settings and Body shows only where Health Connect is available,
      and toggling nothing changes nothing.
- [ ] Existing history, templates and personal records are unchanged.
- [ ] Export and import round-trip (`docs/export-schema.md`).

Per switch, once on:

- [ ] `MARKETPLACE_INGESTION`: the table fills on the next launch and a second launch changes nothing.
- [ ] `HEALTH_CONNECT`: the rationale appears before the permission prompt; denying leaves the
      switch off; a finished workout appears in Health Connect once; an imported workout
      appears in History once; killing Health Connect mid-finish never delays the recap.
- [ ] Turning a switch back off stops the behaviour on the next use, and loses no data.

## What guards this in CI

- `M8DefaultsTest` (`:app`): on the real graph every switch is off, a launch ingests nothing,
  and Health Connect is skipped.
- `MarketplaceBootstrapTest`: only `MARKETPLACE_INGESTION` lets the bundle in.
- `EndToEndJourneyTest`, `TitanAppNavigationTest`: the core session journey, on the same defaults.
- `NetworkPolicyTest`: the app cannot reach the network.
