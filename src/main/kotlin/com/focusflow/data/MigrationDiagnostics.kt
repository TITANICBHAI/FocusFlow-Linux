package com.focusflow.data

import java.io.File
import java.time.Instant

/**
 * Writes local migration telemetry without database values, paths, or errors.
 * Diagnostics are best-effort and never change migration success/failure.
 */
internal object MigrationDiagnostics {
    private const val FILE_NAME = "migration-summary.log"

    data class Counts(
        val recordsExamined: Long = 0,
        val normalized: Long = 0,
        val matched: Long = 0,
        val staleOrManual: Long = 0,
        val conflicts: Long = 0
    )

    fun capture(connection: java.sql.Connection): Counts {
        return try {
            if (!tableExists(connection, "app_references")) return Counts()

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT
                        COUNT(*) AS records_examined,
                        COALESCE(SUM(
                            CASE WHEN primary_process_name <> legacy_process_name
                                 THEN 1 ELSE 0 END
                        ), 0) AS normalized,
                        COALESCE(SUM(
                            CASE WHEN stable_app_id IS NOT NULL
                                      OR resolution_status = 'resolved'
                                 THEN 1 ELSE 0 END
                        ), 0) AS matched,
                        COALESCE(SUM(
                            CASE WHEN resolution_status IN ('unresolved', 'stale', 'manual')
                                 THEN 1 ELSE 0 END
                        ), 0) AS stale_or_manual,
                        COALESCE(SUM(
                            CASE WHEN conflict_status <> 'none'
                                 THEN 1 ELSE 0 END
                        ), 0) AS conflicts
                    FROM app_references
                    """.trimIndent()
                ).use { rows ->
                    if (!rows.next()) return Counts()
                    Counts(
                        recordsExamined = rows.getLong("records_examined"),
                        normalized = rows.getLong("normalized"),
                        matched = rows.getLong("matched"),
                        staleOrManual = rows.getLong("stale_or_manual"),
                        conflicts = rows.getLong("conflicts")
                    )
                }
            }
        } catch (_: Exception) {
            Counts()
        }
    }

    fun write(
        databaseDirectory: File,
        sourceVersion: Int,
        targetVersion: Int,
        backupFileName: String?,
        counts: Counts,
        rollbackNeeded: Boolean,
        outcome: String,
        walPresent: Boolean? = null,
        walBytes: Long? = null
    ) {
        runCatching {
            if (!databaseDirectory.exists() && !databaseDirectory.mkdirs()) return@runCatching

            val fields = mutableListOf(
                "timestamp=${Instant.now()}",
                "event=schema_migration",
                "source_schema_version=$sourceVersion",
                "target_schema_version=$targetVersion",
                "records_examined=${counts.recordsExamined}",
                "normalized=${counts.normalized}",
                "matched=${counts.matched}",
                "stale_or_manual=${counts.staleOrManual}",
                "conflicts=${counts.conflicts}",
                "rollback_needed=$rollbackNeeded",
                "outcome=$outcome",
                "backup_file=${backupFileName ?: "none"}"
            )
            if (walPresent != null) fields += "wal_present=$walPresent"
            if (walBytes != null) fields += "wal_bytes=$walBytes"

            File(databaseDirectory, FILE_NAME).appendText(fields.joinToString(" ") + "\n")
        }
    }

    private fun tableExists(connection: java.sql.Connection, tableName: String): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?"
        ).use { statement ->
            statement.setString(1, tableName)
            statement.executeQuery().use { rows -> rows.next() }
        }
}