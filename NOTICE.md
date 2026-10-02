# Notice

Titan is licensed under the GNU General Public License v3.0: see [LICENSE](LICENSE).

This file lists third-party content bundled with the app (data and assets shipped inside the APK) and the terms it is used under.
It does not list build or runtime-only dependencies (Room, Compose, Hilt, kotlinx.serialization, and the rest of the dependency graph): those are listed with their own licences in-app, under **Settings › About › Open source licences**, generated at build time from the dependency graph.

## Exercise library

The seeded exercise library (names, muscles, equipment and instructions in `core/data/src/main/assets/seed/exercises.sql`) is derived from [yuhonas/free-exercise-db](https://github.com/yuhonas/free-exercise-db), pinned at the commit recorded in `tools/seed/source.lock`.

- **Licence:** [Unlicense](https://github.com/yuhonas/free-exercise-db/blob/main/LICENSE.md): public domain. No attribution is legally required; it is credited here anyway, because the data is not ours.

## Exercise images

The exercise images in `core/data/src/main/assets/seed/images/` come from the same `yuhonas/free-exercise-db` repository and are covered by the same Unlicense grant above.

## Fonts

Titan does not bundle any custom font. It draws exclusively in the system font the device provides through Jetpack Compose's default `Typography`.
