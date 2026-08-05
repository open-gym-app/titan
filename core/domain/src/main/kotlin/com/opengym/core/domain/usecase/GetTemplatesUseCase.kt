package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.WorkoutTemplate
import com.opengym.core.domain.repository.TemplateRepository
import kotlinx.coroutines.flow.Flow

/**
 * The saved templates, ordered by name, re-emitted on every template write.
 *
 * A pass-through today. It exists so a template list screen depends on the use case
 * layer like every other screen does, rather than reaching for the repository
 * directly — which is what would have to be unpicked the first time this list needs
 * a filter, an exercise count, or a "last used" ordering.
 */
class GetTemplatesUseCase(
    private val templateRepository: TemplateRepository,
) {

    operator fun invoke(): Flow<List<WorkoutTemplate>> = templateRepository.getAll()
}
