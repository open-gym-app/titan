package com.titan.core.data.mapper

import com.titan.core.data.db.entity.UserProgressEntity
import com.titan.core.domain.gamification.XpCalculator
import com.titan.core.domain.model.StreakProtection
import com.titan.core.domain.model.UserProgress
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeParseException

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
 * would put a screen and the level-up the user was actually shown at odds after a retune. The
 * row is [repaired] first, so a level the curve cannot price never reaches the screen.
 */
fun UserProgressEntity.toDomain(): UserProgress =
    repaired().let { row ->
        UserProgress(
            totalXp = row.totalXp,
            level = row.currentLevel,
            currentStreak = row.currentStreak,
            longestStreak = row.longestStreak,
            lastCountedAt = row.lastCountedAt?.let(Instant::ofEpochMilli),
            weekGoalMet = row.weekGoalMet,
        )
    }

/**
 * This row with every column a reader would throw on brought back into range, and every other
 * column as stored.
 *
 * Nothing the app writes needs it. Every column is written by `XpCalculator` or
 * `StreakCalculator`, which keep them in range. A restored backup is another matter: the table
 * holds whatever the file held, and a negative total, a level of zero or a period that is not a
 * month would make the XP header, the quest log, the streak step and the next award throw, on
 * every launch, with no way back but clearing storage. So the restore applies this on the way
 * in, and every reader applies it again on the way out, for a row that got in some other way.
 *
 * - `total_xp` is clamped into `0..XpCalculator.MAX_TOTAL_XP`, the totals the curve can price.
 * - `current_level` outside `FIRST_LEVEL..MAX_LEVEL` is replaced by the level the clamped total
 *   buys. A level inside that range is kept even where it disagrees with the total, for the
 *   reason [toDomain] gives.
 * - The three counts are clamped to zero and up. The upper end is one below `Int.MAX_VALUE`,
 *   so the one increment a counted session makes still fits.
 * - `protection_remaining` is clamped into `0..StreakProtection.MONTHLY_ALLOWANCE`.
 * - A `protection_period` that does not parse as a `YYYY-MM` month becomes null, which is the
 *   value a row has before its first month: the next session replenishes the allowance for
 *   the month it falls in.
 */
fun UserProgressEntity.repaired(): UserProgressEntity {
    val total = totalXp.coerceIn(0, XpCalculator.MAX_TOTAL_XP)
    return copy(
        totalXp = total,
        currentLevel = currentLevel.takeIf { it in XpCalculator.FIRST_LEVEL..XpCalculator.MAX_LEVEL }
            ?: XpCalculator.levelForTotalXp(total),
        currentStreak = currentStreak.coerceIn(0, MAX_COUNT),
        longestStreak = longestStreak.coerceIn(0, MAX_COUNT),
        weekSessions = weekSessions.coerceIn(0, MAX_COUNT),
        protectionRemaining = protectionRemaining.coerceIn(0, StreakProtection.MONTHLY_ALLOWANCE),
        protectionPeriod = protectionPeriod?.takeIf(::isMonth),
    )
}

/** Whether [text] is a month as `protection_period` stores one, `YYYY-MM`. */
private fun isMonth(text: String): Boolean =
    try {
        YearMonth.parse(text)
        true
    } catch (_: DateTimeParseException) {
        false
    }

/** The largest streak or week count [repaired] keeps, one increment short of overflowing. */
private const val MAX_COUNT = Int.MAX_VALUE - 1
