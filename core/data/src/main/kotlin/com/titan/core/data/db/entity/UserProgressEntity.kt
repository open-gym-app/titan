package com.titan.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.titan.core.domain.gamification.XpCalculator
import com.titan.core.domain.model.StreakProtection

/**
 * The user's gamification progress: the XP they have banked, the level it buys, the streak
 * they are on, the session that streak was last counted from, and what is left of the monthly
 * allowance that saves it from a missed day.
 *
 * ### One row, pinned by the schema
 *
 * There is no `user_id` column and no user table. The app is single-user, local-first and
 * has no account, so such a column would hold one value forever and invite a join that can
 * never be written. What replaces it is a fixed primary key: [SINGLE_ROW_ID], with a
 * `CHECK` constraint pinning it, so "there is exactly one progress row" is enforced by the
 * database rather than by every caller remembering to pass the same id.
 *
 * Room cannot express a `CHECK` in `@Entity` — see [CREATE_TABLE_SQL], which is the
 * statement the table is actually created from, and `TitanDatabase.singleRowProgressCallback`,
 * which applies it.
 *
 * ### The defaults are the row a user who has never trained would have
 *
 * A fresh install has no row at all, and a caller that has to invent one should not have to
 * decide what "no progress" is: it is zero XP at [XpCalculator.FIRST_LEVEL], no streak, and
 * no session counted yet. `UserProgressDao.mutate` hands exactly this to a transform that
 * finds the table empty, which is what makes the first XP award and the first finished
 * session ordinary writes rather than special cases.
 *
 * @property id always [SINGLE_ROW_ID]; see above.
 * @property totalXp lifetime points, only ever added to — nothing in the app spends XP.
 * @property currentLevel the level [totalXp] buys, always written from the curve in
 *   [XpCalculator] rather than counted up by hand. Stored rather than derived on read so a
 *   level-up can be reported by the write that caused it; a level that disagrees with the
 *   total is the one inconsistency `GamificationRepository.addXp` exists to rule out.
 * @property currentStreak consecutive days trained, per
 *   [com.titan.core.domain.gamification.StreakCalculator].
 * @property longestStreak the best [currentStreak] ever held, which a reset does not lower.
 * @property lastCountedAt when the session the streak was last *counted* from finished, in
 *   epoch milliseconds, or null for a user with no streak history. Deliberately not "when
 *   the last session finished": a second session on a day already counted leaves this where
 *   it is, which is what keeps the same-day rule from sliding the grace window forward with
 *   every workout — the calculator's parameter of the same name says the rest.
 * @property protectionRemaining streak protections left in [protectionPeriod]. Defaulted to a
 *   full allowance, which is what a user who has never trained holds — see [StreakProtection].
 * @property protectionPeriod the month [protectionRemaining] belongs to, as `YYYY-MM`
 *   (`YearMonth.toString()`), or null for an allowance nothing has ever touched. Text rather
 *   than a number because a month is what it is: the column is readable in a database browser
 *   and sorts chronologically, and nothing joins or ranges over it. The rollover into a new
 *   month is applied by
 *   [com.titan.core.domain.gamification.StreakCalculator.advance] on the way in, so this
 *   column trails the calendar for a user who has not trained this month — see
 *   `GamificationRepository.observeStreak` for why that is deliberate.
 * @property protectionConsumedAt when a protection was last spent, in epoch milliseconds, or
 *   null if none ever has been. Kept for the surfaces that report it, not for the mechanic:
 *   nothing decides anything from this column, and the guarantee that a protection is not
 *   spent twice for one day rests on [lastCountedAt] instead.
 */
@Entity(tableName = "user_progress")
data class UserProgressEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = SINGLE_ROW_ID,
    @ColumnInfo(name = "total_xp")
    val totalXp: Long = 0,
    @ColumnInfo(name = "current_level")
    val currentLevel: Int = XpCalculator.FIRST_LEVEL,
    @ColumnInfo(name = "current_streak")
    val currentStreak: Int = 0,
    @ColumnInfo(name = "longest_streak")
    val longestStreak: Int = 0,
    @ColumnInfo(name = "last_counted_at")
    val lastCountedAt: Long? = null,
    @ColumnInfo(name = "protection_remaining")
    val protectionRemaining: Int = StreakProtection.MONTHLY_ALLOWANCE,
    @ColumnInfo(name = "protection_period")
    val protectionPeriod: String? = null,
    @ColumnInfo(name = "protection_consumed_at")
    val protectionConsumedAt: Long? = null,
) {

    companion object {

        /** The only id the table accepts, and the only row it can hold. */
        const val SINGLE_ROW_ID = 1

        /**
         * The table as it is actually created, `CHECK` and all.
         *
         * Identical to the statement Room generates from the annotations above except for
         * the trailing constraint, which `@Entity` has no way to express (Room 2.8). The
         * database callback that runs this replaces Room's own table on create; the columns
         * must therefore keep matching what Room expects, or opening the database fails its
         * schema validation — `DatabaseMigrationTest` is what catches a drift between the
         * two, since it validates against the exported JSON.
         */
        const val CREATE_TABLE_SQL: String =
            "CREATE TABLE IF NOT EXISTS `user_progress` (" +
                "`id` INTEGER NOT NULL, " +
                "`total_xp` INTEGER NOT NULL, " +
                "`current_level` INTEGER NOT NULL, " +
                "`current_streak` INTEGER NOT NULL, " +
                "`longest_streak` INTEGER NOT NULL, " +
                "`last_counted_at` INTEGER, " +
                "`protection_remaining` INTEGER NOT NULL, " +
                "`protection_period` TEXT, " +
                "`protection_consumed_at` INTEGER, " +
                "PRIMARY KEY(`id`), " +
                "CHECK (`id` = $SINGLE_ROW_ID))"
    }
}
