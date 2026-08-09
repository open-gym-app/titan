package com.opengym.app

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * A bare activity that Hilt can inject through, for Compose tests that reach a screen with a
 * `@HiltViewModel` behind it.
 *
 * `hiltViewModel()` walks up to the `ViewModelStoreOwner`'s activity and asks it for a
 * `ViewModelComponent` builder; a plain `ComponentActivity` is not a component holder and the
 * call fails with a message about `@AndroidEntryPoint` that is easy to mistake for a problem
 * in the screen under test. This is the standard rig for that, and it is deliberately empty —
 * it contributes an entry point and nothing else.
 *
 * It lives in `src/debug` rather than `src/test` so that it is a real class in the variant the
 * manifest entry and the unit tests both compile against — a manifest naming a class that only
 * exists on the test classpath is a lint error, and rightly so. It is debug-only, so it cannot
 * reach a release build; nothing launches it outside a test. See [MainActivity] for the real
 * one.
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
