package com.titan.core.domain.backup

import com.titan.core.domain.backup.model.ImportError

/**
 * The database refused a row of an import, after the transaction was rolled back.
 *
 * The one exception `:core:domain` declares for backup, and it exists so the data layer has
 * somewhere to put a storage failure that the domain can translate without knowing what threw
 * it. `:core:domain` has no Room and no SQLite on its classpath, so an unwrapped
 * `SQLiteConstraintException` reaching `ImportUseCase` could only be caught as `Exception` —
 * which would swallow programming errors alongside constraint failures and turn a bug into
 * [ImportError.ConstraintViolation] shown to the user as a corrupt file.
 *
 * `BackupValidator` has already refused everything the schema can express before the wipe, so
 * reaching this means a constraint the validator does not model. It is the backstop, and the
 * rollback has already happened by the time it is thrown: the database is exactly as it was.
 *
 * @param message what SQLite said, forwarded into [ImportError.ConstraintViolation.reason].
 * @param cause the underlying storage exception, kept for the log.
 */
class BackupConstraintException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
