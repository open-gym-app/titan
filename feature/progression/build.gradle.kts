plugins {
    id("titan.android.feature")
}

android {
    namespace = "com.titan.feature.progression"
}

dependencies {
    // The records list pages through a table that grows without bound; see
    // `PersonalRecordsPagingSource`.
    implementation(libs.androidx.paging.compose)

    testImplementation(libs.androidx.paging.testing)
}

// One JVM per test class.
//
// Compose's Robolectric test environment keeps state in JVM statics that outlive a test class,
// and a class that types into a `TextField` — `BodyMeasurementsScreenTest` — leaves enough of it
// behind that the next class's composition never recomposes off a coroutine. The victim here is
// `PersonalRecordsScreenTest`: its rows arrive through `collectAsLazyPagingItems`, so the list
// stays empty and every assertion on a row fails. Which class runs first is not fixed, so
// without this the module's tests fail about half the time.
//
// Scoped to this module because it is the only one holding both kinds of test. The cost is
// about half a minute of extra JVM startup here, which is why it is not set for every module
// in `AndroidComposeConventionPlugin`.
tasks.withType<Test>().configureEach {
    setForkEvery(1L)
}
