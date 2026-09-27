package com.focusflow.data

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoredDataMigrationV10Test {
    @Test
    fun `v10 preserves network rule fields and patterns`() {
        withSchemaV9 { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO network_cutoff_rules
                        (id, pattern, mode, target_process, target_display_name, enabled)
                    VALUES
                        ('network-domain', 'example.com/path?a=1', 'DOMAIN',
                         'Discord.exe', 'Discord', 1),
                        ('network-keyword', 'focus keyword, untouched', 'KEYWORD',
                         'removed-app.exe', 'Removed app', 0)
                    """.trimIndent()
                )
            }

            StoredDataMigrationV10.apply(connection)

            assertEquals(
                listOf(
                    "network-domain|example.com/path?a=1|DOMAIN|Discord.exe|Discord|1",
                    "network-keyword|focus keyword, untouched|KEYWORD|removed-app.exe|Removed app|0"
                ),
                connection.queryStrings(
                    """
                    SELECT id || '|' || pattern || '|' || mode || '|' ||
                        target_process || '|' || target_display_name || '|' || enabled
                    FROM network_cutoff_rules
                    ORDER BY id
                    """.trimIndent()
                )
            )
            assertEquals(
                listOf("discord", "removed-app.exe"),
                connection.queryStrings(
                    """
                    SELECT primary_process_name FROM app_references
                    WHERE owner_type = 'network_cutoff_rule'
                    ORDER BY owner_id
                    """.trimIndent()
                )
            )
        }
    }

    @Test
    fun `v10 records preset launcher and saved app references without rewriting source data`() {
        withSchemaV9 { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO custom_block_presets
                        (id, name, emoji, process_names, created_at)
                    VALUES ('custom-1', 'Deep work', '🧠',
                            'Discord.exe,removed-app.exe', '2026-09-27T09:00:00')
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    INSERT INTO focus_launcher_presets
                        (id, name, process_names, created_at)
                    VALUES ('launcher-1', 'Writing', 'firefox.exe,manual-tool',
                            '2026-09-27T09:00:00')
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    INSERT INTO focus_launcher_session
                        (id, session_start_ms, pin_hash)
                    VALUES (1, 1000, 'hash-not-logged')
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    INSERT INTO focus_launcher_session_apps
                        (session_id, position, process_name, display_name, exe_path)
                    VALUES
                        (1, 0, 'Discord.exe', 'Discord', 'C:\\\\Discord.exe'),
                        (1, 1, 'removed-app.exe', 'Removed app', NULL)
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    INSERT INTO settings(key, value) VALUES
                        ('launcher_selected_apps', 'Discord.exe,manual-tool')
                    """.trimIndent()
                )
            }

            StoredDataMigrationV10.apply(connection)

            assertEquals(
                "Discord.exe,removed-app.exe",
                connection.queryString(
                    "SELECT process_names FROM custom_block_presets WHERE id = 'custom-1'"
                )
            )
            assertEquals(
                "firefox.exe,manual-tool",
                connection.queryString(
                    "SELECT process_names FROM focus_launcher_presets WHERE id = 'launcher-1'"
                )
            )
            assertEquals(
                "Discord.exe,manual-tool",
                connection.queryString(
                    "SELECT value FROM settings WHERE key = 'launcher_selected_apps'"
                )
            )
            assertEquals(
                2L,
                connection.queryLong(
                    """
                    SELECT COUNT(*) FROM app_references
                    WHERE owner_type = 'custom_block_preset'
                    """.trimIndent()
                )
            )
            assertEquals(
                2L,
                connection.queryLong(
                    """
                    SELECT COUNT(*) FROM app_references
                    WHERE owner_type = 'focus_launcher_session'
                    """.trimIndent()
                )
            )
            assertEquals(
                listOf("discord", "manual-tool"),
                connection.queryStrings(
                    """
                    SELECT primary_process_name FROM app_references
                    WHERE owner_type = 'setting:launcher_selected_apps'
                    ORDER BY position
                    """.trimIndent()
                )
            )
        }
    }

    @Test
    fun `v10 preserves VPN setting and does not force exe suffixes`() {
        withSchemaV9 { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO settings(key, value)
                    VALUES ('vpn_custom_processes',
                            'Discord.exe,legacy-tool.exe,spotify')
                    """.trimIndent()
                )
            }

            StoredDataMigrationV10.apply(connection)

            assertEquals(
                "Discord.exe,legacy-tool.exe,spotify",
                connection.queryString(
                    "SELECT value FROM settings WHERE key = 'vpn_custom_processes'"
                )
            )
            assertEquals(
                listOf("discord", "legacy-tool.exe", "spotify"),
                connection.queryStrings(
                    """
                    SELECT primary_process_name FROM app_references
                    WHERE owner_type = 'setting:vpn_custom_processes'
                    ORDER BY position
                    """.trimIndent()
                )
            )
        }
    }

    @Test
    fun `v10 preserves Windows-only settings and records explicit legacy decisions`() {
        withSchemaV9 { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO settings(key, value) VALUES
                        ('start_with_windows', 'true'),
                        ('windows_setup_complete', 'true'),
                        ('unknown_future_key', 'preserve-me')
                    """.trimIndent()
                )
            }

            StoredDataMigrationV10.apply(connection)

            assertEquals(
                listOf("true", "preserve-me", "true"),
                connection.queryStrings(
                    """
                    SELECT value FROM settings
                    WHERE key IN ('unknown_future_key', 'start_with_windows',
                                  'windows_setup_complete')
                    ORDER BY key
                    """.trimIndent()
                )
            )
            assertEquals(
                listOf(
                    "start_with_windows|windows_only|preserve_legacy",
                    "windows_*|windows_only|preserve_legacy"
                ),
                connection.queryStrings(
                    """
                    SELECT setting_pattern || '|' || classification || '|' || action
                    FROM setting_migration_decisions
                    WHERE setting_pattern IN ('start_with_windows', 'windows_*')
                    ORDER BY setting_pattern
                    """.trimIndent()
                )
            )
        }
    }

    @Test
    fun `v10 is idempotent`() {
        withSchemaV9 { connection ->
            connection.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO settings(key, value) VALUES ('vpn_custom_processes', 'Discord.exe')"
                )
            }

            StoredDataMigrationV10.apply(connection)
            val firstReferences = connection.queryLong("SELECT COUNT(*) FROM app_references")
            val firstDecisions = connection.queryLong(
                "SELECT COUNT(*) FROM setting_migration_decisions"
            )
            StoredDataMigrationV10.apply(connection)

            assertTrue(firstReferences > 0)
            assertEquals(
                firstReferences,
                connection.queryLong("SELECT COUNT(*) FROM app_references")
            )
            assertEquals(
                firstDecisions,
                connection.queryLong("SELECT COUNT(*) FROM setting_migration_decisions")
            )
        }
    }

    private fun withSchemaV9(block: (Connection) -> Unit) {
        val dbPath = Files.createTempFile("focusflow-v10-", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
                executeScript(connection, "migrations/schema-v8.sql")
                StoredDataMigrationV9.apply(connection)
                block(connection)
            }
        } finally {
            Files.deleteIfExists(dbPath)
        }
    }

    private fun Connection.queryString(sql: String): String? =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                if (rows.next()) rows.getString(1) else null
            }
        }

    private fun Connection.queryLong(sql: String): Long =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                if (rows.next()) rows.getLong(1) else 0L
            }
        }

    private fun Connection.queryStrings(sql: String): List<String> =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString(1))
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