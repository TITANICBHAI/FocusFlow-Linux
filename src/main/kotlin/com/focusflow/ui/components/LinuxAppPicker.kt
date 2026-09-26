package com.focusflow.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.focusflow.enforcement.AppCatalogState
import com.focusflow.enforcement.AppDescriptor
import com.focusflow.enforcement.AppIconExtractor
import com.focusflow.enforcement.AppSource
import com.focusflow.enforcement.InstalledAppsScanner
import com.focusflow.ui.theme.Error
import com.focusflow.ui.theme.OnSurface2
import com.focusflow.ui.theme.Purple80
import com.focusflow.ui.theme.Success
import com.focusflow.ui.theme.Surface2
import com.focusflow.ui.theme.Surface3
import com.focusflow.ui.theme.Warning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Installed/running filter applied to the shared catalog.
 */
enum class AppPickerPresenceFilter(val label: String) {
    ALL("All"),
    INSTALLED("Installed"),
    RUNNING("Running")
}

/**
 * Package/source filter applied to the shared catalog.
 */
enum class AppPickerSourceFilter(val label: String, val source: AppSource? = null) {
    ALL("All sources"),
    NATIVE("Native", AppSource.NATIVE_DESKTOP),
    FLATPAK("Flatpak", AppSource.FLATPAK),
    SNAP("Snap", AppSource.SNAP),
    RUNNING_ONLY("Running-only", AppSource.RUNNING_ONLY),
    MANUAL("Manual", AppSource.MANUAL)
}

/**
 * Collect the shared catalog and perform its first scan away from the UI
 * thread. Multiple screens can call this safely; the catalog coalesces refresh
 * requests while a scan is already in progress.
 */
@Composable
fun rememberInstalledAppCatalogState(enabled: Boolean = true): AppCatalogState {
    val state by InstalledAppCatalog.state.collectAsState()

    LaunchedEffect(enabled) {
        if (enabled && state.apps.isEmpty()) {
            withContext(Dispatchers.IO) {
                InstalledAppCatalog.refresh()
            }
        }
    }

    return state
}

internal enum class AppPickerContentState {
    LOADING,
    PERMISSION_ERROR,
    SCAN_ERROR,
    EMPTY,
    NO_MATCHES,
    CONTENT
}

internal fun appPickerContentState(
    state: AppCatalogState,
    filteredAppCount: Int,
    visibleStaleCount: Int
): AppPickerContentState = when {
    state.isRefreshing && state.apps.isEmpty() && visibleStaleCount == 0 ->
        AppPickerContentState.LOADING
    state.permissionDenied && state.apps.isEmpty() ->
        AppPickerContentState.PERMISSION_ERROR
    state.errorMessage != null && state.apps.isEmpty() ->
        AppPickerContentState.SCAN_ERROR
    filteredAppCount == 0 && visibleStaleCount == 0 && state.apps.isEmpty() ->
        AppPickerContentState.EMPTY
    filteredAppCount == 0 && visibleStaleCount == 0 ->
        AppPickerContentState.NO_MATCHES
    else -> AppPickerContentState.CONTENT
}

internal fun mergeStaleAppSelections(
    selectedAppKeys: Set<String>,
    staleSelections: Map<String, String>,
    catalogKeys: Set<String>
): Map<String, String> = buildMap {
    staleSelections.forEach { (key, label) -> put(key, label) }
    (selectedAppKeys - catalogKeys).forEach { key -> putIfAbsent(key, key) }
}

