package com.titan.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Writes the `robolectric.properties` that pins JVM unit tests to [AndroidSdk.ROBOLECTRIC] and
 * to [QUALIFIERS].
 *
 * Robolectric reads its global configuration from this file on the test classpath and from
 * nowhere else — no system property and no Gradle DSL reaches it — so the alternative is a
 * checked-in copy per module, or a `@Config(...)` on every test class that drifts from the
 * compile level the day someone bumps it.
 */
internal abstract class WriteRobolectricConfig : DefaultTask() {

    @get:Input
    abstract val sdk: Property<Int>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        outputDirectory.get().asFile.resolve("robolectric.properties")
            .writeText("sdk=${sdk.get()}\nqualifiers=$QUALIFIERS\n")
    }

    private companion object {

        /**
         * The locale every JVM test runs in, and the reason the screenshot references are
         * reviewable at all.
         *
         * Robolectric applies these qualifiers to the Android configuration *and* to
         * `Locale.getDefault()`, which is what `formattingLocale()` reads. Without the pin
         * every date on a captured screen is a function of the machine that captured it: the
         * calendar's week opens on Sunday in the United States and on Monday in Britain, so
         * every cell of the grid shifts a column and `TrainingCalendarPreview` fails on a
         * developer's laptop against a reference CI recorded — and the session rows'
         * `Mon 28 Apr 2026`, the calendar's month heading and the grouped thousands in a
         * volume all move with it.
         *
         * `en-rUS` because that is what the committed references were recorded in, not
         * because it is the app's only locale. The localization pass (#41) is where other
         * locales get screenshots of their own; the point here is that there is exactly one
         * answer and it does not depend on who runs the build.
         */
        const val QUALIFIERS = "en-rUS"
    }
}
