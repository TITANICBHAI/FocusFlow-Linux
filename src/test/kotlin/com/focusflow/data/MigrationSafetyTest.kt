package com.focusflow.data

import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigrationSafetyTest {
    @Test
    fun `preflight backup contains committed WAL data and passes integrity verification`() {
        val directory = Files.createTempDirectory("focusflow-migration-backup-").toFile()
        val source = File(directory, "focusflow.db")
        try {
            DriverManager.getConnection("jdbc:sqlite:${source.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA journal_mode=WAL")
                    statement.execute("CREATE TABLE records (value TEXT NOT NULL)")
                    statement.execute("INSERT INTO records(value) VALUES ('wal-data')")
                    statement.execute("PRAGMA user_version = 7")
                }

                val result = MigrationPreflightBackup.createVerified(
                    connection = connection,
                    databaseFile = source,
                    sourceVersion = 7,
                    targetVersion = 10
                )
                val backup = File(directory, "migration-backups/${result.fileName}")

                assertTrue(backup.exists())
                DriverManager.getConnection("jdbc:sqlite:${backup.absolutePath}").use { snapshot ->
                    snapshot.createStatement().use { statement ->
                        statement.executeQuery("SELECT value FROM records").use { rows ->
                            assertTrue(rows.next())
                            assertEquals("wal-data", rows.getString(1))
                        }
                    }
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `preflight backup failure is explicit and leaves no partial snapshot`() {
        val directory = Files.createTempDirectory("focusflow-migration-failure-").toFile()
        val source = File(directory, "focusflow.db")
        val blockedParent = File(directory, "not-a-directory")
        blockedParent.writeText("not a directory")
        try {
            DriverManager.getConnection("jdbc:sqlite:${source.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE records (value TEXT NOT NULL)")
                }

                val error = runCatching {
                    MigrationPreflightBackup.createVerified(
                        connection = connection,
                        databaseFile = File(blockedParent, "focusflow.db"),
                        sourceVersion = 7,
                        targetVersion = 10
                    )
                }.exceptionOrNull()

                assertTrue(error is MigrationPreflightBackup.Failed)
                assertFalse(File(blockedParent, "migration-backups").exists())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `migration diagnostics contain only aggregate non-sensitive fields`() {
        val directory = Files.createTempDirectory("focusflow-migration-diagnostics-").toFile()
        try {
            MigrationDiagnostics.write(
                databaseDirectory = directory,
                sourceVersion = 9,
                targetVersion = 10,
                backupFileName = "pre_migration_v9_to_v10_test.db",
                counts = MigrationDiagnostics.Counts(
                    recordsExamined = 12,
                    normalized = 4,
                    matched = 3,
                    staleOrManual = 9,
                    conflicts = 1
                ),
                rollbackNeeded = false,
                outcome = "committed",
                walPresent = true,
                walBytes = 2048
            )

            val summary = File(directory, "migration-summary.log").readText()
            assertTrue("source_schema_version=9" in summary)
            assertTrue("target_schema_version=10" in summary)
            assertTrue("records_examined=12" in summary)
            assertTrue("wal_present=true" in summary)
            assertFalse("focusflow.db" in summary)
            assertFalse("password" in summary.lowercase())
            assertFalse("token" in summary.lowercase())
        } finally {
            directory.deleteRecursively()
        }
    }
}