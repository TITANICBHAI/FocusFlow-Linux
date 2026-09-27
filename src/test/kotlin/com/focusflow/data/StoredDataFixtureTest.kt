package com.focusflow.data

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoredDataFixtureTest {
    private val fixtureRoot = "migrations"

    @Test
    fun `migration fixtures materialize without touching the user database`() {
        val cases = listOf(
            "old-v1" to listOf("schema-v1.sql"),
            "fresh-v8" to listOf("schema-v8.sql", "fresh.sql"),
            "malformed-v8" to listOf("schema-v8.sql", "malformed.sql"),
            "stale-v8" to listOf("schema-v8.sql", "stale.sql"),
            "windows-shaped-v8" to listOf("schema-v8.sql", "windows-shaped.sql"),
            "duplicate-v0" to listOf("duplicate-v0.sql")
        )

        cases.forEach { (name, resources) ->
            val dbPath = Files.createTempFile("focusflow-$name-", ".db")
            try {
                DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
                    resources.forEach { resource ->
                        executeScript(connection, "$fixtureRoot/$resource")
                    }

                    val version = connection.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA user_version").use { result ->
                            assertTrue(result.next())
                            result.getInt(1)
                        }
                    }
                    val expectedVersion = if (name == "old-v1") 1 else if (name == "duplicate-v0") 0 else 8
                    assertEquals(expectedVersion, version, "$name schema version")

                    val tables = connection.createStatement().use { statement ->
                        statement.executeQuery(
                            "SELECT name FROM sqlite_master WHERE type = 'table'"
                        ).use { result ->
                            buildSet {
                                while (result.next()) add(result.getString(1))
                            }
                        }
                    }
                    assertTrue("settings" in tables, "$name must contain settings")
                }
            } finally {
                Files.deleteIfExists(dbPath)
            }
        }
    }

    @Test
    fun `fixture scenarios retain representative migration hazards`() {
        assertFixtureContains("malformed-v8", listOf("schema-v8.sql", "malformed.sql")) {
            queryString("SELECT focus_blocked_apps FROM tasks WHERE id = 'malformed-task'") == "firefox.exe,, ,bad"
        }
        assertFixtureContains("stale-v8", listOf("schema-v8.sql", "stale.sql")) {
            queryString("SELECT process_name FROM block_rules WHERE id = 'stale-rule'") == "removed-app.exe"
        }
        assertFixtureContains("windows-shaped-v8", listOf("schema-v8.sql", "windows-shaped.sql")) {
            queryString("SELECT value FROM settings WHERE key = 'vpn_custom_processes'") == "Discord.exe,Spotify.exe"
        }
        assertFixtureContains("duplicate-v0", listOf("duplicate-v0.sql")) {
            queryLong("SELECT COUNT(*) FROM block_rules") == 2L &&
                queryString("SELECT value FROM settings WHERE key = 'unknown_future_key'") == "preserve-me"
        }
    }

    private fun assertFixtureContains(
        name: String,
        resources: List<String>,
        assertion: Connection.() -> Boolean
    ) {
        val dbPath = Files.createTempFile("focusflow-$name-", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
                resources.forEach { executeScript(connection, "$fixtureRoot/$it") }
                assertTrue(connection.assertion(), "$name representative data")
            }
        } finally {
            Files.deleteIfExists(dbPath)
        }
    }

    private fun Connection.queryString(sql: String): String? =
        createStatement().use { it.executeQuery(sql).use { result -> if (result.next()) result.getString(1) else null } }

    private fun Connection.queryLong(sql: String): Long =
        createStatement().use { it.executeQuery(sql).use { result -> if (result.next()) result.getLong(1) else 0L } }

    private fun executeScript(connection: Connection, resource: String) {
        val script = javaClass.classLoader.getResource(resource)?.readText()
            ?: error("Missing migration fixture resource: $resource")
        script.split(';')
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
            }
    }
}