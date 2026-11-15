package com.titan.core.domain.marketplace

import kotlin.time.Duration

/**
 * One condition a marketplace search applies.
 *
 * Each filter judges a template on its own and knows nothing of the others, which is
 * what makes them composable: a search keeps the templates that every filter accepts,
 * so any combination returns the intersection of what each would return alone.
 *
 * Every text-based filter treats a blank value as "no constraint", so a picker or search
 * box left empty never empties the results.
 */
sealed interface MarketplaceFilter {

    fun matches(template: MarketplaceTemplate): Boolean

    /**
     * Keeps templates tagged with the muscle group [tag], ignoring case.
     *
     * Tags are free-form strings in the catalogue, so this matches any tag with that
     * name; it is a separate type from [Equipment] so a search screen can hold one of
     * each picker and the call site says which it is.
     */
    data class MuscleGroup(val tag: String) : MarketplaceFilter {
        private val wanted = tag.normalizedTag()

        override fun matches(template: MarketplaceTemplate): Boolean = template.hasTag(wanted)
    }

    /** Keeps templates tagged with the equipment [tag], ignoring case. See [MuscleGroup]. */
    data class Equipment(val tag: String) : MarketplaceFilter {
        private val wanted = tag.normalizedTag()

        override fun matches(template: MarketplaceTemplate): Boolean = template.hasTag(wanted)
    }

    /** Keeps templates whose estimated duration lies in [range], both ends included. */
    data class DurationRange(val range: ClosedRange<Duration>) : MarketplaceFilter {
        override fun matches(template: MarketplaceTemplate): Boolean = template.estimatedDuration in range
    }

    /** Keeps templates whose title or description contains [query], ignoring case and surrounding whitespace. */
    data class Text(val query: String) : MarketplaceFilter {
        private val needle = query.trim()

        override fun matches(template: MarketplaceTemplate): Boolean =
            needle.isEmpty() ||
                template.title.contains(needle, ignoreCase = true) ||
                template.description.contains(needle, ignoreCase = true)
    }
}

/** [wanted] is already normalized; blank means no constraint. */
private fun MarketplaceTemplate.hasTag(wanted: String): Boolean = wanted.isEmpty() || wanted in normalizedTags

internal fun String.normalizedTag(): String = trim().lowercase()
