package com.titan.core.data.mapper

import com.titan.core.data.db.entity.PetEntity
import com.titan.core.domain.backup.model.PetRow
import com.titan.core.domain.gamification.PetNickname
import com.titan.core.domain.gamification.XpCalculator
import com.titan.core.domain.model.Pet
import com.titan.core.domain.model.PetStats
import java.time.Instant

/**
 * This row as a [Pet], degraded rather than trusted.
 *
 * The second layer of #256's rule: a row can hold what a hand-edited backup or an older bug put
 * there, and [Pet] and [PetStats] refuse values outside their ranges. So the XP and the stats are
 * clamped and a nickname that fails the rule reads as none — a pet the user owns is drawn with
 * what can be salvaged, not thrown on.
 *
 * @param isActive passed in rather than read off the row: which flagged row counts as active is
 *   the repository's call when the table holds two.
 */
fun PetEntity.toDomain(isActive: Boolean = this.isActive): Pet =
    repaired().let { row ->
        Pet(
            id = row.id,
            speciesId = row.speciesId,
            hatchedAt = Instant.ofEpochMilli(row.hatchedAt),
            xp = row.xp,
            stats = PetStats(strength = row.strength, endurance = row.endurance, mobility = row.mobility),
            nickname = row.nickname,
            isActive = isActive,
        )
    }

/**
 * This row with every bounded value pulled into its range — the repair at the restore boundary,
 * and the first half of the reader's degrade. A row already in range comes back equal.
 */
fun PetEntity.repaired(): PetEntity =
    copy(
        xp = xp.coerceIn(0, XpCalculator.MAX_TOTAL_XP),
        strength = strength.coerceIn(0, PetStats.MAX),
        endurance = endurance.coerceIn(0, PetStats.MAX),
        mobility = mobility.coerceIn(0, PetStats.MAX),
        nickname = PetNickname.orNull(nickname),
    )

fun PetEntity.toRow(): PetRow =
    PetRow(
        id = id,
        speciesId = speciesId,
        hatchedFromItemId = hatchedFromItemId,
        hatchedAt = hatchedAt,
        xp = xp,
        strength = strength,
        endurance = endurance,
        mobility = mobility,
        nickname = nickname,
        isActive = isActive,
    )

fun PetRow.toEntity(): PetEntity =
    PetEntity(
        id = id,
        speciesId = speciesId,
        hatchedFromItemId = hatchedFromItemId,
        hatchedAt = hatchedAt,
        xp = xp,
        strength = strength,
        endurance = endurance,
        mobility = mobility,
        nickname = nickname,
        isActive = isActive,
    ).repaired()
