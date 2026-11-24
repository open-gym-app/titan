<h1 align="center">
  Titan
</h1>

<p align="center">
  <a href="https://github.com/open-gym-app/titan/actions/workflows/ci.yml">
    <img src="https://open-gym-app.github.io/img/logo.png" alt="Titan logo" width="120" style="border-radius: 20px;" />
  </a>
</p>

<p align="center">
  <img src="https://img.shields.io/github/license/open-gym-app/titan" alt="GPL-3.0" />
</p>

<p align="center">
  <!-- placeholder — add links once published, see #49 -->
  <img src="https://img.shields.io/badge/Google_Play-414141?style=for-the-badge&logo=google-play&logoColor=white" alt="Get it on Google Play" />
  &nbsp;
  <img src="https://img.shields.io/badge/App_Store-0D96F6?style=for-the-badge&logo=apple&logoColor=white" alt="Download on the App Store" />
</p>

<p align="center">
  <a href="#introduction">Introduction</a> &nbsp;&bull;&nbsp;
  <a href="#what-v1-ships">What v1 ships</a> &nbsp;&bull;&nbsp;
  <a href="#planned">Planned</a> &nbsp;&bull;&nbsp;
  <a href="#getting-started">Getting started</a> &nbsp;&bull;&nbsp;
  <a href="#documentation">Documentation</a> &nbsp;&bull;&nbsp;
  <a href="#contributing">Contributing</a> &nbsp;&bull;&nbsp;
  <a href="#licence">Licence</a> &nbsp;&bull;&nbsp;
  <a href="#issue">Issue?</a>
</p>

## Introduction

Titan is a free, open-source Android workout tracker built with Kotlin and Jetpack Compose.
All data lives on your device. **No account required**, and no ads.

## What v1 ships

- **Session logging**: free-form or from a template, with a rest timer and a plate calculator.
- **Templates**: save a workout's shape and start from it next time.
- **Exercise library**: a searchable library of exercises, seeded from [`yuhonas/free-exercise-db`](https://github.com/yuhonas/free-exercise-db), with the muscles each one works and how to perform it.
- **Progression**: personal records, an estimated one-rep max, and per-exercise progression charts.
- **Suggestions**: a next target for every set, and a warm-up and cool-down for the muscles a session trains. Nothing reaches your log until you accept it, and the warm-up cards can be switched off in Settings.
- **Stats & history**: a calendar of past sessions and the trends behind them.
- **Levels, quests and achievements**: XP for every workout, weekly and monthly quests, achievements and the odd collectible, all earned by training.
- **Reminders**: a notification on the days and at the time you plan to train.
- **Backup & restore**: export everything to a file, or restore a device from one. Nothing leaves the phone unless you send it somewhere yourself.
- **Themes, units and locales**: light or dark, metric or imperial, and the app is available in English, French, Spanish and Portuguese.
- **Accessibility**: built and checked against TalkBack, large fonts and one-handed use; see [ACCESSIBILITY.md](docs/ACCESSIBILITY.md).

## Planned

The following are not in v1 and are not yet built. They show up disabled in Settings today so the app's layout does not shift under people who have learned where things are. See the in-app rows for the up-to-date status of each one.

- **Marketplace**: sharing and downloading templates and challenges from other users.
- **Gym map**: finding gyms and equipment nearby.
- **Health Connect**: syncing body measurements with Android's Health Connect.

## Getting started

### Requirements

- Android Studio
- JDK 17
- Android SDK

### Build

```bash
./gradlew assembleDebug
```

### Run tests

```bash
./gradlew test
```

## Documentation

Full documentation is available at **[open-gym-app.github.io](https://open-gym-app.github.io)**.

Reference documentation lives in [`docs/`](docs/):

- [ARCHITECTURE.md](docs/ARCHITECTURE.md): module structure, dependency rules, navigation and dependency injection.
- [PERSISTENCE.md](docs/PERSISTENCE.md): the database schema, its migration strategy and the storage assumptions built on it.
- [ACCESSIBILITY.md](docs/ACCESSIBILITY.md): what the app promises TalkBack, large fonts and a thumb, how every build checks it, and the record of the last pass on a device.
- [TRANSLATION.md](docs/TRANSLATION.md): the languages the app ships in, the check that keeps every translation in step with the English, and the glossary each one is held to.
- [export-schema.md](docs/export-schema.md): the JSON document a backup writes and a restore reads, and what a restore refuses.
- [import-csv.md](docs/import-csv.md): the plain CSV format a history from any other app or spreadsheet can be converted into and imported, with example files.

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.
Contributions are accepted under the project's licence: see below.

## Licence

Titan is licensed under the **[GNU General Public License v3.0](LICENSE)**.
You are free to use, study, share and modify this software under the terms of that licence.

Third-party content bundled with the app (the seeded exercise library and its images) is listed with its own terms in [NOTICE.md](NOTICE.md). The app's runtime dependencies and their licences are also listed in-app, under Settings › About › Open source licences.

## Issue?

If you encounter a bug or have a feature request, please [open an issue](https://github.com/open-gym-app/titan/issues/new).
