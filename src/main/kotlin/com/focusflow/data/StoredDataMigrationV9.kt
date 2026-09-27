package com.focusflow.data

import com.focusflow.ProcessNameNormalizer
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.util.UUID

/**
 * Schema/data migration for the first stable app-reference storage layer.
 *
 * The migration is deliberately independent of the live app catalog. Existing
 * data can be opened offline, so process-only records start as legacy/
 * unresolved references. The original source columns are not rewritten:
 * primary_process_name is the canonical enforcement value, while
 * legacy_process_name preserves what the user database contained.
 */
internal object StoredDataMigrationV9 {
    private const val SOURCE_LEGACY = "legacy"
    private const val STATUS_UNRESOLVED = "unresolved"
    private const val CONFLICT_NONE = "none"
    private const val CONFLICT_DUPLICATE = "duplicate-normalized-process"
    private const val CONFLICT_ALLOWANCE = "allowance-conflict"

    private const val OWNER_BLOCK_RULE = "block_rule"
    private const val OWNER_SCHEDULE = "block_schedule"
    private const val OWNER_DAILY_ALLOWANCE = "daily_allowance"
    private const val OWNER_TASK_FOCUS_APPS = "task_focus_apps"

    private data class Candidate(
        val referenceId: String,
        val ownerType: String,
        val ownerId: String,
        val position: Int,
        val legacyProcessName: String,
        val primaryProcessName: String,
        val displayName: String?,
        val allowanceMinutes: Int? = null,
        val conflictStatus: String = CONFLICT_NONE,
        val conflictGroupKey: String? = null
    )

    /**
     * Applies v9 inside the caller's existing transaction. It intentionally
     * does not commit or change PRAGMA user_version.
     */
    fun apply(connection: Connection) {
        createSchema(connection)

        val candidates = buildList {
            addBlockRuleCandidates(connection)
            addScheduleCandidates(connection)
            addAllowanceCandidates(connection)
            addTaskCandidates(connection)
        }.let(::classifyConflicts)

        insertCandidates(connection, candidates)
    }

