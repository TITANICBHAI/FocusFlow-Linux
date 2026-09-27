package com.focusflow.data

import com.focusflow.ProcessNameNormalizer
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.util.UUID

/**
 * Extends the v9 app-reference sidecar to the remaining assigned process
 * fields. Source rows and setting values are intentionally not rewritten.
 *
 * DATA-21 is a preserve decision: Windows-only settings remain in the
 * existing settings table, while this migration records the key-pattern
 * policy that prevents a later Linux cleanup from deleting them implicitly.
 */
internal object StoredDataMigrationV10 {
    private const val SOURCE_LEGACY = "legacy"
    private const val STATUS_UNRESOLVED = "unresolved"

    private const val OWNER_NETWORK_RULE = "network_cutoff_rule"
    private const val OWNER_CUSTOM_PRESET = "custom_block_preset"
    private const val OWNER_LAUNCHER_PRESET = "focus_launcher_preset"
    private const val OWNER_LAUNCHER_SESSION = "focus_launcher_session"
    private const val OWNER_LAUNCHER_SETTING = "setting:launcher_selected_apps"
    private const val OWNER_VPN_SETTING = "setting:vpn_custom_processes"

    private data class Candidate(
        val ownerType: String,
        val ownerId: String,
        val position: Int,
        val legacyProcessName: String,
        val primaryProcessName: String,
        val displayName: String? = null
    ) {
        val referenceId: String
            get() {
                val identity = listOf(ownerType, ownerId, position).joinToString("|")
                return UUID.nameUUIDFromBytes(
                    identity.toByteArray(StandardCharsets.UTF_8)
                ).toString()
            }
    }

    fun apply(connection: Connection) {
        val candidates = buildList {
            addNetworkRuleCandidates(connection)
            addPresetCandidates(
                connection = connection,
                table = "custom_block_presets",
                ownerType = OWNER_CUSTOM_PRESET
            )
            addPresetCandidates(
                connection = connection,
                table = "focus_launcher_presets",
                ownerType = OWNER_LAUNCHER_PRESET
            )
            addLauncherSessionCandidates(connection)
            addSettingCandidates(
                connection = connection,
                key = "launcher_selected_apps",
                ownerType = OWNER_LAUNCHER_SETTING
            )
            addSettingCandidates(
                connection = connection,
                key = "vpn_custom_processes",
                ownerType = OWNER_VPN_SETTING
            )
        }

        insertCandidates(connection, candidates)
        recordWindowsOnlySettingDecisions(connection)
    }

