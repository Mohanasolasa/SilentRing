package com.example.autosilent.ui.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.autosilent.domain.model.SoundMode
import com.example.autosilent.ui.home.HomeStatusFormatter
import com.example.autosilent.ui.home.HomeUiState
import com.example.autosilent.ui.util.DateTimeUiFormatter
import java.time.ZoneId

@Composable
fun ActiveStateCard(
    uiState: HomeUiState,
    modifier: Modifier = Modifier
) {
    val isSilent = uiState.currentSoundMode == SoundMode.SILENT
    val hasOverridden = uiState.overriddenSchedules.isNotEmpty()

    val cardColor = if (isSilent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val contentColor = if (isSilent) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor,
            contentColor = contentColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = if (isSilent) Icons.Default.NotificationsOff else Icons.Default.NotificationsActive,
                    contentDescription = if (isSilent) "Silent Mode Active" else "Ring Mode Active",
                    modifier = Modifier.size(36.dp),
                    tint = if (isSilent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column {
                    Text(
                        text = HomeStatusFormatter.formatPrimaryHeading(uiState.currentSoundMode),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = HomeStatusFormatter.formatStatusReason(
                            currentRingerMode = uiState.currentSoundMode,
                            activeSchedules = uiState.activeSchedules,
                            overriddenSchedules = uiState.overriddenSchedules,
                            totalScheduleCount = uiState.schedules.size,
                            enabledScheduleCount = uiState.schedules.count { it.enabled }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            if (hasOverridden) {
                Spacer(modifier = Modifier.height(8.dp))
                val overriddenNames = uiState.overriddenSchedules.joinToString { it.scheduleName }
                val overrideStatus = if (isSilent) "Silence maintained by other schedule" else "Ringing"
                Text(
                    text = "Manual override active for: $overriddenNames ($overrideStatus)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            HomeStatusFormatter.formatNextEventDescription(uiState.nextBoundary, uiState.currentSoundMode)?.let { nextText ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = nextText,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}

