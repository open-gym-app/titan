import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.titan.buildlogic.AndroidSdk
import com.titan.buildlogic.WriteRobolectricConfig
import com.titan.buildlogic.library
import io.github.takahirom.roborazzi.RoborazziExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.project
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/**
 * `titan.android.compose` — Compose for a library module: the compiler plugin, the
 * `compose` build feature, and the BOM-managed UI dependencies.
 *
 * Every Compose artifact is resolved through the BOM, so no module names a Compose
 * version and none of them can drift apart.
 *
 * Applies `titan.android.library` itself for the same reason [AndroidHiltConventionPlugin]
 * does: it configures the `LibraryExtension`, which does not exist until the Android plugin
 * is applied, and the consumer should not have to know the right order.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("titan.android.library")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            pluginManager.apply("io.github.takahirom.roborazzi")

            extensions.configure<LibraryExtension> {
                buildFeatures {
                    compose = true
                }

                testOptions {
                    unitTests {
                        // `createComposeRule()` inflates a real Android view hierarchy, so a
                        // Compose test on the JVM needs the merged resources behind it.
                        // Without this the rule fails at construction with a null
                        // `Build.FINGERPRINT`.
                        isIncludeAndroidResources = true

                        all { test ->
                            // Robolectric's default (software) renderer drops shadows, gradients
                            // and hardware-layer effects, so a screenshot of a Material 3 surface
                            // comes out subtly wrong and, worse, differs between Robolectric
                            // patch versions. Roborazzi warns at configuration time without it.
                            test.systemProperties["robolectric.pixelCopyRenderMode"] = "hardware"
                        }
                    }
                }
            }

            // Robolectric ships no runtime for `AndroidSdk.COMPILE` and refuses to start when
            // the manifest targets a level above the newest one it has, so every JVM unit test
            // in a Compose module needs the pin [WriteRobolectricConfig] generates.
            extensions.configure<LibraryAndroidComponentsExtension> {
                onVariants { variant ->
                    variant.hostTests.forEach { (name, hostTest) ->
                        val task = tasks.register<WriteRobolectricConfig>(
                            "write${variant.name.titleCase()}${name.titleCase()}RobolectricConfig",
                        ) { sdk.set(AndroidSdk.ROBOLECTRIC) }

                        hostTest.sources.resources?.addGeneratedSourceDirectory(
                            task,
                            WriteRobolectricConfig::outputDirectory,
                        )
                    }
                }
            }

            configureRoborazzi()
            configureComposeStability()
            configureComposeReports()

            dependencies {
                val bom = platform(library("androidx-compose-bom"))
                add("implementation", bom)
                add("implementation", library("androidx-compose-ui"))
                add("implementation", library("androidx-compose-ui-graphics"))
                add("implementation", library("androidx-compose-ui-tooling-preview"))
                add("implementation", library("androidx-material3"))
                add("implementation", library("androidx-material-icons-extended"))

                // `collectAsStateWithLifecycle()` and `viewModel()`. Shared components in
                // `:core:ui` collect domain flows too, so this is not feature-only.
                add("implementation", library("androidx-lifecycle-runtime-compose"))
                add("implementation", library("androidx-lifecycle-viewmodel-compose"))

                // Exercise imagery from the seeded WebP assets.
                add("implementation", library("coil-compose"))

                add("debugImplementation", library("androidx-compose-ui-tooling"))

                // The BOM again for the test classpath: `platform()` applies per
                // configuration, so the compile-time constraint above does not reach it.
                add("testImplementation", platform(library("androidx-compose-bom")))
                add("testImplementation", library("androidx-compose-ui-test-junit4"))
                add("debugImplementation", library("androidx-compose-ui-test-manifest"))

                // `ui-test-junit4` on the JVM classpath is only half a harness: the Android
                // runtime it drives comes from Robolectric.
                add("testImplementation", library("robolectric"))

                // Screenshot testing. The first three are what a hand-written capture calls —
                // `captureRoboImage()` and `RoborazziRule`. The last two are the compile
                // classpath for the `@Preview` tests the Roborazzi plugin generates: leave
                // either out and the build fails compiling a generated file under `build/`,
                // which names neither this plugin nor the module's own sources.
                add("testImplementation", library("roborazzi"))
                add("testImplementation", library("roborazzi-compose"))
                add("testImplementation", library("roborazzi-junit-rule"))
                add("testImplementation", library("roborazzi-compose-preview-scanner-support"))
                add("testImplementation", library("composable-preview-scanner"))

                // The tester the generated preview tests instantiate — see `configureRoborazzi`
                // — and the `assertAccessible()` a hand-written screen test calls. Shared code,
                // so it is a module and not a copy per test source set; `:core:testing` is on
                // no main classpath anywhere.
                add("testImplementation", project(":core:testing"))
            }
        }
}

/**
 * Points the Compose compiler at `config/compose/stability.conf`.
 *
 * That file is where this project tells the compiler which types it may treat as values —
 * Kotlin's read-only collection interfaces, and every model in `:core:domain`, which cannot
 * carry `@Immutable` because it is a plain Kotlin library with no Compose dependency. See the
 * file itself for what each entry promises and why it is safe.
 *
 * `:app` sets the same thing in its own build script: it applies no convention plugin, and a
 * stability rule that held in the features but not in the composition root would be the worst
 * of both.
 */
