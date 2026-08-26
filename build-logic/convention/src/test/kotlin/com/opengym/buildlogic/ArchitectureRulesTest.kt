package com.opengym.buildlogic

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
        ":core:ui" to setOf(":core:domain"),
        ":feature:home" to setOf(":core:domain", ":core:ui"),
        ":feature:session" to setOf(":core:domain", ":core:ui"),
        ":feature:history" to setOf(":core:domain", ":core:ui"),
        ":feature:progression" to setOf(":core:domain", ":core:ui"),
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
