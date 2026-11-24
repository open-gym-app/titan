package com.titan.core.data.db

import com.titan.core.data.mapper.BodyMeasurementMapper
import com.titan.core.data.mapper.ExerciseMapper
import com.titan.core.data.mapper.MuscleGroupLinkMapper
import com.titan.core.data.mapper.PersonalRecordMapper
import com.titan.core.data.mapper.SessionExerciseMapper
import com.titan.core.data.mapper.SessionMapper
import com.titan.core.data.mapper.TagMapper
import com.titan.core.data.mapper.TemplateExerciseMapper
import com.titan.core.data.mapper.TemplateMapper
import com.titan.core.data.mapper.WorkoutSetMapper
import com.titan.core.data.repository.BodyMeasurementRepositoryImpl
import com.titan.core.data.repository.CollectedItemRepositoryImpl
import com.titan.core.data.repository.ExerciseRepositoryImpl
import com.titan.core.data.repository.GamificationRepositoryImpl
import com.titan.core.data.repository.HistoryImportRepositoryImpl
import com.titan.core.data.repository.PersonalRecordRepositoryImpl
import com.titan.core.data.repository.ProgressionRepositoryImpl
import com.titan.core.data.repository.QuestRepositoryImpl
import com.titan.core.data.repository.SessionRepositoryImpl
import com.titan.core.data.repository.TemplateRepositoryImpl
import com.titan.core.domain.backup.BackupLock
import com.titan.core.domain.gamification.DropRates
import com.titan.core.domain.gamification.DropTableEvaluator
import com.titan.core.domain.model.ProgressPhoto
import com.titan.core.domain.random.RandomSource
import com.titan.core.domain.repository.PhotoSource
import com.titan.core.domain.repository.ProgressPhotoRepository
import com.titan.core.domain.repository.UserPreferencesRepository
import com.titan.core.domain.time.Clock
import com.titan.core.domain.usecase.AchievementTriggerUseCase
import com.titan.core.domain.usecase.AwardXpUseCase
import com.titan.core.domain.usecase.ImportHistoryUseCase
import com.titan.core.domain.usecase.LootRollUseCase
import com.titan.core.domain.usecase.PrDetectionUseCase
import com.titan.core.domain.usecase.QuestCompletionUseCase
import com.titan.core.domain.usecase.QuestProgressUseCase
import com.titan.core.domain.usecase.SessionCompletionPipeline
import com.titan.core.domain.usecase.SessionLifecycleLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The import engine over a real database, with the completion pipeline it shares with a finished
 * workout — composed as `HistoryImportUseCaseModule` and `GamificationUseCaseModule` compose it,
 * for the suites that run a real import: `HistoryImportCascadeTest`, `HistoryImportWorkerTest` and
 * `LargeHistoryBenchmarkTest`.
 *
 * Photos are the store's own concern, and `ProgressPhotoRepositoryImplTest` drives the real one
 * over real images; here an in-memory one keeps the suites about the history. Loot never drops,
 * as in `SessionCompletionCascadeTest`: a die roll is not these suites' subject, and an assertion
 * that depended on one would be a flake.
 */
class HistoryImportStack(
    db: TitanDatabase,
    preferences: UserPreferencesRepository,
    clock: Clock,
) {

    val sessions = SessionRepositoryImpl(
        database = db,
        sessionMapper = SessionMapper(),
        sessionExerciseMapper = SessionExerciseMapper(),
        setMapper = WorkoutSetMapper(),
    )

    val exercises = ExerciseRepositoryImpl(
        exerciseDao = db.exerciseDao(),
        muscleGroupDao = db.muscleGroupDao(),
        exerciseMapper = ExerciseMapper(),
        tagMapper = TagMapper(),
        muscleGroupLinkMapper = MuscleGroupLinkMapper(),
    )

    val records = PersonalRecordRepositoryImpl(
        personalRecordDao = db.personalRecordDao(),
        personalRecordMapper = PersonalRecordMapper(),
    )

    private val gamification = GamificationRepositoryImpl(
        userProgressDao = db.userProgressDao(),
        achievementDao = db.achievementDao(),
        sessionXpClaimDao = db.sessionXpClaimDao(),
        sessionLootClaimDao = db.sessionLootClaimDao(),
    )

    private val quests = QuestRepositoryImpl(questCompletionDao = db.questCompletionDao())

    private val pipeline: SessionCompletionPipeline = run {
        val awardXp = AwardXpUseCase(gamificationRepository = gamification)
        SessionCompletionPipeline(
            detectPersonalRecords = PrDetectionUseCase(
                sessionRepository = sessions,
                exerciseRepository = exercises,
                personalRecordRepository = records,
                userPreferencesRepository = preferences,
            ),
            awardXp = awardXp,
            triggerAchievements = AchievementTriggerUseCase(gamificationRepository = gamification, awardXp = awardXp),
            completeQuests = QuestCompletionUseCase(
                questProgress = QuestProgressUseCase(
                    progressionRepository = ProgressionRepositoryImpl(
                        setDao = db.workoutSetDao(),
                        sessionDao = db.workoutSessionDao(),
                        setMapper = WorkoutSetMapper(),
                    ),
                    personalRecordRepository = records,
                    questRepository = quests,
                ),
                questRepository = quests,
                awardXp = awardXp,
            ),
            rollLoot = LootRollUseCase(
                dropTable = DropTableEvaluator(RandomSource { DropRates.TOTAL_WEIGHT - 1 }, emptyList()),
                collectedItemRepository = CollectedItemRepositoryImpl(collectedItemDao = db.collectedItemDao()),
                gamificationRepository = gamification,
            ),
            sessionRepository = sessions,
            personalRecordRepository = records,
            gamificationRepository = gamification,
            userPreferencesRepository = preferences,
        )
    }

    val importHistory = ImportHistoryUseCase(
        historyImportRepository = HistoryImportRepositoryImpl(db),
        exerciseRepository = exercises,
        sessionRepository = sessions,
        templateRepository = TemplateRepositoryImpl(
            templateDao = db.workoutTemplateDao(),
            templateMapper = TemplateMapper(),
            templateExerciseMapper = TemplateExerciseMapper(),
        ),
        bodyMeasurementRepository = BodyMeasurementRepositoryImpl(
            bodyMeasurementDao = db.bodyMeasurementDao(),
            bodyMeasurementMapper = BodyMeasurementMapper(),
        ),
        progressPhotoRepository = InMemoryPhotos(),
        completeSession = pipeline,
        clock = clock,
        backupLock = BackupLock(),
        sessionLock = SessionLifecycleLock(),
    )
}

/** A photo store holding nothing but what it is given, for a suite that is not about photos. */
private class InMemoryPhotos : ProgressPhotoRepository {

    private val photos = MutableStateFlow<List<ProgressPhoto>>(emptyList())

    override fun getAll(): Flow<List<ProgressPhoto>> = photos

    override suspend fun add(
        source: PhotoSource,
        takenAt: Long,
        preferImageTime: Boolean,
    ): Long {
        val id = photos.value.size + 1L
        photos.value += ProgressPhoto(id = id, takenAt = takenAt, path = "/photos/$id.jpg", createdAt = takenAt)
        return id
    }

    override suspend fun delete(id: Long) {
        photos.value = photos.value.filterNot { it.id == id }
    }
}
