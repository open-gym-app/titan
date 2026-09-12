package com.opengym.buildlogic

/**
 * The SDK levels the convention plugins compile and run their modules against.
 *
 * Constants rather than version-catalog entries: the catalog addresses artifacts, and a
 * `compileSdk` is not one. Changing either here changes every module that applies a
 * convention plugin at once, which is the point — a module that needs a different level is
 * a decision worth arguing in a PR, not a value worth making configurable.
 *
 * Two modules are *not* covered, because neither applies a convention plugin: `:app` sets
 * `compileSdk`/`minSdk`/`targetSdk` and its toolchain in `app/build.gradle.kts`, and
 * `:core:domain` sets its toolchain in `core/domain/build.gradle.kts`. A bump here is only
 * half a bump until those move too — and `:app` is the one that ships the APK.
 */
internal object AndroidSdk {
    const val COMPILE = 37
    const val MIN = 26

    /**
     * The level the app declares it was written against, and the third copy of a number
     * `:app` also states in `app/build.gradle.kts`. `:benchmark` needs it because a test
     * module whose `targetSdk` trails the app's gets a different set of platform runtime
     * behaviours than the app it is measuring.
     */
    const val TARGET = 36

    /**
     * The floor for `:benchmark`.
     *
     * Macrobenchmark reads its numbers out of the platform's own Perfetto traces, which
     * only carry the frame and startup sections it needs from API 29 onwards. It is above
     * [MIN] on purpose: the app supports Android 8, and the benchmarks measuring it do not
     * have to run there — a phone that old is not the phone this budget is set for.
     */
    const val BENCHMARK_MIN = 29

    /**
     * The SDK level JVM unit tests run against under Robolectric.
     *
     * Not [COMPILE]: Robolectric only runs on levels it ships a runtime for, and it fails
     * outright when the manifest targets a newer one. This trails [COMPILE] on purpose and
     * moves when Robolectric does, not when the compile level does.
     */
    const val ROBOLECTRIC = 34

    /** The JDK every module compiles against. */
    const val JVM_TOOLCHAIN = 17
}
