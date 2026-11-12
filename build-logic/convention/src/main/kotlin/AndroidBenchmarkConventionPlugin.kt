import com.android.build.api.dsl.TestExtension
import com.android.build.api.variant.TestAndroidComponentsExtension
import com.titan.buildlogic.AndroidSdk
import com.titan.buildlogic.library
import com.titan.buildlogic.titanJdk
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * `titan.android.benchmark` — the Macrobenchmark module, `:benchmark`.
 *
 * A separate convention rather than a reuse of `titan.android.library` because the module is
 * a different *kind* of thing: `com.android.test` puts its instrumentation in `src/main`
 * rather than in a test source set, and produces an APK that instruments another one rather
 * than a library to link against. Applying the library convention would configure a
 * `LibraryExtension` that does not exist here.
 *
 * The three things that make a Macrobenchmark run at all, and that are all easy to get wrong:
 *
 * - **`minSdk` is [AndroidSdk.BENCHMARK_MIN], not [AndroidSdk.MIN].** Macrobenchmark reads
 *   frame timings out of the platform's own tracing, which needs API 29; a run on anything
 *   older fails at the first metric with an error about the trace being empty.
 * - **The only variant is `benchmark`.** `targetProjectPath` makes AGP build one variant of
 *   this module per variant of `:app`, and `debug` measurements are meaningless — a debuggable
 *   app is slower by a factor that varies with the screen. `debug` is disabled rather than
 *   ignored so `connectedAndroidTest` cannot silently run it.
 * - **`SELF_INSTRUMENTING`.** The benchmark process must not be the app process, or measuring
 *   a cold start would mean killing the process doing the measuring.
 */
class AndroidBenchmarkConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.android.test")

            extensions.configure<TestExtension> {
                compileSdk = AndroidSdk.COMPILE

                defaultConfig {
                    minSdk = AndroidSdk.BENCHMARK_MIN
                    targetSdk = AndroidSdk.TARGET
                    // `androidx.benchmark` ships no runner of its own — `MacrobenchmarkRule`
                    // is a plain JUnit4 `@Rule`, so the app under test still gets launched by
                    // the standard AndroidX Test runner (pulled in transitively through
                    // `androidx-junit`).
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }

                // The app is a second process, driven through UiAutomator. Without this the
                // instrumentation is loaded *into* the app, and `startActivityAndWait` after a
                // process kill would be killing itself.
                experimentalProperties["android.experimental.self-instrumenting"] = true

                buildTypes {
                    // `:app` has a `benchmark` build type of its own; this is the variant of
                    // this module that is built against it.
                    create("benchmark") {
                        isDebuggable = false

                        // A custom build type does not inherit the implicit debug signing
                        // config the way `debug` itself does — left unset, this test APK
                        // builds unsigned and `adb install` rejects it with
                        // `INSTALL_PARSE_FAILED_NO_CERTIFICATES`.
                        signingConfig = signingConfigs.getByName("debug")

                        // `:core:*` and `:feature:*` are plain library modules with only
                        // `debug`/`release` build types. `benchmarkTestedApks` resolves the
                        // whole graph — `:benchmark` -> `:app` -> those libraries — under
                        // `BuildTypeAttr = benchmark`, and it is *this* build type's fallback
                        // list Gradle consults, not `:app`'s: without it, resolution has no
                        // variant to fall back to for a library that never heard of
                        // `benchmark`.
                        matchingFallbacks += listOf("release")
                    }
                }

                lint {
                    abortOnError = true
                    lintConfig = rootProject.file("lint.xml")
                }
            }

            // AGP creates `debug` for every module and makes it the default variant. A
            // benchmark against a debuggable build measures the interpreter, so that variant
            // is not merely unused here — it is a wrong answer waiting to be read as a right
            // one. It cannot be removed (AGP refuses), so it is disabled instead: nothing
            // builds it, and `connectedAndroidTest` cannot reach it.
            extensions.configure<TestAndroidComponentsExtension> {
                beforeVariants(selector().all()) { variant ->
                    variant.enable = variant.buildType == "benchmark"
                }
            }

            extensions.configure<KotlinAndroidProjectExtension> {
                jvmToolchain { titanJdk() }
            }

            dependencies {
                add("implementation", library("junit"))
                add("implementation", library("androidx-junit"))
                add("implementation", library("androidx-test-uiautomator"))
                add("implementation", library("androidx-benchmark-macro-junit4"))
            }
        }
}
