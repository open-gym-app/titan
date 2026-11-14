import com.titan.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * `titan.android.hilt` — Hilt and the KSP processor that generates its components.
 *
 * Applies `titan.android.library` itself rather than trusting the consumer to list it
 * first: this plugin reads extensions that only exist once the Android plugin is in place,
 * and a `plugins {}` block in the other order would otherwise fail with an error naming
 * neither the cause nor the fix. Applying is idempotent, so a module listing both is fine.
 */
class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("titan.android.library")
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")

            // Hilt aggregates its components through javac, which `titan.jvm.toolchain` (applied
            // by `titan.android.library` above) points at the build's JDK.

            dependencies {
                add("implementation", library("hilt-android"))
                add("ksp", library("hilt-compiler"))
            }
        }
}
