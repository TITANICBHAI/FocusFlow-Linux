package com.focusflow.data

/**
 * Signals that opening must stop without replacing the user's database.
 * Backup refusal and transactional migration failures share this boundary.
 */
internal class MigrationAbortedException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)