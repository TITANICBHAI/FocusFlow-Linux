package com.focusflow.data

import com.focusflow.data.models.BlockRule
import com.focusflow.services.AutoBackupService
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runtime upgrade coverage for DATA-35 through DATA-40.
 *
 * These tests use isolated user homes. They do not touch ~/.focusflow and do
 * not require a running desktop session or privileged enforcement services.
 */
class UpgradeValidationTest {
    private val fixtureRoot = "migrations"

    @Test
    fun `every supported schema version gate upgrades to v10`() {
        // v0 is the empty/pre-schema entry point. The remaining versions use
        // the complete v8 fixture with the version marker changed so every
        // Database.migrate branch is exercised through the real init path.
        (0..8).forEach { sourceVersion ->
            withDatabaseHome(
                resources = if (sourceVersion == 0) {
                    emptyList()
                } else {
                    listOf("schema-v8.sql")
                },
                beforeInit = { connection ->
                    if (sourceVersion > 0) {
                        connection.createStatement().use {
                            it.executeUpdate("PRAGMA user_version = $sourceVersion")
                        }
                    }
                }
            ) { home ->
                assertEquals(10, databaseVersion(home))
                assertTrue(
                    migrationBackups(home).isNotEmpty(),
                    "source version $sourceVersion should create a verified migration backup"
                )
            }
        }

        // The deliberately weak v0 fixture is also a supported upgrade input
        // and must preserve duplicate legacy rows rather than collapsing them.
        withDatabaseHome(resources = listOf("duplicate-v0.sql")) { home ->
            assertEquals(10, databaseVersion(home))
            assertEquals(2, Database.getBlockRules().size)
            assertEquals(2, Database.getDailyAllowances().size)
        }
    }

    @Test
    fun `Windows-shaped database opens on Linux without losing source values`() {
        withDatabaseHome(
            resources = listOf("schema-v8.sql", "windows-shaped.sql")
        ) { home ->
            val rule = Database.getBlockRules().single { it.id == "windows-rule" }
            assertEquals("discord", rule.processName)
            assertEquals("Discord.exe", queryString(home, "SELECT process_name FROM block_rules WHERE id = 'windows-rule'"))

            assertEquals("discord,spotify", Database.getSetting("vpn_custom_processes"))
            assertEquals(
                "Discord.exe,Spotify.exe",
                queryString(home, "SELECT value FROM settings WHERE key = 'vpn_custom_processes'")
            )

            val session = Database.getFocusLauncherSession()
            assertNotNull(session)
            assertEquals("discord", session.apps.single().processName)
            assertEquals(
                "Discord.exe",
                queryString(
                    home,
                    "SELECT process_name FROM focus_launcher_session_apps WHERE session_id = 1"
                )
            )
        }
    }