    private fun MutableList<Candidate>.addNetworkRuleCandidates(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT id, target_process, target_display_name
                FROM network_cutoff_rules
                ORDER BY rowid
                """.trimIndent()
            ).use { rows ->
                while (rows.next()) {
                    addSingle(
                        ownerType = OWNER_NETWORK_RULE,
                        ownerId = rows.getString("id"),
                        position = 0,
                        rawProcessName = rows.getString("target_process"),
                        displayName = rows.getString("target_display_name")
                    )
                }
            }
        }
    }

    private fun MutableList<Candidate>.addPresetCandidates(
        connection: Connection,
        table: String,
        ownerType: String
    ) {
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT id, process_names
                FROM $table
                ORDER BY rowid
                """.trimIndent()
            ).use { rows ->
                while (rows.next()) {
                    addProcessList(
                        ownerType = ownerType,
                        ownerId = rows.getString("id"),
                        rawProcessList = rows.getString("process_names")
                    )
                }
            }
        }
    }

    private fun MutableList<Candidate>.addLauncherSessionCandidates(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT session_id, position, process_name, display_name
                FROM focus_launcher_session_apps
                ORDER BY session_id, position
                """.trimIndent()
            ).use { rows ->
                while (rows.next()) {
                    addSingle(
                        ownerType = OWNER_LAUNCHER_SESSION,
                        ownerId = "session:${rows.getInt("session_id")}",
                        position = rows.getInt("position"),
                        rawProcessName = rows.getString("process_name"),
                        displayName = rows.getString("display_name")
                    )
                }
            }
        }
    }

    private fun MutableList<Candidate>.addSettingCandidates(
        connection: Connection,
        key: String,
        ownerType: String
    ) {
        var position = 0
        connection.prepareStatement(
            "SELECT value FROM settings WHERE key = ? ORDER BY rowid"
        ).use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    rows.getString("value").orEmpty().split(',').forEach { rawValue ->
                        val legacyValue = rawValue.trim()
                        if (legacyValue.isNotEmpty()) {
                            val normalized = ProcessNameNormalizer.normalizeStored(legacyValue)
                            if (normalized != null) {
                                add(
                                    Candidate(
                                        ownerType = ownerType,
                                        ownerId = key,
                                        position = position,
                                        legacyProcessName = legacyValue,
                                        primaryProcessName = normalized
                                    )
                                )
                            }
                        }
                        position++
                    }
                }
            }
        }
    }

    private fun MutableList<Candidate>.addSingle(
        ownerType: String,
        ownerId: String,
        position: Int,
        rawProcessName: String?,
        displayName: String?
    ) {
        val legacyValue = rawProcessName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val normalized = ProcessNameNormalizer.normalizeStored(legacyValue) ?: return
        add(
            Candidate(
                ownerType = ownerType,
                ownerId = ownerId,
                position = position,
                legacyProcessName = legacyValue,
                primaryProcessName = normalized,
                displayName = displayName
            )
        )
    }

    private fun MutableList<Candidate>.addProcessList(
        ownerType: String,
        ownerId: String,
        rawProcessList: String?
    ) {
        rawProcessList.orEmpty().split(',').forEachIndexed { position, rawValue ->
            val legacyValue = rawValue.trim()
            if (legacyValue.isEmpty()) return@forEachIndexed
            val normalized = ProcessNameNormalizer.normalizeStored(legacyValue)
                ?: return@forEachIndexed
            add(
                Candidate(
                    ownerType = ownerType,
                    ownerId = ownerId,
                    position = position,
                    legacyProcessName = legacyValue,
                    primaryProcessName = normalized
                )
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
            ) VALUES (?, ?, ?, ?, ?, NULL, ?, ?, '', ?, ?, NULL, 'none', NULL)
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
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun recordWindowsOnlySettingDecisions(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS setting_migration_decisions (
                    setting_pattern TEXT PRIMARY KEY,
                    classification TEXT NOT NULL,
                    action TEXT NOT NULL,
                    decision_version INTEGER NOT NULL,
                    reason TEXT NOT NULL
                )
                """.trimIndent()
            )
        }

        val decisions = listOf(
            Decision(
                pattern = "start_with_windows",
                classification = "windows_only",
                reason = "Startup is managed by the Windows registry and has no automatic Linux equivalent."
            ),
            Decision(
                pattern = "windows_*",
                classification = "windows_only",
                reason = "Preserve Windows setup and compatibility state until an explicit retirement migration."
            ),
            Decision(
                pattern = "defender_*",
                classification = "windows_only",
                reason = "Windows Defender state cannot be mapped safely to Linux."
            ),
            Decision(
                pattern = "firewall_*",
                classification = "platform_specific",
                reason = "Firewall state must not be interpreted as equivalent across operating systems."
            ),
            Decision(
                pattern = "hosts_*",
                classification = "platform_specific",
                reason = "Hosts-file state is platform-specific and remains preserved until a later policy decision."
            ),
            Decision(
                pattern = "registry_*",
                classification = "windows_only",
                reason = "Registry lockdown state has no Linux equivalent and must not be retired implicitly."
            )
        )

        connection.prepareStatement(
            """
            INSERT OR IGNORE INTO setting_migration_decisions
                (setting_pattern, classification, action, decision_version, reason)
            VALUES (?, ?, 'preserve_legacy', 10, ?)
            """.trimIndent()
        ).use { statement ->
            decisions.forEach { decision ->
                statement.setString(1, decision.pattern)
                statement.setString(2, decision.classification)
                statement.setString(3, decision.reason)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private data class Decision(
        val pattern: String,
        val classification: String,
        val reason: String
    )
}