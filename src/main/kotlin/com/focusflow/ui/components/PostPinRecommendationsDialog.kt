package com.focusflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.focusflow.ui.theme.*

@Composable
fun PostPinRecommendationsDialog(
    onOpenExtension: () -> Unit,
    onOpenNetworkShield: () -> Unit,
    onOpenReleases: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Surface,
            modifier = Modifier.width(560.dp).heightIn(max = 560.dp)
        ) {
            Column(
                modifier = Modifier.padding(30.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier.size(58.dp).clip(RoundedCornerShape(16.dp))
                        .background(Purple80.copy(alpha = 0.15f))
                        .align(Alignment.CenterHorizontally),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Security, null, tint = Purple80, modifier = Modifier.size(30.dp))
                }
                Text(
                    "Your focus setup is ready",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = OnSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Here are optional ways to extend FocusFlow. Nothing changes unless you choose it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                RecommendationCard(
                    icon = Icons.Default.Extension,
                    title = "Optional: URL-level browser protection",
                    body = "Use the Edge extension when window-title matching is not enough.",
                    actionLabel = "View Edge extension",
                    onAction = onOpenExtension
                )
                RecommendationCard(
                    icon = Icons.Default.Shield,
                    title = "Optional: Network Shield",
                    body = "Keep an app open while cutting its network access. Rules require administrator access.",
                    actionLabel = "Open Network Shield",
                    onAction = onOpenNetworkShield
                )
                RecommendationCard(
                    icon = Icons.Default.Language,
                    title = "For Windows: uninstall protection",
                    body = "Direct EXE/MSI builds add a FocusFlow gate to normal uninstall paths. Windows still controls Store/MSIX removal.",
                    actionLabel = "View EXE/MSI releases",
                    onAction = onOpenReleases
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Continue to FocusFlow", color = Purple80, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun RecommendationCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Surface2).padding(14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                .background(Purple80.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Purple80, modifier = Modifier.size(19.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = OnSurface, fontWeight = FontWeight.SemiBold)
            Text(body, color = OnSurface2, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(actionLabel, color = Purple80, fontSize = 12.sp)
            }
        }
    }
}