package com.titan.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A marketplace template ingested from a bundle (M8-04), keyed by the source's own id.
 *
 * ### The key is the remote id
 *
 * `remote_id` is the primary key rather than a unique column beside a rowid: ingestion is
 * "this id again, update it in place", and making the id the key makes a second row for one
 * template something the table cannot hold. `achievement` is keyed by its domain id for the
 * same reason.
 *
 * ### Soft delete
 *
 * A template that has gone unseen for longer than the TTL is hidden, not deleted: `is_hidden`
 * is set and the row stays, so one that reappears in a later bundle is simply shown again.
 * `last_seen_at` is the clock the TTL runs against.
 *
 * @property tags newline-separated, as `gym.equipment_tags` is; empty string for none.
 * @property averageRating 0.0..5.0, enforced by the domain's `MarketplaceTemplate`.
 * @property createdAt epoch milliseconds, UTC, as the source published it.
 * @property lastSeenAt epoch milliseconds, UTC, of the last ingestion that carried it.
 */
@Entity(tableName = "marketplace_template")
data class MarketplaceTemplateEntity(
    @PrimaryKey
    @ColumnInfo(name = "remote_id")
    val remoteId: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "author")
    val author: String,
    @ColumnInfo(name = "description")
    val description: String,
    @ColumnInfo(name = "tags")
    val tags: String,
    @ColumnInfo(name = "download_count")
    val downloadCount: Int,
    @ColumnInfo(name = "average_rating")
    val averageRating: Double,
    @ColumnInfo(name = "duration_minutes")
    val durationMinutes: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long,
    @ColumnInfo(name = "is_hidden")
    val isHidden: Boolean,
)
