import com.opengym.buildlogic.library
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
 * `:core:data` or on each other — see docs/ARCHITECTURE.md.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("opengym.android.library")
            pluginManager.apply("opengym.android.compose")
            pluginManager.apply("opengym.android.hilt")

            // Destinations are `@Serializable` route types, so every feature needs the
            // serialization compiler plugin to generate their serializers.
            pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

            dependencies {
                add("implementation", project(":core:domain"))
                add("implementation", project(":core:ui"))

                // Each feature exposes a `NavGraphBuilder` extension and owns its route
                // types; `:app` is the only caller that navigates across a module boundary.
                add("implementation", library("androidx-navigation-compose"))

                // The ViewModel-to-Compose bridge. `viewModel()` and `hiltViewModel()` live in
                // these two artifacts, not in the Compose BOM or in `hilt-android`: a screen
                // that gets its state from a `@HiltViewModel` — which is every screen here —
                // does not compile without them, so they belong in the plugin rather than in
                // four identical `dependencies {}` blocks.
                //
                // Import `hiltViewModel` from `androidx.hilt.lifecycle.viewmodel.compose`, not
                // from `androidx.hilt.navigation.compose` — the latter still compiles but is
                // deprecated as of androidx.hilt 1.4.0. `hilt-navigation-compose` is still the
                // right entry point (#84): it brings the new package transitively and adds the
                // nav-graph-scoped variants a feature's `NavGraphBuilder` extension needs.
                add("implementation", library("androidx-lifecycle-viewmodel-compose"))
                add("implementation", library("androidx-hilt-navigation-compose"))
            }
        }
}
