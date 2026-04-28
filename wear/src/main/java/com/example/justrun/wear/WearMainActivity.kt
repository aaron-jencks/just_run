package com.example.justrun.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.justrun.UnitSystem
import com.example.justrun.wear.theme.JustRunWearTheme
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JustRunWearTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val state by WearSyncStore.state.collectAsState()
                    val dailyActivityPermissionsLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { grants ->
                        if (grants.values.all { it }) {
                            WearHealthPassiveMonitor.ensureRegistered(this@WearMainActivity)
                            WearHealthSnapshotStore.syncCurrentSnapshotToPhone(this@WearMainActivity)
                        }
                    }
                    val permissionsLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { grants ->
                        if (grants.values.all { it }) {
                            ContextCompat.startForegroundService(
                                this@WearMainActivity,
                                Intent(this@WearMainActivity, WearHeartRateService::class.java)
                            )
                        }
                    }
                    LaunchedEffect(Unit) {
                        if (!hasDailyActivityPermission(this@WearMainActivity)) {
                            dailyActivityPermissionsLauncher.launch(requiredDailyActivityPermissions())
                        } else {
                            WearHealthPassiveMonitor.ensureRegistered(this@WearMainActivity)
                            WearHealthSnapshotStore.syncCurrentSnapshotToPhone(this@WearMainActivity)
                        }
                        if (!hasHeartRatePermission(this@WearMainActivity)) {
                            permissionsLauncher.launch(requiredHeartRatePermissions())
                        } else {
                            syncHeartRateServiceState()
                        }
                    }
                    LaunchedEffect(state.active, state.heartRateEnabled, state.paused, state.autoPaused) {
                        if (
                            state.active &&
                            state.heartRateEnabled &&
                            !state.paused &&
                            !state.autoPaused &&
                            !hasHeartRatePermission(this@WearMainActivity)
                        ) {
                            permissionsLauncher.launch(requiredHeartRatePermissions())
                        } else {
                            syncHeartRateServiceState()
                        }
                    }
                    WearRunScreen(
                        state = state,
                        onPauseToggle = { sendCommand(if (state.paused || state.autoPaused) PATH_RESUME else PATH_PAUSE) },
                        onStop = { sendCommand(PATH_STOP) },
                        onLap = { sendCommand(PATH_MARK_LAP) }
                    )
                }
            }
        }
    }

    private fun sendCommand(path: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val nodes = runCatching { Tasks.await(Wearable.getNodeClient(applicationContext).connectedNodes) }.getOrDefault(emptyList())
            nodes.forEach { node ->
                runCatching { Tasks.await(Wearable.getMessageClient(applicationContext).sendMessage(node.id, path, ByteArray(0))) }
            }
        }
    }

    private fun syncHeartRateServiceState() {
        val state = WearSyncStore.state.value
        val config = WearHealthSnapshotStore.readMonitoringConfig(this)
        val shouldRun = hasDailyActivityPermission(this) || (
            hasHeartRatePermission(this) &&
                config.heartRateEnabled &&
                (state.active || config.backgroundHeartMonitoringEnabled)
            )
        val serviceIntent = Intent(this, WearHeartRateService::class.java)
        if (shouldRun) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            stopService(serviceIntent)
        }
    }
}

