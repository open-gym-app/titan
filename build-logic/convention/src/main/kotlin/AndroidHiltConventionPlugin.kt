import com.opengym.buildlogic.AndroidSdk
import com.opengym.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

/**
 * `opengym.android.hilt` — Hilt and the KSP processor that generates its components.
 */
class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")

        // Hilt aggregates its components through javac, which needs a real compiler
        // rather than whichever JVM happens to be running Gradle.
        val toolchains = extensions.getByType<JavaToolchainService>()
        tasks.withType<JavaCompile>().configureEach {
            javaCompiler.set(
                toolchains.compilerFor {
                    languageVersion.set(JavaLanguageVersion.of(AndroidSdk.JVM_TOOLCHAIN))
                },
            )
        }

        dependencies {
            add("implementation", library("hilt-android"))
            add("ksp", library("hilt-compiler"))
        }
    }
}
