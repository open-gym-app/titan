package com.opengym.feature.progression.records

import com.opengym.core.domain.model.PrType

/** What the records list reports upward. */
sealed interface PersonalRecordsEvent {

    /**
     * A filter chip was chosen.
     *
     * Carries the kind, or **null** for the `ALL` chip — the same shape as the state's own
     * filter, so selecting a chip is an assignment rather than a translation.
     */
    data class FilterSelected(val type: PrType?) : PersonalRecordsEvent
}