@Composable
private fun WearRunScreen(
    state: WearRunState,
    onPauseToggle: () -> Unit,
    onStop: () -> Unit,
    onLap: () -> Unit
) {
    val isPaused = state.paused || state.autoPaused
    var flashElapsed by remember { mutableStateOf(false) }
    var lastPausedState by remember { mutableStateOf<Boolean?>(null) }
    val elapsedColor by animateColorAsState(
        targetValue = if (flashElapsed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
        label = "elapsed_flash"
    )
    LaunchedEffect(isPaused, state.active) {
        if (!state.active) {
            lastPausedState = null
            flashElapsed = false
            return@LaunchedEffect
        }
        if (lastPausedState != null && lastPausedState != isPaused) {
            flashElapsed = true
            kotlinx.coroutines.delay(700)
            flashElapsed = false
        }
        lastPausedState = isPaused
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            state.goalLabel.ifBlank { "Run" },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
        if (!state.active) {
            Text("No active run", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center)
            Text(
                "Start a run on your phone and it will appear here automatically.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            return
        }

        Text(
            buildElapsedPrimaryLine(state),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            color = elapsedColor
        )
        buildElapsedSecondaryLine(state)?.let { secondaryElapsed ->
            Text(
                secondaryElapsed,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Text(
            buildDistanceLine(state),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            buildPaceHeartRateLine(state),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconControlButton(
                onClick = onPauseToggle,
                symbol = if (isPaused) "\u25b6" else "\u23f8",
                modifier = Modifier.weight(1f)
            )
            IconControlButton(
                onClick = onStop,
                symbol = "\u25a0",
                modifier = Modifier.weight(1f),
                filled = true
            )
        }

        Text(
            "Lap",
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onLap)
                .padding(vertical = 4.dp),
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun IconControlButton(
    onClick: () -> Unit,
    symbol: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false
) {
    val background = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val tint = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(background, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(symbol, color = tint, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center)
        }
    }
}

private fun formatDistance(distanceKm: Float, unitSystem: UnitSystem): String {
    val displayDistance = if (unitSystem == UnitSystem.SI) distanceKm else distanceKm * 0.621371f
    val unitLabel = if (unitSystem == UnitSystem.SI) "km" else "mi"
    return "${"%.1f".format(displayDistance)} $unitLabel"
}

private fun formatPace(paceMinPerKm: Float?, unitSystem: UnitSystem): String {
    if (paceMinPerKm == null) return "--:-- /${if (unitSystem == UnitSystem.SI) "km" else "mi"}"
    val displayPace = if (unitSystem == UnitSystem.SI) paceMinPerKm else paceMinPerKm * 1.60934f
    if (unitSystem == UnitSystem.IMPERIAL && displayPace >= 60f) return "\u221e /mi"
    val wholeMinutes = displayPace.toInt()
    val seconds = ((displayPace - wholeMinutes) * 60).toInt().coerceIn(0, 59)
    return "%d:%02d /%s".format(wholeMinutes, seconds, if (unitSystem == UnitSystem.SI) "km" else "mi")
}

private fun formatDurationHms(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

private fun buildElapsedPrimaryLine(state: WearRunState): String = when (state.goal) {
    "DURATION" -> formatCompactDuration(state.elapsedSeconds)
    else -> formatDurationHms(state.elapsedSeconds)
}

private fun buildElapsedSecondaryLine(state: WearRunState): String? = when (state.goal) {
    "DURATION" -> "/ ${formatCompactGoalLabel(state.goalLabel)}"
    else -> null
}

private fun buildDistanceLine(state: WearRunState): String {
    val distance = formatDistance(state.distanceKm, state.unitSystem)
    return when (state.goal) {
        "DISTANCE" -> "$distance / ${state.goalLabel}"
        else -> distance
    }
}

private fun formatCompactDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun formatCompactGoalLabel(goalLabel: String): String {
    val segments = goalLabel.split(':')
    if (segments.size != 3) return goalLabel
    val hours = segments[0].toIntOrNull() ?: return goalLabel
    val minutes = segments[1].toIntOrNull() ?: return goalLabel
    val seconds = segments[2].toIntOrNull() ?: return goalLabel
    return formatCompactDuration(hours * 3600 + minutes * 60 + seconds)
}

private fun formatHeartRate(heartRate: Int?): String =
    "${heartRate ?: "--"} bpm \u2665"

private fun buildPaceHeartRateLine(state: WearRunState): String {
    val pace = "Pace ${formatPace(state.avgPaceMinPerKm, state.unitSystem)}"
    if (!state.heartRateEnabled) return pace
    return "$pace  ${formatHeartRate(state.heartRate)}"
}
