package com.titan.core.domain.marketplace

import com.titan.core.domain.model.MarketplaceTemplate

fun listing(
    id: String,
    rating: Double = 4.0,
    downloads: Int = 100,
    tags: List<String> = emptyList(),
    title: String = id,
    description: String = "",
    minutes: Int = 45,
    createdAt: Long = 1_000,
) = MarketplaceTemplate(
    id = id,
    title = title,
    author = "author",
    description = description,
    tags = tags,
    downloadCount = downloads,
    averageRating = rating,
    durationMinutes = minutes,
    createdAt = createdAt,
)