private fun Project.configureComposeStability() =
    extensions.configure<ComposeCompilerGradlePluginExtension> {
        stabilityConfigurationFiles.add(
            rootProject.layout.projectDirectory.file("config/compose/stability.conf"),
        )
    }

/**
 * The Compose compiler's own stability report, behind `-Ptitan.composeReports`.
 *
 * ### What it is for
 *
 * "Does this list item recompose more than once per data change" is a question about
 * *skippability*, and the compiler already knows the answer for every composable in the
 * module: which ones it could make skippable, which parameters it considers unstable, and why.
 * Layout Inspector answers the same question one screen at a time, by hand, on a device, and
 * leaves nothing behind that a later change can be checked against.
 *
 * So this is the sweep #42 asks for, in a form anyone can re-run:
 *
 * ```
 * ./gradlew :feature:history:compileBenchmarkKotlin -Ptitan.composeReports
 * less feature/history/build/compose-reports/history-composables.txt
 * ```
 *
 * A `restartable skippable fun SessionRow` is a row that will not recompose when its
 * neighbours' data changes. An `unstable parameter` line names the type to annotate or the
 * lambda to hoist.
 *
 * ### Why it is behind a property
 *
 * The reports are compiler output, not build output: turning them on unconditionally would
 * add a directory of generated text to every module on every build, and — because the
 * destination is a task input — would invalidate the Kotlin compile task for everyone who does
 * not want them.
 */
private fun Project.configureComposeReports() {
    if (!providers.gradleProperty(COMPOSE_REPORTS_PROPERTY).isPresent) return

    val destination = layout.buildDirectory.dir("compose-reports")
    extensions.configure<ComposeCompilerGradlePluginExtension> {
        reportsDestination.set(destination)
        metricsDestination.set(destination)
    }
}

private const val COMPOSE_REPORTS_PROPERTY = "titan.composeReports"

/**
 * Screenshot tests, as reference images committed under `src/test/screenshots` and compared
 * by `verifyRoborazziDebug`. See CONTRIBUTING.md for the authoring and re-recording workflow.
 *
 * Roborazzi rather than AGP's Compose Preview Screenshot Testing plugin: the latter is still
 * `0.0.1-alpha16`, needs `android.experimental.enableScreenshotTest`, and puts its previews in
 * a separate `screenshotTest/` source set with its own task graph. Roborazzi captures from
 * inside the Robolectric runtime this module already has, so a screenshot test is an ordinary
 * JVM unit test — same source set, same SDK pin, same CI job, and it can capture a state
 * reached by driving the UI rather than only a `@Preview`.
 *
 * `compare` and `generateComposePreviewRobolectricTests` are both still experimental, hence
 * the opt-in on the whole function.
 */
@OptIn(ExperimentalRoborazziApi::class)
private fun Project.configureRoborazzi() =
    extensions.configure<RoborazziExtension> {
        // References are reviewed input, not build output: a diff in `src/` is what makes an
        // unintended visual change visible in a pull request.
        outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))

        // Must be set explicitly. Left alone it follows `outputDir`, and a failing run would
        // write its `_compare.png` diffs in among the committed references.
        compare {
            outputDir.set(layout.buildDirectory.dir("outputs/roborazzi"))
        }

        // Generates one Robolectric test per `@Preview` in the module, so a screen gets its
        // screenshot coverage from the previews it already carries for the IDE. Scanning is
        // scoped to the module's own namespace: the classpath of a `:feature:*` module also
        // holds `:core:ui`, and an unscoped scan would re-capture every shared component once
        // per feature.
        generateComposePreviewRobolectricTests {
            enable.set(true)
            packages.set(
                provider { listOfNotNull(extensions.getByType<LibraryExtension>().namespace) },
            )
            robolectricConfig.set(mapOf("sdk" to "[${AndroidSdk.ROBOLECTRIC}]"))

            // Every preview is also run through the Accessibility Test Framework — the checks
            // Accessibility Scanner runs on a device — before it is captured (#40). The
            // tester lives in `:core:testing`; what it checks and why is documented there and
            // in docs/ACCESSIBILITY.md.
            testerQualifiedClassName.set("com.titan.core.testing.AccessibleComposePreviewTester")
        }
    }

private fun String.titleCase(): String = replaceFirstChar(Char::uppercaseChar)
