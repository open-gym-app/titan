package com.titan.core.domain.model

/**
 * What one muscle group was trained over a period — the row behind the progression screen's
 * muscle map.
 *
 * The window-wide twin of what [com.titan.core.domain.stats.MuscleVolumeCalculator] produces
 * for a session the caller is already holding. Both attribute a set to every group its exercise
 * links to; this one is summed in SQLite, so a year of training is one query rather than a
 * year of sets loaded into memory. See [com.titan.core.domain.repository.ProgressionRepository].
 *
 * @property muscle the group. Carries its id and name; the icon is not read back, because the
 *   two things drawn from this — the body diagram and the ranked list beside it — resolve a
 *   group by name and by id respectively.
 * @property sets completed sets that credited this group, and what the map is **ranked** by.
 *   Sets rather than volume because the map answers "what have I been training": one heavy
 *   compound would otherwise outrank a whole session of accessory work, and a group trained
 *   only by bodyweight or cardio movements would rank last however hard it was worked, its
 *   volume being zero by construction.
 * @property volume attributed volume in milligram-reps, 100% for a primary link and 50% for a
 *   secondary one — identical to `MuscleVolumeCalculator`'s figures for identical input.
 *
 *   **Not conserved, and not a share of effort.** Every secondary link takes a full half
 *   regardless of how many there are, so these sum to more than the training volume that
 *   produced them. They compare a muscle against *itself over time*; a caller must not render
 *   them as a proportion of a whole that does not exist.
 * @property isPrimary whether any exercise in the period worked this group as a prime mover.
 *   Drawn at full accent by `BodyDiagram` and `MuscleContribution` alike, so the picture and
 *   the list agree.
 */
data class MuscleTraining(
    val muscle: MuscleGroup,
    val sets: Int,
    val volume: Long,
    val isPrimary: Boolean,
)
