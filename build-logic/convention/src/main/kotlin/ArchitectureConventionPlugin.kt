import com.opengym.buildlogic.CheckModuleDependencies
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.kotlin.dsl.register

/**
 * `opengym.architecture` — registers `checkArchitecture`, which asserts the module
 * dependency graph against docs/ARCHITECTURE.md.
 *
 * Applied to the root project only: the graph is a property of the build, not of any one
 * module, and a per-module check could only ever see its own edges.
 *
 * The root build script wires it into every module's `check`, so it runs under
 * `./gradlew build` and in CI alongside the other gates.
 */
class ArchitectureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        require(this == rootProject) {
            "opengym.architecture applies to the root project; it was applied to $path"
        }

        tasks.register<CheckModuleDependencies>("checkArchitecture") {
            group = "verification"
            description = "Asserts the module dependency graph against docs/ARCHITECTURE.md."

            // Resolved lazily: this runs once the task graph is built, by which point every
            // subproject has been evaluated and its `dependencies {}` block is complete.
            // Reading it eagerly here would see whichever modules happened to be configured
            // first, and pass by seeing nothing.
            moduleGraph.set(
                provider {
                    subprojects
                        // `:core` and `:feature` are directory containers with no build file
                        // and no dependencies of their own.
                        .filter { it.buildFile.exists() }
                        .associate { it.path to it.projectDependencyPaths() }
                },
            )
        }
        Unit
    }
}

/**
 * Every `project(":x")` this module declares, across every configuration.
 *
 * Declared dependencies rather than a resolved classpath: the question is what this module's
 * build file *says* it needs, and resolving would also drag in the transitive modules the
 * rules deliberately say nothing about — `:app` reaching `:core:domain` through `:core:data`
 * is not an architectural decision anybody made.
 *
 * The module's own path is dropped. The Android plugin adds every library module to its unit
 * test configurations as a dependency on itself, which is how a test sees the code it tests;
 * left in, it reads as `:feature:home -> :feature:home` and every module fails a rule it has
 * not broken.
 */
private fun Project.projectDependencyPaths(): Set<String> =
    configurations.flatMapTo(mutableSetOf()) { configuration ->
        configuration.dependencies.withType(ProjectDependency::class.java)
            .map { it.path }
            .filterNot { it == path }
    }
