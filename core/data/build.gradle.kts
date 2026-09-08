plugins {
    id("opengym.android.library")
    id("opengym.android.hilt")
    // Declared here as well as inside the Hilt convention: Room's compiler is the other
    // KSP processor this module runs, and it should not stop working the day the DI
    // modules move somewhere else and `opengym.android.hilt` comes off. Applying is
    // idempotent.
    alias(libs.plugins.ksp)
    id("opengym.android.jacoco")
}

android {
    namespace = "com.opengym.core.data"

    sourceSets {
        // Exported Room schemas, so MigrationTestHelper can build historical
        // versions of the database from their on-disk JSON spec.
        getByName("test") {
            assets.srcDirs("$projectDir/schemas")
        }
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
    }

    testOptions {
        unitTests {
            // Robolectric-backed DAO tests run against a real in-memory SQLite
            // on the JVM, no emulator required.
            isIncludeAndroidResources = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(project(":core:domain"))

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    // `NotificationManagerCompat` and `NotificationCompat`, which this module is the only one
    // allowed to import — see `AndroidNotifier`.
    implementation(libs.androidx.core.ktx)
    // `ProcessLifecycleOwner`, the one thing that can tell an alarm firing in the background
    // from one firing while the user is looking at the app — see `AppForegroundState`.
    implementation(libs.androidx.lifecycle.process)

    // The weekly auto-backup (#181). The worker lives here rather than in `:feature:settings`
    // because it is I/O with no screen behind it — it runs while the app is not open — and
    // `androidx.hilt:hilt-compiler` is the KSP processor that teaches `HiltWorkerFactory` to
    // build it. `:app` supplies that factory to WorkManager; see `OpenGymApplication`.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // The backup round trip asserts on the *document*, not only on the rows it restores: which
    // keys a table's rows carry, that a null column is written rather than omitted, that a
    // version this build does not read is refused. All of that needs a JSON parser here.
    // `:core:domain` keeps its own copy `implementation`, so nothing in `main` sees this.
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.room.testing)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    testImplementation(libs.androidx.paging.testing)
    // `WorkManagerTestInitHelper` and `TestListenableWorkerBuilder`, so the backup worker and
    // the periodic request behind it are driven on the JVM rather than on an emulator.
    testImplementation(libs.androidx.work.testing)
    // The progress row is observed as a Flow; asserting an emission arrives is Turbine's job
    // rather than a timeout's — see `UserProgressDaoTest`.
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
