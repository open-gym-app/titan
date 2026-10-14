package com.titan.core.domain.marketplace

import com.titan.core.domain.model.MarketplaceTemplate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** The bundle could not be turned into templates. Nothing has been written when this is thrown. */
class BundleFormatException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * Reads the curated bundle's JSON:
 *
 * ```json
 * { "version": 1, "templates": [ { "remote_id": "…", "title": "…", "author": "…",
 *   "description": "…", "tags": ["…"], "download_count": 0, "average_rating": 0.0,
 *   "duration_minutes": 45, "created_at": 0 } ] }
 * ```
 *
 * `remote_id`, `title`, `duration_minutes` and `created_at` are required; the rest default.
 *
 * All or nothing: one bad entry fails the whole bundle. A partial read would make the
 * reconciler see every unreadable template as absent and start the clock on hiding it, and a
 * bundle shipped in the APK is not something to be lenient about. Keys this build does not know
 * are ignored, so a newer bundle can add fields; a different `version` is refused.
 *
 * Read as a JSON tree rather than through generated serializers so that every rule above is
 * visible code with a test, not behaviour of the plugin.
 */
object MarketplaceBundleParser {

    const val SUPPORTED_VERSION = 1

    fun parse(text: String): List<MarketplaceTemplate> {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            fail("The marketplace bundle is not valid JSON: ${e.message}", e)
        }
        val bundle = root as? JsonObject ?: fail("The bundle must be a JSON object")

        val version = bundle.primitive("version", "The bundle").intOrNull
        if (version != SUPPORTED_VERSION) {
            fail("Bundle version $version is not supported (expected $SUPPORTED_VERSION)")
        }
        val entries = bundle["templates"] as? JsonArray ?: fail("\"templates\" must be an array")

        val templates = entries.mapIndexed { index, entry ->
            val obj = entry as? JsonObject ?: fail("Template #$index must be an object")
            obj.toTemplate("Template #$index")
        }
        val duplicate = templates.groupBy { it.id }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) fail("remote_id \"${duplicate.key}\" appears more than once")
        return templates
    }

    private fun JsonObject.toTemplate(where: String): MarketplaceTemplate {
        val remoteId = text("remote_id", where)
        if (remoteId.isBlank()) fail("$where has a blank remote_id")
        val named = "Template \"$remoteId\""
        val title = text("title", named)
        if (title.isBlank()) fail("$named has a blank title")

        val author = optionalText("author", named).trim()
        val description = optionalText("description", named).trim()
        val tags = tags(named)
        val downloads = optionalInt("download_count", named)
        val rating = optionalDouble("average_rating", named)
        val minutes = primitive("duration_minutes", named).intOrNull ?: notNumber("duration_minutes", named)
        val createdAt = primitive("created_at", named).longOrNull ?: notNumber("created_at", named)

        return try {
            MarketplaceTemplate(
                id = remoteId,
                title = title.trim(),
                author = author,
                description = description,
                tags = tags,
                downloadCount = downloads,
                averageRating = rating,
                durationMinutes = minutes,
                createdAt = createdAt,
            )
        } catch (e: IllegalArgumentException) {
            fail("$named is invalid: ${e.message}", e)
        }
    }

    private fun JsonObject.primitive(
        key: String,
        where: String,
    ): JsonPrimitive {
        val value = this[key]
        if (value == null || value is JsonNull) fail("$where is missing \"$key\"")
        return value as? JsonPrimitive ?: fail("$where: \"$key\" must be a single value")
    }

    private fun JsonObject.text(
        key: String,
        where: String,
    ): String {
        val value = primitive(key, where)
        if (!value.isString) fail("$where: \"$key\" must be a string")
        return value.content
    }

    private fun JsonObject.optionalText(
        key: String,
        where: String,
    ): String = if (this[key] == null) "" else text(key, where)

    private fun JsonObject.optionalInt(
        key: String,
        where: String,
    ): Int = if (this[key] == null) 0 else primitive(key, where).intOrNull ?: notNumber(key, where)

    private fun JsonObject.optionalDouble(
        key: String,
        where: String,
    ): Double = if (this[key] == null) 0.0 else primitive(key, where).doubleOrNull ?: notNumber(key, where)

    private fun JsonObject.tags(where: String): List<String> {
        val raw = this["tags"] ?: return emptyList()
        val array = raw as? JsonArray ?: fail("$where: \"tags\" must be an array")
        return array
            .map { element ->
                val tag = element as? JsonPrimitive
                if (tag == null || !tag.isString) fail("$where: tags must be strings")
                tag.content.trim()
            }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
    }

    private fun fail(
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw BundleFormatException(message, cause)

    private fun notNumber(
        key: String,
        where: String,
    ): Nothing = fail("$where: \"$key\" must be a number")
}
