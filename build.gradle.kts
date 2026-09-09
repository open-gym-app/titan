// The `apply false` entries are not dead weight even where no build script names the alias:
// they are what puts these plugins on the build script classpath for the `opengym.android.*`
// convention plugins, which declare them `compileOnly` and apply them by id. Removing one as
// "unused" fails the build at "Plugin with id '...' not found" — see
// build-logic/convention/build.gradle.kts.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.sonarqube)

    // Registers `checkArchitecture`, wired into `check` below. Applied rather than
    // `apply false`: unlike the others this one configures the root project itself, because
    // the module dependency graph is a property of the build and not of any module in it.
    id("opengym.architecture")
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

// `sonar.host.url` and `sonar.token` are deliberately absent here: whoever runs
// `./gradlew sonar` supplies them, via the `SONAR_HOST_URL`/`SONAR_TOKEN` env vars the
// scanner reads on its own (or `-Dsonar.host.url=...`/`-Dsonar.token=...`), so no server
// address or credential for it lives in version control. Coverage and JUnit report paths
// are not set here either — those are module-specific and live next to what produces them: see
// `core/domain/build.gradle.kts` and the `subprojects` block below for the Android
// modules, which all share the same convention plugin and so the same paths.
sonar {
    properties {
        property("sonar.projectKey", "opengym")
        property("sonar.projectName", "OpenGym")
        property("sonar.projectDescription", "Open Gym - A fitness app built with Kotlin and Jetpack Compose")
        property("sonar.sourceEncoding", "UTF-8")

        // Generated code and build output. Sonar only ever scans each module's declared
        // Kotlin source sets, so this mostly guards against a source set that happens to
        // point somewhere under `build/` or a `generated/` directory of its own.
        property("sonar.exclusions", "**/build/**,**/generated/**")

        // One `detekt` task at the root covers every module's sources in a single XML
        // report (see the `detekt {}` block above), so this is set once, not per module.
        property("sonar.kotlin.detekt.reportPaths", "$rootDir/build/reports/detekt/detekt.xml")

        property("sonar.links.homepage", "https://github.com/open-gym-app/open-gym")
        property("sonar.links.ci", "https://github.com/open-gym-app/open-gym/actions")
        property("sonar.links.issue", "https://github.com/open-gym-app/open-gym/issues")
        property("sonar.links.scm", "https://github.com/open-gym-app/open-gym.git")
    }
}

// The convention plugins' own unit tests. An included build's `test` task is not reached by
// anything the main build or CI invokes, so without this wiring `ArchitectureRulesTest` would
// only ever run when somebody thought to ask for it by name — and the rules it covers are
// exactly the ones nobody would notice had stopped working.
val checkBuildLogic = tasks.register("checkBuildLogic") {
    group = "verification"
    description = "Runs the build-logic convention plugins' unit tests."
    dependsOn(gradle.includedBuild("build-logic").task(":convention:test"))
}

