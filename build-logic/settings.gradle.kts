dependencyResolutionManagement {
    repositories {
        google()
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
