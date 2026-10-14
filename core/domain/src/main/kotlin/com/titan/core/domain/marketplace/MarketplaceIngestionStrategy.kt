package com.titan.core.domain.marketplace

import kotlinx.coroutines.flow.Flow

/** Brings marketplace templates from an outside [IngestionSource] into the local table. */
interface MarketplaceIngestionStrategy {

    /**
     * Runs one ingestion of [source] and emits its [IngestionResult] once it has been applied.
     *
     * Cold: nothing is read until the flow is collected. An unreadable or malformed source fails
     * the flow *before* anything is written, so a bad bundle can never hide the templates
     * already stored.
     */
    fun ingest(source: IngestionSource): Flow<IngestionResult>
}
