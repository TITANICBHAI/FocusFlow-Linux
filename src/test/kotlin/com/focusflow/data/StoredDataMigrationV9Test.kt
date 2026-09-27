package com.focusflow.data

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoredDataMigrationV9Test {
    @Test
    fun `v9 preserves source rows and stores canonical unresolved references`() {
        withSchemaV8 { connection ->
            executeScript(connection, "migrations/stale.sql")
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    UPDATE block_rules
                    SET enabled = 0, block_network = 1
                    WHERE id = 'stale-rule'
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    INSERT INTO focus_sessions(
                        id, task_id, task_name, start_time, planned_minutes,
                        actual_minutes, completed, interrupted, notes
                    ) VALUES (
                        'history-1', 'stale-task', 'Historical task',
                        '2026-09-26T10:00:00', 25, 25, 1, 0, 'keep this history'
                    )
                    """.trimIndent()
                )
            }

            val originalRule = connection.queryString(
                "SELECT process_name FROM block_rules WHERE id = 'stale-rule'"
            )
            val originalSchedule = connection.queryString(
                "SELECT process_names FROM block_schedules WHERE id = 'stale-schedule'"
            )
            val originalTask = connection.queryString(
                "SELECT focus_blocked_apps FROM tasks WHERE id = 'stale-task'"
            )

            StoredDataMigrationV9.apply(connection)

            assertEquals(originalRule, connection.queryString(
                "SELECT process_name FROM block_rules WHERE id = 'stale-rule'"
            ))
            assertEquals(originalSchedule, connection.queryString(
                "SELECT process_names FROM block_schedules WHERE id = 'stale-schedule'"
            ))
            assertEquals(originalTask, connection.queryString(
                "SELECT focus_blocked_apps FROM tasks WHERE id = 'stale-task'"
            ))
            assertEquals(
                "0:1",
                connection.queryString(
                    """
                    SELECT enabled || ':' || block_network FROM block_rules
                    WHERE id = 'stale-rule'
                    """.trimIndent()
                )
            )
            assertEquals(
                "Stale schedule|1,2,3|9:0-10:0|1",
                connection.queryString(
                    """
                    SELECT name || '|' || days_of_week || '|' ||
                        start_hour || ':' || start_minute || '-' ||
                        end_hour || ':' || end_minute || '|' || enabled
                    FROM block_schedules
                    WHERE id = 'stale-schedule'
                    """.trimIndent()
                )
            )
            assertEquals(
                1L,
                connection.queryLong("SELECT COUNT(*) FROM focus_sessions WHERE id = 'history-1'")
            )

            assertEquals(
                "removed-app.exe",
                connection.queryString(
                    """
                    SELECT primary_process_name FROM app_references
                    WHERE owner_type = 'block_rule' AND owner_id = 'stale-rule'
                    """.trimIndent()
                )
            )
            assertEquals(
                "unresolved",
                connection.queryString(
                    """
                    SELECT resolution_status FROM app_references
                    WHERE owner_type = 'block_rule' AND owner_id = 'stale-rule'
                    """.trimIndent()
                )
            )
            assertEquals(
                2L,
                connection.queryLong(
                    """
                    SELECT COUNT(*) FROM app_references
                    WHERE owner_type = 'block_schedule' AND owner_id = 'stale-schedule'
                    """.trimIndent()
                )
            )
            assertEquals(
                2L,
                connection.queryLong(
                    """
                    SELECT COUNT(*) FROM app_references
                    WHERE owner_type = 'task_focus_apps' AND owner_id = 'stale-task'
                    """.trimIndent()
                )
            )
        }
    }

    @Test
    fun `v9 normalizes known process values without using stable id as enforcement`() {
        withSchemaV8 { connection ->
            executeScript(connection, "migrations/windows-shaped.sql")

            StoredDataMigrationV9.apply(connection)

            assertEquals(
                "Discord.exe",
                connection.queryString(
                    """
                    SELECT legacy_process_name FROM app_references
                    WHERE owner_type = 'block_rule' AND owner_id = 'windows-rule'
                    """.trimIndent()
                )
            )
            assertEquals(
                "discord",
                connection.queryString(
                    """
                    SELECT primary_process_name FROM app_references
                    WHERE owner_type = 'block_rule' AND owner_id = 'windows-rule'
                    """.trimIndent()
                )
            )
            assertEquals(
                0L,
                connection.queryLong(
                    """
                    SELECT COUNT(*) FROM app_references
                    WHERE owner_type = 'block_rule' AND owner_id = 'windows-rule'
                      AND stable_app_id IS NOT NULL
                    """.trimIndent()
                )
            )
            assertEquals(
                "spotify",
                connection.queryString(
                    """
                    SELECT primary_process_name FROM app_references
                    WHERE owner_type = 'daily_allowance'
                    """.trimIndent()
                )
            )
        }
    }

    @Test
    fun `v9 marks normalized allowance conflicts without choosing a value`() {
        withSchemaV8 { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DROP TABLE daily_allowances")
                statement.executeUpdate(
                    """
                    CREATE TABLE daily_allowances (
                        process_name TEXT,
                        display_name TEXT,
                        allowance_minutes INTEGER
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    INSERT INTO daily_allowances VALUES
                    ('Spotify.exe', 'Spotify', 30),
                    ('spotify', 'Spotify changed', 45)
                    """.trimIndent()
                )
            }

            StoredDataMigrationV9.apply(connection)

            assertEquals(
                2L,
                connection.queryLong(
                    """
                    SELECT COUNT(*) FROM app_references
                    WHERE owner_type = 'daily_allowance'
                      AND conflict_status = 'allowance-conflict'
                    """.trimIndent()
                )
            )
            assertEquals(
                1L,
                connection.queryLong(
                    """
                    SELECT COUNT(DISTINCT primary_process_name) FROM app_references
                    WHERE owner_type = 'daily_allowance'
                    """.trimIndent()
                )
            )
            assertEquals(
                setOf(30, 45),
                connection.queryIntSet(
                    """
                    SELECT allowance_minutes FROM daily_allowances
                    """.trimIndent()
                )
            )
        }
    }

    @Test
    fun `v9 is retryable and idempotent`() {
        withSchemaV8 { connection ->
            executeScript(connection, "migrations/windows-shaped.sql")

            StoredDataMigrationV9.apply(connection)
            val firstCount = connection.queryLong("SELECT COUNT(*) FROM app_references")
            StoredDataMigrationV9.apply(connection)
            val secondCount = connection.queryLong("SELECT COUNT(*) FROM app_references")

            assertTrue(firstCount > 0)
            assertEquals(firstCount, secondCount)
        }
    }

    private fun withSchemaV8(block: (Connection) -> Unit) {
        val dbPath = Files.createTempFile("focusflow-v9-", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
                executeScript(connection, "migrations/schema-v8.sql")
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

    private fun Connection.queryIntSet(sql: String): Set<Int> =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                buildSet {
                    while (rows.next()) add(rows.getInt(1))
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