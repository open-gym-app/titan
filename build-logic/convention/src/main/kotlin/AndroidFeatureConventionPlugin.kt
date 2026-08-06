import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/**
 * `opengym.android.feature` — everything a `:feature:*` vertical slice needs. Applying
 * this plugin plus a `namespace` should be the whole of a feature module's build file.
 *
 * The two module dependencies are the only ones a feature is allowed: `:core:domain`
 * for use cases and models, `:core:ui` for the design system. Features may not depend on
 * `:core:data` or on each other — see ARCHITECTURE.md.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("opengym.android.library")
        pluginManager.apply("opengym.android.compose")
        pluginManager.apply("opengym.android.hilt")

        dependencies {
            add("implementation", project(":core:domain"))
            add("implementation", project(":core:ui"))
        }
    }
}
