plugins {
    id("titan.android.library")
}

android {
    namespace = "com.titan.core.testing"
}

// Test support that has to be *shared*: the module sits on every Compose module's test
// classpath (see `AndroidComposeConventionPlugin`) and on nothing else. Its dependencies are
// therefore `implementation`, not `testImplementation` — from this module's point of view
// they are the code, and a consumer's unit tests are what run it.
//
// No Compose compiler and no `@Composable` here: what the tester drives is the preview a
// module already has, through Roborazzi's own API, and a helper that needed to compose
// something of its own would belong in the test that composes it.
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui.test.junit4)
    implementation(libs.roborazzi)
    implementation(libs.roborazzi.compose)
    implementation(libs.roborazzi.compose.preview.scanner.support)
    implementation(libs.roborazzi.accessibility.check)
    implementation(libs.composable.preview.scanner)
}
