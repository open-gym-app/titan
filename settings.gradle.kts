pluginManagement {
    // Shared build logic (the `titan.android.*` convention plugins). An included
    // build rather than `buildSrc`, so editing a convention plugin does not invalidate
    // every module's build script cache.
    includeBuild("build-logic")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "titan"

include(":app")
include(":core:domain")
include(":core:data")
include(":core:ui")

// Shared test support (#40): the Roborazzi preview tester that runs the Accessibility Test
// Framework over every `@Preview`, and the helpers a screen's own accessibility test calls.
// A module rather than a `testFixtures` source set because it has to be visible to seven
// modules' unit tests, and only to those.
include(":core:testing")

// One module per vertical slice. A feature is reachable only from `:app`, which owns the
// `NavHost` and supplies every cross-feature navigation action — see docs/ARCHITECTURE.md.
// `checkArchitecture` fails the build if one of them ever depends on another.
include(":feature:onboarding")
include(":feature:home")
include(":feature:session")
include(":feature:history")
include(":feature:progression")
include(":feature:settings")

// On-device performance (#42). A `com.android.test` module: it has no `main` source set and
// ships nothing, it instruments `:app`'s `benchmark` build type from a second process, and it
// is where the Baseline Profile committed into `:app` is generated. Not a `:feature:`, and
// deliberately not under `core/` either — it is neither.
include(":benchmark")
