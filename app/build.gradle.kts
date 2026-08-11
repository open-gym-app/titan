import java.util.Properties
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // `:app` declares navigation types of its own — the tab scaffold's graph route and, until
    // #97 gives settings a module, the settings destination — so it needs the serialization
    // compiler plugin the way a feature does. The `opengym.android.feature` convention
    // applies it for the modules that use it; `:app` applies no convention plugin.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    id("opengym.android.jacoco")
}

private val keystoreProperties = Properties().also { props ->
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(props::load)
}

private fun prop(envKey: String, fileKey: String): String? =
    (System.getenv(envKey) ?: keystoreProperties.getProperty(fileKey))?.takeIf { it.isNotEmpty() }

android {
    namespace = "com.opengym.app"

    // :app does not apply a convention plugin, so these are a second copy of the levels in
    // build-logic/.../AndroidSdk.kt. Bump both together, or the APK ships against an older
    // platform than the libraries it packages were compiled for.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.opengym.app"
        minSdk = 26
        // Deliberately behind `compileSdk`: raising this opts the app in to platform 37's
        // runtime behaviour changes, which is a decision that needs its own testing pass.
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = prop("KEYSTORE_PATH", "storeFile")?.let(::file)
            storePassword = prop("KEYSTORE_PASSWORD", "storePassword")
            keyAlias = prop("KEY_ALIAS", "keyAlias")
            keyPassword = prop("KEY_PASSWORD", "keyPassword")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
        // `BuildConfig.VERSION_NAME`, which settings' About group shows. Off by default since
        // AGP 8, and `:app` is the only module that should ever turn it on: a library's
        // BuildConfig carries the library's version, not the app's.
        buildConfig = true
    }

    testOptions {
        unitTests {
            // `createComposeRule()` inflates a real Android view hierarchy, so a Compose
            // test on the JVM needs the merged resources and a Robolectric runtime behind
            // it. The SDK pin that goes with this lives in
            // `src/test/resources/robolectric.properties` — the library modules get both
            // from `opengym.android.compose`, which :app does not apply.
            isIncludeAndroidResources = true
        }
    }

    lint {
        abortOnError = true
        lintConfig = rootProject.file("lint.xml")
        htmlReport = true
        xmlReport = true
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<JavaCompile>().configureEach {
    javaCompiler.set(javaToolchains.compilerFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))

    // Every feature, because `:app` is the composition root: it calls each feature's
    // `NavGraphBuilder` extension and supplies the lambdas that carry the user across a
    // module boundary. This is the only module allowed to depend on more than one.
    implementation(project(":feature:home"))
    implementation(project(":feature:session"))
    implementation(project(":feature:history"))
    implementation(project(":feature:progression"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // `:app` owns the NavHost and is the only module that navigates across a feature
    // boundary, so it is the only non-feature module that needs navigation.
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // `ui-test-junit4` on the JVM classpath is only half a harness: the Android runtime it
    // drives comes from Robolectric.
    testImplementation(libs.robolectric)

    // The shell's navigation test drives the real graph, and the real graph now contains a
    // screen with a `@HiltViewModel` behind it. `hiltViewModel()` resolves through the
    // activity, so the test needs a Hilt-enabled application *and* a Hilt-enabled activity —
    // see `HiltTestActivity` and `src/debug/AndroidManifest.xml`.
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
