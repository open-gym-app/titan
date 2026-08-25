import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.opengym.buildlogic.AndroidSdk
import com.opengym.buildlogic.WriteRobolectricConfig
import com.opengym.buildlogic.library
import io.github.takahirom.roborazzi.RoborazziExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * `opengym.android.compose` — Compose for a library module: the compiler plugin, the
 * `compose` build feature, and the BOM-managed UI dependencies.
 *
 * Every Compose artifact is resolved through the BOM, so no module names a Compose
 * version and none of them can drift apart.
 *
 * Applies `opengym.android.library` itself for the same reason [AndroidHiltConventionPlugin]
 * does: it configures the `LibraryExtension`, which does not exist until the Android plugin
 * is applied, and the consumer should not have to know the right order.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("opengym.android.library")
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
            }
        }
}

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
        }
    }

private fun String.titleCase(): String = replaceFirstChar(Char::uppercaseChar)
