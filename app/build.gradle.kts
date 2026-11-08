import java.util.Properties
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // `:app` declares a navigation type of its own — the tab scaffold's graph route — so it
    // needs the serialization compiler plugin the way a feature does. The `titan.android.feature` convention
    // applies it for the modules that use it; `:app` applies no convention plugin.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    // Generates the JSON the open source licences screen reads at runtime (#208), from this
    // module's own dependency graph — which, as the composition root, is the whole app's.
    alias(libs.plugins.aboutlibraries)
    id("titan.android.jacoco")
}

private val keystoreProperties = Properties().also { props ->
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(props::load)
}

private fun prop(envKey: String, fileKey: String): String? =
    (System.getenv(envKey) ?: keystoreProperties.getProperty(fileKey))?.takeIf { it.isNotEmpty() }

// The version comes from the release tag, passed by .github/workflows/release.yml as
// `-PreleaseVersion=1.2.3` or `-PreleaseVersion=1.2.3-rc.4`, and is never edited by hand.
//
// `versionCode` is MAJOR * 1_000_000 + MINOR * 10_000 + PATCH * 100 + N, where N is the
// release candidate number and 99 for the release itself. Play only accepts a code higher
// than every one it has seen, and this keeps that true as long as tags are pushed in order:
// 1.0.0-rc.1 < 1.0.0-rc.2 < 1.0.0 < 1.0.1-rc.1. It caps a version at 98 candidates.
//
// A build without the property is a local one: `0.0.0-dev`, below anything ever uploaded.
private data class ReleaseVersion(val name: String, val code: Int)

private val releaseVersion: ReleaseVersion =
    (findProperty("releaseVersion") as String?)?.let { name ->
        val pattern = Regex("""(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?""")
        val match = requireNotNull(pattern.matchEntire(name)) {
            "releaseVersion must be MAJOR.MINOR.PATCH or MAJOR.MINOR.PATCH-rc.N, was '$name'"
        }
        val (major, minor, patch, rc) = match.destructured
        val candidate = rc.toIntOrNull() ?: 99
        val fitsCandidate = rc.isEmpty() || candidate in 1..98
        require(minor.toInt() < 100 && patch.toInt() < 100 && fitsCandidate) {
            "releaseVersion '$name' does not fit the versionCode scheme"
        }
        val code = major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100
        ReleaseVersion(name, code + candidate)
    } ?: ReleaseVersion("0.0.0-dev", 1)

android {
    namespace = "com.titan.app"

    // :app does not apply a convention plugin, so these are a second copy of the levels in
    // build-logic/.../AndroidSdk.kt. Bump both together, or the APK ships against an older
    // platform than the libraries it packages were compiled for.
    compileSdk = 37

    defaultConfig {
        applicationId = "app.titan.gym"
        minSdk = 26
        // Deliberately behind `compileSdk`: raising this opts the app in to platform 37's
        // runtime behaviour changes, which is a decision that needs its own testing pass.
        targetSdk = 36
        versionCode = releaseVersion.code
        versionName = releaseVersion.name

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
            // Builds `en-XA` and `ar-XB` into the debug APK from the English strings: the first
            // is accented and about 30% longer, the second is right-to-left. Both are how a
            // truncated label or a left-anchored row is found before a translator finds it, and
            // neither is reachable without this flag - Developer options only lists a
            // pseudolocale that the installed build actually carries. Debug only, so nothing
            // ships them.
            isPseudoLocalesEnabled = true
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

        // What `:benchmark` measures (#42). Release-like, because a benchmark against a
        // debuggable build measures the interpreter and answers a question nobody asked.
        //
        // ### No `applicationIdSuffix`
        //
        // Unlike `staging`. The benchmarks and the profile generator address the app by
        // package name from a second process, and a suffix here would make every one of them
        // carry a name that is `app.titan.gym` in the build that ships and something else in
        // the build that was measured.
        //
        // ### Minification is off, and that is the trade
        //
        // A Baseline Profile is generated as a list of *original* class and method names;
        // AGP rewrites it through R8's mapping when it packages the minified APK, so the file
        // this build type produces is exactly what `src/main/baselineProfiles` should hold.
        // Generated from a minified build it would be a list of `a.b.c` that means nothing to
        // the next R8 run. The cost is that startup here is measured over more code than
        // `release` ships, so the absolute figure is a ceiling and the profile's *improvement*
        // — which is what the acceptance criterion asks for — is the number that transfers.
        //
        // Signed with the debug key so a benchmark run needs no `keystore.properties`.
        create("benchmark") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    // The languages the app ships in: English, and the three translations under
    // `values-fr`, `values-es` and `values-pt-rBR` in every module (docs/TRANSLATION.md).
    androidResources {
        // Keeps only these locales' resources out of every library the app packages — the
        // AndroidX and Material artifacts carry strings in some eighty languages, and an APK
        // that ships German dialog buttons over an English app is not a German translation.
        // AGP adds the two pseudolocales to this list on its own for the debug build.
        localeFilters += listOf("en", "fr", "es", "pt-rBR")
        // Writes the `<locale-config>` Android 13's per-app language setting reads from the
        // locales the resources actually carry, so the list can never drift from the
        // directories. The unqualified `values/` is named as `en-US` in
        // `src/main/res/resources.properties`, which the generated config needs to know.
        generateLocaleConfig = true
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
            // from `titan.android.compose`, which :app does not apply.
            isIncludeAndroidResources = true

            all { test ->
                // The store listing's generated assets (#46) — the demo exports under
                // `tools/demo/` and the phone screenshots under `fastlane/` — belong to the
                // repository, not to this module, and a unit test runs with the module
                // directory as its working directory. Passed as an argument provider rather
                // than a system property, so the checkout's absolute path does not become part
                // of the task's cache key and a second worktree can still reuse its results.
                test.jvmArgumentProviders += RepositoryRoot(rootProject.projectDir)
                // `DemoDatasetTest` checks the committed exports, so they are inputs: without
                // this, an export changed on its own — a hand edit, a merge — leaves the task
                // up to date and the check never runs.
                test.inputs.files(rootProject.fileTree("tools/demo") { include("*.json") })
                    .withPropertyName("demoExports")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                // `make demo-data` and `make store-screenshots` pass this to write those assets;
                // without it `DemoDatasetTest` checks the committed exports instead, and
                // `StoreScreenshotTest` does not run at all.
                test.systemProperty(
                    "titan.storeAssets.record",
                    providers.gradleProperty("titan.storeAssets.record").getOrElse("false"),
                )
                // The same renderer `titan.android.compose` gives the library modules, for the
                // one screenshot test here: the default software renderer drops shadows and
                // gradients, and a store screenshot is the last place for a subtly wrong card.
                test.systemProperties["robolectric.pixelCopyRenderMode"] = "hardware"
            }
        }
    }

    // `htmlReport`/`xmlReport` are deliberately absent, as in `AndroidLibraryConventionPlugin`:
    // AGP deprecated both and writes every report format unconditionally now.
    lint {
        abortOnError = true
        lintConfig = rootProject.file("lint.xml")
        error += "HardcodedText"
    }
}