// Deprecation is an error, not a warning, in every module's Kotlin.
//
// A warning that nothing fails on is a warning nobody reads: this repository had accumulated
// 47 of them across the test source sets alone, which is exactly the volume at which the one
// that matters becomes invisible. `-Xwarning-level` (Kotlin 2.2+) promotes a single diagnostic
// rather than turning on `allWarningsAsErrors`, which would also fail the build on unused
// parameters and non-exhaustive `when`s — real signals, but not ones worth blocking a merge on.
//
// The escape hatch is per-call and stays per-call: `@Suppress("DEPRECATION")` at the narrowest
// scope that covers the call, with a comment saying why there is no replacement to move to.
// `RestTimerAlert.defaultVibrator` is the shape to copy — a deprecated API kept because a
// `minSdk`-era device has no other one.
//
// Deliberately not `DEPRECATION_ERROR:error`: that diagnostic is already an error.
//
// `:build-logic` is a separate build and is not reached from here; it carries the same block in
// `build-logic/convention/build.gradle.kts`.
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
        compilerOptions {
            freeCompilerArgs.add("-Xwarning-level=DEPRECATION:error")
        }
    }

    afterEvaluate {
        tasks.withType<Test>().configureEach {
            // The zone every JVM test runs in, and the counterpart to the locale
            // `WriteRobolectricConfig` pins. A session is stamped in epoch millis UTC and
            // rendered in the device's zone, so the same instant draws `2:00 PM` on a
            // machine in Paris and `12:00 PM` on a CI runner in UTC — and every screenshot
            // that shows a clock time fails on whichever of the two did not record it.
            // `SessionRowPreview` is the clearest case: its unnamed row falls back to the
            // start time as a title, which is exactly the half of a timestamp that moves.
            //
            // UTC because it is the one zone no contributor's location argues with, and
            // because `robolectric.properties` cannot carry this — Robolectric configures
            // the Android locale from its qualifiers but leaves the JVM's default zone to
            // whatever started it. Nothing about the app itself is pinned here: it still
            // renders in the device's zone, which is what `DateTimeFormat` takes a `ZoneId`
            // parameter for.
            systemProperty("user.timezone", "UTC")

            // ### What a Robolectric test JVM is allowed to take
            //
            // The counterpart to the budget in `gradle.properties`: that file bounds how many
            // of these run at once, this bounds how large each one gets. Both halves are
            // needed — four unbounded JVMs are no safer than sixteen bounded ones.
            //
            // Metaspace is the one that matters here, and it is the reason a heap limit alone
            // was not enough. Robolectric loads the Android framework through a sandbox
            // classloader *per SDK-and-configuration combination*, so a module's screenshot
            // tests hold several complete copies of the framework's class metadata — which
            // lives in native memory and does not count against `-Xmx`. A test JVM given 512m
            // of heap and no metaspace ceiling still grows past a gigabyte of resident memory,
            // which is invisible in a heap dump and visible only to the kernel.
            //
            // 1g of heap is above what any module needs today (they passed on Gradle's 512m
            // default) and leaves room for the hardware render mode the Compose convention
            // plugin turns on, which allocates its bitmaps on the Java heap.
            maxHeapSize = "1g"
            jvmArgs("-XX:MaxMetaspaceSize=512m")

            // One fork per module task, never several. Gradle would otherwise scale this with
            // the core count *inside* a task that `org.gradle.workers.max` is already running
            // beside three others, and the two limits would multiply instead of holding.
            maxParallelForks = 1

            testLogging {
                events("failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showCauses = true
                showStackTraces = true
            }
        }
        tasks.findByName("check")?.dependsOn(
            rootProject.tasks.named("detekt"),
            rootProject.tasks.named("checkArchitecture"),
            rootProject.tasks.named("checkBuildLogic"),
        )

        val isAndroidModule = plugins.hasPlugin("com.android.application") ||
            plugins.hasPlugin("com.android.library")

        // Every module that opts into coverage — `:core:domain` directly, the other three
        // through `opengym.android.jacoco` — reports its own JaCoCo XML and JUnit results
        // to the same Sonar analysis. An Android module's debug unit tests write under
        // variant-named paths (`jacocoDebugUnitTestReport`, `testDebugUnitTest`); the plain
        // `jacoco` plugin `:core:domain` applies on its own writes under the source set's
        // name instead (`test`). Both are fixed by the task names each side registers, not
        // discovered, so the path is correct whether or not the report has been generated
        // yet in this invocation — `sonar` only reads it once its own task runs.
        if (plugins.hasPlugin("jacoco")) {
            val jacocoXmlReportPath = if (isAndroidModule) {
                "$projectDir/build/reports/jacoco/jacocoDebugUnitTestReport/jacocoDebugUnitTestReport.xml"
            } else {
                "$projectDir/build/reports/jacoco/test/jacocoTestReport.xml"
            }
            val junitReportPath = if (isAndroidModule) {
                "$projectDir/build/test-results/testDebugUnitTest"
            } else {
                "$projectDir/build/test-results/test"
            }

            sonar {
                properties {
                    property("sonar.coverage.jacoco.xmlReportPaths", jacocoXmlReportPath)
                    property("sonar.junit.reportPaths", junitReportPath)
                }
            }
        }

        // `lint`/`lintDebug` (already run in CI, see `.github/workflows/ci.yml`) always
        // writes the debug variant's report to this fixed path — same "debug" variant
        // JaCoCo and the JUnit results above use, and for the same reason: it is the one
        // variant CI and Sonar both read.
        if (isAndroidModule) {
            sonar {
                properties {
                    property("sonar.androidLint.reportPaths", "$projectDir/build/reports/lint-results-debug.xml")
                }
            }
        }
    }
}