internal fun filterAppCatalog(
    apps: List<AppDescriptor>,
    query: String,
    presence: AppPickerPresenceFilter,
    source: AppPickerSourceFilter
): List<AppDescriptor> {
    val normalizedQuery = query.trim()
    return apps
        .asSequence()
        .filter { app ->
            when (presence) {
                AppPickerPresenceFilter.ALL -> true
                AppPickerPresenceFilter.INSTALLED ->
                    app.source != AppSource.RUNNING_ONLY && app.source != AppSource.MANUAL
                AppPickerPresenceFilter.RUNNING -> app.isRunning
            }
        }
        .filter { app -> source.source == null || app.source == source.source }
        .filter { app ->
            normalizedQuery.isBlank() ||
                sequenceOf(
                    app.displayName,
                    app.processName,
                    app.desktopId,
                    app.packageId
                ).filterNotNull().any { value ->
                    value.contains(normalizedQuery, ignoreCase = true)
                } ||
                app.processAliases.any { it.contains(normalizedQuery, ignoreCase = true) }
        }
        .distinctBy { it.catalogKey() }
        .toList()
}

/**
 * Shared Linux application picker.
 *
 * Search and filter state is local to this component and survives catalog
 * refreshes. Selection is controlled by the caller, so a refresh cannot clear
 * it. Stale references are supplied as stable-key/display-name pairs and stay
 * visible until the caller explicitly removes them.
 */
