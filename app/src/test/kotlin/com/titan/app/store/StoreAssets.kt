package com.titan.app.store

import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Where the store listing's generated assets live, and the moment they are generated at.
 *
 * Shared by [DemoDatasetTest], which writes the demo exports under `tools/demo/`, and
 * [StoreScreenshotTest], which restores them and captures the listing's phone screenshots into
 * `fastlane/metadata/android/`. See `tools/demo/README.md` for the whole pipeline.
 */
internal object StoreAssets {

    /**
     * The last day the demo lifter trained: a Monday, so the week strip on Home shows one
     * workout done and the rest of the week ahead.
     */
    val LAST_TRAINING_DAY: LocalDate = LocalDate.of(2026, 9, 28)

    /**
     * "Now", for everything generated: the export's own timestamp and the clock the screenshots
     * are taken under. The Wednesday evening after [LAST_TRAINING_DAY], which is the next
     * workout in the rotation — the one the active-session screenshot shows under way.
     *
     * Pinned rather than read, so a screenshot taken next year still says "two days ago" about
     * the last workout instead of "a year ago", and the streak is still alive.
     */
    val DEMO_NOW: Long = LAST_TRAINING_DAY.plusDays(2).atTime(17, 40).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** Seeds every random draw the generator makes. Change it and every demo file changes. */
    const val DEMO_SEED = 46L

    /** What the export's `app_version` says, so a demo file is recognisable as one. */
    const val DEMO_APP_VERSION = "demo"

    /**
     * The repository root, which Gradle passes in: a unit test runs with the module directory as
     * its working directory, and the assets belong to the repository rather than to `:app`.
     */
    val rootDir: File
        get() = File(
            System.getProperty(ROOT_DIR_PROPERTY) ?: error("$ROOT_DIR_PROPERTY is not set; run through Gradle"),
        )

    fun demoFile(locale: StoreLocale): File = File(rootDir, "tools/demo/titan-demo-${locale.tag}.json")

    fun screenshotDir(locale: StoreLocale): File =
        File(rootDir, "fastlane/metadata/android/${locale.tag}/images/phoneScreenshots")

    /** Whether this run writes the assets (`make demo-data`, `make store-screenshots`) or checks them. */
    val isRecording: Boolean
        get() = System.getProperty(RECORD_PROPERTY).toBoolean()

    private const val ROOT_DIR_PROPERTY = "titan.rootDir"
    private const val RECORD_PROPERTY = "titan.storeAssets.record"
}

/**
 * The four listings, one per language the app ships in (docs/TRANSLATION.md).
 *
 * The Robolectric qualifiers that put the app in each language are not here: a test names them
 * in `@Config`, which takes only compile-time constants.
 *
 * @property tag the Play Console's locale code, which is also the directory name under
 *   `fastlane/metadata/android/` that Play Publisher and F-Droid both read.
 */
internal enum class StoreLocale(val tag: String) {
    EN_US("en-US"),
    FR_FR("fr-FR"),
    ES_ES("es-ES"),
    PT_BR("pt-BR"),
}