kotlin {
    jvmToolchain(17)
}

// The open source licences screen's data (#208), generated into `R.raw.aboutlibraries` as
// part of this module's own Android build — the `.android` plugin variant registers that
// automatically, so there is nothing to invoke by hand.
aboutLibraries {
    // No network calls at build time: keeps the build reproducible offline and needs no token.
    // The plugin ships no licence *text* of its own — a POM names a licence but does not carry
    // it, and the only place the plugin can get the text is the SPDX list on GitHub, which
    // `offlineMode` rules out. Without text the screen's rows do nothing when tapped (the
    // container only opens its dialog for a licence with content), so the text is checked in
    // under `config/aboutlibraries/licenses/` instead, one file per SPDX id, and merged in by
    // `hash` below.
    offlineMode = true
    collect {
        configPath.set(rootProject.layout.projectDirectory.dir("config/aboutlibraries"))
    }
    license {
        // Every licence the graph pulls in must have a text file in the config folder above,
        // or its rows are dead again. A licence that is not listed here fails the build rather
        // than shipping silently — add its text, then its id. This is also the GPL-3.0
        // compatibility gate: a dependency under a licence GPL cannot carry has to be argued
        // here before it can be added at all.
        strictMode.set(com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL)
        allowedLicenses.addAll("Apache-2.0", "BSD-3-Clause")
    }
}

// The same stability configuration every Compose module gets from `titan.android.compose`,
// which `:app` does not apply. See `config/compose/stability.conf`; a rule that held in the
// features and not in the composition root would be the worst of both.
composeCompiler {
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("config/compose/stability.conf"),
    )
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
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:home"))
    implementation(project(":feature:session"))
    implementation(project(":feature:history"))
    implementation(project(":feature:progression"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
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

    // `:app` is where WorkManager is configured, because it is the module that owns the
    // `Application`: the auto-backup worker (#181) is constructed by `HiltWorkerFactory`,
    // which only reaches the graph from the composition root. The worker itself is in
    // `:core:data`.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)

    // Installs `src/main/baselineProfiles/baseline-prof.txt` on first run. Android 9 to 11
    // have no ART profile installer of their own, and on 12+ this is what asks the platform
    // to compile the profile immediately rather than whenever it next feels like it. Without
    // it the committed profile is bytes in the APK that never reach ART — see
    // `benchmark/README.md` for the two figures that say what it buys.
    implementation(libs.androidx.profileinstaller)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // `ui-test-junit4` on the JVM classpath is only half a harness: the Android runtime it
    // drives comes from Robolectric.
    testImplementation(libs.robolectric)
    // `captureRoboImage()`, for the store listing's phone screenshots (#46). Only the capture
    // library: `:app` keeps no reference images, so the Roborazzi plugin and its
    // record/verify tasks stay with the library modules.
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)

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

/**
 * The repository root, handed to `:app`'s unit tests as `titan.rootDir` for the store listing's
 * generated assets (see `StoreAssets`). Internal to the task's inputs: the path says where the
 * checkout is, not what the tests do, and the files the tests read there are declared on their
 * own.
 */
class RepositoryRoot(
    @get:Internal val directory: File,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Dtitan.rootDir=${directory.absolutePath}")
}
