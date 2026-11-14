import com.titan.buildlogic.catalogVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JavaToolchainSpec
import org.gradle.jvm.toolchain.JvmVendorSpec
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

/**
 * The JDK every module compiles against: `jdk` from `jdkVendor`, both in the version catalog.
 *
 * The vendor is pinned because a version alone is satisfied by whichever JDK 17 Gradle finds
 * first, on the machine or through the toolchain resolver, so two machines could compile one
 * commit with two different compilers. Adoptium's Temurin is what CI's `setup-java` installs
 * and what the resolver downloads for a developer, so pinning it changes neither. CI and the
 * release build also turn auto-download off (`.github/ci-gradle.properties`): there the JDK
 * comes from `setup-java` or the build fails, never from a download in the middle of it.
 *
 * The catalog rather than a constant here because build-logic's own `convention/build.gradle.kts`
 * needs the same JDK and cannot call into the code it is compiling; it reads the catalog too.
 */
private fun JavaToolchainSpec.titanJdk(project: Project) {
    languageVersion.set(JavaLanguageVersion.of(project.catalogVersion("jdk")))
    vendor.set(jvmVendor(project.catalogVersion("jdkVendor")))
}

/** The `JvmVendorSpec` constant called [name], or a configuration failure that names it. */
private fun jvmVendor(name: String): JvmVendorSpec =
    runCatching { JvmVendorSpec::class.java.getField(name).get(null) as JvmVendorSpec }.getOrElse {
        throw IllegalStateException(
            "jdkVendor = \"$name\" in gradle/libs.versions.toml is not a JvmVendorSpec constant",
            it,
        )
    }
