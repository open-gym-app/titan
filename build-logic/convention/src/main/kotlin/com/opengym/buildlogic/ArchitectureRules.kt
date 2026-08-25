package com.opengym.buildlogic

/**
 * The dependency directions from docs/ARCHITECTURE.md, in a form a build can enforce.
 *
 * The document has always said `:feature:*` may not depend on another `:feature:*` and that
 * `:core:ui` may not reach `:core:data`. Nothing checked it. `./gradlew build` succeeds on a
 * graph that violates every rule in the table, because a cycle is the only module-level
 * mistake Gradle itself objects to — so the rules held exactly as long as everybody
 * remembered them, and the first violation would have been found in review, or not at all.
 *
 * Kept as a pure function over a `path -> dependencies` map so the rules can be tested
 * without a Gradle build. [CheckModuleDependencies] is the thin task that reads the real
 * graph and calls this.
 */
internal object ArchitectureRules {

    /**
     * A module with no rule is a violation, not an exemption.
     *
     * A new `:core:network` or `:feature:map` should have to state where it sits before it
     * can depend on anything, and the failure names the file to state it in. The alternative
     * — an unknown module defaulting to "anything goes" — makes the check quietly weaker
     * every time the project grows, which is the opposite of what it is for.
     */
    private val allowed: List<Pair<(String) -> Boolean, Set<String>?>> = listOf(
        // The composition root wires the layers together; it is the one module that may see
        // all of them.
        { path: String -> path == ":app" } to null,

        // The stable centre. It depends on nothing, which is what makes it testable without
        // an Android runtime.
        { path: String -> path == ":core:domain" } to emptySet(),

        // Implements the repository interfaces the domain declares.
        { path: String -> path == ":core:data" } to setOf(":core:domain"),

        // Shared components take domain types directly. The ban on `:core:ui -> :core:data`
        // is the point of this entry: presentation must not bypass the domain layer.
        { path: String -> path == ":core:ui" } to setOf(":core:domain"),

        // A vertical slice: use cases from the domain, the design system from `:core:ui`,
        // and nothing else — least of all another feature.
        { path: String -> path.startsWith(":feature:") } to setOf(":core:domain", ":core:ui"),
    )

    /** A dependency the architecture does not allow, with the reason it does not. */
    data class Violation(
        val from: String,
        val to: String,
        val reason: String,
    ) {
        override fun toString(): String = "$from -> $to: $reason"
    }

    /**
     * @param graph every module's declared project dependencies, keyed by Gradle path.
     * @return the violations, in a stable order so the failure message does not churn.
     */
    fun violationsIn(graph: Map<String, Set<String>>): List<Violation> =
        graph.toSortedMap().flatMap { (module, dependencies) ->
            val permitted = permittedFor(module)
            when (permitted) {
                null -> emptyList()

                else -> dependencies.sorted()
                    .filterNot { it in permitted }
                    .map { Violation(module, it, reasonFor(module, it, permitted)) }
            }
        } + unknownModules(graph)

    private fun permittedFor(module: String): Set<String>? =
        allowed.firstOrNull { (matches, _) -> matches(module) }?.second

    private fun isKnown(module: String): Boolean = allowed.any { (matches, _) -> matches(module) }

    private fun unknownModules(graph: Map<String, Set<String>>): List<Violation> =
        graph.keys.sorted().filterNot(::isKnown).map { module ->
            Violation(
                from = module,
                to = "(none)",
                reason = "no rule for this module. Add it to docs/ARCHITECTURE.md and to " +
                    "ArchitectureRules, so what it may depend on is a decision rather than an accident",
            )
        }

    private fun reasonFor(
        from: String,
        to: String,
        permitted: Set<String>,
    ): String =
        when {
            from.startsWith(":feature:") && to.startsWith(":feature:") ->
                "features are independent vertical slices; cross-feature navigation goes through :app"

            to == ":core:data" ->
                "only :app and :core:data itself may touch the data layer — go through :core:domain"

            permitted.isEmpty() ->
                "$from must depend on nothing at all"

            else ->
                "$from may only depend on ${permitted.sorted().joinToString(", ")}"
        }
}
