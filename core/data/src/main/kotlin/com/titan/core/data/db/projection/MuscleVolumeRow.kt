package com.titan.core.data.db.projection

import androidx.room.ColumnInfo

/**
 * What one muscle group was trained over a date range, produced by
 * [com.titan.core.data.db.dao.WorkoutSetDao.getVolumeByMuscleGroup].
 *
 * Two sums are returned because callers want both readings and neither is
 * derivable from the other once the rows are collapsed:
 *
 *  - [totalVolume] credits every linked muscle group with the *whole* volume of
 *    the set (`weight x reps`, milligrams). Summing this column across muscle
 *    groups therefore exceeds total training volume - that is expected, a set
 *    trains several muscles at once.
 *  - [weightedVolume] applies the same attribution rule as the domain-side
 *    `MuscleVolumeCalculator`: 100% to a primary link, 50% to a secondary one.
 *
 * Either way a set contributes to a given muscle group exactly once, however
 * many secondary muscles the exercise also trains.
 */
data class MuscleVolumeRow(
    @ColumnInfo(name = "muscle_group_id")
    val muscleGroupId: Long,
    @ColumnInfo(name = "muscle_group_name")
    val muscleGroupName: String,
    @ColumnInfo(name = "total_volume")
    val totalVolume: Long,
    @ColumnInfo(name = "weighted_volume")
    val weightedVolume: Long,
    /**
     * How many completed sets credited this group.
     *
     * The figure the progression screen's muscle map draws (`18 sets`), and what it ranks by.
     * Sets rather than volume because the map is read as "what have I been training", and a
     * heavy compound movement would otherwise put one lift above a whole session of accessory
     * work. It is also the only reading that means anything for the groups a bodyweight or
     * cardio movement trains, whose volume is zero however hard they were worked.
     *
     * Counted **once per set per group**, like both sums above: the composite primary key on
     * `exercise_muscle_group` means the fan-out cannot repeat a group for one exercise, so
     * there is no double count for the `GROUP BY` to collapse.
     */
    @ColumnInfo(name = "set_count")
    val setCount: Int,
    /**
     * Whether *any* of the range's sets worked this group as a prime mover.
     *
     * `MAX(is_primary)` over the group, so one exercise targeting it directly is enough. That
     * is the same rule `MuscleContribution.isPrimary` states and the same one `BodyDiagram`
     * draws by: a muscle a lifter trains directly in one movement and assists with in three is
     * a muscle they train, and showing it as an assistor because most of its rows were
     * secondary would report the sets rather than the training.
     */
    @ColumnInfo(name = "worked_directly")
    val workedDirectly: Boolean,
)
