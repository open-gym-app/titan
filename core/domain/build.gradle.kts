plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    jacoco
    // A plain JVM library, so none of the Android conventions apply; this one is the JDK.
    id("titan.jvm.toolchain")
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// This module is pure business logic with no Android framework escape hatches, so a
// line left uncovered here is a rule nobody ran, not a piece of platform glue that
// cannot be reached from a JVM test. That is what justifies floors this high; see
// CONTRIBUTING.md#test-coverage for what the numbers buy and what they deliberately
// do not measure.
private val lineFloor = "0.95".toBigDecimal()

// Held per package rather than over the module, because a module-wide ratio is exactly
// what lets one unexercised corner hide behind everything else: at ~630 lines, a 95%
// module floor still has room for a whole use case nobody tested.
private val packageLineFloor = "0.95".toBigDecimal()

// Branches are a stricter question than lines — an `if` nobody took both ways is a rule
// half-run — but the counter only means that outside `model`, see the exclusion below.
private val packageBranchFloor = "0.75".toBigDecimal()

// The data holders are held to the module-wide line floor and no more. JaCoCo counts the
// branches in the `equals`/`hashCode` Kotlin generates for a data class, and on a wide
// one that is most of what it counts: `ActiveSet` alone contributes 72 missed branches,
// which drags the module's branch ratio from 87% down to 71% on its own. A floor set
// under that measures how many fields the models have, not how well they are tested.
//
// `backup.model` is the same case at its worst: it is the export format, so it is twenty-one
// data classes that between them restate every column of every table — `WorkoutSetRow` alone
// is seventeen fields — and it holds no behaviour beyond one `when` over the table enum. The
// backup logic that *does* branch (the validator, the writer, the format, the two use cases)
// lives in `backup` and `usecase`, which carry the full floor; the split between the two
// packages is exactly so that the exclusion here cannot cover for it.
private val generatedHeavyPackages = listOf(
    "com.titan.core.domain.model",
    "com.titan.core.domain.backup.model",
)

// The seeded catalogue, which the import parsers' tests resolve their exercise mapping tables
// against (see `ImporterFixtures.catalogue`). It is generated into `:core:data`, but this module
// cannot depend on that one, so the tests read the file itself, by its path from this module's
// directory — the directory tests run in. Declared as an input so that regenerating the seed
// reruns them rather than leaving a stale pass up to date.
private val seedCatalogue = file("../data/src/main/assets/seed/exercises.sql")

// The plain CSV import format's example files, which `docs/import-csv.md` offers users to start
// from. The parser's tests import them as published, by their path from this module's directory
// like the seed, so an edit to either reruns the tests that hold the documentation to the parser.
private val importCsvExamples = files("../../docs/import-csv-workouts.csv", "../../docs/import-csv-measurements.csv")

tasks.test {
    // So a plain `./gradlew test` leaves a readable report behind, rather than only the
    // raw exec data and only for whoever thought to ask for the report task by name.
    finalizedBy(tasks.jacocoTestReport)

    inputs.file(seedCatalogue).withPathSensitivity(PathSensitivity.NONE)
    inputs.files(importCsvExamples).withPathSensitivity(PathSensitivity.NAME_ONLY)
}

// Kotlin compiles an interface's default arguments twice: into the interface, where Kotlin
// callers reach them, and into a `DefaultImpls` class kept only for Java code compiled against
// older Kotlin. This codebase has no such caller, so those copies can never run, and counting
// them held `ProgressPhotoRepository`'s one defaulted parameter against the repository package's
// floor. The interface's own copy is still measured.
tasks.withType<JacocoReportBase>().configureEach {
    classDirectories.setFrom(
        sourceSets.main.get().output.classesDirs.asFileTree.matching { exclude("**/*\$DefaultImpls.class") },
    )
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        // XML for CI and any coverage service that reads it; HTML for a human chasing
        // down which branch went unexercised.
        xml.required = true
        html.required = true
        csv.required = false
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        // The module-wide ratchet, over every class including the data holders.
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = lineFloor
            }
        }

        // The same floor again per package, so the ratio above cannot be met by the
        // rest of the module while one package goes unexercised, plus the branch floor
        // the generated-heavy packages are excused from.
        rule {
            element = "PACKAGE"
            excludes = generatedHeavyPackages
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = packageLineFloor
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = packageBranchFloor
            }
        }
    }
}

// Wired into `check` rather than left as an opt-in task: a floor nobody runs is not a
// floor. `./gradlew :core:domain:check` — and so CI — fails below it.
tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
}
