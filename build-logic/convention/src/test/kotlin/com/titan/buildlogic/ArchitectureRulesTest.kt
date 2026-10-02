package com.titan.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the build enforces, tested against graphs the build cannot produce on demand.
 *
 * `checkArchitecture` running green on `main` proves the current graph is legal. It does not
 * prove the check would *catch* anything — a task that never fails and a task that cannot
 * fail look identical from the outside. These cases are the difference: each one is a
 * violation the project would otherwise have to commit in order to find out.
 */
class ArchitectureRulesTest {

    private val legalGraph = mapOf(
        ":app" to setOf(
            ":core:domain",
            ":core:data",
            ":core:ui",
            ":feature:home",
            ":feature:session",
            ":feature:history",
            ":feature:progression",
        ),
        ":core:domain" to emptySet(),
        ":core:data" to setOf(":core:domain"),
        // The `:core:testing` edges are `testImplementation`, written by the Compose
        // convention plugin; the graph the check reads does not know a configuration from
        // another, so they are part of the legal graph as much as `:core:domain` is.
        ":core:ui" to setOf(":core:domain", ":core:testing"),
        ":core:testing" to emptySet(),
        ":feature:home" to setOf(":core:domain", ":core:ui", ":core:testing"),
        ":feature:session" to setOf(":core:domain", ":core:ui", ":core:testing"),
        ":feature:history" to setOf(":core:domain", ":core:ui", ":core:testing"),
        ":feature:progression" to setOf(":core:domain", ":core:ui", ":core:testing"),
        // The one edge AGP writes rather than a contributor: `targetProjectPath = ":app"`.
        ":benchmark" to setOf(":app"),
    )

    @Test
    fun `the project's own graph is legal`() {
        assertEquals(emptyList<ArchitectureRules.Violation>(), ArchitectureRules.violationsIn(legalGraph))
    }

    @Test
    fun `a feature depending on another feature is rejected`() {
        val violations = ArchitectureRules.violationsIn(
            legalGraph + (":feature:home" to setOf(":core:domain", ":core:ui", ":feature:session")),
        )

        assertEquals(1, violations.size)
        assertEquals(":feature:home", violations.single().from)
        assertEquals(":feature:session", violations.single().to)
        assertTrue(violations.single().reason.contains("independent vertical slices"))
    }

    @Test
    fun `a feature reaching the data layer is rejected`() {
        val violations = ArchitectureRules.violationsIn(
            legalGraph + (":feature:session" to setOf(":core:domain", ":core:ui", ":core:data")),
        )

        assertEquals(listOf(":core:data"), violations.map { it.to })
        assertTrue(violations.single().reason.contains("go through :core:domain"))
    }

    @Test
    fun `presentation reaching the data layer is rejected`() {
        val violations = ArchitectureRules.violationsIn(
            legalGraph + (":core:ui" to setOf(":core:domain", ":core:data")),
        )

        assertEquals(listOf(":core:ui" to ":core:data"), violations.map { it.from to it.to })
    }

    @Test
    fun `the domain depending on anything at all is rejected`() {
        val violations = ArchitectureRules.violationsIn(
            legalGraph + (":core:domain" to setOf(":core:ui")),
        )

        assertEquals(1, violations.size)
        assertTrue(violations.single().reason.contains("must depend on nothing at all"))
    }

    @Test
    fun `test support reaching into the code it tests is rejected`() {
        // The tester drives whatever preview it is handed; the day it imports `:core:ui` it is
        // composing screens of its own, and those belong in the test that wants them.
        val violations = ArchitectureRules.violationsIn(
            legalGraph + (":core:testing" to setOf(":core:ui")),
        )

        assertEquals(listOf(":core:testing" to ":core:ui"), violations.map { it.from to it.to })
        assertTrue(violations.single().reason.contains("must depend on nothing at all"))
    }

    @Test
    fun `a module with no rule is rejected rather than waved through`() {
        val violations = ArchitectureRules.violationsIn(
            legalGraph + (":core:network" to setOf(":core:domain")),
        )

        assertEquals(listOf(":core:network"), violations.map { it.from })
        assertTrue(violations.single().reason.contains("no rule for this module"))
    }

    @Test
    fun `app may depend on every module`() {
        val violations = ArchitectureRules.violationsIn(
            legalGraph + (":app" to legalGraph.keys.minus(":app")),
        )

        assertEquals(emptyList<ArchitectureRules.Violation>(), violations)
    }

    @Test
    fun `the benchmark module linking against what it measures is rejected`() {
        val violations = ArchitectureRules.violationsIn(
            legalGraph + (":benchmark" to setOf(":app", ":core:ui")),
        )

        assertEquals(1, violations.size)
        assertEquals(":core:ui", violations.single().to)
    }

    @Test
    fun `every violation is reported, not only the first`() {
        val violations = ArchitectureRules.violationsIn(
            legalGraph +
                (":feature:home" to setOf(":feature:session", ":core:data")) +
                (":core:ui" to setOf(":core:data")),
        )

        assertEquals(
            listOf(
                ":core:ui" to ":core:data",
                ":feature:home" to ":core:data",
                ":feature:home" to ":feature:session",
            ),
            violations.map { it.from to it.to },
        )
    }
}
