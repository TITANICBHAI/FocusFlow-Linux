package com.focusflow.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.focusflow.data.models.CanonicalAppReference
import com.focusflow.enforcement.AppDescriptor
import com.focusflow.enforcement.LaunchCaptureSelection
import com.focusflow.enforcement.LaunchCaptureStatus
import com.focusflow.enforcement.LinuxLaunchCaptureController
import com.focusflow.enforcement.ProcessInstanceKey
import com.focusflow.ui.theme.OnSurface
import com.focusflow.ui.theme.OnSurface2
import com.focusflow.ui.theme.Purple80
import com.focusflow.ui.theme.Surface2
import kotlinx.coroutines.launch

@Composable
fun LinuxLaunchAndDetectDialog(
    app: AppDescriptor,
    reference: CanonicalAppReference,
    onDismiss: () -> Unit,
    onSave: suspend (LaunchCaptureSelection) -> Boolean
) {
    val controller = remember(app.stableCaptureKey(), reference.referenceId) {
        LinuxLaunchCaptureController()
    }
    val capture by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedKey by remember { mutableStateOf<ProcessInstanceKey?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var closeLauncherAfterRuntime by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(controller) {
        onDispose { controller.close() }
    }

    val visibleCandidates = capture.candidates
        .filter { it.isRunning }
        .take(12)
    val selectedCandidate = visibleCandidates.firstOrNull {
        it.processInstanceKey == selectedKey
    }
    val maySave = selectedCandidate != null &&
        capture.status !in setOf(
            LaunchCaptureStatus.CANCELLED,
            LaunchCaptureStatus.HANDOFF_COMPLETE
        ) &&
        !isSaving

    AlertDialog(
        onDismissRequest = {
            controller.cancelCapture()
            onDismiss()
        },
        containerColor = Surface2,
        title = {
            Text("Launch & Detect — ${app.displayName}", color = OnSurface)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This is configuration-time discovery. Observed candidates do not receive Focus Launcher authorization.",
                    color = OnSurface2
                )
                if (capture.status == LaunchCaptureStatus.READY) {
                    Text(
                        "FocusFlow will start this app, capture a process baseline first, and watch for new or changed process instances.",
                        color = OnSurface2
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (
                            capture.status == LaunchCaptureStatus.ARMING ||
                            capture.status == LaunchCaptureStatus.CAPTURING ||
                            capture.status == LaunchCaptureStatus.POST_EXIT_GRACE
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(18.dp).height(18.dp),
                                color = Purple80,
                                strokeWidth = 2.dp
                            )
                        }
                        Text(
                            capture.status.name.lowercase().replace('_', ' '),
                            color = OnSurface
                        )
                    }
                }

                capture.message?.let {
                    Text(it, color = OnSurface2)
                }
                saveError?.let {
                    Text(it, color = OnSurface2)
                }
                if (capture.unknownIdentityObservationCount > 0) {
                    Text(
                        "${capture.unknownIdentityObservationCount} observation(s) had unknown PID/start-time identity and cannot be selected.",
                        color = OnSurface2
                    )
                }

                if (visibleCandidates.isNotEmpty()) {
                    Text("Detected processes", color = OnSurface)
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(
                            visibleCandidates,
                            key = { "launch_candidate_${it.processInstanceKey.pid}_${it.processInstanceKey.processStartTicks}" }
                        ) { candidate ->
                            val selected = candidate.processInstanceKey == selectedKey
                            OutlinedCard(
                                onClick = { selectedKey = candidate.processInstanceKey },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selected,
                                        onClick = { selectedKey = candidate.processInstanceKey }
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(candidate.displayName, color = OnSurface)
                                        Text(
                                            candidate.executablePath
                                                ?: candidate.comm
                                                ?: "Executable path unavailable",
                                            color = OnSurface2
                                        )
                                        Text(
                                            if (candidate.status.name == "ASSOCIATED") {
                                                "Attributed to this application"
                                            } else {
                                                "Candidate only — not authorized"
                                            },
                                            color = OnSurface2
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (
                    capture.launcherProcessKey != null &&
                    (capture.primaryRuntimeInstance != null || selectedCandidate != null)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Checkbox(
                            checked = closeLauncherAfterRuntime,
                            onCheckedChange = { closeLauncherAfterRuntime = it },
                            enabled = !isSaving
                        )
                        Text(
                            "Close the original launcher after saving, only if its identity is still safe.",
                            color = OnSurface2
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (capture.status) {
                LaunchCaptureStatus.READY -> Button(
                    onClick = {
                        saveError = null
                        controller.start(app, reference)
                    }
                ) {
                    Text("Start")
                }
                else -> Button(
                    enabled = maySave,
                    onClick = {
                        val candidate = selectedCandidate ?: return@Button
                        isSaving = true
                        saveError = null
                        scope.launch {
                            val selection = controller.configureCandidate(
                                candidate.processInstanceKey,
                                reference
                            )
                            if (selection == null) {
                                saveError =
                                    "This process could not be safely attributed to the selected application. No selector was saved."
                                isSaving = false
                                return@launch
                            }
                            val saved = runCatching { onSave(selection) }.getOrDefault(false)
                            if (saved) {
                                controller.completeSelection()
                                if (
                                    closeLauncherAfterRuntime &&
                                    !controller.closeLauncherSafely(
                                        selection.candidate.processInstanceKey
                                    )
                                ) {
                                    saveError =
                                        "Runtime configuration was saved; launcher close was skipped because fresh identity checks did not pass."
                                    isSaving = false
                                    return@launch
                                }
                                isSaving = false
                                onDismiss()
                            } else {
                                saveError = "The runtime configuration could not be saved."
                                isSaving = false
                            }
                        }
                    }
                ) {
                    if (isSaving) {
                        LinearProgressIndicator(modifier = Modifier.width(24.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Save runtime")
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSaving,
                onClick = {
                    controller.cancelCapture()
                    onDismiss()
                }
            ) {
                Text("Cancel", color = OnSurface2)
            }
        }
    )
}

private fun AppDescriptor.stableCaptureKey(): String =
    canonicalReference?.stableAppId
        ?: desktopId
        ?: packageId
        ?: exePath
        ?: processName