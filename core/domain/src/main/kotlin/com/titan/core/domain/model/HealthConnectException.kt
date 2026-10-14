package com.titan.core.domain.model

/** Everything a [com.titan.core.domain.repository.HealthConnectGateway] may throw, wrapped. */
class HealthConnectException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
