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

    /** The JDK every module compiles against. */
    const val JVM_TOOLCHAIN = 17
}
