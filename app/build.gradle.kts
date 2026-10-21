import com.github.triplet.gradle.androidpublisher.ReleaseStatus
import com.titan.buildlogic.ReleaseVersion
import com.titan.buildlogic.SyncPlayReleaseNotes
import java.util.Properties
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // `:app` declares navigation types of its own — the tab scaffold's graph route and, until
    // #97 gives settings a module, the settings destination — so it needs the serialization
    // compiler plugin the way a feature does. The `titan.android.feature` convention
    // applies it for the modules that use it; `:app` applies no convention plugin.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    // Generates the JSON the open source licences screen reads at runtime (#208), from this
    // module's own dependency graph — which, as the composition root, is the whole app's.
    alias(libs.plugins.aboutlibraries)
    // Uploads the release bundle to Play's internal track from the release workflow (#209).
    // Build-time only: it adds nothing to the APK.
    alias(libs.plugins.play.publisher)
    id("titan.android.jacoco")
}

private val keystoreProperties = Properties().also { props ->
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(props::load)
}

private fun prop(envKey: String, fileKey: String): String? =
    (System.getenv(envKey) ?: keystoreProperties.getProperty(fileKey))?.takeIf { it.isNotEmpty() }

// The version comes from the tag and from nowhere else: `.github/workflows/release.yml` passes
// the tag without its `v` as `-PreleaseVersion=`, and `ReleaseVersion` (build-logic) turns it
// into the name and the code below. Without the property this is `0.0.0-dev` / `1`, which is
// every local build and CI's own `assembleRelease`. The rule, and why a candidate shares its
// release's code, are written up in release/RELEASE.md.
private val releaseVersion = ReleaseVersion.parse(providers.gradleProperty("releaseVersion").orNull)

// Play's names for the four locales `androidResources.localeFilters` (below) ships, and the
// directories `fastlane/metadata/android/` is keyed by.
private val shippedLocales = listOf("en-US", "fr-FR", "es-ES", "pt-BR")

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

    packaging {
        dex {
            // Store the dex files uncompressed. With a `minSdk` below 28 AGP's default is to
            // deflate them; stored, Android 9 and later map them straight out of the APK
            // instead of extracting a copy at install, and a verifier comparing the release
            // to its own build (release/RELEASE.md) sees R8's bytes with one less transform
            // between. The cost lands on Android 8, which extracts the dex regardless and so
            // holds it twice.
            useLegacyPackaging = false
        }
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
            // Nothing in the release runtime classpath ships an `.so` today, so this produces
            // nothing. It is set now so that the first dependency that does gets its symbols
            // packed into the AAB (and `native-debug-symbols.zip` beside it, which the release
            // workflow archives) rather than shipping stripped, with no crash on Play ever
            // symbolised.
            ndk {
                debugSymbolLevel = "FULL"
            }
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

// ### Publishing to Play (#209)
//
// The release workflow's `publishReleaseBundle` and nothing else: the internal track, as a
// draft, so a build appears in the Console and rolls out to nobody until a person promotes it
// (#47, #49). No track above internal is ever named here or in the workflow. Credentials come
// from the `ANDROID_PUBLISHER_CREDENTIALS` environment variable GPP reads on its own, so no
// key file is ever on disk. `resolutionStrategy` keeps its `FAIL` default on purpose: a
// `versionCode` Play has already seen means a tag was reused, and the fix is a new tag, not a
// silently bumped number the tag no longer describes.
play {
    track.set("internal")
    releaseStatus.set(ReleaseStatus.DRAFT)
    defaultToAppBundles.set(true)
    // Off for every variant but `release` (below). `staging` and `benchmark` are release-like
    // and GPP would otherwise register an upload task for each; neither is a build that
    // should ever reach a store, under any track.
    enabled.set(false)
}

android {
    playConfigs {
        register("release") {
            enabled.set(true)
        }
    }
}

// ### Release notes: one file, three consumers
//
// Play's "What's new", F-Droid's changelog and the GitHub Release body are all
// `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` (release/RELEASE.md).
// GPP reads release notes from a fixed layout of its own — `src/<sourceSet>/play/release-notes/
// <locale>/<track>.txt`, an input its resource task discovers rather than a property — so this
// copies the changelog into that layout before it looks, and fails the build when a locale's
// file is missing or over Play's limit. `default.txt` rather than `internal.txt`: the notes
// follow the build when it is promoted, and the workflow only ever publishes to one track
// anyway. The destination is generated and gitignored; the source is the only file anyone
// edits.
private val syncPlayReleaseNotes = tasks.register<SyncPlayReleaseNotes>("syncPlayReleaseNotes") {
    group = "publishing"
    description = "Copies this version's fastlane changelogs into the layout Gradle Play Publisher reads."
    metadata.set(rootProject.layout.projectDirectory.dir("fastlane/metadata/android"))
    version.set(releaseVersion.name)
    versionCode.set(releaseVersion.code)
    locales.set(shippedLocales)
    destination.set(layout.projectDirectory.dir("src/release/play/release-notes"))
}

// GPP registers `generateReleasePlayResources` from a variant callback, after this script has
// run, so it is wired by name as it appears rather than looked up.
tasks.configureEach {
    if (name == "generateReleasePlayResources") dependsOn(syncPlayReleaseNotes)
}

// What the release workflow reads the version back out of, in `$GITHUB_OUTPUT` form, so the
// name on the GitHub Release, the changelog it attaches and the decision to skip Play for a
// candidate all come from the same parse as the APK's own `versionCode`.
tasks.register("printReleaseVersion") {
    group = "publishing"
    description = "Prints the version the -PreleaseVersion property resolves to."
    val version = releaseVersion
    doLast {
        println("version=${version.name}")
        println("version_code=${version.code}")
        println("prerelease=${version.isReleaseCandidate}")
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
