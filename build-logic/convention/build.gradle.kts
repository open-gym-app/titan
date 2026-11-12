import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec

plugins {
    `kotlin-dsl`
}

group = "com.titan.buildlogic"

// Matches the toolchain every module compiles against, vendor included (see `titanJdk()` in
// AndroidSdk.kt); the convention plugins are loaded into the same daemon that builds them.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

// `compileOnly`: these are the Gradle plugins the conventions *apply*, not libraries they
// ship. Compiling against them is enough because they are already on the consuming build's
// script classpath — the root `build.gradle.kts` declares each one `apply false`, and that
// block is what puts them there. It is load-bearing: dropping an entry from it because no
// build script names the alias any more breaks every convention plugin that applies that id
// at "Plugin with id '...' not found". Declaring them `implementation` instead would put a
// second copy of AGP/KGP on the classpath, in a different classloader from the one the
// modules use.
dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.gradle.plugin)
    compileOnly(libs.ksp.gradle.plugin)
    compileOnly(libs.hilt.gradle.plugin)
    compileOnly(libs.roborazzi.gradle.plugin)

    // `ArchitectureRules` is a pure function over a module graph, so its cases can be stated
    // as an ordinary unit test rather than as a Gradle build that has to fail on purpose.
    // The root build's `check` runs this via `checkBuildLogic` — an included build's tests
    // are not otherwise reached by anything the main build or CI invokes.
    testImplementation(libs.junit)
}

// `jacoco` is a Gradle core plugin — no version catalog entry, no `compileOnly`, it is
// always on the Gradle API classpath `AndroidJacocoConventionPlugin` compiles against.

// The counterpart to the `subprojects` block in the root `build.gradle.kts`: deprecation is an
// error here too. This build is where a convention plugin configures AGP and KGP, which is
// where a deprecated DSL property is most likely to be reached for and least likely to be
// noticed — the warning lands during `:build-logic:convention:compileKotlin`, above every line
// of the main build's output. See that block for the rationale and the escape hatch.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xwarning-level=DEPRECATION:error")
    }
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "titan.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "titan.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "titan.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("androidFeature") {
            id = "titan.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("androidBenchmark") {
            id = "titan.android.benchmark"
            implementationClass = "AndroidBenchmarkConventionPlugin"
        }
        register("androidJacoco") {
            id = "titan.android.jacoco"
            implementationClass = "AndroidJacocoConventionPlugin"
        }
        register("architecture") {
            id = "titan.architecture"
            implementationClass = "ArchitectureConventionPlugin"
        }
    }
}
