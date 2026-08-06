import com.android.build.api.dsl.LibraryExtension
import com.opengym.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

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
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("opengym.android.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.configure<LibraryExtension> {
            buildFeatures {
                compose = true
            }
        }

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
        }
    }
}
