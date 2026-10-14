package com.titan.core.domain.marketplace

import com.titan.core.domain.model.MarketplaceTemplate

/**
 * One criterion a listing must meet to be returned.
 *
 * Filters know nothing of each other: [SearchMarketplaceUseCase] keeps the listings every
 * filter in its list accepts, so any subset can be combined and an empty list accepts all.
 */
fun interface MarketplaceFilter {

    fun matches(template: MarketplaceTemplate): Boolean

    companion object {

        /** Listings carrying [tag], compared case-insensitively. */
        fun tag(tag: String): MarketplaceFilter {
            val wanted = tag.trim()
            return MarketplaceFilter { listing -> listing.tags.any { it.equals(wanted, ignoreCase = true) } }
        }

        /** [tag] as a muscle group. Tags share one namespace, so this is [tag] named for its purpose. */
        fun muscleGroup(tag: String): MarketplaceFilter = tag(tag)

        /** [tag] as a piece of equipment; see [muscleGroup]. */
        fun equipment(tag: String): MarketplaceFilter = tag(tag)

        /** Listings whose session length in minutes falls within [minutes], inclusive. */
        fun duration(minutes: IntRange): MarketplaceFilter = MarketplaceFilter { it.durationMinutes in minutes }

        /** Listings whose title or description contains [query], ignoring case. A blank query matches all. */
        fun text(query: String): MarketplaceFilter {
            val needle = query.trim()
            return MarketplaceFilter { listing ->
                needle.isEmpty() ||
                    listing.title.contains(needle, ignoreCase = true) ||
                    listing.description.contains(needle, ignoreCase = true)
            }
        }
    }
}
