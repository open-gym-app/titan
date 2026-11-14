package com.titan.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/**
 * The root project's `libs` catalog, reachable from a plugin's `apply` where the
 * script-generated `libs` accessor is not.
 */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * Resolves a `libs` alias, failing the configuration phase with the alias name when it
 * is missing. `findLibrary(...).get()` on its own throws a bare `NoSuchElementException`
 * that names neither the alias nor the plugin that asked for it.
 */
internal fun Project.library(alias: String): Provider<MinimalExternalModuleDependency> =
    libs.findLibrary(alias).orElseThrow {
        IllegalStateException(
            "No library '$alias' in gradle/libs.versions.toml (required by a convention plugin)",
        )
    }

/** A `[versions]` entry of the `libs` catalog, failing with its name when it is missing. */
internal fun Project.catalogVersion(alias: String): String =
    libs.findVersion(alias).orElseThrow {
        IllegalStateException(
            "No version '$alias' in gradle/libs.versions.toml (required by a convention plugin)",
        )
    }.requiredVersion
