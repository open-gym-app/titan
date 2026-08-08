// The `apply false` entries are not dead weight even where no build script names the alias:
// they are what puts these plugins on the build script classpath for the `opengym.android.*`
// convention plugins, which declare them `compileOnly` and apply them by id. Removing one as
// "unused" fails the build at "Plugin with id '...' not found" — see
// build-logic/convention/build.gradle.kts.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/detekt-baseline.xml")
    source.setFrom(
        fileTree(rootDir) {
            include("**/src/**/*.kt")
            exclude("**/build/**")
        },
    )
}

dependencies {
    detektPlugins(libs.detekt.formatting)
}

subprojects {
    afterEvaluate {
        tasks.withType<Test>().configureEach {
            testLogging {
                events("failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showCauses = true
                showStackTraces = true
            }
        }
        tasks.findByName("check")?.dependsOn(rootProject.tasks.named("detekt"))
    }
}
