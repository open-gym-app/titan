import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    `kotlin-dsl`
}

group = "com.opengym.buildlogic"

// Matches the toolchain every module compiles against; the convention plugins are
// loaded into the same daemon that builds them.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
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
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "opengym.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "opengym.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "opengym.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("androidFeature") {
            id = "opengym.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
    }
}
