import com.titan.buildlogic.library
import com.titan.buildlogic.titanJdk
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

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

            // Hilt aggregates its components through javac, which needs a real compiler
            // rather than whichever JVM happens to be running Gradle.
            val toolchains = extensions.getByType<JavaToolchainService>()
            tasks.withType<JavaCompile>().configureEach {
                javaCompiler.set(
                    toolchains.compilerFor { titanJdk() },
                )
            }

            dependencies {
                add("implementation", library("hilt-android"))
                add("ksp", library("hilt-compiler"))
            }
        }
}
