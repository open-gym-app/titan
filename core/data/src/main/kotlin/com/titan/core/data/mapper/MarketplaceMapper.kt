package com.titan.core.data.mapper

import com.titan.core.data.db.entity.MarketplaceTemplateEntity
import com.titan.core.domain.backup.model.MarketplaceTemplateRow
import com.titan.core.domain.marketplace.StoredTemplate
import com.titan.core.domain.model.MarketplaceTemplate

fun MarketplaceTemplate.toEntity(
    lastSeenAt: Long,
    isHidden: Boolean = false,
): MarketplaceTemplateEntity =
    MarketplaceTemplateEntity(
        remoteId = id,
        title = title,
        author = author,
        description = description,
        tags = packTags(tags),
        downloadCount = downloadCount,
        averageRating = averageRating,
        durationMinutes = durationMinutes,
        createdAt = createdAt,
        lastSeenAt = lastSeenAt,
        isHidden = isHidden,
    )

/**
 * Throws if the row breaks the domain's invariants, which only a restore from a hand-edited or
 * newer file can cause; callers that read many rows decide what to do about that.
 */
fun MarketplaceTemplateEntity.toDomain(): MarketplaceTemplate =
    MarketplaceTemplate(
        id = remoteId,
        title = title,
        author = author,
        description = description,
        tags = unpackTags(tags),
        downloadCount = downloadCount,
        averageRating = averageRating,
        durationMinutes = durationMinutes,
        createdAt = createdAt,
    )

fun MarketplaceTemplateEntity.toStored(): StoredTemplate = StoredTemplate(toDomain(), lastSeenAt, isHidden)

fun MarketplaceTemplateEntity.toRow(): MarketplaceTemplateRow =
    MarketplaceTemplateRow(
        remoteId = remoteId,
        title = title,
        author = author,
        description = description,
        tags = unpackTags(tags),
        downloadCount = downloadCount,
        averageRating = averageRating,
        durationMinutes = durationMinutes,
        createdAt = createdAt,
        lastSeenAt = lastSeenAt,
        isHidden = isHidden,
    )

fun MarketplaceTemplateRow.toEntity(): MarketplaceTemplateEntity =
    MarketplaceTemplateEntity(
        remoteId = remoteId,
        title = title,
        author = author,
        description = description,
        tags = packTags(tags),
        downloadCount = downloadCount,
        averageRating = averageRating,
        durationMinutes = durationMinutes,
        createdAt = createdAt,
        lastSeenAt = lastSeenAt,
        isHidden = isHidden,
    )
