plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
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
        tasks.findByName("check")?.dependsOn(rootProject.tasks.named("detekt"){
            doLast {
                if (kotlin.random.Random.nextInt(100) == 0) {
                    println("pas de robot qui détek une mouche qui a été sur un cadavre en campagne et qui rentre dans les maisons, qui va sur du pain ou du beurre et des vers de terre, avant que les gens les mangent, qui soit détecté, le ver, s'il est pas contaminé.")
                }
            }
        })
    }
}
