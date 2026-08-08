package com.opengym.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build on a module dependency [ArchitectureRules] does not allow.
 *
 * The graph is passed in as an input rather than read from the projects here, so the task
 * is a pure function of a value Gradle can hash: it is up to date when the module graph has
 * not changed, and it does not reach across projects while it runs.
 */
internal abstract class CheckModuleDependencies : DefaultTask() {

    @get:Input
    abstract val moduleGraph: MapProperty<String, Set<String>>

    @TaskAction
    fun check() {
        val graph = moduleGraph.get()
        val violations = ArchitectureRules.violationsIn(graph)
        if (violations.isEmpty()) {
            logger.lifecycle("Module graph matches docs/ARCHITECTURE.md (${graph.size} modules).")
            return
        }
        throw GradleException(
            buildString {
                appendLine("The module dependency graph violates docs/ARCHITECTURE.md:")
                violations.forEach { appendLine("  $it") }
                appendLine()
                append(
                    "Extract the shared concern into :core:domain (if it is a contract) or " +
                        ":core:ui (if it is a visual primitive), rather than relaxing the rule.",
                )
            },
        )
    }
}
