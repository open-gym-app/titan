plugins {
    id("opengym.android.feature")
}

android {
    namespace = "com.opengym.feature.settings"
}

dependencies {
    // The one thing this module needs that the feature convention does not supply:
    // `rememberLauncherForActivityResult`, which is how the notification permission prompt is
    // raised. Declared rather than leaned on through `navigation-compose`'s transitive copy —
    // this module calls it directly, so it belongs on this module's own compile classpath.
    implementation(libs.androidx.activity.compose)
}