    @Test
    fun `Linux database survives application reinstall with stale references intact`() {
        withDatabaseHome(
            resources = listOf("schema-v8.sql"),
            beforeInit = { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        INSERT INTO block_rules
                            (id, process_name, display_name, enabled, block_network)
                        VALUES ('linux-rule', 'removed-linux-app', 'Removed Linux app', 1, 0)
                        """.trimIndent()
                    )
                }
            }
        ) { home ->
            val firstRead = Database.getBlockRules().single { it.id == "linux-rule" }
            val firstReferences = Database.getAppReferences("block_rule", "linux-rule")
            assertEquals("removed-linux-app", firstRead.processName)
            assertEquals(1, firstReferences.size)
            assertEquals("unresolved", firstReferences.single().resolutionStatus.wireValue)

            resetDatabaseConnection()
            Database.init()

            val secondRead = Database.getBlockRules().single { it.id == "linux-rule" }
            val secondReferences = Database.getAppReferences("block_rule", "linux-rule")
            assertEquals(firstRead, secondRead)
            assertEquals(firstReferences, secondReferences)
            assertEquals(10, databaseVersion(home))
        }
    }

    @Test
    fun `backup and restore returns the upgraded database to its saved state`() {
        withDatabaseHome(
            resources = listOf("schema-v8.sql"),
            beforeInit = { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        INSERT INTO block_rules
                            (id, process_name, display_name, enabled, block_network)
                        VALUES ('before-backup', 'firefox', 'Firefox', 1, 0)
                        """.trimIndent()
                    )
                }
            }
        ) { home ->
            val backup = assertIs<AutoBackupService.BackupResult.Success>(
                AutoBackupService.runBackupNow()
            )
            assertEquals(10, databaseVersion(home))
            assertTrue(AutoBackupService.verifyBackup(backup.file))

            Database.upsertBlockRule(
                BlockRule(
                    id = "after-backup",
                    processName = "new-app",
                    displayName = "New app",
                    enabled = true,
                    blockNetwork = false
                )
            )
            assertTrue(Database.getBlockRules().any { it.id == "after-backup" })

            assertIs<AutoBackupService.RestoreResult.Success>(
                AutoBackupService.restoreBackup(backup.file)
            )
            resetDatabaseConnection()
            Database.init()

            assertTrue(Database.getBlockRules().any { it.id == "before-backup" })
            assertFalse(Database.getBlockRules().any { it.id == "after-backup" })
            assertEquals(10, databaseVersion(home))
        }
    }

    @Test
    fun `migration preserves active schedules allowances tasks sessions VPN and network rules`() {
        withDatabaseHome(
            resources = listOf("schema-v8.sql"),
            beforeInit = ::insertActiveData
        ) { home ->
            assertEquals(10, databaseVersion(home))

            val task = Database.getTasks().single { it.id == "active-task" }
            assertEquals(listOf("discord", "focus-helper"), task.focusBlockedApps)
            assertEquals("active schedule", Database.getBlockSchedules().single().name)
            assertEquals(
                "spotify",
                Database.getDailyAllowances().single().processName
            )
            assertEquals(1, Database.getRecentSessions().count { it.id == "active-session" })
            assertEquals("discord,focus-helper", Database.getSetting("vpn_custom_processes"))

            val networkRule = Database.getNetworkCutoffRules().single { it.id == "active-network" }
            assertEquals("discord", networkRule.targetProcess)
            assertEquals("example.com", networkRule.pattern)

            assertEquals(
                "Discord.exe,focus-helper",
                queryString(home, "SELECT value FROM settings WHERE key = 'vpn_custom_processes'")
            )
            assertEquals(
                "Discord.exe",
                queryString(
                    home,
                    "SELECT target_process FROM network_cutoff_rules WHERE id = 'active-network'"
                )
            )
        }
    }

    private fun insertActiveData(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                INSERT INTO tasks
                    (id, title, created_at, focus_mode, focus_blocked_apps, focus_require_pin)
                VALUES ('active-task', 'Active task', '2026-09-27T08:00:00',
                        1, 'Discord.exe,focus-helper', 1)
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                INSERT INTO focus_sessions
                    (id, task_id, task_name, start_time, planned_minutes, actual_minutes, completed)
                VALUES ('active-session', 'active-task', 'Active task',
                        '2026-09-27T08:05:00', 25, 10, 0)
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                INSERT INTO block_schedules
                    (id, name, days_of_week, start_hour, start_minute,
                     end_hour, end_minute, enabled, process_names)
                VALUES ('active-schedule', 'active schedule', '1,2,3,4,5',
                        9, 0, 17, 0, 1, 'Discord.exe')
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                INSERT INTO daily_allowances
                    (process_name, display_name, allowance_minutes)
                VALUES ('Spotify.exe', 'Spotify', 30)
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                INSERT INTO settings(key, value)
                VALUES ('vpn_custom_processes', 'Discord.exe,focus-helper')
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                INSERT INTO network_cutoff_rules
                    (id, pattern, mode, target_process, target_display_name, enabled)
                VALUES ('active-network', 'example.com', 'DOMAIN',
                        'Discord.exe', 'Discord', 1)
                """.trimIndent()
            )
        }
    }

    private fun <T> withDatabaseHome(
        resources: List<String>,
        beforeInit: (Connection) -> Unit = {},
        block: (File) -> T
    ): T {
        synchronized(Database) {
            resetDatabaseConnection()
            val previousHome = System.getProperty("user.home")
            val home = Files.createTempDirectory("focusflow-upgrade-").toFile()
            try {
                System.setProperty("user.home", home.absolutePath)
                val databaseDirectory = File(home, ".focusflow").also { it.mkdirs() }
                val databaseFile = File(databaseDirectory, "focusflow.db")
                DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}").use { connection ->
                    resources.forEach { executeScript(connection, "$fixtureRoot/$it") }
                    beforeInit(connection)
                }
                Database.init()
                return block(home)
            } finally {
                resetDatabaseConnection()
                if (previousHome == null) {
                    System.clearProperty("user.home")
                } else {
                    System.setProperty("user.home", previousHome)
                }
                home.deleteRecursively()
            }
        }
    }

    private fun resetDatabaseConnection() {
        val field = Database::class.java.getDeclaredField("connection")
        field.isAccessible = true
        val connection = runCatching { field.get(Database) as? Connection }.getOrNull()
        runCatching { connection?.close() }
        field.set(Database, null)
    }

    private fun databaseVersion(home: File): Int =
        DriverManager.getConnection(
            "jdbc:sqlite:${File(home, ".focusflow/focusflow.db").absolutePath}"
        ).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { rows ->
                    assertTrue(rows.next())
                    rows.getInt(1)
                }
            }
        }

    private fun migrationBackups(home: File): List<File> =
        File(home, ".focusflow/migration-backups")
            .listFiles { file -> file.name.endsWith(".db") }
            ?.toList()
            ?: emptyList()

    private fun queryString(home: File, sql: String): String? =
        DriverManager.getConnection(
            "jdbc:sqlite:${File(home, ".focusflow/focusflow.db").absolutePath}"
        ).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    if (rows.next()) rows.getString(1) else null
                }
            }
        }

    private fun executeScript(connection: Connection, resource: String) {
        val script = javaClass.classLoader.getResource(resource)?.readText()
            ?: error("Missing migration fixture resource: $resource")
        script.split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
            }
    }
}