package com.focusflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.focusflow.i18n.LocalizationManager
import com.focusflow.services.ReviewPromptService
import com.focusflow.ui.theme.*

@Composable
fun ReviewPromptDialog() {
    val s = LocalizationManager.strings
    AlertDialog(
        onDismissRequest = { ReviewPromptService.onDismiss() },
        containerColor = Surface2,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                        .background(Purple80.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Star, null, tint = Purple80, modifier = Modifier.size(20.dp))
                }
                Text(s.reviewTitle, color = OnSurface, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Text(s.reviewBody, color = OnSurface2, style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            Button(
                onClick = { ReviewPromptService.onRateNow() },
                colors = ButtonDefaults.buttonColors(containerColor = Purple80)
            ) {
                Text(s.reviewRateMsStore)
            }
        },
        dismissButton = {
            TextButton(onClick = { ReviewPromptService.onDecline() }) {
                Text(s.reviewNoThanks, color = OnSurface2)
            }
        }
    )
}