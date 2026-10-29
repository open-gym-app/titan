plugins {
    id("titan.android.feature")
}

android {
    namespace = "com.titan.feature.session"
}

dependencies {
    // `rememberLauncherForActivityResult`, for the notification prompt raised over the first
    // rest — the same reason `:feature:settings` declares it.
    implementation(libs.androidx.activity.compose)
}