    private fun createSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS app_references (
                    id TEXT PRIMARY KEY,
                    owner_type TEXT NOT NULL,
                    owner_id TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    legacy_process_name TEXT NOT NULL,
                    stable_app_id TEXT,
                    display_name TEXT,
                    primary_process_name TEXT NOT NULL,
                    process_aliases TEXT NOT NULL DEFAULT '',
                    source TEXT NOT NULL DEFAULT 'legacy',
                    resolution_status TEXT NOT NULL DEFAULT 'unresolved',
                    last_resolved_at_ms INTEGER,
                    conflict_status TEXT NOT NULL DEFAULT 'none',
                    conflict_group_key TEXT,
                    UNIQUE (owner_type, owner_id, position)
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_app_refs_owner " +
                    "ON app_references(owner_type, owner_id, position)"
            )
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_app_refs_process " +
                    "ON app_references(primary_process_name)"
            )
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_app_refs_stable_id " +
                    "ON app_references(stable_app_id)"
            )
        }
    }

    private fun MutableList<Candidate>.addBlockRuleCandidates(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT id, process_name, display_name
                FROM block_rules
                ORDER BY rowid
                """.trimIndent()
            ).use { rows ->
                while (rows.next()) {
                    addSingle(
                        ownerType = OWNER_BLOCK_RULE,
                        ownerId = rows.getString("id"),
                        position = 0,
                        rawProcessName = rows.getString("process_name"),
                        displayName = rows.getString("display_name")
                    )?.let(::add)
                }
            }
        }
    }

    private fun MutableList<Candidate>.addScheduleCandidates(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT id, process_names
                FROM block_schedules
                ORDER BY rowid
                """.trimIndent()
            ).use { rows ->
                while (rows.next()) {
                    addList(
                        ownerType = OWNER_SCHEDULE,
                        ownerId = rows.getString("id"),
                        rawProcessList = rows.getString("process_names")
                    )
                }
            }
        }
    }

    private fun MutableList<Candidate>.addAllowanceCandidates(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT rowid, process_name, display_name, allowance_minutes
                FROM daily_allowances
                ORDER BY rowid
                """.trimIndent()
            ).use { rows ->
                while (rows.next()) {
                    addSingle(
                        ownerType = OWNER_DAILY_ALLOWANCE,
                        // daily_allowances has no stable row ID. SQLite rowid
                        // distinguishes legacy duplicate rows without deleting
                        // either record.
                        ownerId = "rowid:${rows.getLong("rowid")}",
                        position = 0,
                        rawProcessName = rows.getString("process_name"),
                        displayName = rows.getString("display_name"),
                        allowanceMinutes = rows.getInt("allowance_minutes")
                    )?.let(::add)
                }
            }
        }
    }

    private fun MutableList<Candidate>.addTaskCandidates(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT id, focus_blocked_apps
                FROM tasks
                ORDER BY rowid
                """.trimIndent()
            ).use { rows ->
                while (rows.next()) {
                    addList(
                        ownerType = OWNER_TASK_FOCUS_APPS,
                        ownerId = rows.getString("id"),
                        rawProcessList = rows.getString("focus_blocked_apps")
                    )
                }
            }
        }
    }

    private fun MutableList<Candidate>.addSingle(
        ownerType: String,
        ownerId: String,
        position: Int,
        rawProcessName: String?,
        displayName: String?,
        allowanceMinutes: Int? = null
    ): Candidate? {
        val legacyValue = rawProcessName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = ProcessNameNormalizer.normalizeStored(legacyValue) ?: return null
        return candidate(
            ownerType = ownerType,
            ownerId = ownerId,
            position = position,
            legacyProcessName = legacyValue,
            primaryProcessName = normalized,
            displayName = displayName,
            allowanceMinutes = allowanceMinutes
        )
    }

    private fun MutableList<Candidate>.addList(
        ownerType: String,
        ownerId: String,
        rawProcessList: String?
    ) {
        rawProcessList.orEmpty().split(',').forEachIndexed { position, rawValue ->
            val legacyValue = rawValue.trim()
            if (legacyValue.isEmpty()) return@forEachIndexed
            val normalized = ProcessNameNormalizer.normalizeStored(legacyValue) ?: return@forEachIndexed
            add(
                candidate(
                    ownerType = ownerType,
                    ownerId = ownerId,
                    position = position,
                    legacyProcessName = legacyValue,
                    primaryProcessName = normalized,
                    displayName = null
                )
            )
        }
    }

    private fun candidate(
        ownerType: String,
        ownerId: String,
        position: Int,
        legacyProcessName: String,
        primaryProcessName: String,
        displayName: String?,
        allowanceMinutes: Int? = null
    ): Candidate {
        val identity = listOf(ownerType, ownerId, position).joinToString("|")
        return Candidate(
            referenceId = UUID.nameUUIDFromBytes(
                identity.toByteArray(StandardCharsets.UTF_8)
            ).toString(),
            ownerType = ownerType,
            ownerId = ownerId,
            position = position,
            legacyProcessName = legacyProcessName,
            primaryProcessName = primaryProcessName,
            displayName = displayName,
            allowanceMinutes = allowanceMinutes
        )
    }

    private fun classifyConflicts(candidates: List<Candidate>): List<Candidate> {
        val grouped = candidates
            .filter { it.ownerType == OWNER_BLOCK_RULE || it.ownerType == OWNER_DAILY_ALLOWANCE }
            .groupBy { it.ownerType to it.primaryProcessName }

        val metadata = grouped
            .filterValues { it.size > 1 }
            .flatMap { (group, values) ->
                val groupKey = "${group.first}|${group.second}"
                val status = if (group.first == OWNER_DAILY_ALLOWANCE &&
                    values.map { it.allowanceMinutes to it.displayName }.distinct().size > 1
                ) {
                    CONFLICT_ALLOWANCE
                } else {
                    CONFLICT_DUPLICATE
                }
                values.map { it.referenceId to (status to groupKey) }
            }
            .toMap()

        return candidates.map { candidate ->
            val conflict = metadata[candidate.referenceId] ?: return@map candidate
            candidate.copy(
                conflictStatus = conflict.first,
                conflictGroupKey = conflict.second
            )
        }
    }

    private fun insertCandidates(connection: Connection, candidates: List<Candidate>) {
        connection.prepareStatement(
            """
            INSERT OR IGNORE INTO app_references (
                id, owner_type, owner_id, position, legacy_process_name,
                stable_app_id, display_name, primary_process_name,
                process_aliases, source, resolution_status, last_resolved_at_ms,
                conflict_status, conflict_group_key
            ) VALUES (?, ?, ?, ?, ?, NULL, ?, ?, '', ?, ?, NULL, ?, ?)
            """.trimIndent()
        ).use { statement ->
            candidates.forEach { candidate ->
                statement.setString(1, candidate.referenceId)
                statement.setString(2, candidate.ownerType)
                statement.setString(3, candidate.ownerId)
                statement.setInt(4, candidate.position)
                statement.setString(5, candidate.legacyProcessName)
                statement.setString(6, candidate.displayName)
                statement.setString(7, candidate.primaryProcessName)
                statement.setString(8, SOURCE_LEGACY)
                statement.setString(9, STATUS_UNRESOLVED)
                statement.setString(10, candidate.conflictStatus)
                statement.setString(11, candidate.conflictGroupKey)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }
}