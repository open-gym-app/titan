plugins {
    id("opengym.android.feature")
}

android {
    namespace = "com.opengym.feature.progression"
}

dependencies {
    // The records list pages through a table that grows without bound; see
    // `PersonalRecordsPagingSource`.
    implementation(libs.androidx.paging.compose)

    testImplementation(libs.androidx.paging.testing)
}
