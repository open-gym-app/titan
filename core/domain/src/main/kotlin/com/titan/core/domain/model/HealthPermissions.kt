package com.titan.core.domain.model

/** The Health Connect permissions the app asks for — exercise sessions, read and write. */
object HealthPermissions {
    const val READ_EXERCISE = "android.permission.health.READ_EXERCISE"
    const val WRITE_EXERCISE = "android.permission.health.WRITE_EXERCISE"
    val REQUIRED: Set<String> = setOf(READ_EXERCISE, WRITE_EXERCISE)
}
