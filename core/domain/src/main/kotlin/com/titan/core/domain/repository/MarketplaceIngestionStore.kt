package com.titan.core.domain.repository

import com.titan.core.domain.marketplace.IngestionPlan
import com.titan.core.domain.marketplace.StoredTemplate

/** The local marketplace table, as an ingestion needs to read and write it. */
interface MarketplaceIngestionStore {

    /**
     * Reads every stored template, hands them to [planner], and applies the plan it returns
     * with every touched row stamped [nowMillis] - all as one transaction, so the plan is made
     * from exactly the rows it is then applied to. Returns the plan that was applied.
     */
    suspend fun reconcile(
        nowMillis: Long,
        planner: (List<StoredTemplate>) -> IngestionPlan,
    ): IngestionPlan
}
