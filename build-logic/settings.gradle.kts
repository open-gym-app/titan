pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

// An included build does not inherit the root build's settings plugins, so the toolchain
// resolver has to be declared here too. Without it, the JDK 17 toolchain that
// `convention/build.gradle.kts` requests can only be satisfied by a local installation:
// on a machine running Gradle under a different JDK, every invocation fails at
// configuration with "Toolchain download repositories have not been configured", before
// any module is configured. Keep the version in step with the root `settings.gradle.kts`.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // The content filter the root settings.gradle.kts puts on google(); see the comment there.
    repositories {
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.android\\..*")
                includeGroupByRegex("com\\.google\\.testing\\.platform")
                includeGroupByRegex("com\\.google\\.prefab")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }

    // The convention plugins declare their dependencies from the same catalog the
    // modules do, so a version bump in gradle/libs.versions.toml reaches the build
    // logic too rather than needing a second edit here.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

include(":convention")
