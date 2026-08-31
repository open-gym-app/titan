package com.opengym.core.domain.model

/**
 * One session's worth of one exercise — what a lifter means by "last time I benched".
 *
 * The unit the per-exercise drill-down is built from: a bar on its volume chart, a point on
 * its one-rep-max line and a row of its recent-attempts table are all this, so the three
 * cannot disagree about what a session held. The domain twin of `ExerciseAttemptRow`, which
 * is what SQLite actually rolls up — see
 * [com.opengym.core.domain.repository.ProgressionRepository.getExerciseAttempts] for the
 * filter and the bucketing.
 *
 * ### A session, not a slot
 *
 * A workout that held the exercise twice is **one** attempt, which is the grouping
 * [com.opengym.core.domain.usecase.PrDetectionUseCase] judges records by. Two half-attempts
 * beside a record built from both is a screen arguing with itself.
 *
 * @property sessionId the session it was performed in.
 * @property finishedAt when that session was closed out, epoch millis. What the attempt is
 *   dated by, matching every other progression aggregate.
 * @property sets completed working sets. Warm-ups are not among them: this screen reads how
 *   hard the lift was rather than how much work the week held, which is the opposite of
 *   [WeeklyVolume]'s reading of the same table.
 * @property volume `weight × reps` over those sets, in milligram-reps. Zero is a real value —
 *   a timed hold or a run is an attempt that moved nothing.
 * @property topSet the heaviest set of the attempt, ties going to the one that got more reps
 *   out of the weight. **Null** for an attempt with no set worth quoting, which in practice
 *   means one made entirely of sets carrying neither a weight nor a rep count.
 * @property firstSetAt the earliest set stamp of the attempt, epoch millis. What decides
 *   whether a record was set *here*: a record carries its own set's stamp rather than the
 *   session's, so [finishedAt] cannot answer it — see [ExerciseProgression.newRecords].
 */
data class ExerciseAttempt(
    val sessionId: Long,
    val finishedAt: Long,
    val sets: Int,
    val volume: Long,
    val topSet: AttemptSet?,
    val firstSetAt: Long,
)

/**
 * The set an attempt is quoted by: `80 kg × 5`, `× 12`.
 *
 * A pair rather than two nullable fields on [ExerciseAttempt] because they are one fact and
 * are read together everywhere — the table's `TOP` cell, and the one-rep-max estimate, which
 * needs both or neither.
 *
 * @property weight milligrams, or **null** for a bodyweight movement.
 * @property reps how many, or **null** for a set measured by time or distance.
 */
data class AttemptSet(
    val weight: Long?,
    val reps: Int?,
)
