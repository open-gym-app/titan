import com.android.build.api.dsl.LibraryExtension
import com.opengym.buildlogic.AndroidSdk
import com.opengym.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * `opengym.android.library` — the baseline every `:core:*` and `:feature:*` Android
 * module shares: SDK levels, the JVM toolchain, the project-wide lint configuration and
 * the JVM unit-test dependencies.
 *
 * Deliberately not applied to `:app` (an application module with signing config and
 * build variants) or `:core:domain` (a plain Kotlin JVM library).
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            compileSdk = AndroidSdk.COMPILE

            defaultConfig {
                minSdk = AndroidSdk.MIN
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }

            lint {
                abortOnError = true
                lintConfig = rootProject.file("lint.xml")
                htmlReport = true
                xmlReport = true
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
