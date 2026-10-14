package com.titan.core.domain.feature

/**
 * The M8 features that can each be switched off on their own.
 *
 * Every one defaults to **off**: a user who never opts in sees and runs exactly what they did
 * before M8. See `docs/m8-rollout.md`.
 */
enum class M8Feature {
    GYM_DIRECTORY,
    GYM_REVIEWS,
    MARKETPLACE_SEARCH,
    MARKETPLACE_INGESTION,
    HEALTH_CONNECT,
}
