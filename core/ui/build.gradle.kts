plugins {
    id("opengym.android.library")
    id("opengym.android.compose")
    id("opengym.android.jacoco")
}

android {
    namespace = "com.opengym.core.ui"
}

dependencies {
    // Shared components take domain types directly - `RestTimerBar(state: RestTimerState)`
    // rather than a parallel UI model per screen. `:core:domain` is pure Kotlin with no
    // Android dependencies, so nothing leaks upward. See docs/ARCHITECTURE.md; the ban on
    // `:core:ui` -> `:core:data` still stands.
    //
    // `api`, not `implementation`: the domain types appear in the public signatures of the
    // shared components, so a consumer cannot call them without those types on its own
    // compile classpath.
    api(project(":core:domain"))
}
