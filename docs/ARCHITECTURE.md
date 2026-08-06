# Architecture

## Module structure

```text
open-gym/
├── build-logic/            # Included build - the `opengym.android.*` convention plugins
├── app/                    # Composition root - wires DI graph, declares Application/Activity
├── core/
│   ├── domain/             # Business logic - pure Kotlin JVM, zero Android deps
│   ├── data/               # Data access - Room, repositories, Hilt bindings
│   └── ui/                 # Shared Compose components and theming
└── feature/                # (placeholder) one sub-module per vertical slice
```

Gradle module names: `:app`, `:core:domain`, `:core:data`, `:core:ui`.

## Build logic

Shared Gradle configuration lives in `build-logic/`, an included build rather than
`buildSrc` so that editing a convention plugin does not invalidate every module's build
script cache. Its plugins read `gradle/libs.versions.toml` through the same catalog the
modules use.

| Plugin id | Applies |
| --- | --- |
| `opengym.android.library` | `com.android.library`, the SDK levels, the JVM toolchain, the shared `lint {}` block, and the JVM unit-test dependencies |
| `opengym.android.compose` | The Compose compiler plugin, `buildFeatures { compose = true }`, and the BOM-managed UI dependencies |
| `opengym.android.hilt` | Hilt and the KSP processor that generates its components |
| `opengym.android.feature` | The three above, plus `:core:domain`, `:core:ui` and the ViewModel-to-Compose bridge — everything a vertical slice needs |

`opengym.android.compose` and `opengym.android.hilt` apply `opengym.android.library`
themselves, so the order a module lists them in does not matter.

A feature module's build file should be the plugin and a `namespace`, nothing more:

```kotlin
plugins {
    id("opengym.android.feature")
}

android {
    namespace = "com.opengym.feature.home"
}
```

`:app` and `:core:domain` are deliberately left out: `:app` is an application module with
signing config and build variants that is not worth generalising for one consumer, and
`:core:domain` is a plain Kotlin JVM library carrying a JaCoCo coverage floor no other
module shares. Both therefore keep their own copy of the SDK levels and toolchain, and a
bump has to be made in all three places — `:app` in particular is the module that ships.

For everything else, `compileSdk`, `minSdk` and the JVM toolchain are constants in
`build-logic/.../AndroidSdk.kt` rather than catalog entries — the catalog addresses
artifacts, and an SDK level is not one.

## Layers and responsibilities

### Domain (`:core:domain`)

The inner-most layer. Contains entities, use cases, and repository interfaces.
Must compile as a plain Kotlin JVM library - no `android {}` block, no Android imports.
All business rules live here so they can be tested without an Android runtime.

### Data (`:core:data`)

Implements the repository interfaces declared in `:core:domain`.
Owns all I/O: Room DAOs, remote API clients, data mappers.
Depends on `:core:domain` for the contracts it fulfils.
The schema, its migration strategy, and the storage assumptions analytics and import/export rely on are documented in [PERSISTENCE.md](PERSISTENCE.md).

### Presentation (`:core:ui` + future `:feature:*`)

Compose screens, ViewModels, and shared UI components.
ViewModels call domain use cases directly - they do not touch data sources.
`:core:ui` holds only reusable widgets and the design system; it carries no business logic.

### App (`:app`)

Composition root only. Declares `Application`, the single `Activity`, and the top-level navigation graph. Connects all layers through Hilt modules. Contains no business logic.

## Allowed dependency directions

```mermaid
graph TD
    app[:app]
    data[:core:data]
    domain[:core:domain]
    ui[:core:ui]
    feature[":feature:*"]

    app --> domain
    app --> data
    app --> ui
    data --> domain
    ui --> domain
    feature --> domain
    feature --> ui
```

| Edge | Why it is allowed |
| --- | --- |
| `:app` → everything | Composition root; it is the only place the layers are wired together |
| `:core:data` → `:core:domain` | Implements the repository interfaces the domain declares |
| `:core:ui` → `:core:domain` | Shared components take domain types directly — `RestTimerBar(state: RestTimerState)`, `StatChip(stat: SessionStats)` — rather than each feature translating into a private mirror of the same model. `:core:domain` is pure Kotlin with zero Android dependencies, so the edge carries nothing upward |
| `:feature:*` → `:core:domain` | ViewModels call use cases directly |
| `:feature:*` → `:core:ui` | The design system and shared widgets |

`:core:ui` → `:core:domain` is a dependency on *types*, not on behaviour. A shared
component may accept and render a domain model; it may not call a use case or a
repository. That work belongs to the feature's ViewModel.

## Prohibited dependencies

