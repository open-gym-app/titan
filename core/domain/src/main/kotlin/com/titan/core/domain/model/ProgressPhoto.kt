package com.titan.core.domain.model

/**
 * One progress photo: a picture of the user's physique, and when it was taken.
 *
 * ### Kept on the device, and only there
 *
 * Photos are the most personal thing the app holds. They live in the app's internal storage —
 * never the shared gallery, never Android's cloud backup — and they are deliberately **not** part
 * of an export: a backup file is something users copy to drives and clouds, and a progress photo
 * should not travel in one by default. A restore therefore leaves them exactly as they are, and
 * "Delete all data" removes them with everything else. See PERSISTENCE.md.
 *
 * ### Linked to a day, not to a row
 *
 * A photo is placed next to the body measurements by [takenAt] alone, with no foreign key to a
 * `body_measurement` row. A restore replaces those rows and renumbers them, so a stored id would
 * point at the wrong entry, or at nothing, while a date keeps meaning the day it was taken.
 *
 * @property takenAt when the photo was taken, epoch milliseconds UTC — the camera's own timestamp
 *   for a picked photo when it has one, otherwise the moment it was added; for an imported photo,
 *   the time its source declared.
 * @property path where the image is on this device, absolute. Read-only to everything but the
 *   repository, and not stable across installs: never store it anywhere else.
 * @property createdAt when it was added to the app.
 */
data class ProgressPhoto(
    val id: Long = 0,
    val takenAt: Long,
    val path: String,
    val createdAt: Long,
)
