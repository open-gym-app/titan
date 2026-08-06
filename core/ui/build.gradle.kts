plugins {
    id("opengym.android.library")
    id("opengym.android.compose")
}

android {
    namespace = "com.opengym.core.ui"
}

dependencies {
    // Shared components take domain types directly - `RestTimerBar(state: RestTimerState)`
    // rather than a parallel UI model per screen. `:core:domain` is pure Kotlin with no
    // Android dependencies, so nothing leaks upward. See docs/ARCHITECTURE.md; the ban on
    // `:core:ui` -> `:core:data` still stands.
    implementation(project(":core:domain"))
}
