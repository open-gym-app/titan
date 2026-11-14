package com.titan.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.notification.ReminderScheduler
import com.titan.core.domain.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The preferences the *whole app* renders against, held for as long as the process runs.
 *
 * There is one of these and it is read at the root, which is what makes #M4-23's "theme
 * changes apply immediately, everywhere, without a restart" true by construction rather than
 * by every screen remembering to subscribe: the settings screen writes to the store, this
 * flow re-emits, `MainActivity` re-renders `TitanTheme` with a new scheme, and every
 * composable in the app is below it. No screen listens for a theme change because no screen
 * has to.
 *
 * The same argument covers the unit system, which is why both travel together.
 *
 * It also answers the one preference question that must *not* be answered continuously: where
 * the app opens. See [MainUiState.Ready.startsOnOnboarding].
 *
 * `SharingStarted.Eagerly`, unusually for a `stateIn`: the collector is the activity's
 * content, so "nobody is subscribed" means the app is in the background, and dropping the
 * subscription there buys a re-read of one small file in exchange for a chance of rendering
 * [MainUiState.Loading] — a blank window — on the way back to the foreground.
 *
 * ### The reminders are re-armed on every launch
 *
 * On Android 12 and 12L the user can revoke the exact-alarm permission in system settings, and
 * the platform answers by killing the app and cancelling every exact alarm it had set: the week
 * of reminders is gone, and no broadcast says so. Nothing else would put them back before the
 * next reboot, since the only other callers of [ReminderScheduler.sync] are a reminder firing,
 * the reminder settings and the boot receiver. Opening the app is the first thing that can run
 * after a revocation, so this is where the alarms come back, inexact now. `sync` is idempotent,
 * so on every other launch it rewrites the same seven alarms. Failures are swallowed for the
 * reason `BootReceiver` gives: the app's first frame is no place for a scheduling error.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    userPreferencesRepository: UserPreferencesRepository,
    reminderScheduler: ReminderScheduler,
) : ViewModel() {

    init {
        viewModelScope.launch { runCatching { reminderScheduler.sync() } }
    }

    val uiState: StateFlow<MainUiState> = userPreferencesRepository.observe()
        // `runningFold` rather than `map`, for one field: the routing decision is latched on
        // the first read and every later emission carries the same answer. Folding is what
        // gives an emission access to the one before it without a `var` captured in an
        // operator, which is the alternative and is a mutable field on a hot flow.
        .runningFold<UserPreferences, MainUiState>(MainUiState.Loading) { previous, preferences ->
            MainUiState.Ready(
                preferences = preferences,
                startsOnOnboarding = (previous as? MainUiState.Ready)?.startsOnOnboarding
                    ?: !preferences.onboardingCompleted,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = MainUiState.Loading,
        )
}

/**
 * Whether the stored preferences have been read yet.
 *
 * The distinction is load-bearing for exactly one frame, and it is worth the type: rendering
 * the app with *default* preferences while the real ones are read means a user who chose the
 * light theme on a dark phone watches the app correct itself. [Loading] draws nothing,
 * `MainActivity` keeps the splash screen up, and the first thing composed is already right.
 */
sealed interface MainUiState {

    data object Loading : MainUiState

    /**
     * @param preferences the stored settings, re-read on every change.
     * @param startsOnOnboarding whether this *launch* began before onboarding was completed.
     *
     *   Latched on the first read and never recomputed, which is the whole point of the
     *   field: it decides the `NavHost`'s start destination, and Navigation Compose rebuilds
     *   the graph — and empties the back stack with it — when that value changes. Read live
     *   from [UserPreferences.onboardingCompleted], the flag flipping at the *end* of
     *   onboarding would therefore tear the graph down underneath the navigation that
     *   onboarding is in the middle of making.
     *
     *   Stated as "where this launch starts" rather than "has onboarding been done", because
     *   those stop being the same sentence the moment the flag is written, and the shell needs
     *   the first one.
     */
    data class Ready(
        val preferences: UserPreferences,
        val startsOnOnboarding: Boolean,
    ) : MainUiState
}
