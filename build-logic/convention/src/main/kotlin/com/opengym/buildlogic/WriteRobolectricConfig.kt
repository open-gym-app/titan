package com.opengym.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Writes the `robolectric.properties` that pins JVM unit tests to [AndroidSdk.ROBOLECTRIC].
 *
 * Robolectric reads its global configuration from this file on the test classpath and from
 * nowhere else — no system property and no Gradle DSL reaches it — so the alternative is a
 * checked-in copy per module, or a `@Config(sdk = [...])` on every test class that drifts
 * from the compile level the day someone bumps it.
 */
internal abstract class WriteRobolectricConfig : DefaultTask() {

    @get:Input
    abstract val sdk: Property<Int>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        outputDirectory.get().asFile.resolve("robolectric.properties")
            .writeText("sdk=${sdk.get()}\n")
    }
}
