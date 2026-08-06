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

// `compileOnly`: these are the Gradle plugins the conventions *apply*, not libraries
// they ship. Gradle puts them on the consuming build's classpath when the plugin is
// applied, so putting them on the runtime classpath here would load them twice.
dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.gradle.plugin)
    compileOnly(libs.ksp.gradle.plugin)
    compileOnly(libs.hilt.gradle.plugin)
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
