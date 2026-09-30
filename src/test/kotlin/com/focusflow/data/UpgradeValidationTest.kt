package com.focusflow.data

import com.focusflow.data.models.BlockRule
import com.focusflow.data.models.AppResolutionStatus
import com.focusflow.data.models.AppReferenceSource
import com.focusflow.data.models.LaunchDefinition
import com.focusflow.data.models.RuntimeAuthorizationPurpose
import com.focusflow.data.models.RuntimeDefinition
import com.focusflow.data.models.StoredCanonicalAppReference
import com.focusflow.services.AutoBackupService
import com.focusflow.enforcement.ExecutionEnvironment
import com.focusflow.enforcement.RuntimeFamily
import com.focusflow.enforcement.RuntimeRole
import com.focusflow.enforcement.RuntimeSelector
import com.focusflow.enforcement.SelectorExpression
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
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
    fun `every supported schema version gate upgrades to v11`() {
        // v0 is the deliberately weak pre-schema input. Versions 1–7 are
        // materialized from the real v1 shape; v8 uses the complete legacy
        // fixture and v9/v10 apply their real migration entry points.
        (0..10).forEach { sourceVersion ->
            withDatabaseHome(
                resources = when {
                    sourceVersion == 0 -> listOf("duplicate-v0.sql")
                    sourceVersion >= 8 -> listOf("schema-v8.sql")
                    else -> listOf("schema-v1.sql")
                },
                beforeInit = { connection ->
                    when (sourceVersion) {
                        in 1..7 -> materializeSchemaVersion(connection, sourceVersion)
                        9 -> {
                            StoredDataMigrationV9.apply(connection)
                            connection.createStatement().use {
                                it.executeUpdate("PRAGMA user_version = 9")
                            }
                        }
                        10 -> {
                            StoredDataMigrationV9.apply(connection)
                            StoredDataMigrationV10.apply(connection)
                            connection.createStatement().use {
                                it.executeUpdate("PRAGMA user_version = 10")
                            }
                        }
                    }
                }
            ) { home ->
                assertEquals(11, databaseVersion(home))
                assertTrue(
                    migrationBackups(home).isNotEmpty(),
                    "source version $sourceVersion should create a verified migration backup"
                )
            }
        }

        // The deliberately weak v0 fixture is also a supported upgrade input
        // and must preserve duplicate legacy rows rather than collapsing them.
        withDatabaseHome(resources = listOf("duplicate-v0.sql")) { home ->
            assertEquals(11, databaseVersion(home))
            assertEquals(2, queryLong(home, "SELECT COUNT(*) FROM block_rules"))
            assertEquals(2, queryLong(home, "SELECT COUNT(*) FROM daily_allowances"))
            assertEquals(
                2,
                queryLong(
                    home,
                    "SELECT COUNT(*) FROM app_references WHERE owner_type = 'block_rule'"
                )
            )
        }
    }

    @Test
    fun `canonical references runtime selectors and structured launches round trip`() {
        withDatabaseHome(
            resources = listOf("schema-v8.sql"),
            beforeInit = { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        INSERT INTO block_rules
                            (id, process_name, display_name, enabled, block_network)
                        VALUES
                            ('compat-rule', 'Discord.exe', 'Discord', 1, 0),
                            ('generic-java', 'java', 'Java', 1, 0)
                        """.trimIndent()
                    )
                }
            }
        ) { home ->
            val legacy = Database.getCanonicalAppReferences(
                "block_rule",
                "compat-rule"
            ).single()
            assertEquals(
                Database.getAppReferences("block_rule", "compat-rule").single().id,
                legacy.reference.referenceId
            )
            assertEquals("Discord.exe", legacy.reference.legacyProcessName)

            val generic = Database.getCanonicalAppReferences(
                "block_rule",
                "generic-java"
            ).single()
            assertEquals("java", generic.reference.legacyProcessName)
            assertEquals(AppResolutionStatus.UNRESOLVED, generic.reference.resolutionStatus)
            assertTrue(generic.reference.runtimeDefinitions.isEmpty())

            val created = Database.createCanonicalAppReference(
                ownerType = "manual",
                ownerId = "path-only",
                position = 0,
                displayName = "Path-only tool",
                source = AppReferenceSource.MANUAL,
                resolutionStatus = AppResolutionStatus.UNRESOLVED
            )
            val referenceId = created.reference.referenceId
            assertTrue(referenceId.startsWith("foc-"))
            assertEquals(null, created.reference.stableAppId)
            assertEquals(null, created.reference.legacyProcessName)
            assertTrue(Database.getAppReferences("manual", "path-only").isEmpty())

            val selector = SelectorExpression.All(
                listOf(
                    SelectorExpression.Predicate(
                        RuntimeSelector.ExecutableBasename("java")
                    ),
                    SelectorExpression.Predicate(
                        RuntimeSelector.MainClass("org.example.Tool")
                    )
                )
            )
            val runtime = RuntimeDefinition(
                id = "runtime-path-tool",
                referenceId = referenceId,
                role = RuntimeRole.PRIMARY,
                selector = selector,
                executionEnvironment = ExecutionEnvironment.NATIVE,
                runtimeFamily = RuntimeFamily.JAVA,
                authorizationPurpose = RuntimeAuthorizationPurpose.PRIMARY_RUNTIME
            )
            Database.upsertRuntimeDefinition(runtime)
            assertEquals(listOf(runtime), Database.getRuntimeDefinitions(referenceId))

            val launch = LaunchDefinition(
                id = "launch-path-tool",
                referenceId = referenceId,
                type = "desktop-entry",
                executablePath = "/usr/bin/java",
                executable = "java",
                argv = listOf(
                    "-cp",
                    "/opt/tool/lib/*",
                    "--data-dir=/home/test/World One",
                    "org.example.Tool"
                ),
                workingDirectory = "/home/test/World One",
                desktopFilePath = "/home/test/.local/share/applications/tool.desktop",
                desktopId = "tool.desktop",
                handoffPolicy = "observe-target"
            )
            Database.upsertLaunchDefinition(launch)
            assertEquals(launch, Database.getLaunchDefinition(referenceId))

            val updatedLegacy = legacy.copy(
                reference = legacy.reference.copy(stableAppId = "manual:discord")
            )
            Database.upsertCanonicalAppReference(updatedLegacy)
            assertEquals(
                "Discord.exe",
                queryString(
                    home,
                    "SELECT process_name FROM block_rules WHERE id = 'compat-rule'"
                )
            )
            assertEquals(
                "Discord.exe",
                Database.getAppReferences("block_rule", "compat-rule")
                    .single().legacyProcessName
            )

            val invalid = runtime.copy(
                id = "empty-runtime",
                selector = SelectorExpression.All(emptyList())
            )
            assertFailsWith<IllegalArgumentException> {
                Database.upsertRuntimeDefinition(invalid)
            }

            resetDatabaseConnection()
            DriverManager.getConnection(
                "jdbc:sqlite:${File(home, ".focusflow/focusflow.db").absolutePath}"
            ).use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO app_runtime_definitions (
                        id, reference_id, role, selector_schema_version,
                        selector_serialization, execution_environment,
                        runtime_family, authorization_purpose, enabled
                    ) VALUES ('malformed-runtime', ?, 'PRIMARY', 1, 'not-a-selector',
                              'native', 'java', 'primary_runtime', 1)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, referenceId)
                    statement.executeUpdate()
                }
            }
            Database.init()
            assertFailsWith<IllegalArgumentException> {
                Database.getRuntimeDefinitions(referenceId)
            }

            assertEquals(referenceId, Database.getCanonicalAppReference(referenceId)
                ?.reference?.referenceId)
            assertEquals(
                "launch-path-tool",
                Database.getCanonicalAppReference(referenceId)
                    ?.reference?.launchDefinitionId
            )
            assertTrue(Database.deleteRuntimeDefinition("malformed-runtime"))
            assertTrue(Database.deleteRuntimeDefinition(runtime.id))
            assertTrue(Database.deleteLaunchDefinition(referenceId))
            assertTrue(Database.deleteCanonicalAppReference(referenceId))
            assertEquals(null, Database.getCanonicalAppReference(referenceId))
            assertEquals(
                "Discord.exe",
                Database.getAppReferences("block_rule", "compat-rule")
                    .single().legacyProcessName
            )
        }
    }

    @Test
    fun `v11 migration failure rolls back and leaves verified v10 database readable`() {
        withDatabaseHome(
            resources = listOf("schema-v8.sql"),
            beforeInit = { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        INSERT INTO block_rules
                            (id, process_name, display_name, enabled, block_network)
                        VALUES ('preserved-rule', 'legacy-app', 'Legacy app', 1, 0)
                        """.trimIndent()
                    )
                    StoredDataMigrationV9.apply(connection)
                    StoredDataMigrationV10.apply(connection)
                    statement.executeUpdate("PRAGMA user_version = 10")
                    statement.executeUpdate(
                        "CREATE TABLE app_runtime_definitions (id TEXT PRIMARY KEY)"
                    )
                }
            }
        ) { home ->
            assertEquals(10, databaseVersion(home))
            assertEquals(
                "legacy-app",
                queryString(home, "SELECT process_name FROM block_rules WHERE id = 'preserved-rule'")
            )
            assertEquals(
                0,
                queryLong(
                    home,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' " +
                        "AND name='canonical_app_references'"
                )
            )
            assertTrue(
                File(home, ".focusflow/migration-backups")
                    .listFiles { file -> file.name.endsWith(".db") }
                    ?.isNotEmpty() == true
            )
        }
    }

    @Test
    fun `verified backup failure refuses v11 migration without fresh database recovery`() {
        withDatabaseHome(
            resources = listOf("schema-v8.sql"),
            beforeInit = { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        INSERT INTO block_rules
                            (id, process_name, display_name, enabled, block_network)
                        VALUES ('preserved-rule', 'legacy-app', 'Legacy app', 1, 0)
                        """.trimIndent()
                    )
                }
                StoredDataMigrationV9.apply(connection)
                StoredDataMigrationV10.apply(connection)
                connection.createStatement().use {
                    it.executeUpdate("PRAGMA user_version = 10")
                }
            },
            beforeDatabaseInit = { _, home ->
                File(home, ".focusflow/migration-backups").writeText("not a directory")
            }
        ) { home ->
            assertEquals(10, databaseVersion(home))
            assertEquals(
                "legacy-app",
                queryString(home, "SELECT process_name FROM block_rules WHERE id = 'preserved-rule'")
            )
            assertEquals(
                0,
                queryLong(
                    home,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' " +
                        "AND name='canonical_app_references'"
                )
            )
            assertTrue(File(home, ".focusflow/migration-backups").isFile)
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

            // Simulate an uninstall/reinstall using a consistent user-data
            // snapshot, without carrying the old WAL sidecars forward.
            val reinstallSnapshot = File(home, "focusflow-reinstall.db")
            Database.vacuumInto(reinstallSnapshot.absolutePath)
            resetDatabaseConnection()
            File(home, ".focusflow").deleteRecursively()
            File(home, ".focusflow").mkdirs()
            Files.copy(
                reinstallSnapshot.toPath(),
                File(home, ".focusflow/focusflow.db").toPath()
            )
            Database.init()

            val secondRead = Database.getBlockRules().single { it.id == "linux-rule" }
            val secondReferences = Database.getAppReferences("block_rule", "linux-rule")
            assertEquals(firstRead, secondRead)
            assertEquals(firstReferences, secondReferences)
            assertEquals(11, databaseVersion(home))
        }
    }

    @Test
    fun `backup and restore returns the upgraded database to its saved state`() {
        lateinit var preUpgradeBackup: File
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
            },
            beforeDatabaseInit = { connection, home ->
                preUpgradeBackup = createBackupBeforeUpgrade(connection, home)
            }
        ) { home ->
            assertEquals(8, databaseVersionOfFile(preUpgradeBackup))
            assertEquals(10, databaseVersion(home))

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

            // Restore the v8 backup as an application-upgrade rollback. The
            // restore path reopens the database, so v9/v10 run again.
            assertIs<AutoBackupService.RestoreResult.Success>(
                AutoBackupService.restoreBackup(preUpgradeBackup)
            )

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

    private fun materializeSchemaVersion(connection: Connection, version: Int) {
        connection.createStatement().use { statement ->
            if (version >= 2) {
                statement.execute("ALTER TABLE block_rules ADD COLUMN source TEXT DEFAULT 'manual'")
            }
            if (version >= 3) {
                statement.execute("ALTER TABLE tasks ADD COLUMN focus_blocked_apps TEXT DEFAULT ''")
                statement.execute("ALTER TABLE tasks ADD COLUMN focus_require_pin INTEGER DEFAULT 0")
            }
            if (version >= 4) {
                statement.execute(
                    """
                    CREATE TABLE network_cutoff_rules (
                        id TEXT PRIMARY KEY,
                        pattern TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        target_process TEXT,
                        target_display_name TEXT,
                        enabled INTEGER DEFAULT 1
                    )
                    """.trimIndent()
                )
                statement.execute(
                    "CREATE INDEX idx_net_rules_mode ON network_cutoff_rules(mode)"
                )
            }
            if (version >= 5) {
                statement.execute(
                    """
                    CREATE TABLE custom_block_presets (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        emoji TEXT NOT NULL DEFAULT '🚫',
                        process_names TEXT NOT NULL DEFAULT '',
                        created_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
            if (version >= 6) {
                statement.execute(
                    """
                    CREATE TABLE daily_usage (
                        date TEXT NOT NULL,
                        process_name TEXT NOT NULL,
                        seconds_used INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (date, process_name)
                    )
                    """.trimIndent()
                )
                statement.execute(
                    "CREATE INDEX idx_daily_usage_date ON daily_usage(date)"
                )
            }
            if (version >= 7) {
                statement.execute(
                    """
                    CREATE TABLE focus_launcher_presets (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        process_names TEXT NOT NULL DEFAULT '',
                        created_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
            statement.execute("PRAGMA user_version = $version")
        }
    }

    private fun createBackupBeforeUpgrade(connection: Connection, home: File): File {
        val backupDir = File(home, ".focusflow/backups").also { it.mkdirs() }
        val backup = File(backupDir, "focusflow_upgrade_v8.db")
        val escapedPath = backup.absolutePath.replace("'", "''")
        connection.createStatement().use { statement ->
            statement.execute("VACUUM INTO '$escapedPath'")
        }
        File(backup.parent, "${backup.nameWithoutExtension}.sha256").writeText(sha256(backup))
        return backup
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8_192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun <T> withDatabaseHome(
        resources: List<String>,
        beforeInit: (Connection) -> Unit = {},
        beforeDatabaseInit: ((Connection, File) -> Unit)? = null,
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
                    beforeDatabaseInit?.invoke(connection, home)
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

    private fun queryLong(home: File, sql: String): Long =
        DriverManager.getConnection(
            "jdbc:sqlite:${File(home, ".focusflow/focusflow.db").absolutePath}"
        ).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    if (rows.next()) rows.getLong(1) else 0L
                }
            }
        }

    private fun databaseVersionOfFile(databaseFile: File): Int =
        DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { rows ->
                    assertTrue(rows.next())
                    rows.getInt(1)
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