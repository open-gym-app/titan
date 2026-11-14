import com.titan.buildlogic.titanJdk
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension

/**
 * `titan.jvm.toolchain` — the JDK a module's Kotlin and Java compile with (see `titanJdk()`).
 *
 * Every Android convention plugin applies it, and so do the two modules that apply none of
 * them, `:app` and `:core:domain`, so the JDK is chosen in one place for the whole build. It
 * has to come after the module's Kotlin plugin (or, for an Android module, its Android plugin,
 * which brings Kotlin with it): it configures the extension that plugin creates.
 *
 * `JavaCompile` is set as well as Kotlin's toolchain because AGP does not pass the Kotlin
 * toolchain on to javac, and Hilt aggregates its components through javac: without this it
 * would run on whichever JVM happens to be running Gradle.
 */
class JvmToolchainConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            val kotlin =
                checkNotNull(extensions.findByType<KotlinProjectExtension>()) {
                    "$path applies titan.jvm.toolchain before its Kotlin or Android plugin; list it after them"
                }
            kotlin.jvmToolchain { titanJdk(target) }

            val toolchains = extensions.getByType<JavaToolchainService>()
            tasks.withType<JavaCompile>().configureEach {
                javaCompiler.set(toolchains.compilerFor { titanJdk(target) })
            }
        }
}
