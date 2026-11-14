pluginManagement {
    // The filtered google() the root build uses, for plugins and dependencies both.
    apply(from = "../gradle/google.settings.gradle.kts")

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
    // Beside the filtered google() that ../gradle/google.settings.gradle.kts added above.
    repositories {
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
