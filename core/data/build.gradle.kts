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
    // The progress row is observed as a Flow; asserting an emission arrives is Turbine's job
    // rather than a timeout's — see `UserProgressDaoTest`.
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
