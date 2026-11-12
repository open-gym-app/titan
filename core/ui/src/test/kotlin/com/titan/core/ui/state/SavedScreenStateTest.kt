package com.titan.core.ui.state

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * The mechanism every screen's saved state goes through.
 *
 * A process death is simulated the way the feature suites simulate it: a second handle built from
 * what the first one holds. Only what was *saved* crosses — the flow, and the ViewModel it would
 * belong to, are gone — which is the property the whole thing is for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedScreenStateTest {

    @Test
    fun `nothing saved means the initial value`() =
        runTest {
            val state = SavedStateHandle().savedScreenState(scope()) { Screen(query = "opening") }

            assertEquals("opening", state.value.query)
        }

    @Test
    fun `a value of another type under the key opens the screen fresh rather than crashing it`() =
        runTest {
            // The handle is seeded from the destination's arguments, so this key holds whatever
            // was put there before the screen read it. Anything that is not a saved document is
            // not saved state.
            val state = SavedStateHandle(mapOf(SCREEN_STATE_KEY to 42)).savedScreenState(scope()) {
                Screen(query = "opening")
            }

            assertEquals("opening", state.value.query)
        }

    @Test
    fun `what was written comes back`() =
        runTest {
            val handle = SavedStateHandle()
            handle.savedScreenState(scope()) { Screen() }
                .update { it.copy(query = "bench", tab = 2, date = LocalDate.of(2026, 4, 28)) }

            val restored = handle.afterProcessDeath().savedScreenState(scope()) { Screen() }.value

            assertEquals("bench", restored.query)
            assertEquals(2, restored.tab)
            assertEquals(LocalDate.of(2026, 4, 28), restored.date)
        }

    @Test
    fun `a transient field does not come back`() =
        runTest {
            val handle = SavedStateHandle()
            handle.savedScreenState(scope()) { Screen() }.update { it.copy(query = "bench", isSaving = true) }

            val restored = handle.afterProcessDeath().savedScreenState(scope()) { Screen() }.value

            assertEquals("bench", restored.query)
            assertEquals("a write cannot outlive the process that started it", false, restored.isSaving)
        }

    @Test
    fun `the last value written is the one that comes back`() =
        runTest {
            val handle = SavedStateHandle()
            val state = handle.savedScreenState(scope()) { Screen() }
            state.update { it.copy(query = "b") }
            state.update { it.copy(query = "be") }
            state.update { it.copy(query = "ben") }

            assertEquals("ben", handle.afterProcessDeath().savedScreenState(scope()) { Screen() }.value.query)
        }

    @Test
    fun `a saved document this build cannot read opens on the initial value`() =
        runTest {
            // What an interrupted write or a hand-edited store would leave behind. The cost of
            // being wrong about this is a screen that will not open, so it opens fresh instead.
            val handle = SavedStateHandle(mapOf(SCREEN_STATE_KEY to "{not json"))

            val state = handle.savedScreenState(scope()) { Screen(query = "opening") }

            assertEquals("opening", state.value.query)
        }

    @Test
    fun `a state class the encoder cannot write does not take the screen down`() =
        runTest {
            val handle = SavedStateHandle()

            // `Unwritable` reaches a sealed case nobody marked `@Serializable`, which the
            // compiler cannot catch. The flow still works; only its restoration is lost.
            val state = handle.savedScreenState(scope()) { Unwritable() }
            state.update { it.copy(value = Unserializable.Case) }

            assertEquals(Unserializable.Case, state.value.value)
            // The write was dropped rather than half-applied: what is stored is the last value
            // that could be written, and the restoration is what is lost.
            assertNull(handle.afterProcessDeath().savedScreenState(scope()) { Unwritable() }.value.value)
        }

    /**
     * The scope the mirror runs on: unconfined against the test's own scheduler, so a write has
     * reached the handle by the time the call that made it returns.
     */
    private fun TestScope.scope() = TestScope(UnconfinedTestDispatcher(testScheduler))

    /** The handle as the system hands it back after the process was killed. */
    private fun SavedStateHandle.afterProcessDeath(): SavedStateHandle =
        SavedStateHandle(keys().associateWith { get<Any?>(it) })

    /** A screen state with one of everything: text, a scalar, a date and a flag that must not last. */
    @Serializable
    private data class Screen(
        val query: String = "",
        val tab: Int = 0,
        @Serializable(with = LocalDateSerializer::class)
        val date: LocalDate? = null,
        @Transient
        val isSaving: Boolean = false,
    )

    @Serializable
    private data class Unwritable(val value: Unserializable? = null)

    /** Deliberately not `@Serializable`, and deliberately reached through a polymorphic slot. */
    private sealed interface Unserializable {
        data object Case : Unserializable
    }
}
