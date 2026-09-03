package com.opengym.core.data.mapper

import com.opengym.core.data.db.entity.UserProgressEntity
import com.opengym.core.domain.model.UserProgress
import java.time.Instant

/**
 * [UserProgressEntity] to [UserProgress].
 *
 * One direction only, and no class to inject: the row is never written from a domain value —
 * every write is a transform applied inside `UserProgressDao.mutate`, which is what keeps a
 * read-modify-write atomic — so there is no `toEntity` for a caller to reach for. A file-level
 * function rather than an injected mapper for the same reason: there is nothing to configure
 * and nothing to stub.
 *
 * The stored level is passed through rather than re-derived from the total. It was written by
 * the curve in force at the time (see `GamificationRepository.addXp`), and re-deriving here
 * would put a screen and the level-up the user was actually shown at odds after a retune.
 */
fun UserProgressEntity.toDomain(): UserProgress =
    UserProgress(
        totalXp = totalXp,
        level = currentLevel,
        currentStreak = currentStreak,
        lastCountedAt = lastCountedAt?.let(Instant::ofEpochMilli),
    )
