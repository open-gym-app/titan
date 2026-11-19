plugins {
    id("titan.android.feature")
}

android {
    namespace = "com.titan.feature.titan"
}

dependencies {
    testImplementation(libs.turbine)
}
