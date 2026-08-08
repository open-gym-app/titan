import com.opengym.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * `opengym.android.jacoco` — JaCoCo coverage for the `debug` unit tests of an Android
 * module (`:app`, `:core:data`, `:core:ui`). `:core:domain` is a plain Kotlin JVM module
 * and wires the `jacoco` plugin directly (see its build script); this plugin exists
 * because that convention has nothing to attach to on an Android module — the exec data
 * and compiled classes live under variant-specific paths, and only `debug` is measured:
 * it is the only variant CI's `test` step and Sonar both read, so a `release`/`staging`
 * report would just be a second, unused number computed from the same test sources.
 *
 * Everything below is deferred to `afterEvaluate`. Both `testDebugUnitTest` and
 * `compileDebugKotlin` get registered at points during configuration that vary with what
 * else is applied — the Variant API's own `onVariants`/`configureTestTask` callbacks fire
 * early enough for a plain library module but too early to see either task on `:app` or
 * `:core:data`, where Hilt and KSP add their own variant wiring. `afterEvaluate` is the
 * one point guaranteed to run after every plugin, AGP and KGP included, has finished.
 */
class AndroidJacocoConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("jacoco")

        extensions.configure<JacocoPluginExtension> {
            toolVersion = libs.findVersion("jacoco").get().requiredVersion
        }

        tasks.withType<Test>().configureEach {
            configure<JacocoTaskExtension> {
                // Robolectric loads classes from the boot classpath with no attributable
                // source location; without this, JaCoCo throws instead of skipping them.
                // https://github.com/robolectric/robolectric/issues/2230
                isIncludeNoLocationClasses = true
                excludes = listOf("jdk.internal.*")
            }
        }

        afterEvaluate {
            val test = tasks.named<Test>("testDebugUnitTest")

            // The classes JaCoCo instruments come straight from the Kotlin compile task's
            // own output directory rather than a guessed `build/...` path, so this keeps
            // working if AGP moves where it stages this task's output.
            val compileKotlin = tasks.named<KotlinCompile>("compileDebugKotlin")

            val reportTask = tasks.register<JacocoReport>("jacocoDebugUnitTestReport") {
                group = "verification"
                description = "Generates a JaCoCo coverage report for the debug unit tests."
                dependsOn(test)
                reports {
                    xml.required.set(true)
                    html.required.set(true)
                    csv.required.set(false)
                }
                sourceDirectories.setFrom(files("src/main/kotlin", "src/main/java"))
                classDirectories.setFrom(
                    compileKotlin.flatMap { it.destinationDirectory }.map { dir ->
                        fileTree(dir) { exclude(jacocoCoverageExclusions) }
                    },
                )
                // The exec data path the `jacoco` plugin already wired onto this exact
                // test task, not a re-derived guess at where it writes it.
                executionData.setFrom(
                    test.map { it.extensions.getByType<JacocoTaskExtension>().destinationFile },
                )
            }

            test.configure { finalizedBy(reportTask) }
        }
    }
}

// Generated code that a coverage floor would end up measuring instead of anything
// anyone wrote: Room/Hilt-generated classes, Compose's per-file singleton holder, and
// the framework's own R/BuildConfig/Manifest classes.
private val jacocoCoverageExclusions = listOf(
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",
    "android/**/*.*",
    "**/*_Factory*.*",
    "**/*_MembersInjector.class",
    "**/Hilt_*.*",
    "**/*_HiltModules*.*",
    "**/hilt_aggregated_deps/**",
    "**/dagger/hilt/**",
    "**/*Dagger*.*",
    "**/*_Impl.*",
    "**/*_Impl$*.*",
    "**/ComposableSingletons$*.*",
)
