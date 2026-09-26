package com.focusflow.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.focusflow.enforcement.AppCatalogState
import com.focusflow.enforcement.AppDescriptor
import com.focusflow.ui.theme.Error
import com.focusflow.ui.theme.OnSurface2
import com.focusflow.ui.theme.Purple80
import com.focusflow.ui.theme.Surface2
import com.focusflow.ui.theme.Surface3

/**
 * Shared, selection-only application picker foundation.
 *
 * Search, source filters, icons, stale references, and refresh controls are
 * intentionally supplied by later tracker items. Callers select by the stable
 * catalog key rather than persisting a display name.
 */
@Composable
fun LinuxAppPicker(
    state: AppCatalogState,
    selectedAppKeys: Set<String>,
    onSelectionChanged: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    multiSelect: Boolean = true,
    enabled: Boolean = true,
    emptyMessage: String = "No applications available"
) {
    Column(modifier = modifier) {
        when {
            state.isRefreshing && state.apps.isEmpty() -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Purple80,
                        strokeWidth = 2.dp
                    )
                }
            }

            state.apps.isEmpty() -> {
                Text(
                    text = emptyMessage,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    color = OnSurface2,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        items = state.apps,
                        key = { it.catalogKey() }
                    ) { app ->
                        val key = app.catalogKey()
                        val selected = key in selectedAppKeys
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    enabled = enabled,
                                    role = if (multiSelect) Role.Checkbox else Role.RadioButton,
                                    onClick = {
                                        if (multiSelect) {
                                            onSelectionChanged(
                                                if (selected) selectedAppKeys - key
                                                else selectedAppKeys + key
                                            )
                                        } else {
                                            onSelectionChanged(setOf(key))
                                        }
                                    }
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) Surface3 else Surface2
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (multiSelect) {
                                    Checkbox(
                                        checked = selected,
                                        onCheckedChange = null,
                                        enabled = enabled
                                    )
                                } else {
                                    RadioButton(
                                        selected = selected,
                                        onClick = null,
                                        enabled = enabled
                                    )
                                }
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
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.isPartial || state.errorMessage != null) {
            Text(
                text = state.errorMessage
                    ?: "Some applications could not be loaded",
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                color = Error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun AppDescriptor.catalogKey(): String =
    desktopId ?: packageId ?: processName.lowercase()