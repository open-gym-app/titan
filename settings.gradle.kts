pluginManagement {
    // Shared build logic (the `opengym.android.*` convention plugins). An included
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

rootProject.name = "open-gym"

include(":app")
include(":core:domain")
include(":core:data")
include(":core:ui")
