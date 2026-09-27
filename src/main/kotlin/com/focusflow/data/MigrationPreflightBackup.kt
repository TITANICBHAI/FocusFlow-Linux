package com.focusflow.data

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Creates the migration safety snapshot before schema work starts.
 *
 * VACUUM INTO reads committed pages from the database and its WAL and writes a
 * single, WAL-free SQLite file. The destination is then opened independently
 * and checked before the migration is allowed to continue.
 */
internal object MigrationPreflightBackup {
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")

    data class Result(
        val fileName: String,
        val walPresent: Boolean,
        val walBytes: Long
    )

    class Failed(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun createVerified(
        connection: Connection,
        databaseFile: File,
        sourceVersion: Int,
        targetVersion: Int
    ): Result {
        val backupDir = File(databaseFile.parentFile, "migration-backups")
        val fileName = "pre_migration_v${sourceVersion}_to_v${targetVersion}_" +
            "${LocalDateTime.now().format(timestampFormat)}.db"
        val destination = File(backupDir, fileName)

        try {
            if (!backupDir.exists() && !backupDir.mkdirs()) {
                throw IllegalStateException("Could not create migration backup directory")
            }
            if (destination.exists()) {
                throw IllegalStateException("Migration backup destination already exists")
            }

            val escapedPath = destination.absolutePath.replace("'", "''")
            connection.createStatement().use { statement ->
                statement.execute("VACUUM INTO '$escapedPath'")
            }

            verifySnapshot(destination, sourceVersion)

            val walFile = File(
                databaseFile.parentFile,
                "${databaseFile.name}-wal"
            )
            return Result(
                fileName = destination.name,
                walPresent = walFile.exists(),
                walBytes = walFile.length().coerceAtLeast(0L)
            )
        } catch (e: Exception) {
            runCatching { destination.delete() }
            if (e is Failed) throw e
            throw Failed("Verified migration backup could not be created", e)
        }
    }

    private fun verifySnapshot(snapshot: File, expectedVersion: Int) {
        if (!snapshot.exists() || snapshot.length() <= 0L) {
            throw IllegalStateException("Migration backup is missing or empty")
        }

        DriverManager.getConnection("jdbc:sqlite:${snapshot.absolutePath}").use { backup ->
            val integrity = backup.createStatement().use { statement ->
                statement.executeQuery("PRAGMA quick_check").use { rows ->
                    if (rows.next()) rows.getString(1) else "error"
                }
            }
            if (integrity != "ok") {
                throw IllegalStateException("Migration backup failed SQLite integrity check")
            }

            val version = backup.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { rows ->
                    if (rows.next()) rows.getInt(1) else -1
                }
            }
            if (version != expectedVersion) {
                throw IllegalStateException("Migration backup has an unexpected schema version")
            }
        }
    }
}