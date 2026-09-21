package com.focusflow.services

import com.focusflow.data.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordinates the Store review prompt after meaningful user actions.
 *
 * Feedback delivery is intentionally disabled here. The uploaded source
 * contained an embedded webhook value, which must not be copied into the
 * application binary; a future implementation should use workspace-managed
 * secrets or a proper integration.
 */
object ReviewPromptService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _shouldShow = MutableStateFlow(false)
    val shouldShow: StateFlow<Boolean> = _shouldShow.asStateFlow()

    private const val STORE_PRODUCT_ID = "9njn9fprq7t1"
    private const val KEY_DISMISSED = "review_permanently_dismissed"
    private const val KEY_DECLINED = "review_declined_date"
    private const val KEY_LEGACY = "review_prompt_shown"

    val feedbackEnabled: Boolean get() = false

    fun triggerCheck() {
        scope.launch {
            if (shouldShowPrompt()) _shouldShow.value = true
        }
    }

    fun onRateNow() {
        _shouldShow.value = false
        scope.launch { Database.setSetting(KEY_DISMISSED, "true") }
        openStore()
    }

    fun onDecline() {
        _shouldShow.value = false
        scope.launch { Database.setSetting(KEY_DECLINED, java.time.LocalDate.now().toString()) }
    }

    fun onDismiss() = onDecline()

    fun sendFeedback(message: String, onResult: (Boolean) -> Unit = {}) {
        onResult(false)
    }

    private fun shouldShowPrompt(): Boolean {
        val opens = Database.getSetting("app_open_count")?.toIntOrNull() ?: 0
        if (opens < 10) return false
        if (Database.getSetting(KEY_DISMISSED) == "true") return false
        if (Database.getSetting(KEY_LEGACY) == "true") return false

        val declined = Database.getSetting(KEY_DECLINED) ?: return true
        val days = runCatching {
            java.time.temporal.ChronoUnit.DAYS.between(
                java.time.LocalDate.parse(declined),
                java.time.LocalDate.now()
            )
        }.getOrDefault(31L)
        return days >= 30
    }

    private fun openStore() {
        runCatching {
            java.awt.Desktop.getDesktop().browse(
                java.net.URI("ms-windows-store://review/?ProductId=$STORE_PRODUCT_ID")
            )
        }.recoverCatching {
            java.awt.Desktop.getDesktop().browse(
                java.net.URI("https://www.microsoft.com/store/apps/$STORE_PRODUCT_ID")
            )
        }
    }
}