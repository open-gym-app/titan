plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.opengym.core.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

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

    lint {
        abortOnError = true
        lintConfig = rootProject.file("lint.xml")
        htmlReport = true
        xmlReport = true
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

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:domain"))

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.paging.testing)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