| From | May NOT depend on | Reason |
| ------ | ------------------- | -------- |
| `:core:domain` | `:core:data`, `:core:ui`, `:app`, `:feature:*` | Domain must be the stable, dependency-free centre |
| `:core:ui` | `:core:data` | Presentation must not bypass the domain layer |
| `:core:data` | `:core:ui`, `:app`, `:feature:*` | Data layer is unaware of how results are displayed |
| `:feature:*` | other `:feature:*` | Features are independent vertical slices; cross-feature navigation goes through `:app` |

Any dependency that violates these rules must be refactored: extract the shared concern into
`:core:domain` (if it is a contract) or `:core:ui` (if it is a visual primitive).

## Navigation

### Features do not know about each other

`:feature:*` modules may not depend on one another, so a feature cannot name another
feature's destination. Instead each feature exposes a `NavGraphBuilder` extension and
hoists every outbound navigation action as a lambda. `:app` owns the `NavHost`, calls
each extension, and supplies the lambdas — it is the only place in the codebase that
calls `navController.navigate(...)` across a module boundary.

```kotlin
// :feature:home — declares what it needs to leave with, not where it goes
fun NavGraphBuilder.homeGraph(
    onStartEmptySession: () -> Unit,
    onResumeSession: (sessionId: Long) -> Unit,
) {
    composable<HomeRoute> {
        HomeRoute(
            onStartEmptySession = onStartEmptySession,
            onResumeSession = onResumeSession,
        )
    }
}
```

```kotlin
// :app — the only module that knows both sides of the edge
NavHost(navController = navController, startDestination = HomeRoute) {
    homeGraph(
        onStartEmptySession = { navController.navigate(NewSessionRoute) },
        onResumeSession = { id -> navController.navigate(ActiveSessionRoute(id)) },
    )
    sessionGraph(
        onSessionFinished = { id -> navController.navigate(SummaryRoute(id)) },
    )
}
```

**Route types live in the module that owns the destination.** `ActiveSessionRoute` is
declared in `:feature:session` and `:app` imports it. This is the one direction that
works: `:app` already depends on every feature, so no new edge is created, and the
feature that owns a screen owns the shape of its arguments.

A lambda parameter named for the *event* (`onSessionFinished`) rather than the
destination (`goToSummary`) keeps the feature honest — it is reporting what happened, and
`:app` decides where that leads.

### Routes are type-safe

Destinations are `@Serializable` objects or data classes, navigated with
`composable<Route>` and read back with `toRoute<Route>()`. No string route templates, no
`navArgument` declarations, no manual argument parsing — a wrong argument type is a
compile error rather than a null at runtime.

```kotlin
@Serializable
data object HomeRoute

@Serializable
data class ActiveSessionRoute(val sessionId: Long)

composable<ActiveSessionRoute> { entry ->
    val route: ActiveSessionRoute = entry.toRoute()
    ActiveSessionRoute(sessionId = route.sessionId)
}
```

The `opengym.android.feature` convention plugin applies the `kotlin-serialization`
plugin, so a feature module needs no extra setup to declare one.

## Dependency injection (Hilt)

### Module location

Each Hilt module lives in a `di/` package inside the Gradle module that owns the dependency it provides - e.g. Room/DAO bindings live in `core/data/.../di/DatabaseModule.kt`. Do not centralize unrelated bindings in a single top-level module; split by concern instead.

### Scoping rules

| Scope | Component | Use for |
| --- | --- | --- |
| `@Singleton` | `SingletonComponent` | App-lifetime dependencies: database, DAOs, network clients, repositories |
| `@ViewModelScoped` | `ViewModelComponent` | Dependencies shared across a single ViewModel's injection graph but recreated per screen |
| *(none)* | any | Cheap, stateless objects (e.g. mappers) - a fresh instance per injection site is fine, don't over-scope |

### `@Provides` vs `@Binds`

Use `@Provides` when the type needs construction logic or you don't own its constructor (e.g. configuring a `Room.databaseBuilder`):

```kotlin
// ✅ construction logic required - @Provides is correct
@Provides
@Singleton
fun provideDatabase(@ApplicationContext context: Context): OpenGymDatabase =
    Room.databaseBuilder(context, OpenGymDatabase::class.java, "open-gym.db")
        .addMigrations(*OpenGymDatabase.MIGRATIONS)
        .build()
```

Use `@Binds` when you're only mapping an interface to an implementation - it's an abstract method, so Hilt generates the wiring without a method body:

```kotlin
// ✅ pure interface-to-impl mapping - @Binds is correct
@Binds
abstract fun bindWorkoutRepository(impl: WorkoutRepositoryImpl): WorkoutRepository

// ❌ no construction logic here - should be @Binds, not @Provides
@Provides
fun provideWorkoutRepository(impl: WorkoutRepositoryImpl): WorkoutRepository = impl
```
