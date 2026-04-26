package com.example.justrun

import android.net.Uri
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.example.justrun.ui.theme.JustRunTheme
import kotlin.math.max
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JustRunTheme {
                JustRunApp()
            }
        }
    }
}

private enum class AppScreen {
    HOME,
    SETUP,
    SETTINGS,
    VOICE_CUES,
    RUNNING,
    SUMMARY
}

@Composable
private fun JustRunApp() {
    val context = LocalContext.current
    remember(context) { AppGraph.init(context); true }
    val settings by AppGraph.settingsRepository.settings.collectAsState()
    val runHistory by AppGraph.runRepository.runs.collectAsState()
    val trackingSession by AppGraph.trackingController.trackingSession.collectAsState()
    var screen by remember { mutableStateOf(AppScreen.HOME) }
    var selectedRunId by remember { mutableStateOf(runHistory.firstOrNull()?.id ?: 0) }
    var runSetup by remember {
        mutableStateOf(RunSetupState())
    }
    var pendingStart by remember { mutableStateOf(false) }
    var pendingExportRun by remember { mutableStateOf<RunRecord?>(null) }
    val activeRun = trackingSession.activeRun

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants["android.permission.ACCESS_FINE_LOCATION"] == true ||
            grants["android.permission.ACCESS_COARSE_LOCATION"] == true
        if (granted && pendingStart) {
            AppGraph.trackingController.startRun(runSetup, settings)
            screen = AppScreen.RUNNING
        }
        pendingStart = false
    }
    val importGpxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                GpxCodec.importRun(inputStream, displayName)
            } ?: error("Unable to open GPX file.")
        }.onSuccess { importedRun ->
            AppGraph.runRepository.addRun(importedRun)
            selectedRunId = importedRun.id
            screen = AppScreen.SUMMARY
            Toast.makeText(context, "Imported ${importedRun.title}", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "GPX import failed", Toast.LENGTH_SHORT).show()
        }
    }
    val exportGpxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        val runToExport = pendingExportRun
        pendingExportRun = null
        if (uri == null || runToExport == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                GpxCodec.exportRun(runToExport, outputStream)
            } ?: error("Unable to create GPX file.")
        }.onSuccess {
            Toast.makeText(context, "Exported ${runToExport.title}", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "GPX export failed", Toast.LENGTH_SHORT).show()
        }
    }

    val selectedRun = runHistory.firstOrNull { it.id == selectedRunId }

    LaunchedEffect(activeRun?.startedAtMillis) {
        if (activeRun != null) screen = AppScreen.RUNNING
    }

    LaunchedEffect(trackingSession.completedRunId, runHistory) {
        val completedRunId = trackingSession.completedRunId ?: return@LaunchedEffect
        if (runHistory.any { it.id == completedRunId }) {
            selectedRunId = completedRunId
            screen = AppScreen.SUMMARY
            AppGraph.trackingController.publish(trackingSession.copy(completedRunId = null))
        }
    }

    BackHandler(enabled = screen != AppScreen.HOME) {
        screen = AppScreen.HOME
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (screen) {
            AppScreen.HOME -> HomeScreen(
                runs = runHistory,
                unitSystem = settings.unitSystem,
                capabilities = TrackingCapabilities(
                    gpsEnabled = settings.gpsTrackingEnabled,
                    heartRateEnabled = settings.heartRateTrackingEnabled,
                    autoPauseEnabled = settings.autoPause
                ),
                activeRun = activeRun,
                onOpenSettings = { screen = AppScreen.SETTINGS },
                onStartRun = { screen = AppScreen.SETUP },
                onDeleteRun = { run ->
                    AppGraph.runRepository.deleteRun(run.id)
                    if (selectedRunId == run.id) {
                        selectedRunId = runHistory.firstOrNull { it.id != run.id }?.id ?: 0L
                    }
                },
                onImportSample = { importGpxLauncher.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")) },
                onOpenRun = { run ->
                    selectedRunId = run.id
                    screen = AppScreen.SUMMARY
                },
                onResumeActiveRun = { screen = AppScreen.RUNNING }
            )

            AppScreen.SETUP -> RunSetupScreen(
                setup = runSetup,
                settings = settings,
                onBack = { screen = AppScreen.HOME },
                onSetupChanged = { runSetup = it.copy(goal = sanitizeRunGoal(it.goal, settings.gpsTrackingEnabled)) },
                onStartRun = {
                    if (settings.gpsTrackingEnabled && !hasLocationPermissions(context)) {
                        pendingStart = true
                        permissionsLauncher.launch(runStartPermissions(context))
                    } else {
                        AppGraph.trackingController.startRun(runSetup, settings)
                        screen = AppScreen.RUNNING
                    }
                }
            )

            AppScreen.SETTINGS -> SettingsScreen(
                settings = settings,
                onBack = { screen = AppScreen.HOME },
                onOpenVoiceCues = { screen = AppScreen.VOICE_CUES },
                onSettingsChanged = {
                    AppGraph.settingsRepository.update(
                        it.copy(
                            autoPause = sanitizeAutoPause(it.autoPause, it.gpsTrackingEnabled),
                            lapMode = sanitizeLapMode(it.lapMode, it.gpsTrackingEnabled),
                            lapTrigger = sanitizeLapTrigger(it.lapTrigger, it.gpsTrackingEnabled),
                            lapDistanceKm = sanitizeLapDistanceKm(it.lapDistanceKm),
                            lapTimeSeconds = sanitizeLapTimeSeconds(it.lapTimeSeconds)
                        )
                    )
                    runSetup = runSetup.copy(goal = sanitizeRunGoal(runSetup.goal, it.gpsTrackingEnabled))
                }
            )

            AppScreen.VOICE_CUES -> VoiceCueSettingsScreen(
                settings = settings,
                onBack = { screen = AppScreen.SETTINGS },
                onSettingsChanged = { AppGraph.settingsRepository.update(it) }
            )

            AppScreen.RUNNING -> if (activeRun != null) RunScreen(
                activeRun = activeRun,
                unitSystem = settings.unitSystem,
                capabilities = TrackingCapabilities(
                    gpsEnabled = settings.gpsTrackingEnabled,
                    heartRateEnabled = settings.heartRateTrackingEnabled,
                    autoPauseEnabled = settings.autoPause
                ),
                onBack = { screen = AppScreen.HOME },
                onPauseToggle = {
                    if (activeRun.paused) AppGraph.trackingController.resumeRun()
                    else AppGraph.trackingController.pauseRun()
                },
                onMarkLap = { AppGraph.trackingController.markLap() },
                onStopRun = { AppGraph.trackingController.stopRun() }
            ) else {
                screen = AppScreen.HOME
            }

            AppScreen.SUMMARY -> if (selectedRun != null) RunSummaryScreen(
                run = selectedRun,
                unitSystem = settings.unitSystem,
                capabilities = TrackingCapabilities(
                    gpsEnabled = selectedRun.hadGpsTracking,
                    heartRateEnabled = selectedRun.hadHeartRateTracking,
                    autoPauseEnabled = settings.autoPause
                ),
                onBack = { screen = AppScreen.HOME },
                onExport = {
                    pendingExportRun = selectedRun
                    exportGpxLauncher.launch("${slugifyFilename(selectedRun.title)}.gpx")
                },
                onStartAnother = { screen = AppScreen.SETUP }
            ) else {
                screen = AppScreen.HOME
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    runs: List<RunRecord>,
    unitSystem: UnitSystem,
    capabilities: TrackingCapabilities,
    activeRun: ActiveRunState?,
    onOpenSettings: () -> Unit,
    onStartRun: () -> Unit,
    onDeleteRun: (RunRecord) -> Unit,
    onImportSample: () -> Unit,
    onOpenRun: (RunRecord) -> Unit,
    onResumeActiveRun: () -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            painter = painterResource(R.drawable.home_banner),
                            contentDescription = "Just Run",
                            modifier = Modifier.height(32.dp)
                        )
                        Text(
                            "No Server. No Tracking. Your Data Stays with You",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            Button(onClick = onStartRun, shape = RoundedCornerShape(20.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Start Run")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(start = 20.dp, end = 20.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (activeRun != null) {
                item {
                    ActiveRunCard(
                        activeRun = activeRun,
                        unitSystem = unitSystem,
                        onResume = onResumeActiveRun
                    )
                }
            }
            item {
                QuickStatusCard(
                    unitSystem = unitSystem,
                    capabilities = capabilities,
                    runs = runs
                )
            }
            if (!capabilities.gpsEnabled || !capabilities.heartRateEnabled) {
                item {
                    CapabilityStatusCard(capabilities = capabilities)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onImportSample,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import GPX")
                    }
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Profile")
                    }
                }
            }
            item {
                Text(
                    "Recent Runs",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (runs.isEmpty()) {
                item {
                    EmptyHistoryCard()
                }
            }
            items(runs, key = { it.id }) { run ->
                RunHistoryCard(
                    run = run,
                    unitSystem = unitSystem,
                    onOpen = { onOpenRun(run) },
                    onDelete = { onDeleteRun(run) }
                )
            }
            item {
                Spacer(Modifier.height(88.dp))
            }
        }
    }
}

@Composable
private fun ActiveRunCard(
    activeRun: ActiveRunState,
    unitSystem: UnitSystem,
    onResume: () -> Unit
) {
    Card(
        onClick = onResume,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Run In Progress",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                when {
                    activeRun.paused || activeRun.autoPaused -> "Paused"
                    else -> "Running"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HistoryChip(formatDurationSeconds(activeRun.elapsedSeconds), Modifier.weight(1f))
                HistoryChip(
                    if (activeRun.gpsEnabled) formatDistance(activeRun.distanceKm, unitSystem) else "Timer only",
                    Modifier.weight(1f)
                )
                HistoryChip(
                    if (activeRun.gpsEnabled) formatPace(activeRun.avgPaceMinPerKm, unitSystem) else "--:--",
                    Modifier.weight(1f)
                )
            }
            Text(
                "Tap to return to the live run screen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QuickStatusCard(
    unitSystem: UnitSystem,
    capabilities: TrackingCapabilities,
    runs: List<RunRecord>
) {
    val totalDistance = runs.sumOf { it.distanceKm.toDouble() }.toFloat()
    val recentPaces = runs.mapNotNull { it.avgPaceMinPerKm }
    val averagePace = recentPaces.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    Card(shape = RoundedCornerShape(24.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MetricPill(
                label = if (capabilities.gpsEnabled) "This week" else "Mode",
                value = if (capabilities.gpsEnabled) formatDistance(totalDistance, unitSystem) else "Timer only",
                modifier = Modifier.weight(1f)
            )
            MetricPill(
                label = if (capabilities.gpsEnabled) "Avg pace" else "GPS",
                value = if (capabilities.gpsEnabled) formatPace(averagePace, unitSystem) else "Off",
                modifier = Modifier.weight(1f)
            )
            MetricPill(
                label = if (capabilities.heartRateEnabled) "Heart rate" else "Runs",
                value = if (capabilities.heartRateEnabled) "On" else runs.size.toString(),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun EmptyHistoryCard() {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No runs yet", fontWeight = FontWeight.SemiBold)
            Text(
                "Start a run to record real distance, pace, elevation, and route data.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CapabilityStatusCard(capabilities: TrackingCapabilities) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Tracking Status", fontWeight = FontWeight.SemiBold)
            Text(
                buildString {
                    append("GPS tracking is ")
                    append(if (capabilities.gpsEnabled) "enabled" else "disabled")
                    append(". Heart-rate tracking is ")
                    append(if (capabilities.heartRateEnabled) "enabled" else "disabled")
                    append(".")
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RunHistoryCard(
    run: RunRecord,
    unitSystem: UnitSystem,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.DirectionsRun,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(run.title, fontWeight = FontWeight.SemiBold)
                    Text(run.dateLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete run")
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HistoryChip(formatDistance(run.distanceKm, unitSystem), Modifier.weight(1f))
                    HistoryChip(formatDurationSeconds(run.durationSeconds), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HistoryChip(formatPace(run.avgPaceMinPerKm, unitSystem), Modifier.weight(1f))
                    HistoryChip("${run.calories} kcal", Modifier.weight(1f))
                    HistoryChip(formatElevation(run.elevationGainM, unitSystem), Modifier.weight(1f))
                }
            }
            Text(run.notes, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    settings: SettingsState,
    onBack: () -> Unit,
    onOpenVoiceCues: () -> Unit,
    onSettingsChanged: (SettingsState) -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                "Athlete Profile",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Units", fontWeight = FontWeight.SemiBold)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        UnitSystem.entries.forEachIndexed { index, unitSystem ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = UnitSystem.entries.size),
                                onClick = { onSettingsChanged(settings.copy(unitSystem = unitSystem)) },
                                selected = settings.unitSystem == unitSystem,
                                label = {
                                    Text(if (unitSystem == UnitSystem.SI) "Metric" else "Imperial")
                                }
                            )
                        }
                    }
                    Text(
                        "Distance targets and live run distance will follow this preference.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            ToggleCard(
                title = "GPS tracking",
                subtitle = "Allow precise location tracking, route mapping, distance runs, and GPX export.",
                checked = settings.gpsTrackingEnabled,
                onCheckedChange = {
                    onSettingsChanged(
                        settings.copy(
                            gpsTrackingEnabled = it,
                            autoPause = sanitizeAutoPause(settings.autoPause, it)
                        )
                    )
                }
            )
            ToggleCard(
                title = "Heart-rate tracking",
                subtitle = "Collect heart-rate data when the device or watch can provide it.",
                checked = settings.heartRateTrackingEnabled,
                onCheckedChange = { onSettingsChanged(settings.copy(heartRateTrackingEnabled = it)) }
            )
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Athlete Profile", fontWeight = FontWeight.SemiBold)
                    NumericSettingField(
                        label = "Weight",
                        value = weightForDisplay(settings.weightKg, settings.unitSystem),
                        suffix = if (settings.unitSystem == UnitSystem.SI) "kg" else "lb",
                        allowDecimal = true,
                        onValueCommitted = { onSettingsChanged(settings.copy(weightKg = weightToKg(it.coerceIn(weightRange(settings.unitSystem)), settings.unitSystem))) }
                    )
                    HeightSettingField(
                        label = "Height",
                        heightCm = settings.heightCm,
                        unitSystem = settings.unitSystem,
                        onValueCommitted = { onSettingsChanged(settings.copy(heightCm = it)) }
                    )
                    NumericSettingField(
                        label = "Age",
                        value = settings.age,
                        suffix = "years",
                        allowDecimal = false,
                        onValueCommitted = { onSettingsChanged(settings.copy(age = it.coerceIn(13f, 90f))) }
                    )
                }
            }
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Daily Activity Goals", fontWeight = FontWeight.SemiBold)
                    Text(
                        "These feed watch complications and future daily activity tracking.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    NumericSettingField(
                        label = "Daily steps",
                        value = settings.dailyStepGoal.toFloat(),
                        suffix = "steps",
                        allowDecimal = false,
                        onValueCommitted = { onSettingsChanged(settings.copy(dailyStepGoal = it.toInt().coerceIn(1_000, 30_000))) }
                    )
                    NumericSettingField(
                        label = "Daily calories",
                        value = settings.dailyCalorieGoal.toFloat(),
                        suffix = "kcal",
                        allowDecimal = false,
                        onValueCommitted = { onSettingsChanged(settings.copy(dailyCalorieGoal = it.toInt().coerceIn(500, 6_000))) }
                    )
                }
            }
            ToggleCard(
                title = "Auto pause",
                subtitle = if (settings.gpsTrackingEnabled) {
                    "Pause when the runner stops moving."
                } else {
                    "Requires GPS tracking to detect movement."
                },
                checked = settings.autoPause,
                enabled = settings.gpsTrackingEnabled,
                onCheckedChange = { onSettingsChanged(settings.copy(autoPause = sanitizeAutoPause(it, settings.gpsTrackingEnabled))) }
            )
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Lap splits", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (settings.gpsTrackingEnabled) {
                            "Split runs automatically or mark laps yourself."
                        } else {
                            "With GPS off, laps must be marked manually."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        LapMode.entries.forEachIndexed { index, lapMode ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = LapMode.entries.size),
                                onClick = { onSettingsChanged(settings.copy(lapMode = sanitizeLapMode(lapMode, settings.gpsTrackingEnabled))) },
                                selected = settings.lapMode == sanitizeLapMode(lapMode, settings.gpsTrackingEnabled),
                                enabled = settings.gpsTrackingEnabled || lapMode == LapMode.MANUAL,
                                label = { Text(if (lapMode == LapMode.AUTOMATIC) "Automatic" else "Manual") }
                            )
                        }
                    }
                    if (settings.lapMode == LapMode.AUTOMATIC && settings.gpsTrackingEnabled) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            LapTrigger.entries.forEachIndexed { index, trigger ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = LapTrigger.entries.size),
                                    onClick = { onSettingsChanged(settings.copy(lapTrigger = trigger)) },
                                    selected = settings.lapTrigger == trigger,
                                    label = { Text(if (trigger == LapTrigger.DISTANCE) "Distance" else "Time") }
                                )
                            }
                        }
                        when (settings.lapTrigger) {
                            LapTrigger.DISTANCE -> DistanceIntervalPickerCard(
                                unitSystem = settings.unitSystem,
                                distanceKm = settings.lapDistanceKm,
                                label = "Automatic lap distance",
                                onValueChange = { onSettingsChanged(settings.copy(lapDistanceKm = sanitizeLapDistanceKm(it))) }
                            )
                            LapTrigger.TIME -> LapTimeIntervalCard(
                                totalSeconds = settings.lapTimeSeconds,
                                onValueChange = { onSettingsChanged(settings.copy(lapTimeSeconds = sanitizeLapTimeSeconds(it))) }
                            )
                        }
                    }
                }
            }
            ToggleCard(
                title = "Voice cues",
                subtitle = "Speak pace and distance updates locally on device.",
                checked = settings.voiceCues,
                onCheckedChange = { onSettingsChanged(settings.copy(voiceCues = it)) }
            )
            OutlinedButton(
                onClick = onOpenVoiceCues,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Configure voice cues")
            }
            ToggleCard(
                title = "Wear OS mirroring",
                subtitle = "Prepare a live workout view and pause/stop controls for Pixel Watch.",
                checked = settings.watchMirroring,
                onCheckedChange = { onSettingsChanged(settings.copy(watchMirroring = it)) }
            )
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("What comes next", fontWeight = FontWeight.SemiBold)
                    Text(
                        "These values will feed calorie estimation, heart-rate zones, and watch summaries once the tracking layer is connected.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceCueSettingsScreen(
    settings: SettingsState,
    onBack: () -> Unit,
    onSettingsChanged: (SettingsState) -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Voice Cues") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            ToggleCard(
                title = "Enabled",
                subtitle = "Speak local progress and lap updates during a run.",
                checked = settings.voiceCues,
                onCheckedChange = { onSettingsChanged(settings.copy(voiceCues = it)) }
            )
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Interval Type", fontWeight = FontWeight.SemiBold)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        VoiceCueIntervalType.entries.forEachIndexed { index, type ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = VoiceCueIntervalType.entries.size),
                                onClick = { onSettingsChanged(settings.copy(voiceCueIntervalType = type)) },
                                selected = settings.voiceCueIntervalType == type,
                                label = {
                                    Text(
                                        when (type) {
                                            VoiceCueIntervalType.TIME -> "Time"
                                            VoiceCueIntervalType.DISTANCE -> "Distance"
                                            VoiceCueIntervalType.LAP -> "Lap"
                                        }
                                    )
                                }
                            )
                        }
                    }
                    Text(
                        when (settings.voiceCueIntervalType) {
                            VoiceCueIntervalType.TIME -> "Time updates are announced at your configured minute interval."
                            VoiceCueIntervalType.DISTANCE -> "Distance updates are announced at your configured ${if (settings.unitSystem == UnitSystem.SI) "kilometer" else "mile"} interval."
                            VoiceCueIntervalType.LAP -> "Progress updates are announced when each lap completes."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    when (settings.voiceCueIntervalType) {
                        VoiceCueIntervalType.TIME -> NumericSettingField(
                            label = "Time interval",
                            value = settings.voiceCueTimeIntervalSeconds / 60f,
                            suffix = "min",
                            allowDecimal = false,
                            onValueCommitted = {
                                onSettingsChanged(
                                    settings.copy(
                                        voiceCueTimeIntervalSeconds = sanitizeVoiceCueTimeIntervalSeconds((it.toInt().coerceAtLeast(1)) * 60)
                                    )
                                )
                            }
                        )
                        VoiceCueIntervalType.DISTANCE -> NumericSettingField(
                            label = "Distance interval",
                            value = distanceInDisplayUnits(settings.voiceCueDistanceIntervalKm, settings.unitSystem),
                            suffix = if (settings.unitSystem == UnitSystem.SI) "km" else "mi",
                            allowDecimal = true,
                            onValueCommitted = {
                                onSettingsChanged(
                                    settings.copy(
                                        voiceCueDistanceIntervalKm = sanitizeVoiceCueDistanceIntervalKm(
                                            distanceDisplayToKm(it.coerceAtLeast(0.1f), settings.unitSystem)
                                        )
                                    )
                                )
                            }
                        )
                        VoiceCueIntervalType.LAP -> Unit
                    }
                }
            }
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Lead-in", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Choose how each progress cue starts before the selected metrics are spoken.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ProgressCueLeadIn.entries.forEach { leadIn ->
                        FilterChip(
                            selected = settings.voiceCueLeadIn == leadIn,
                            onClick = { onSettingsChanged(settings.copy(voiceCueLeadIn = leadIn)) },
                            label = { Text(leadIn.label()) }
                        )
                    }
                }
            }
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Metric Order", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Enable the metrics you want spoken and adjust the order for each unified progress cue.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val visibleMetrics = availableVoiceCueMetrics(settings.voiceCueIntervalType, settings.voiceCueMetricOrder)
                    visibleMetrics.forEachIndexed { index, metric ->
                        VoiceCueMetricRow(
                            metric = metric,
                            enabled = metric in settings.voiceCueEnabledMetrics,
                            canMoveUp = index > 0,
                            canMoveDown = index < visibleMetrics.lastIndex,
                            onEnabledChange = { enabled ->
                                onSettingsChanged(
                                    settings.copy(
                                        voiceCueEnabledMetrics = setVoiceCueMetricEnabled(
                                            orderedMetrics = settings.voiceCueMetricOrder,
                                            enabledMetrics = settings.voiceCueEnabledMetrics,
                                            metric = metric,
                                            enabled = enabled
                                        )
                                    )
                                )
                            },
                            onMoveUp = {
                                onSettingsChanged(
                                    settings.copy(
                                        voiceCueMetricOrder = moveVisibleVoiceCueMetric(
                                            fullMetrics = settings.voiceCueMetricOrder,
                                            visibleMetrics = visibleMetrics,
                                            fromIndex = index,
                                            toIndex = index - 1
                                        )
                                    )
                                )
                            },
                            onMoveDown = {
                                onSettingsChanged(
                                    settings.copy(
                                        voiceCueMetricOrder = moveVisibleVoiceCueMetric(
                                            fullMetrics = settings.voiceCueMetricOrder,
                                            visibleMetrics = visibleMetrics,
                                            fromIndex = index,
                                            toIndex = index + 1
                                        )
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceCueMetricRow(
    metric: VoiceCueMetric,
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(metric.label(), fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
                TextButton(onClick = onMoveUp, enabled = canMoveUp) { Text("Up") }
                TextButton(onClick = onMoveDown, enabled = canMoveDown) { Text("Down") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunSetupScreen(
    setup: RunSetupState,
    settings: SettingsState,
    onBack: () -> Unit,
    onSetupChanged: (RunSetupState) -> Unit,
    onStartRun: () -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("New Run") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Choose your run", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Set the workout type and target before tracking begins.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            GoalSelector(
                selectedGoal = setup.goal,
                enabledGoals = availableRunGoals(settings.gpsTrackingEnabled),
                onGoalChanged = { onSetupChanged(setup.copy(goal = it)) }
            )

            when (setup.goal) {
                RunGoal.ENDLESS -> {
                    Card(shape = RoundedCornerShape(24.dp)) {
                        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Open run", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Track until you manually pause or stop. No distance or time target.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                RunGoal.DURATION -> {
                    DurationPickerCard(
                        totalSeconds = setup.durationSeconds,
                        onValueChange = { onSetupChanged(setup.copy(durationSeconds = it)) }
                    )
                }

                RunGoal.DISTANCE -> {
                    DistancePickerCard(
                        unitSystem = settings.unitSystem,
                        distanceKm = setup.distanceKm,
                        onValueChange = { onSetupChanged(setup.copy(distanceKm = it)) }
                    )
                }
            }

            Card(shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Summary", fontWeight = FontWeight.SemiBold)
                    Text(runGoalSummary(setup, settings.unitSystem), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (!settings.gpsTrackingEnabled) {
                Card(shape = RoundedCornerShape(24.dp)) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("GPS disabled", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Distance runs are unavailable. Duration and endless runs still work fully offline.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Button(
                onClick = onStartRun,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                enabled = canStartRun(setup.goal, settings.gpsTrackingEnabled)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Start Run")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunScreen(
    activeRun: ActiveRunState,
    unitSystem: UnitSystem,
    capabilities: TrackingCapabilities,
    onBack: () -> Unit,
    onPauseToggle: () -> Unit,
    onMarkLap: () -> Unit,
    onStopRun: () -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Live Run") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Card(shape = RoundedCornerShape(24.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Workout target", fontWeight = FontWeight.SemiBold)
                        Text(formatGoalLabel(activeRun, unitSystem), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HistoryChip(
                        text = when (activeRun.goal) {
                            RunGoal.ENDLESS -> "Endless"
                            RunGoal.DURATION -> "Duration"
                            RunGoal.DISTANCE -> "Distance"
                        }
                    )
                }
            }
            if (capabilities.gpsEnabled) {
                Card(shape = RoundedCornerShape(28.dp)) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Route Preview", fontWeight = FontWeight.SemiBold)
                        RouteMapCard(
                            distanceKm = activeRun.distanceKm,
                            unitSystem = unitSystem,
                            routePoints = activeRun.routePoints
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MetricPill(label = "Elapsed", value = formatDurationHms(activeRun.elapsedSeconds), modifier = Modifier.weight(1f))
                            MetricPill(label = "Remaining", value = formatRemaining(activeRun, unitSystem), modifier = Modifier.weight(1f))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricPill(
                        label = "Distance",
                        value = formatDistance(activeRun.distanceKm, unitSystem),
                        modifier = Modifier.weight(1f)
                    )
                    MetricPill(label = "Avg pace", value = formatPace(activeRun.avgPaceMinPerKm, unitSystem), modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricPill(label = "Current pace", value = formatPace(activeRun.currentPaceMinPerKm, unitSystem), modifier = Modifier.weight(1f))
                    MetricPill(label = "Calories", value = "${activeRun.calories}", modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (capabilities.heartRateEnabled) {
                        MetricPill(label = "Heart rate", value = formatHeartRate(activeRun.heartRate), modifier = Modifier.weight(1f))
                    }
                    MetricPill(label = "Cadence", value = "${activeRun.cadence} spm", modifier = Modifier.weight(1f))
                    MetricPill(label = "Elev gain", value = formatElevation(activeRun.elevationGainM, unitSystem), modifier = Modifier.weight(1f))
                }
            } else {
                Card(shape = RoundedCornerShape(28.dp)) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("GPS disabled", fontWeight = FontWeight.SemiBold)
                        Text(
                            "This run is tracking time only. Distance, route, pace, and elevation are unavailable until GPS tracking is enabled.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MetricPill(label = "Elapsed", value = formatDurationHms(activeRun.elapsedSeconds), modifier = Modifier.weight(1f))
                            MetricPill(label = "Calories", value = "${activeRun.calories}", modifier = Modifier.weight(1f))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (capabilities.heartRateEnabled) {
                        MetricPill(label = "Heart rate", value = formatHeartRate(activeRun.heartRate), modifier = Modifier.weight(1f))
                    }
                    MetricPill(label = "Cadence", value = "${activeRun.cadence} spm", modifier = Modifier.weight(1f))
                }
            }
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Laps", fontWeight = FontWeight.SemiBold)
                    Text(
                        when (activeRun.lapMode) {
                            LapMode.AUTOMATIC -> "Automatic ${formatLapTrigger(activeRun, unitSystem)} splits. You can still mark an extra lap manually."
                            LapMode.MANUAL -> "Manual laps only. Tap the button when you want to mark a split."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricPill(label = "Splits", value = "${activeRun.lapSplits.size}", modifier = Modifier.weight(1f))
                        MetricPill(
                            label = "Current lap",
                            value = "${activeRun.lapSplits.size + 1}",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedButton(
                        onClick = onMarkLap,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Mark Lap")
                    }
                }
            }
            Card(shape = RoundedCornerShape(24.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onPauseToggle,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(
                            if (activeRun.paused || activeRun.autoPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (activeRun.paused || activeRun.autoPaused) "Resume" else "Pause")
                    }
                    Button(
                        onClick = onStopRun,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop Run")
                    }
                }
            }
            Text(
                "This layout is designed to mirror cleanly onto a Wear OS workout card with pause and stop controls.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunSummaryScreen(
    run: RunRecord,
    unitSystem: UnitSystem,
    capabilities: TrackingCapabilities,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onStartAnother: () -> Unit
) {
    val paceChartSeries = buildSummaryPaceSeries(run)
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Run Summary") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(run.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(run.dateLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricPill(label = "Distance", value = formatDistance(run.distanceKm, unitSystem), modifier = Modifier.weight(1f))
                        MetricPill(label = "Time", value = formatDurationSeconds(run.durationSeconds), modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricPill(label = "Avg pace", value = formatPace(run.avgPaceMinPerKm, unitSystem), modifier = Modifier.weight(1f))
                        MetricPill(label = "Calories", value = "${run.calories} kcal", modifier = Modifier.weight(1f))
                    }
                }
            }
            if (capabilities.gpsEnabled) {
                Card(shape = RoundedCornerShape(28.dp)) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Route", fontWeight = FontWeight.SemiBold)
                        RouteMapCard(
                            distanceKm = run.distanceKm,
                            unitSystem = unitSystem,
                            routePoints = run.routePoints
                        )
                        Text(
                            "Local GPS, elevation, and heart-rate samples will render here once the sensor pipeline is connected.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                DualChartCard(
                    title = "Pace Over Time",
                    subtitle = "Split-based pace distribution with a fallback to recorded GPS segments and consistency trends.",
                    values = paceChartSeries,
                    unit = paceUnitLabel(unitSystem),
                    displayValues = paceChartSeries.map { paceValueForDisplay(it, unitSystem) },
                    lowGood = true
                )
                DualChartCard(
                    title = "Elevation Over Time",
                    subtitle = "Climbs, descents, and cumulative effort through the route.",
                    values = buildSummaryElevationSeries(run),
                    unit = elevationUnitLabel(unitSystem),
                    displayValues = buildSummaryElevationSeries(run).map { elevationValueForDisplay(it.toInt(), unitSystem) },
                    lowGood = false
                )
            } else {
                Card(shape = RoundedCornerShape(28.dp)) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("GPS disabled", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Route, pace, distance, and elevation details are unavailable for new runs while GPS tracking is off.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (!capabilities.heartRateEnabled) {
                Card(shape = RoundedCornerShape(24.dp)) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Heart-rate disabled", fontWeight = FontWeight.SemiBold)
                        Text(
                            "New runs will not collect heart-rate samples until heart-rate tracking is re-enabled.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (run.lapSplits.isNotEmpty()) {
                Card(shape = RoundedCornerShape(24.dp)) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Splits", fontWeight = FontWeight.SemiBold)
                        run.lapSplits.forEach { lap ->
                            Card(
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Lap ${lap.index}", fontWeight = FontWeight.SemiBold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        MetricPill(label = "Time", value = formatDurationSeconds(lap.durationSeconds), modifier = Modifier.weight(1f))
                                        MetricPill(label = "Distance", value = formatDistance(lap.distanceKm, unitSystem), modifier = Modifier.weight(1f))
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        MetricPill(label = "Pace", value = formatPace(lap.avgPaceMinPerKm, unitSystem), modifier = Modifier.weight(1f))
                                        MetricPill(label = "Calories", value = "${lap.calories} kcal", modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Export", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (run.routePoints.isNotEmpty()) {
                            "Export the recorded route, elevation, and timestamps as a standard GPX track file."
                        } else {
                            "This run has no GPS track, so there is nothing to export as GPX."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = onExport,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(18.dp),
                            enabled = run.routePoints.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Export GPX")
                        }
                        Button(
                            onClick = onStartAnother,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Run Again")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalSelector(
    selectedGoal: RunGoal,
    enabledGoals: List<RunGoal> = RunGoal.entries,
    onGoalChanged: (RunGoal) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Run Type", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            RunGoal.entries.forEachIndexed { index, goal ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = RunGoal.entries.size),
                    onClick = { onGoalChanged(goal) },
                    selected = selectedGoal == goal,
                    enabled = goal in enabledGoals,
                    label = {
                        Text(
                            when (goal) {
                                RunGoal.ENDLESS -> "Endless"
                                RunGoal.DURATION -> "Duration"
                                RunGoal.DISTANCE -> "Distance"
                            }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun MetricPill(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(value, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
        }
    }
}

@Composable
private fun HistoryChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun NumericSettingField(
    label: String,
    value: Float,
    suffix: String,
    allowDecimal: Boolean,
    onValueCommitted: (Float) -> Unit
) {
    var inputText by remember(label, suffix) { mutableStateOf(formatSettingNumber(value, allowDecimal)) }

    LaunchedEffect(value, allowDecimal) {
        val parsed = inputText.toFloatOrNull()
        if (parsed == null || kotlin.math.abs(parsed - value) > if (allowDecimal) 0.05f else 0.5f) {
            inputText = formatSettingNumber(value, allowDecimal)
        }
    }

    OutlinedTextField(
        value = inputText,
        onValueChange = { newValue ->
            if (newValue.all { it.isDigit() || (allowDecimal && it == '.') }) {
                inputText = newValue
                newValue.toFloatOrNull()?.let(onValueCommitted)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        singleLine = true,
        label = { Text(label) },
        suffix = { Text(suffix) },
        keyboardOptions = KeyboardOptions(keyboardType = if (allowDecimal) KeyboardType.Decimal else KeyboardType.Number)
    )
}

@Composable
private fun HeightSettingField(
    label: String,
    heightCm: Float,
    unitSystem: UnitSystem,
    onValueCommitted: (Float) -> Unit
) {
    if (unitSystem == UnitSystem.SI) {
        NumericSettingField(
            label = label,
            value = heightCm,
            suffix = "cm",
            allowDecimal = true,
            onValueCommitted = { onValueCommitted(it.coerceIn(130f, 220f)) }
        )
        return
    }

    val totalInches = heightForDisplay(heightCm, UnitSystem.IMPERIAL).toInt().coerceIn(51, 87)
    val initialFeet = totalInches / 12
    val initialInches = totalInches % 12
    var feetText by remember(label, unitSystem) { mutableStateOf(initialFeet.toString()) }
    var inchesText by remember(label, unitSystem) { mutableStateOf(initialInches.toString()) }

    LaunchedEffect(heightCm, unitSystem) {
        val currentFeet = feetText.toIntOrNull()
        val currentInches = inchesText.toIntOrNull()
        if (currentFeet != initialFeet) feetText = initialFeet.toString()
        if (currentInches != initialInches) inchesText = initialInches.toString()
    }

    fun commitHeight(nextFeet: String = feetText, nextInches: String = inchesText) {
        val feet = nextFeet.toIntOrNull() ?: return
        val inches = nextInches.toIntOrNull() ?: return
        val normalizedFeet = feet.coerceIn(4, 7)
        val normalizedInches = inches.coerceIn(0, 11)
        val normalizedTotalInches = (normalizedFeet * 12 + normalizedInches).coerceIn(51, 87)
        onValueCommitted(heightToCm(normalizedTotalInches.toFloat(), UnitSystem.IMPERIAL))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = feetText,
                onValueChange = { newValue ->
                    if (newValue.all(Char::isDigit)) {
                        feetText = newValue
                        commitHeight(nextFeet = newValue)
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(20.dp),
                singleLine = true,
                label = { Text("Feet") },
                suffix = { Text("ft") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = inchesText,
                onValueChange = { newValue ->
                    if (newValue.all(Char::isDigit)) {
                        inchesText = newValue
                        commitHeight(nextInches = newValue)
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(20.dp),
                singleLine = true,
                label = { Text("Inches") },
                suffix = { Text("in") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
    }
}

@Composable
private fun DurationPickerCard(
    totalSeconds: Int,
    onValueChange: (Int) -> Unit
) {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    Card(shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Duration target", fontWeight = FontWeight.SemiBold)
            Text("Set any target up to 24:00:00.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeUnitPicker(
                    label = "HH",
                    value = hours,
                    onDecrement = { onValueChange(adjustHours(totalSeconds, -1)) },
                    onIncrement = { onValueChange(adjustHours(totalSeconds, 1)) },
                    modifier = Modifier.weight(1f)
                )
                TimeUnitPicker(
                    label = "MM",
                    value = minutes,
                    onDecrement = { onValueChange(adjustDurationComponent(totalSeconds, DurationPart.MINUTES, -1)) },
                    onIncrement = { onValueChange(adjustDurationComponent(totalSeconds, DurationPart.MINUTES, 1)) },
                    modifier = Modifier.weight(1f)
                )
                TimeUnitPicker(
                    label = "SS",
                    value = seconds,
                    onDecrement = { onValueChange(adjustDurationComponent(totalSeconds, DurationPart.SECONDS, -1)) },
                    onIncrement = { onValueChange(adjustDurationComponent(totalSeconds, DurationPart.SECONDS, 1)) },
                    modifier = Modifier.weight(1f)
                )
            }
            Text(formatDurationHms(totalSeconds), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TimeUnitPicker(
    label: String,
    value: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier
) {
    val incrementSource = remember { MutableInteractionSource() }
    val decrementSource = remember { MutableInteractionSource() }
    val incrementPressed by incrementSource.collectIsPressedAsState()
    val decrementPressed by decrementSource.collectIsPressedAsState()
    val currentIncrement by rememberUpdatedState(onIncrement)
    val currentDecrement by rememberUpdatedState(onDecrement)

    LaunchedEffect(incrementPressed) {
        if (incrementPressed) {
            delay(300)
            while (true) {
                currentIncrement()
                delay(80)
            }
        }
    }

    LaunchedEffect(decrementPressed) {
        if (decrementPressed) {
            delay(300)
            while (true) {
                currentDecrement()
                delay(80)
            }
        }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(
                onClick = currentIncrement,
                shape = RoundedCornerShape(14.dp),
                interactionSource = incrementSource
            ) {
                Text("+")
            }
            Text("%02d".format(value), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            OutlinedButton(
                onClick = currentDecrement,
                shape = RoundedCornerShape(14.dp),
                interactionSource = decrementSource
            ) {
                Text("-")
            }
        }
    }
}

@Composable
private fun DistancePickerCard(
    unitSystem: UnitSystem,
    distanceKm: Float,
    onValueChange: (Float) -> Unit
) {
    DistanceIntervalPickerCard(
        unitSystem = unitSystem,
        distanceKm = distanceKm,
        label = "Distance target",
        helperText = "Set any target up to ${formatDistanceValue(if (unitSystem == UnitSystem.SI) 160.9f else 100f, if (unitSystem == UnitSystem.SI) "km" else "mi")}.",
        onValueChange = onValueChange
    )
}

@Composable
private fun DistanceIntervalPickerCard(
    unitSystem: UnitSystem,
    distanceKm: Float,
    label: String,
    helperText: String = "Set the distance interval.",
    onValueChange: (Float) -> Unit
) {
    val unitLabel = if (unitSystem == UnitSystem.SI) "km" else "mi"
    val displayDistance = distanceInDisplayUnits(distanceKm, unitSystem)
    val maxDisplay = if (unitSystem == UnitSystem.SI) 160.9f else 100f
    var inputText by remember(unitSystem) { mutableStateOf(formatDistanceNumber(displayDistance)) }

    LaunchedEffect(displayDistance, unitSystem) {
        val parsed = inputText.toFloatOrNull()
        if (parsed == null || kotlin.math.abs(parsed - displayDistance) > 0.05f) {
            inputText = formatDistanceNumber(displayDistance)
        }
    }

    Card(shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(helperText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = inputText,
                onValueChange = { newValue ->
                    if (newValue.all { it.isDigit() || it == '.' }) {
                        inputText = newValue
                        newValue.toFloatOrNull()?.let { parsed ->
                            val clamped = parsed.coerceIn(0f, maxDisplay)
                            onValueChange(distanceDisplayToKm(clamped, unitSystem))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                singleLine = true,
                suffix = { Text(unitLabel) },
                label = { Text(label) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
        }
    }
}

@Composable
private fun LapTimeIntervalCard(
    totalSeconds: Int,
    onValueChange: (Int) -> Unit
) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Automatic lap time", fontWeight = FontWeight.SemiBold)
            Text("Create a new split at this elapsed-time interval.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeUnitPicker(
                    label = "HH",
                    value = totalSeconds / 3600,
                    onDecrement = { onValueChange(adjustHours(totalSeconds, -1)) },
                    onIncrement = { onValueChange(adjustHours(totalSeconds, 1)) },
                    modifier = Modifier.weight(1f)
                )
                TimeUnitPicker(
                    label = "MM",
                    value = (totalSeconds % 3600) / 60,
                    onDecrement = { onValueChange(adjustDurationComponent(totalSeconds, DurationPart.MINUTES, -1)) },
                    onIncrement = { onValueChange(adjustDurationComponent(totalSeconds, DurationPart.MINUTES, 1)) },
                    modifier = Modifier.weight(1f)
                )
                TimeUnitPicker(
                    label = "SS",
                    value = totalSeconds % 60,
                    onDecrement = { onValueChange(adjustDurationComponent(totalSeconds, DurationPart.SECONDS, -1)) },
                    onIncrement = { onValueChange(adjustDurationComponent(totalSeconds, DurationPart.SECONDS, 1)) },
                    modifier = Modifier.weight(1f)
                )
            }
            Text(formatDurationHms(totalSeconds), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
private fun RouteMapCard(
    distanceKm: Float,
    unitSystem: UnitSystem,
    routePoints: List<LocationPoint>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF122033))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .padding(16.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stepX = size.width / 6f
                val stepY = size.height / 7f

                for (i in 1..5) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.08f),
                        start = Offset(stepX * i, 0f),
                        end = Offset(stepX * i, size.height),
                        strokeWidth = 1f
                    )
                }

                for (i in 1..6) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.08f),
                        start = Offset(0f, stepY * i),
                        end = Offset(size.width, stepY * i),
                        strokeWidth = 1f
                    )
                }

                val projectedRoute = routePoints.projectToCanvas(size.width, size.height)

                if (projectedRoute.size >= 2) {
                    val path = Path().apply {
                        moveTo(projectedRoute.first().x, projectedRoute.first().y)
                        projectedRoute.drop(1).forEach { point ->
                            lineTo(point.x, point.y)
                        }
                    }

                    drawPath(
                        path = path,
                        brush = Brush.linearGradient(listOf(Color(0xFF66E3A6), Color(0xFF4C8BF5))),
                        style = Stroke(width = 12f, cap = StrokeCap.Round)
                    )

                    drawCircle(Color.White, radius = 10f, center = projectedRoute.last())
                    drawCircle(Color(0xFF66E3A6), radius = 12f, center = projectedRoute.first(), style = Stroke(width = 6f))
                } else if (projectedRoute.size == 1) {
                    drawCircle(Color.White, radius = 10f, center = projectedRoute.first())
                }
            }

            Card(
                modifier = Modifier.align(Alignment.TopStart),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.28f))
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text("Live GPS", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("${formatDistance(distanceKm, unitSystem)} tracked", color = Color.White.copy(alpha = 0.75f))
                }
            }

            if (routePoints.isEmpty()) {
                Card(
                    modifier = Modifier.align(Alignment.BottomStart),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.28f))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text("Waiting for GPS points", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Route will draw as points are recorded.", color = Color.White.copy(alpha = 0.75f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DualChartCard(
    title: String,
    subtitle: String,
    values: List<Float>,
    displayValues: List<Float>,
    unit: String,
    lowGood: Boolean
) {
    var highlightedIndex by remember(values) { mutableStateOf<Int?>(null) }
    val highlightedValue = highlightedIndex?.let { index -> displayValues.getOrNull(index) }
    Card(shape = RoundedCornerShape(28.dp)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                highlightedValue?.let { "$subtitle  ${"%.1f".format(it)} $unit" } ?: subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            MiniChart(
                values = values,
                lowGood = lowGood,
                selectedIndex = highlightedIndex,
                onSelectionChange = { highlightedIndex = it }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val best = displayValues.minOrNull() ?: 0f
                val average = if (displayValues.isEmpty()) 0f else displayValues.average().toFloat()
                val peak = displayValues.maxOrNull() ?: 0f
                MetricPill(label = "Best", value = "${"%.1f".format(best)} $unit", modifier = Modifier.weight(1f))
                MetricPill(label = "Average", value = "${"%.1f".format(average)} $unit", modifier = Modifier.weight(1f))
                MetricPill(label = "Peak", value = "${"%.1f".format(peak)} $unit", modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MiniChart(
    values: List<Float>,
    lowGood: Boolean,
    selectedIndex: Int?,
    onSelectionChange: (Int?) -> Unit
) {
    val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .pointerInput(values) {
                    if (values.isEmpty()) return@pointerInput
                    fun updateSelection(x: Float) {
                        val chartWidth = size.width.toFloat().coerceAtLeast(1f)
                        val rawIndex = ((x / chartWidth) * (values.size - 1)).toInt().coerceIn(0, values.lastIndex)
                        onSelectionChange(rawIndex)
                    }
                    detectTapGestures(
                        onPress = { offset ->
                            updateSelection(offset.x)
                            tryAwaitRelease()
                        }
                    )
                }
                .pointerInput(values) {
                    if (values.isEmpty()) return@pointerInput
                    fun updateSelection(x: Float) {
                        val chartWidth = size.width.toFloat().coerceAtLeast(1f)
                        val rawIndex = ((x / chartWidth) * (values.size - 1)).toInt().coerceIn(0, values.lastIndex)
                        onSelectionChange(rawIndex)
                    }
                    detectDragGestures(
                        onDragStart = { offset -> updateSelection(offset.x) },
                        onDrag = { change, _ ->
                            updateSelection(change.position.x)
                            change.consume()
                        }
                    )
                }
        ) {
            if (values.isEmpty()) return@Canvas

            val minValue = values.minOrNull() ?: 0f
            val maxValue = max(values.maxOrNull() ?: 1f, minValue + 1f)
            val chartWidth = size.width
            val chartHeight = size.height
            val spacing = if (values.size > 1) chartWidth / (values.size - 1) else chartWidth

            val points = values.mapIndexed { index, value ->
                val x = spacing * index
                val normalized = (value - minValue) / (maxValue - minValue)
                val y = chartHeight - (normalized * chartHeight)
                Offset(x, y)
            }

            for (i in 0..3) {
                val y = chartHeight * (i / 3f)
                drawLine(
                    color = outlineColor,
                    start = Offset(0f, y),
                    end = Offset(chartWidth, y),
                    strokeWidth = 1f
                )
            }

            val fillPath = Path().apply {
                moveTo(points.first().x, chartHeight)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, chartHeight)
                close()
            }

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    listOf(
                        if (lowGood) Color(0xFF4C8BF5).copy(alpha = 0.35f) else Color(0xFF66E3A6).copy(alpha = 0.35f),
                        Color.Transparent
                    )
                )
            )

            val strokeColor = if (lowGood) Color(0xFF4C8BF5) else Color(0xFF2BB673)
            val linePath = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { point -> lineTo(point.x, point.y) }
            }

            drawPath(path = linePath, color = strokeColor, style = Stroke(width = 8f, cap = StrokeCap.Round))

            points.forEach { point ->
                drawCircle(color = Color.White, radius = 6f, center = point)
                drawCircle(color = strokeColor, radius = 3f, center = point)
            }

            selectedIndex?.takeIf { it in points.indices }?.let { index ->
                val selectedPoint = points[index]
                drawLine(
                    color = Color.White.copy(alpha = 0.6f),
                    start = Offset(selectedPoint.x, 0f),
                    end = Offset(selectedPoint.x, chartHeight),
                    strokeWidth = 2f
                )
                drawCircle(color = Color.White, radius = 10f, center = selectedPoint)
                drawCircle(color = strokeColor, radius = 6f, center = selectedPoint)
            }
        }
    }
}

private fun runGoalSummary(setup: RunSetupState, unitSystem: UnitSystem): String = when (setup.goal) {
    RunGoal.ENDLESS -> "Start an open-ended run and stop it manually when finished."
    RunGoal.DURATION -> "Start a timed run with a ${formatDurationHms(setup.durationSeconds)} target."
    RunGoal.DISTANCE -> "Start a distance run with a ${formatDistance(setup.distanceKm, unitSystem)} target."
}

internal fun buildSummaryPaceSeries(run: RunRecord): List<Float> =
    run.routePoints
        .windowed(size = 2, step = 1, partialWindows = false)
        .mapNotNull { (start, end) ->
            val distanceKm = summaryDistanceBetweenKm(start, end)
            val durationSeconds = ((end.timestampMillis - start.timestampMillis) / 1000f).coerceAtLeast(1f)
            if (distanceKm <= 0f || durationSeconds > 15f) null else (durationSeconds / 60f) / distanceKm
        }
        .filter { it.isFinite() && it > 0f }
        .evenlySampled(60)
        .ifEmpty {
            run.paceSeries.filter { it.isFinite() && it > 0f }
        }
        .ifEmpty {
            run.lapSplits
                .mapNotNull { it.avgPaceMinPerKm }
                .filter { it.isFinite() && it > 0f }
        }

internal fun buildSummaryElevationSeries(run: RunRecord): List<Float> =
    run.routePoints
        .map { it.altitudeMeters.toFloat() }
        .filter { it.isFinite() }
        .evenlySampled(60)
        .ifEmpty { run.elevationSeries.filter { it.isFinite() } }

private fun List<Float>.evenlySampled(maxSamples: Int): List<Float> {
    if (size <= maxSamples) return this
    if (maxSamples <= 1) return listOf(first())
    val sourceLastIndex = lastIndex.toFloat()
    return List(maxSamples) { sampleIndex ->
        val sourceIndex = ((sampleIndex.toFloat() / (maxSamples - 1)) * sourceLastIndex).toInt()
        this[sourceIndex.coerceIn(indices)]
    }
}

private fun summaryDistanceBetweenKm(start: LocationPoint, end: LocationPoint): Float {
    val earthRadiusKm = 6371.0
    val latDelta = Math.toRadians(end.latitude - start.latitude)
    val lonDelta = Math.toRadians(end.longitude - start.longitude)
    val startLat = Math.toRadians(start.latitude)
    val endLat = Math.toRadians(end.latitude)
    val haversine = sin(latDelta / 2).pow(2.0) +
        cos(startLat) * cos(endLat) * sin(lonDelta / 2).pow(2.0)
    val arc = 2 * asin(sqrt(haversine))
    return (earthRadiusKm * arc).toFloat()
}

private fun formatGoalLabel(activeRun: ActiveRunState, unitSystem: UnitSystem): String = when (activeRun.goal) {
    RunGoal.ENDLESS -> "Open run"
    RunGoal.DURATION -> "${formatDurationHms(activeRun.targetDurationSeconds ?: 0)} goal"
    RunGoal.DISTANCE -> "${formatDistance(activeRun.targetDistanceKm ?: 0f, unitSystem)} goal"
}

private fun formatRemaining(activeRun: ActiveRunState, unitSystem: UnitSystem): String = when (activeRun.goal) {
    RunGoal.ENDLESS -> "No finish target"
    RunGoal.DURATION -> {
        val remaining = ((activeRun.targetDurationSeconds ?: 0) - activeRun.elapsedSeconds).coerceAtLeast(0)
        "${formatDurationHms(remaining)} remaining"
    }
    RunGoal.DISTANCE -> {
        val remainingDistance = ((activeRun.targetDistanceKm ?: 0f) - activeRun.distanceKm).coerceAtLeast(0f)
        "${formatDistance(remainingDistance, unitSystem)} left"
    }
}

private fun formatLapTrigger(activeRun: ActiveRunState, unitSystem: UnitSystem): String = when (activeRun.lapTrigger) {
    LapTrigger.DISTANCE -> "${formatDistance(activeRun.lapDistanceKm ?: 0f, unitSystem)} laps"
    LapTrigger.TIME -> "${formatDurationHms(activeRun.lapTimeSeconds ?: 0)} laps"
}

private enum class DurationPart {
    MINUTES,
    SECONDS
}

private fun adjustHours(totalSeconds: Int, delta: Int): Int {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val nextHours = (hours + delta).wrapInRange(0, 24)
    return nextHours * 3600 + minutes * 60 + seconds
}

private fun adjustDurationComponent(totalSeconds: Int, part: DurationPart, delta: Int): Int {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    val nextMinutes: Int
    val nextSeconds: Int
    when (part) {
        DurationPart.MINUTES -> {
            nextMinutes = (minutes + delta).wrapInRange(0, 59)
            nextSeconds = seconds
        }
        DurationPart.SECONDS -> {
            nextMinutes = minutes
            nextSeconds = (seconds + delta).wrapInRange(0, 59)
        }
    }

    return hours * 3600 + nextMinutes * 60 + nextSeconds
}

internal fun formatDurationHms(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

private fun formatDurationSeconds(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

private fun hasLocationPermissions(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun runStartPermissions(context: android.content.Context): Array<String> {
    val permissions = mutableListOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.POST_NOTIFICATIONS
    )
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
    ) {
        permissions += android.Manifest.permission.ACTIVITY_RECOGNITION
    }
    return permissions.toTypedArray()
}

private fun distanceInDisplayUnits(distanceKm: Float, unitSystem: UnitSystem): Float =
    if (unitSystem == UnitSystem.SI) distanceKm else distanceKm * 0.621371f

private fun distanceDisplayToKm(distance: Float, unitSystem: UnitSystem): Float =
    if (unitSystem == UnitSystem.SI) distance else distance / 0.621371f

private fun formatSettingNumber(value: Float, allowDecimal: Boolean): String =
    if (allowDecimal) {
        if (value % 1f == 0f) value.toInt().toString() else String.format(java.util.Locale.US, "%.1f", value)
    } else {
        value.toInt().toString()
    }

private fun weightForDisplay(weightKg: Float, unitSystem: UnitSystem): Float =
    if (unitSystem == UnitSystem.SI) weightKg else weightKg * 2.20462f

private fun weightToKg(weight: Float, unitSystem: UnitSystem): Float =
    if (unitSystem == UnitSystem.SI) weight else weight / 2.20462f

private fun heightForDisplay(heightCm: Float, unitSystem: UnitSystem): Float =
    if (unitSystem == UnitSystem.SI) heightCm else heightCm / 2.54f

private fun heightToCm(height: Float, unitSystem: UnitSystem): Float =
    if (unitSystem == UnitSystem.SI) height else height * 2.54f

private fun weightRange(unitSystem: UnitSystem): ClosedFloatingPointRange<Float> =
    if (unitSystem == UnitSystem.SI) 40f..130f else 88f..287f

private fun heightRange(unitSystem: UnitSystem): ClosedFloatingPointRange<Float> =
    if (unitSystem == UnitSystem.SI) 130f..220f else 51f..87f

internal fun formatWeight(weightKg: Float, unitSystem: UnitSystem): String =
    if (unitSystem == UnitSystem.SI) {
        "${weightKg.toInt()} kg"
    } else {
        "${weightForDisplay(weightKg, unitSystem).toInt()} lb"
    }

internal fun formatHeight(heightCm: Float, unitSystem: UnitSystem): String =
    if (unitSystem == UnitSystem.SI) {
        "${heightCm.toInt()} cm"
    } else {
        val totalInches = heightForDisplay(heightCm, unitSystem).toInt()
        val feet = totalInches / 12
        val inches = totalInches % 12
        "$feet ft $inches in"
    }

private fun paceValueForDisplay(paceMinPerKm: Float, unitSystem: UnitSystem): Float =
    if (unitSystem == UnitSystem.SI) paceMinPerKm else paceMinPerKm * 1.60934f

private fun paceUnitLabel(unitSystem: UnitSystem): String =
    if (unitSystem == UnitSystem.SI) "min/km" else "min/mi"

internal fun formatPace(paceMinPerKm: Float?, unitSystem: UnitSystem): String =
    paceMinPerKm?.let {
        val displayPace = paceValueForDisplay(it, unitSystem)
        if (displayPace >= MAX_DISPLAY_PACE_MIN_PER_MILE && unitSystem == UnitSystem.IMPERIAL) {
            "\u221e /mi"
        } else {
            "${formatMinutesValue(displayPace)} /${if (unitSystem == UnitSystem.SI) "km" else "mi"}"
        }
    } ?: "--:-- /${if (unitSystem == UnitSystem.SI) "km" else "mi"}"

private fun elevationValueForDisplay(elevationM: Int, unitSystem: UnitSystem): Float =
    if (unitSystem == UnitSystem.SI) elevationM.toFloat() else elevationM * 3.28084f

private fun elevationUnitLabel(unitSystem: UnitSystem): String =
    if (unitSystem == UnitSystem.SI) "m" else "ft"

internal fun formatElevation(elevationM: Int, unitSystem: UnitSystem): String =
    "${String.format("%.0f", elevationValueForDisplay(elevationM, unitSystem))} ${elevationUnitLabel(unitSystem)}"

internal fun formatDistance(distanceKm: Float, unitSystem: UnitSystem): String {
    val unitLabel = if (unitSystem == UnitSystem.SI) "km" else "mi"
    return formatDistanceValue(distanceInDisplayUnits(distanceKm, unitSystem), unitLabel)
}

private fun formatDistanceValue(value: Float, unitLabel: String): String =
    "${String.format("%.1f", value)} $unitLabel"

private fun formatDistanceNumber(value: Float): String =
    String.format("%.1f", value)

private fun slugifyFilename(value: String): String =
    value
        .trim()
        .lowercase(java.util.Locale.US)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "run" }

private fun moveVoiceCueMetric(
    metrics: List<VoiceCueMetric>,
    fromIndex: Int,
    toIndex: Int
): List<VoiceCueMetric> {
    if (fromIndex !in metrics.indices || toIndex !in metrics.indices) return metrics
    val updated = metrics.toMutableList()
    val metric = updated.removeAt(fromIndex)
    updated.add(toIndex, metric)
    return updated
}

private fun setVoiceCueMetricEnabled(
    orderedMetrics: List<VoiceCueMetric>,
    enabledMetrics: List<VoiceCueMetric>,
    metric: VoiceCueMetric,
    enabled: Boolean
): List<VoiceCueMetric> {
    val currentlyEnabled = enabledMetrics.toSet()
    return orderedMetrics.filter { candidate ->
        when {
            candidate == metric -> enabled
            else -> candidate in currentlyEnabled
        }
    }
}

private fun availableVoiceCueMetrics(
    trigger: VoiceCueIntervalType,
    orderedMetrics: List<VoiceCueMetric>
): List<VoiceCueMetric> =
    orderedMetrics.filterNot { metric ->
        metric == VoiceCueMetric.LAP_DISTANCE && trigger != VoiceCueIntervalType.LAP
    }

private fun moveVisibleVoiceCueMetric(
    fullMetrics: List<VoiceCueMetric>,
    visibleMetrics: List<VoiceCueMetric>,
    fromIndex: Int,
    toIndex: Int
): List<VoiceCueMetric> {
    if (fromIndex !in visibleMetrics.indices || toIndex !in visibleMetrics.indices) return fullMetrics
    val sourceMetric = visibleMetrics[fromIndex]
    val targetMetric = visibleMetrics[toIndex]
    val fullFromIndex = fullMetrics.indexOf(sourceMetric)
    val fullToIndex = fullMetrics.indexOf(targetMetric)
    return moveVoiceCueMetric(fullMetrics, fullFromIndex, fullToIndex)
}

private fun VoiceCueMetric.label(): String = when (this) {
    VoiceCueMetric.ELAPSED_TIME -> "Elapsed time"
    VoiceCueMetric.REMAINING_TIME -> "Remaining time"
    VoiceCueMetric.AVERAGE_PACE -> "Average pace"
    VoiceCueMetric.TOTAL_DISTANCE -> "Total distance"
    VoiceCueMetric.LAP_DISTANCE -> "Lap distance"
}

private fun ProgressCueLeadIn.label(): String = when (this) {
    ProgressCueLeadIn.NONE -> "No lead-in"
    ProgressCueLeadIn.LAP_NUMBER -> "Lap number completed"
    ProgressCueLeadIn.DISTANCE_COMPLETED -> "Distance completed"
    ProgressCueLeadIn.TIME_COMPLETED -> "Time completed"
}

private fun formatMinutesValue(value: Float): String {
    val wholeMinutes = value.toInt()
    val seconds = ((value - wholeMinutes) * 60).toInt().coerceIn(0, 59)
    return "%d:%02d".format(wholeMinutes, seconds)
}

private fun formatHeartRate(heartRate: Int?): String =
    heartRate?.let { "$it bpm" } ?: "-- bpm"

private const val MAX_DISPLAY_PACE_MIN_PER_MILE = 60f

private fun List<LocationPoint>.projectToCanvas(width: Float, height: Float): List<Offset> {
    if (isEmpty()) return emptyList()

    val minLat = minOf { it.latitude }
    val maxLat = maxOf { it.latitude }
    val minLon = minOf { it.longitude }
    val maxLon = maxOf { it.longitude }
    val latSpan = (maxLat - minLat).takeIf { it > 0.0 } ?: 0.0001
    val lonSpan = (maxLon - minLon).takeIf { it > 0.0 } ?: 0.0001
    val padding = 24f
    val usableWidth = (width - padding * 2).coerceAtLeast(1f)
    val usableHeight = (height - padding * 2).coerceAtLeast(1f)

    return map { point ->
        val normalizedX = ((point.longitude - minLon) / lonSpan).toFloat()
        val normalizedY = ((point.latitude - minLat) / latSpan).toFloat()
        Offset(
            x = padding + normalizedX * usableWidth,
            y = height - padding - normalizedY * usableHeight
        )
    }
}

private fun Int.wrapInRange(min: Int, max: Int): Int {
    val rangeSize = max - min + 1
    val normalized = (this - min) % rangeSize
    return if (normalized < 0) normalized + rangeSize + min else normalized + min
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun JustRunPreview() {
    JustRunTheme {
        JustRunApp()
    }
}
