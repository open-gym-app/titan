plugins {
    id("titan.android.library")
    id("titan.android.compose")
    id("titan.android.jacoco")

    // The saved-screen-state helper encodes a ViewModel's own state as JSON, so this module
    // needs the serialization compiler plugin the features already apply for their route
    // types — see `com.titan.core.ui.state.savedScreenState`.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.titan.core.ui"
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

    // `SavedStateHandle` is the receiver of `savedScreenState`, and `Json` is what it encodes
    // with, so both are in that function's public signature by way of its inline body: a
    // feature calling it compiles against them whether or not it names them. `api` is what
    // makes that true rather than accidental - every feature already sees the first through
    // navigation and the second through its own serialization plugin.
    api(libs.androidx.lifecycle.viewmodel.savedstate)
    api(libs.kotlinx.serialization.json)

    // `rememberLauncherForActivityResult`, for `NotificationPermissionPrompt`: the settings and
    // the active workout both raise that prompt, and features cannot share code but through here.
    implementation(libs.androidx.activity.compose)
}
