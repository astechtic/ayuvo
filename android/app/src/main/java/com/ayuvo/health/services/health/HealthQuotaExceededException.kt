package com.ayuvo.health.services.health

/** Health Connect refused the call for quota / rate-limit reasons; the engine backs off calmly. */
class HealthQuotaExceededException(cause: Throwable? = null) :
    Exception("Health Connect is rate limited", cause)

/**
 * The requested window lies before the platform's readable boundary (no
 * READ_HEALTH_DATA_HISTORY → 30 days before the first grant). Treated as the
 * backfill floor, never as a failure.
 */
class HealthReadBoundaryException(cause: Throwable? = null) :
    Exception("Outside the readable Health Connect window", cause)

/** Any other read failure (binder timeout, service restarting). Retried on a later run. */
class HealthReadFailedException(cause: Throwable? = null) :
    Exception("Health Connect read failed: ${cause?.javaClass?.simpleName}", cause)
