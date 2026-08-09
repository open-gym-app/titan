package com.opengym.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The preferences the *whole app* renders against, held for as long as the process runs.
 *
 * There is one of these and it is read at the root, which is what makes #M4-23's "theme
 * changes apply immediately, everywhere, without a restart" true by construction rather than
 * by every screen remembering to subscribe: the settings screen writes to the store, this
 * flow re-emits, `MainActivity` re-renders `OpenGymTheme` with a new scheme, and every
 * composable in the app is below it. No screen listens for a theme change because no screen
 * has to.
 *
 * The same argument covers the unit system, which is why both travel together.
 *
 * `SharingStarted.Eagerly`, unusually for a `stateIn`: the collector is the activity's
 * content, so "nobody is subscribed" means the app is in the background, and dropping the
 * subscription there buys a re-read of one small file in exchange for a chance of rendering
 * [MainUiState.Loading] — a blank window — on the way back to the foreground.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    val uiState: StateFlow<MainUiState> = userPreferencesRepository.observe()
        .map<UserPreferences, MainUiState>(MainUiState::Ready)
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
 * light theme on a dark phone watches the app correct itself. [Loading] draws nothing, the
 * platform keeps showing the window background, and the first thing composed is already
 * right.
 */
sealed interface MainUiState {

    data object Loading : MainUiState

    data class Ready(val preferences: UserPreferences) : MainUiState
}
