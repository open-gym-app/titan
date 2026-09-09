package com.opengym.feature.history

import androidx.lifecycle.SavedStateHandle

/**
 * The handle as the system hands it back after the process was killed.
 *
 * Only what was actually *saved* survives — everything a ViewModel held in a field, and the
 * ViewModel itself, is gone. So a restoration test drives one instance, takes this, and builds
 * a second one on it; asserting on the same instance would assert nothing, because a
 * `SavedStateHandle` a live ViewModel is writing into is not the one it would be handed back.
 *
 * The route arguments come across too, which is the same thing navigation does: a destination
 * is recreated from the back stack entry that named it, so its arguments are as durable as the
 * saved state beside them.
 */
internal fun SavedStateHandle.afterProcessDeath(): SavedStateHandle =
    SavedStateHandle(keys().associateWith { get<Any?>(it) })
