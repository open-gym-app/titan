package com.opengym.buildlogic

/**
 * The SDK levels every Android module in this build compiles and runs against.
 *
 * Constants rather than version-catalog entries: the catalog addresses artifacts, and a
 * `compileSdk` is not one. Changing either here changes every module at once, which is
 * the point — a module that needs a different level is a decision worth arguing in a PR,
 * not a value worth making configurable.
 */
internal object AndroidSdk {
    const val COMPILE = 36
    const val MIN = 26

    /** The JDK every module compiles against. */
    const val JVM_TOOLCHAIN = 17
}
