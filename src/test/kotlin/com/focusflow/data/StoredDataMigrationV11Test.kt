package com.focusflow.data

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoredDataMigrationV11Test {
    @Test
    fun `v11 copies stable IDs and preserves stale ambiguous and generic references`() {
        withSchemaV10 { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO app_references (
                        id, owner_type, owner_id, position, legacy_process_name,
                        stable_app_id, display_name, primary_process_name,
                        process_aliases, source, resolution_status,
                        last_resolved_at_ms, conflict_status, conflict_group_key
                    ) VALUES
                        ('duplicate-a', 'fixture', 'dupes', 0, 'Discord.exe',
                         NULL, 'Discord', 'discord', '', 'legacy', 'ambiguous',
                         NULL, 'duplicate-normalized-process', 'dupe-group'),
                        ('duplicate-b', 'fixture', 'dupes', 1, 'discord',
                         NULL, 'Discord', 'discord', '', 'legacy', 'ambiguous',
                         NULL, 'duplicate-normalized-process', 'dupe-group'),
                        ('stale-ref', 'fixture', 'stale', 0, 'removed-app',
                         NULL, 'Removed app', 'removed-app', '', 'legacy', 'stale',
                         NULL, 'none', NULL),
                        ('generic-java', 'fixture', 'generic', 0, 'java',
                         NULL, 'Java app', 'java', '', 'legacy', 'unresolved',
                         NULL, 'none', NULL)
                    """.trimIndent()
                )
            }

            val legacyBefore = connection.queryStrings(
                """
                SELECT id || '|' || owner_type || '|' || owner_id || '|' || position ||
                       '|' || legacy_process_name || '|' || COALESCE(stable_app_id, '') ||
                       '|' || COALESCE(display_name, '') || '|' || primary_process_name ||
                       '|' || process_aliases || '|' || source || '|' || resolution_status ||
                       '|' || conflict_status || '|' || COALESCE(conflict_group_key, '')
                FROM app_references ORDER BY id
                """.trimIndent()
            )
            val rawSourceBefore = connection.queryString(
                "SELECT process_name FROM block_rules WHERE id = 'source-rule'"
            )

            StoredDataMigrationV11.apply(connection)

            val legacyAfter = connection.queryStrings(
                """
                SELECT id || '|' || owner_type || '|' || owner_id || '|' || position ||
                       '|' || legacy_process_name || '|' || COALESCE(stable_app_id, '') ||
                       '|' || COALESCE(display_name, '') || '|' || primary_process_name ||
                       '|' || process_aliases || '|' || source || '|' || resolution_status ||
                       '|' || conflict_status || '|' || COALESCE(conflict_group_key, '')
                FROM app_references ORDER BY id
                """.trimIndent()
            )
            val canonicalAfter = connection.queryStrings(
                """
                SELECT reference_id || '|' || owner_type || '|' || owner_id || '|' || position ||
                       '|' || legacy_process_name || '|' || COALESCE(stable_app_id, '') ||
                       '|' || COALESCE(display_name, '') || '|' || primary_process_name ||
                       '|' || process_aliases || '|' || source || '|' || resolution_status ||
                       '|' || conflict_status || '|' || COALESCE(conflict_group_key, '')
                FROM canonical_app_references ORDER BY reference_id
                """.trimIndent()
            )

            assertEquals(legacyBefore, legacyAfter)
            assertEquals(rawSourceBefore, connection.queryString(
                "SELECT process_name FROM block_rules WHERE id = 'source-rule'"
            ))
            assertEquals(legacyAfter, canonicalAfter)
            assertEquals(0L, connection.queryLong("SELECT COUNT(*) FROM app_runtime_definitions"))
            assertEquals(
                listOf("ambiguous|duplicate-normalized-process|dupe-group",
                    "ambiguous|duplicate-normalized-process|dupe-group"),
                connection.queryStrings(
                    """
                    SELECT resolution_status || '|' || conflict_status || '|' ||
                           conflict_group_key
                    FROM canonical_app_references
                    WHERE owner_id = 'dupes'
                    ORDER BY position
                    """.trimIndent()
                )
            )
            assertEquals(
                "java|java|unresolved",
                connection.queryString(
                    """
                    SELECT legacy_process_name || '|' || primary_process_name || '|' ||
                           resolution_status
                    FROM canonical_app_references WHERE reference_id = 'generic-java'
                    """.trimIndent()
                )
            )

            StoredDataMigrationV11.apply(connection)
            assertEquals(
                legacyBefore.size.toLong(),
                connection.queryLong("SELECT COUNT(*) FROM canonical_app_references")
            )
        }
    }

    @Test
    fun `failed partial v11 schema change rolls back without changing v10 data`() {
        withSchemaV10 { connection ->
            connection.createStatement().use { statement ->
                // Simulates an interrupted/partially installed schema with an
                // incompatible table name; v11 must fail inside the transaction.
                statement.executeUpdate(
                    "CREATE TABLE app_runtime_definitions (id TEXT PRIMARY KEY)"
                )
            }
            val oldSource = connection.queryString(
                "SELECT process_name FROM block_rules WHERE id = 'source-rule'"
            )
            connection.autoCommit = false
            assertFailsWith<Exception> {
                StoredDataMigrationV11.apply(connection)
            }
            connection.rollback()
            connection.autoCommit = true

            assertEquals(10, connection.queryLong("PRAGMA user_version").toInt())
            assertEquals(oldSource, connection.queryString(
                "SELECT process_name FROM block_rules WHERE id = 'source-rule'"
            ))
            assertEquals(1L, connection.queryLong("SELECT COUNT(*) FROM app_references"))
            assertFalse(connection.tableExists("canonical_app_references"))
            assertFalse(connection.tableExists("app_launch_definitions"))
            assertEquals(
                listOf("id"),
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA table_info(app_runtime_definitions)").use { rows ->
                        buildList {
                            while (rows.next()) add(rows.getString("name"))
                        }
                    }
                }
            )
        }
    }

    private fun withSchemaV10(block: (Connection) -> Unit) {
        val dbPath = Files.createTempFile("focusflow-v11-", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
                executeScript(connection, "migrations/schema-v8.sql")
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        INSERT INTO block_rules
                            (id, process_name, display_name, enabled, block_network)
                        VALUES ('source-rule', 'Discord.exe', 'Discord', 1, 0)
                        """.trimIndent()
                    )
                }
                StoredDataMigrationV9.apply(connection)
                StoredDataMigrationV10.apply(connection)
                connection.createStatement().use {
                    it.executeUpdate("PRAGMA user_version = 10")
                }
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

    private fun Connection.tableExists(tableName: String): Boolean =
        prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?"
        ).use { statement ->
            statement.setString(1, tableName)
            statement.executeQuery().use { rows -> rows.next() }
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