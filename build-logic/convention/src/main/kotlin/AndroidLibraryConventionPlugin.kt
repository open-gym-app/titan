import com.android.build.api.dsl.LibraryExtension
import com.titan.buildlogic.AndroidSdk
import com.titan.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * `titan.android.library` — the baseline every `:core:*` and `:feature:*` Android
 * module shares: SDK levels, the JVM toolchain, the project-wide lint configuration and
 * the JVM unit-test dependencies.
 *
 * Deliberately not applied to `:app` (an application module with signing config and
 * build variants) or `:core:domain` (a plain Kotlin JVM library).
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.android.library")

            extensions.configure<LibraryExtension> {
                compileSdk = AndroidSdk.COMPILE

                defaultConfig {
                    minSdk = AndroidSdk.MIN
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }

                // No `htmlReport`/`xmlReport` here: AGP deprecated both and now writes every
                // report format unconditionally, so setting them said nothing and warned. The
                // XML one is still consumed — `sonar.androidLint.reportPaths` in the root
                // build script points at `build/reports/lint-results-debug.xml`, which is
                // exactly where AGP puts it on its own.
                lint {
                    abortOnError = true
                    lintConfig = rootProject.file("lint.xml")
                    error += "HardcodedText"
                }
            }

            extensions.configure<KotlinAndroidProjectExtension> {
                jvmToolchain(AndroidSdk.JVM_TOOLCHAIN)
            }

            dependencies {
                add("testImplementation", library("junit"))
                add("testImplementation", library("mockk"))
                add("testImplementation", library("kotlinx-coroutines-test"))
            }
        }
}
