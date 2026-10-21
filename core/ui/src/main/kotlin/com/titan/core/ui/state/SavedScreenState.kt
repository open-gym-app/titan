package com.titan.core.ui.state

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * A ViewModel's own screen state, held in a [MutableStateFlow] that survives process death.
 *
 * ### What this is for, and what it is not for
 *
 * Every screen here keeps the part of its state that no store owns — a search box, a selected
 * tab, an open dialog, a half-typed field — in a private `MutableStateFlow`. That flow survives
 * a configuration change, because the ViewModel does, and does **not** survive the process being
 * killed while the app sits in the background, because nothing does. This restores it from
 * [SavedStateHandle], which is the one thing that outlives the process without outliving the
 * task.
 *
 * It is deliberately *not* a substitute for a store. Saved instance state is kept by the system
 * alongside the task record and goes when the task does — a swipe out of Recents, a reboot, a
 * long enough absence. Anything the user would call **their work** belongs in a repository; see
 * `TemplateDraftRepository` for the case that made the difference concrete. What belongs here is
 * everything else: where they were, what they had narrowed to, what was open.
 *
 * ### Why the state is stored as a JSON string
 *
 * [SavedStateHandle] takes what a `Bundle` takes, and a screen state is a Kotlin data class with
 * nested sealed types in it. The two ways to bridge that are `@Parcelize` — a second
 * representation of every state class, and a compiler plugin per module — and
 * `androidx.savedstate`'s serialization, which encodes into a real `Bundle` and therefore cannot
 * be exercised by a plain JVM unit test. A JSON document needs neither: every state class is
 * already reachable by the `kotlinx.serialization` plugin each feature applies for its route
 * types, the encoding is a `String` that a `SavedStateHandle` holds without any Android
 * framework behind it, and the same test that drives the ViewModel can drive its restoration.
 *
 * It is also the app's existing answer to this question one layer down: the in-progress workout
 * draft is a JSON document for the same reason — see `ActiveSessionFormat` and PERSISTENCE.md.
 *
 * The size argument runs the other way from what it looks like. `TransactionTooLargeException`
 * is a limit on the *whole* of a task's saved state, and a JSON string is not measurably worse
 * than the `Bundle` of boxed values it replaces. What keeps a screen inside the limit is not
 * choosing a denser encoding, it is not putting the rows a query can re-read into saved state at
 * all — see the note on each ViewModel that reads its list back rather than saving it.
 *
 * ### `ignoreUnknownKeys`, and why nothing is versioned
 *
 * Saved state is read back by the *same build that wrote it*, always: an app update restarts the
 * process and drops the task's saved state with it. So there is no compatibility ladder to
 * climb, and no envelope version to check. `ignoreUnknownKeys` is on anyway, as is a failed
 * decode returning [initial], because the cost of being wrong about that is the app failing to
 * open a screen — where the cost of the fallback is a screen that opens fresh.
 */
private val screenStateJson = Json {
    ignoreUnknownKeys = true
    // Spelled out because this is an encoding read back from storage rather than one produced
    // and consumed in the same breath: a discriminator changed by a library upgrade would
    // silently stop every saved sealed value decoding.
    classDiscriminator = "type"
}

/**
 * The key every screen state is saved under.
 *
 * One key rather than a name per screen, because a [SavedStateHandle] belongs to a single
 * destination: nothing else is writing into it, so a second name would only be a chance to spell
 * it differently in two places. The `com.titan` prefix keeps it clear of the route arguments
 * navigation puts in the same handle, which are named by their own properties.
 */
const val SCREEN_STATE_KEY: String = "com.titan.screenState"

/**
 * A [MutableStateFlow] seeded from saved state, and written back to it as it changes.
 *
 * Drop-in for the `MutableStateFlow(ScreenState())` a ViewModel would otherwise hold: the flow
 * behaves exactly as before, and the only new obligation is that `T` is `@Serializable`.
 *
 * Fields the screen must **not** come back holding — a write in flight, a one-shot message, a
 * flag set by a permission callback — are marked `@Transient` on the state class rather than
 * filtered here, so the decision is written down beside the field it is about.
 *
 * @param scope the ViewModel's own scope. The collector that mirrors into the handle lives for
 *   as long as the ViewModel; a `MutableStateFlow` conflates, so a burst of keystrokes costs one
 *   encode rather than one per character.
 * @param key see [SCREEN_STATE_KEY]. Worth overriding only where one destination genuinely holds
 *   two independent states.
 * @param initial what the screen opens on when there is nothing saved — a first visit, or a
 *   saved document this build could not read.
 */
inline fun <reified T : Any> SavedStateHandle.savedScreenState(
    scope: CoroutineScope,
    key: String = SCREEN_STATE_KEY,
    noinline initial: () -> T,
): MutableStateFlow<T> = savedScreenState(scope, serializer(), key, initial)

/**
 * [savedScreenState]'s body, with the serializer passed rather than reified.
 *
 * Separate so the inline function above carries only the `serializer()` lookup: everything else
 * is ordinary code that does not have to be copied into every call site.
 */
@PublishedApi
internal fun <T : Any> SavedStateHandle.savedScreenState(
    scope: CoroutineScope,
    serializer: KSerializer<T>,
    key: String,
    initial: () -> T,
): MutableStateFlow<T> {
    val restored = get<String>(key)?.let { saved ->
        try {
            screenStateJson.decodeFromString(serializer, saved)
        } catch (_: IllegalArgumentException) {
            // `SerializationException` is an `IllegalArgumentException`, and so is what a state
            // class's own `require` throws from the constructor the decoder calls. Both mean the
            // same thing here: there is no state to restore.
            null
        }
    }

    val state = MutableStateFlow(restored ?: initial())

    scope.launch {
        state.collect { value ->
            try {
                this@savedScreenState[key] = screenStateJson.encodeToString(serializer, value)
            } catch (_: IllegalArgumentException) {
                // A state class the encoder cannot write — in practice a sealed hierarchy with a
                // case nobody marked `@Serializable`, which the compiler cannot catch. Dropping
                // the write costs the screen its restoration; letting it out would cancel
                // `viewModelScope` and take the screen itself down over a save nobody asked for.
                //
                // It is not silent where it matters: nothing is ever written after this, so every
                // restoration test for the screen fails at once rather than one field going
                // quietly missing.
            }
        }
    }

    return state
}
