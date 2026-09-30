package com.focusflow.data

import java.sql.Connection

/**
 * Adds process-optional canonical references and their separate runtime and
 * launch definitions. Existing source rows and v9/v10 sidecar rows are retained
 * verbatim; the old sidecar ID becomes the internal reference ID on backfill.
 */
internal object StoredDataMigrationV11 {
    fun apply(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS canonical_app_references (
                    reference_id TEXT PRIMARY KEY,
                    owner_type TEXT NOT NULL,
                    owner_id TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    stable_app_id TEXT,
                    display_name TEXT,
                    legacy_process_name TEXT,
                    primary_process_name TEXT,
                    process_aliases TEXT NOT NULL DEFAULT '',
                    source TEXT NOT NULL DEFAULT 'legacy',
                    resolution_status TEXT NOT NULL DEFAULT 'unresolved',
                    conflict_status TEXT NOT NULL DEFAULT 'none',
                    conflict_group_key TEXT,
                    last_resolved_at_ms INTEGER
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS app_runtime_definitions (
                    id TEXT PRIMARY KEY,
                    reference_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    selector_schema_version INTEGER NOT NULL,
                    selector_serialization TEXT NOT NULL,
                    execution_environment TEXT NOT NULL,
                    runtime_family TEXT,
                    authorization_purpose TEXT NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
                    FOREIGN KEY (reference_id)
                        REFERENCES canonical_app_references(reference_id)
                        ON DELETE CASCADE
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS app_launch_definitions (
                    id TEXT PRIMARY KEY,
                    reference_id TEXT NOT NULL UNIQUE,
                    type TEXT NOT NULL,
                    executable_path TEXT,
                    executable TEXT,
                    working_directory TEXT,
                    desktop_file_path TEXT,
                    desktop_id TEXT,
                    package_id TEXT,
                    dbus_activatable INTEGER NOT NULL DEFAULT 0
                        CHECK (dbus_activatable IN (0, 1)),
                    handoff_policy TEXT NOT NULL DEFAULT 'none',
                    FOREIGN KEY (reference_id)
                        REFERENCES canonical_app_references(reference_id)
                        ON DELETE CASCADE
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS app_launch_arguments (
                    launch_definition_id TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    argument TEXT NOT NULL,
                    PRIMARY KEY (launch_definition_id, position),
                    FOREIGN KEY (launch_definition_id)
                        REFERENCES app_launch_definitions(id)
                        ON DELETE CASCADE
                )
                """.trimIndent()
            )

            // Deliberately create indexes after tables so a malformed pre-existing
            // schema fails inside the caller's migration transaction and rolls back.
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_canonical_app_refs_owner " +
                    "ON canonical_app_references(owner_type, owner_id, position)"
            )
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_runtime_definitions_reference " +
                    "ON app_runtime_definitions(reference_id)"
            )
        }

        // Migrate only metadata already present in the sidecar. In particular,
        // no process name (including generic values such as "java") is promoted
        // into an authorization-bearing runtime definition.
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                INSERT OR IGNORE INTO canonical_app_references (
                    reference_id, owner_type, owner_id, position,
                    stable_app_id, display_name, legacy_process_name,
                    primary_process_name, process_aliases, source,
                    resolution_status, conflict_status, conflict_group_key,
                    last_resolved_at_ms
                )
                SELECT id, owner_type, owner_id, position,
                       stable_app_id, display_name, legacy_process_name,
                       primary_process_name, process_aliases, source,
                       resolution_status, conflict_status, conflict_group_key,
                       last_resolved_at_ms
                FROM app_references
                """.trimIndent()
            )
        }
    }
}