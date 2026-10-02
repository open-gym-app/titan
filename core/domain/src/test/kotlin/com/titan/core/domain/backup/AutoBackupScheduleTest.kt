package com.titan.core.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two questions the backup screen's row asks of the schedule, and the default a device that
 * has never been asked about it reports.
 */
class AutoBackupScheduleTest {

    /** Off, and with nothing to say about runs that have not happened. */
    @Test
    fun `a device that has never been asked has it off and has never run`() {
        val schedule = AutoBackupSchedule()

        assertFalse(schedule.enabled)
        assertFalse(schedule.hasRun)
        assertFalse(schedule.hasFailed)
        assertNull(schedule.lastSuccessAt)
        assertNull(schedule.lastFailureAt)
    }

    @Test
    fun `a run that wrote a file is a run that has happened`() {
        val schedule = AutoBackupSchedule(enabled = true, lastSuccessAt = LAST_SUCCESS_AT)

        assertTrue(schedule.hasRun)
        assertFalse(schedule.hasFailed)
        assertEquals(LAST_SUCCESS_AT, schedule.lastSuccessAt)
    }

    /**
     * Both facts at once, which is the whole reason there are two fields.
     *
     * A failure says something is wrong; the earlier success says how much it has cost so far.
     * Collapsing them into one "last run" would answer the first and hide the second.
     */
    @Test
    fun `a failure after a success keeps both`() {
        val schedule = AutoBackupSchedule(
            enabled = true,
            lastSuccessAt = LAST_SUCCESS_AT,
            lastFailureAt = LAST_FAILURE_AT,
        )

        assertTrue(schedule.hasRun)
        assertTrue(schedule.hasFailed)
    }

    private companion object {

        const val LAST_SUCCESS_AT = 1_777_161_600_000L
        const val LAST_FAILURE_AT = 1_777_766_400_000L
    }
}
