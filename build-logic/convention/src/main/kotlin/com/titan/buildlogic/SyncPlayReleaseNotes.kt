package com.titan.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/**
 * Copies one version's changelogs from `fastlane/metadata/android/<locale>/changelogs/
 * <versionCode>.txt` into the layout Gradle Play Publisher reads release notes from,
 * `src/release/play/release-notes/<locale>/default.txt` (see `app/build.gradle.kts`).
 *
 * A task of its own rather than a `Sync`, because a `Sync` whose sources are missing skips
 * as `NO-SOURCE` and never runs the check that is half the point: a release whose changelog
 * is missing in a locale, or over Play's [PLAY_LIMIT] characters, fails *here* — the first
 * Gradle task the release workflow runs — and not at the upload step, after the environment
 * was approved and two builds were spent.
 */
abstract class SyncPlayReleaseNotes @Inject constructor(private val fileSystem: FileSystemOperations) : DefaultTask() {

    /** `fastlane/metadata/android`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val metadata: DirectoryProperty

    /** For the error message only; [versionCode] is what names the file. */
    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    /** Play's names for the locales the app ships in. */
    @get:Input
    abstract val locales: ListProperty<String>

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @TaskAction
    fun sync() {
        val fileName = "${versionCode.get()}.txt"
        fun changelog(locale: String) = metadata.get().file("$locale/changelogs/$fileName").asFile

        val missing = locales.get().filterNot { changelog(it).isFile }
        check(missing.isEmpty()) {
            "No changelog for ${version.get()} in ${missing.joinToString()}: write " +
                "fastlane/metadata/android/<locale>/changelogs/$fileName for every locale " +
                "(release/RELEASE.md)"
        }
        val overlong = locales.get().filter { changelog(it).readText().length > PLAY_LIMIT }
        check(overlong.isEmpty()) {
            "The ${version.get()} changelog is over Play's $PLAY_LIMIT-character limit " +
                "in ${overlong.joinToString()}"
        }

        fileSystem.sync {
            locales.get().forEach { locale ->
                from(changelog(locale)) {
                    into(locale)
                    rename { "default.txt" }
                }
            }
            into(destination)
        }
    }

    private companion object {
        /** What the Console enforces on "What's new", per locale. */
        const val PLAY_LIMIT = 500
    }
}