@Composable
fun LinuxAppPicker(
    state: AppCatalogState,
    selectedAppKeys: Set<String>,
    onSelectionChanged: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    multiSelect: Boolean = true,
    enabled: Boolean = true,
    emptyMessage: String = "No applications available",
    staleSelections: Map<String, String> = emptyMap(),
    onRefresh: () -> Unit = {},
    allowManualEntry: Boolean = true,
    onManualEntry: (AppDescriptor) -> Unit = {}
) {
    var query by rememberSaveable { mutableStateOf("") }
    var presenceValue by rememberSaveable { mutableStateOf(AppPickerPresenceFilter.ALL.name) }
    var sourceValue by rememberSaveable { mutableStateOf(AppPickerSourceFilter.ALL.name) }
    var manualProcess by rememberSaveable { mutableStateOf("") }
    var manualError by rememberSaveable { mutableStateOf<String?>(null) }
    var manualEntries by remember { mutableStateOf(emptyList<AppDescriptor>()) }

    val presence = enumValueOf<AppPickerPresenceFilter>(presenceValue)
    val source = enumValueOf<AppPickerSourceFilter>(sourceValue)
    val catalogApps = remember(state.apps, manualEntries) {
        (manualEntries + state.apps).distinctBy { it.catalogKey() }
    }
    val filteredApps = filterAppCatalog(catalogApps, query, presence, source)
    val catalogKeys = remember(catalogApps) { catalogApps.map { it.catalogKey() }.toSet() }
    val staleEntries = mergeStaleAppSelections(selectedAppKeys, staleSelections, catalogKeys)
    val visibleStaleEntries = staleEntries
        .filter { (key, label) ->
            presence == AppPickerPresenceFilter.ALL &&
                source == AppPickerSourceFilter.ALL &&
                (query.isBlank() ||
                    key.contains(query.trim(), ignoreCase = true) ||
                    label.contains(query.trim(), ignoreCase = true))
        }

    Column(modifier = modifier) {
        PickerToolbar(
            query = query,
            onQueryChanged = { query = it },
            presence = presence,
            onPresenceChanged = { presenceValue = it.name },
            source = source,
            onSourceChanged = { sourceValue = it.name },
            state = state,
            enabled = enabled,
            onRefresh = onRefresh
        )

        if (allowManualEntry) {
            ManualProcessEntry(
                value = manualProcess,
                error = manualError,
                enabled = enabled,
                onValueChange = {
                    manualProcess = it
                    manualError = null
                },
                onAdd = {
                    val manual = InstalledAppsScanner.createManualProcessEntry(manualProcess)
                    if (manual == null) {
                        manualError = "Enter a process name using letters, numbers, '.', '_' or '-'."
                    } else {
                        manualEntries = (manualEntries + manual)
                            .distinctBy { it.catalogKey() }
                        val key = manual.catalogKey()
                        onSelectionChanged(
                            if (multiSelect) selectedAppKeys + key else setOf(key)
                        )
                        onManualEntry(manual)
                        manualProcess = ""
                        manualError = null
                    }
                }
            )
        }

        when (appPickerContentState(state, filteredApps.size, visibleStaleEntries.size)) {
            AppPickerContentState.LOADING -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .semantics { contentDescription = "Loading applications" },
                        color = Purple80,
                        strokeWidth = 2.dp
                    )
                }
            }

            AppPickerContentState.PERMISSION_ERROR -> {
                PickerStateMessage(
                    title = "Permission required",
                    message = "FocusFlow could not read installed applications. Check application-directory permissions and refresh.",
                    color = Warning
                )
            }

            AppPickerContentState.SCAN_ERROR -> {
                PickerStateMessage(
                    title = "Application scan failed",
                    message = state.errorMessage ?: "Installed applications could not be loaded. Try refreshing.",
                    color = Error
                )
            }

            AppPickerContentState.EMPTY -> {
                Text(
                    text = emptyMessage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = emptyMessage
                        },
                    color = OnSurface2,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            AppPickerContentState.NO_MATCHES -> {
                Text(
                    text = "No matching applications",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = OnSurface2,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            AppPickerContentState.CONTENT -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    visibleStaleEntries.forEach { (key, label) ->
                        item(key = "stale:$key") {
                            StaleAppRow(
                                label = label,
                                selected = key in selectedAppKeys,
                                enabled = enabled,
                                multiSelect = multiSelect,
                                onClick = {
                                    onSelectionChanged(selectedAppKeys - key)
                                }
                            )
                        }
                    }
                    items(
                        items = filteredApps,
                        key = { "app:${it.catalogKey()}" }
                    ) { app ->
                        val key = app.catalogKey()
                        val selected = key in selectedAppKeys
                        CatalogAppRow(
                            app = app,
                            selected = selected,
                            enabled = enabled,
                            multiSelect = multiSelect,
                            onClick = {
                                onSelectionChanged(
                                    if (multiSelect) {
                                        if (selected) selectedAppKeys - key
                                        else selectedAppKeys + key
                                    } else {
                                        setOf(key)
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }

        if (state.isPartial || state.errorMessage != null) {
            PickerStateMessage(
                title = if (state.permissionDenied) {
                    "Some applications need permission"
                } else {
                    "Some applications could not be loaded"
                },
                message = state.errorMessage
                    ?: "Some applications could not be loaded. Refresh to try again.",
                color = if (state.permissionDenied) Warning else Error
            )
        }
    }
}

@Composable
private fun ManualProcessEntry(
    value: String,
    error: String?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Manual process") },
                placeholder = { Text("e.g. firefox or firefox.exe") },
                isError = error != null,
                supportingText = error?.let { { Text(it, color = Error) } }
            )
            TextButton(
                onClick = onAdd,
                enabled = enabled && value.isNotBlank()
            ) {
                Text("Add")
            }
        }
    }
}

@Composable
private fun PickerStateMessage(
    title: String,
    message: String,
    color: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "$title. $message"
            },
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(title, color = color, style = MaterialTheme.typography.titleSmall)
        Text(message, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PickerToolbar(
    query: String,
    onQueryChanged: (String) -> Unit,
    presence: AppPickerPresenceFilter,
    onPresenceChanged: (AppPickerPresenceFilter) -> Unit,
    source: AppPickerSourceFilter,
    onSourceChanged: (AppPickerSourceFilter) -> Unit,
    state: AppCatalogState,
    enabled: Boolean,
    onRefresh: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                singleLine = true,
                placeholder = { Text("Search applications") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search applications")
                }
            )
            IconButton(
                onClick = onRefresh,
                enabled = enabled && !state.isRefreshing
            ) {
                if (state.isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Purple80,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh applications")
                }
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(AppPickerPresenceFilter.entries, key = { "presence:${it.name}" }) { filter ->
                FilterChip(
                    selected = presence == filter,
                    onClick = { onPresenceChanged(filter) },
                    enabled = enabled,
                    label = { Text(filter.label) }
                )
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(AppPickerSourceFilter.entries, key = { "source:${it.name}" }) { filter ->
                FilterChip(
                    selected = source == filter,
                    onClick = { onSourceChanged(filter) },
                    enabled = enabled,
                    label = { Text(filter.label) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${state.apps.size} applications",
                color = OnSurface2,
                style = MaterialTheme.typography.bodySmall
            )
            state.lastRefreshedAtMs?.let { timestamp ->
                Text(
                    text = "  ·  Updated ${formatRefreshTime(timestamp)}",
                    color = OnSurface2,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (state.isRefreshing && state.apps.isNotEmpty()) {
                Text(
                    text = "  ·  Refreshing…",
                    color = Purple80,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun CatalogAppRow(
    app: AppDescriptor,
    selected: Boolean,
    enabled: Boolean,
    multiSelect: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                role = if (multiSelect) Role.Checkbox else Role.RadioButton,
                onClick = onClick
            )
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) Purple80 else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Surface3 else Surface2
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CatalogAppIcon(app)
            Spacer(modifier = Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.displayName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = app.processName,
                    color = OnSurface2,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SourceBadge(app.source)
                    if (app.isRunning) {
                        Surface(
                            color = Success.copy(alpha = 0.18f),
                            contentColor = Success,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.size(6.dp).background(Success, CircleShape)
                                )
                                Spacer(modifier = Modifier.size(4.dp))
                                Text("Running", fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
            if (multiSelect) {
                Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
            } else {
                RadioButton(selected = selected, onClick = null, enabled = enabled)
            }
        }
    }
}

@Composable
private fun StaleAppRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    multiSelect: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                role = if (multiSelect) Role.Checkbox else Role.RadioButton,
                onClick = onClick
            ),
        colors = CardDefaults.cardColors(containerColor = Warning.copy(alpha = 0.12f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(34.dp).background(Warning.copy(alpha = 0.22f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("?", color = Warning, fontSize = 16.sp)
            }
            Spacer(modifier = Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Not currently available",
                    color = Warning,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (multiSelect) {
                Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
            } else {
                RadioButton(selected = selected, onClick = null, enabled = enabled)
            }
        }
    }
}

@Composable
private fun SourceBadge(source: AppSource) {
    Surface(
        color = Surface3,
        contentColor = OnSurface2,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = source.label(),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 10.sp
        )
    }
}

@Composable
private fun CatalogAppIcon(app: AppDescriptor) {
    val fallbackColor = Purple80.copy(alpha = 0.7f)
    val letter = app.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    var iconBitmap by remember(app.catalogKey(), app.exePath, app.iconName) {
        mutableStateOf<ImageBitmap?>(null)
    }

    LaunchedEffect(app.catalogKey(), app.exePath, app.iconName) {
        iconBitmap = withContext(Dispatchers.IO) {
            val executable = app.exePath ?: InstalledAppsScanner.getExePathFor(app.processName)
            executable?.let {
                AppIconExtractor.extractIcon(it, app.processName)
            }
        }
    }

    Box(
        modifier = Modifier
            .size(34.dp)
            .background(
                if (iconBitmap == null) fallbackColor.copy(alpha = 0.2f) else Color.Transparent,
                RoundedCornerShape(9.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap!!,
                contentDescription = app.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(34.dp)
            )
        } else {
            Text(letter, color = fallbackColor, fontWeight = FontWeight.Bold)
        }
    }
}

private fun AppSource.label(): String = when (this) {
    AppSource.NATIVE_DESKTOP -> "Native"
    AppSource.FLATPAK -> "Flatpak"
    AppSource.SNAP -> "Snap"
    AppSource.WINDOWS_REGISTRY -> "Registry"
    AppSource.RUNNING_ONLY -> "Running-only"
    AppSource.MANUAL -> "Manual"
}

private fun formatRefreshTime(timestamp: Long): String =
    DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestamp))

fun AppDescriptor.catalogKey(): String =
    desktopId ?: packageId ?: processName.lowercase()