package com.example.justrun.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.justrun.ActiveRunState
import com.example.justrun.AppGraph
import com.example.justrun.LapMode
import com.example.justrun.LapSplit
import com.example.justrun.LapTrigger
import com.example.justrun.LocationPoint
import com.example.justrun.MainActivity
import com.example.justrun.R
import com.example.justrun.RunGoal
import com.example.justrun.RunRecord
import com.example.justrun.TrackingSession
import com.example.justrun.UnitSystem
import com.example.justrun.ProgressCueLeadIn
import com.example.justrun.VoiceCueIntervalType
import com.example.justrun.VoiceCueMetric
import com.example.justrun.sanitizeVoiceCueDistanceIntervalKm
import com.example.justrun.sanitizeVoiceCueTimeIntervalSeconds
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.ArrayDeque
import kotlin.math.roundToInt

class RunTrackingService : Service(), TextToSpeech.OnInitListener, SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var stepDetectorSensor: Sensor? = null
    private var timerJob: Job? = null
    private var activeRun: ActiveRunState? = null
    private var autoPauseEnabled: Boolean = false
    private var voiceCuesEnabled: Boolean = false
    private var lapMode: LapMode = LapMode.AUTOMATIC
    private var lapTrigger: LapTrigger = LapTrigger.DISTANCE
    private var lapDistanceKm: Float = 1.60934f
    private var lapTimeSeconds: Int = 10 * 60
    private var runnerWeightKg: Float = 72f
    private var runnerAgeYears: Float = 31f
    private var unitSystem: UnitSystem = UnitSystem.SI
    private var lastMovementAtMillis: Long = 0L
    private var stationarySinceMillis: Long? = null
    private var calorieRemainder: Float = 0f
    private var turnAroundCueAnnounced: Boolean = false
    private var targetReachedCueAnnounced: Boolean = false
    private var voiceCueIntervalType: VoiceCueIntervalType = VoiceCueIntervalType.DISTANCE
    private var voiceCueLeadIn: ProgressCueLeadIn = ProgressCueLeadIn.DISTANCE_COMPLETED
    private var voiceCueMetricOrder: List<VoiceCueMetric> = VoiceCueMetric.entries
    private var voiceCueEnabledMetrics: Set<VoiceCueMetric> = VoiceCueMetric.entries.toSet()
    private var voiceCueTimeIntervalSeconds: Int = DEFAULT_VOICE_CUE_TIME_INTERVAL_SECONDS
    private var voiceCueDistanceIntervalKm: Float = DEFAULT_VOICE_CUE_DISTANCE_INTERVAL_KM
    private var utteranceSequence: Long = 0L
    private var textToSpeech: TextToSpeech? = null
    private var textToSpeechReady: Boolean = false
    private val recentStepTimestamps = ArrayDeque<Long>()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val current = activeRun ?: return
            result.lastLocation?.let { location ->
                val point = LocationPoint(
                    timestampMillis = System.currentTimeMillis(),
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitudeMeters = location.altitude,
                    accuracyMeters = location.accuracy,
                    speedMetersPerSecond = location.speed
                )

                val lastPoint = current.routePoints.lastOrNull()
                val accepted = shouldAcceptPoint(lastPoint, point)
                val isMoving = isMovementSample(point, lastPoint)

                if (isMoving) {
                    lastMovementAtMillis = point.timestampMillis
                    stationarySinceMillis = null
                } else if (stationarySinceMillis == null) {
                    stationarySinceMillis = point.timestampMillis
                }

                if (!accepted) {
                    val shouldAutoPause = autoPauseEnabled &&
                        !current.paused &&
                        stationarySinceMillis != null &&
                        point.timestampMillis - stationarySinceMillis!! >= AUTO_PAUSE_AFTER_MS
                    if (shouldAutoPause && !current.autoPaused) {
                        activeRun = current.copy(autoPaused = true)
                        speakVoiceCue("Run paused", AUTO_PAUSE_UTTERANCE_ID)
                        publishState()
                    }
                    return@let
                }

                val route = current.routePoints + point
                val incrementalDistanceKm = if (lastPoint == null) 0f else distanceBetweenKm(lastPoint, point)
                val elevationGain = if (lastPoint == null) 0 else maxOf(0.0, point.altitudeMeters - lastPoint.altitudeMeters).roundToInt()
                val nextDistance = current.distanceKm + incrementalDistanceKm
                val pace = if (nextDistance > 0f && current.elapsedSeconds > 0) {
                    current.elapsedSeconds / 60f / nextDistance
                } else {
                    null
                }

                activeRun = current.copy(
                    routePoints = route,
                    distanceKm = nextDistance,
                    avgPaceMinPerKm = pace,
                    currentPaceMinPerKm = if (point.speedMetersPerSecond > 0f) (1000f / point.speedMetersPerSecond) / 60f else current.currentPaceMinPerKm,
                    elevationGainM = current.elevationGainM + elevationGain,
                    autoPaused = false
                )
                if (current.autoPaused) {
                    speakVoiceCue("Run resumed", AUTO_RESUME_UTTERANCE_ID)
                }
                val completedLapCount = maybeRecordAutomaticLaps()
                if (completedLapCount > 0) {
                    repeat(completedLapCount) { offset ->
                        val lapIndex = ((activeRun?.lapSplits?.size ?: 0) - completedLapCount + offset + 1).coerceAtLeast(1)
                        maybeSpeakLapProgressCue(activeRun, lapIndex)
                    }
                }
                maybeSpeakIntervalCue(previousRun = current, run = activeRun)
                maybeSpeakTurnAroundCue(activeRun)
                maybeSpeakTargetReachedCue(activeRun)
                publishState()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(applicationContext)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        createNotificationChannel()
        textToSpeech = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRun(intent)
            ACTION_PAUSE -> pauseRun(manualPause = true)
            ACTION_RESUME -> resumeRun()
            ACTION_MARK_LAP -> markLap()
            ACTION_UPDATE_HEART_RATE -> updateHeartRate(intent.getIntExtra(EXTRA_HEART_RATE_BPM, -1))
            ACTION_STOP -> stopRun()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        timerJob?.cancel()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        unregisterCadenceSensor()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        scope.cancel()
    }

    override fun onInit(status: Int) {
        textToSpeechReady = status == TextToSpeech.SUCCESS
        if (textToSpeechReady) {
            textToSpeech?.language = Locale.US
        }
    }

    private fun startRun(intent: Intent) {
        val gpsEnabled = intent.getBooleanExtra(EXTRA_GPS_ENABLED, true)
        val heartRateEnabled = intent.getBooleanExtra(EXTRA_HR_ENABLED, true)
        autoPauseEnabled = intent.getBooleanExtra(EXTRA_AUTO_PAUSE_ENABLED, false)
        lapMode = intent.getStringExtra(EXTRA_LAP_MODE)?.let(LapMode::valueOf) ?: LapMode.AUTOMATIC
        lapTrigger = intent.getStringExtra(EXTRA_LAP_TRIGGER)?.let(LapTrigger::valueOf) ?: LapTrigger.DISTANCE
        lapDistanceKm = intent.getFloatExtra(EXTRA_LAP_DISTANCE_KM, 1.60934f)
        lapTimeSeconds = intent.getIntExtra(EXTRA_LAP_TIME_SECONDS, 10 * 60)
        voiceCuesEnabled = intent.getBooleanExtra(EXTRA_VOICE_CUES_ENABLED, true)
        voiceCueIntervalType = intent.getStringExtra(EXTRA_VOICE_CUE_INTERVAL_TYPE)?.let(VoiceCueIntervalType::valueOf)
            ?: VoiceCueIntervalType.DISTANCE
        voiceCueTimeIntervalSeconds = sanitizeVoiceCueTimeIntervalSeconds(
            intent.getIntExtra(EXTRA_VOICE_CUE_TIME_INTERVAL_SECONDS, DEFAULT_VOICE_CUE_TIME_INTERVAL_SECONDS)
        )
        voiceCueDistanceIntervalKm = sanitizeVoiceCueDistanceIntervalKm(
            intent.getFloatExtra(EXTRA_VOICE_CUE_DISTANCE_INTERVAL_KM, DEFAULT_VOICE_CUE_DISTANCE_INTERVAL_KM)
        )
        voiceCueLeadIn = intent.getStringExtra(EXTRA_VOICE_CUE_LEAD_IN)?.let(ProgressCueLeadIn::valueOf)
            ?: ProgressCueLeadIn.DISTANCE_COMPLETED
        voiceCueMetricOrder = intent.getStringExtra(EXTRA_VOICE_CUE_METRIC_ORDER)
            ?.split(',')
            ?.mapNotNull { raw -> VoiceCueMetric.entries.firstOrNull { it.name == raw } }
            ?.takeIf { it.isNotEmpty() }
            ?: VoiceCueMetric.entries
        voiceCueEnabledMetrics = intent.getStringExtra(EXTRA_VOICE_CUE_ENABLED_METRICS)
            ?.split(',')
            ?.mapNotNull { raw -> VoiceCueMetric.entries.firstOrNull { it.name == raw } }
            ?.takeIf { it.isNotEmpty() }
            ?.toSet()
            ?: VoiceCueMetric.entries.toSet()
        unitSystem = intent.getStringExtra(EXTRA_UNIT_SYSTEM)?.let(UnitSystem::valueOf) ?: UnitSystem.SI
        runnerWeightKg = intent.getFloatExtra(EXTRA_WEIGHT_KG, 72f)
        runnerAgeYears = intent.getFloatExtra(EXTRA_AGE_YEARS, 31f)
        val goal = RunGoal.valueOf(intent.getStringExtra(EXTRA_GOAL) ?: RunGoal.ENDLESS.name)
        val distanceGoalKm = intent.getFloatExtra(EXTRA_DISTANCE_GOAL_KM, 0f).takeIf { it > 0f }
        val durationGoalSeconds = intent.getIntExtra(EXTRA_DURATION_GOAL_SECONDS, 0).takeIf { it > 0 }
        calorieRemainder = 0f
        lastMovementAtMillis = System.currentTimeMillis()
        stationarySinceMillis = null
        turnAroundCueAnnounced = false
        targetReachedCueAnnounced = false
        utteranceSequence = 0L
        recentStepTimestamps.clear()

        activeRun = ActiveRunState(
            goal = goal,
            targetDistanceKm = distanceGoalKm,
            targetDurationSeconds = durationGoalSeconds,
            startedAtMillis = System.currentTimeMillis(),
            elapsedSeconds = 0,
            distanceKm = 0f,
            avgPaceMinPerKm = null,
            currentPaceMinPerKm = null,
            calories = 0,
            heartRate = null,
            cadence = 0,
            elevationGainM = 0,
            lapSplits = emptyList(),
            lapMode = lapMode,
            lapTrigger = lapTrigger,
            lapDistanceKm = lapDistanceKm.takeIf { lapMode == LapMode.AUTOMATIC && lapTrigger == LapTrigger.DISTANCE },
            lapTimeSeconds = lapTimeSeconds.takeIf { lapMode == LapMode.AUTOMATIC && lapTrigger == LapTrigger.TIME },
            paused = false,
            autoPaused = false,
            gpsEnabled = gpsEnabled,
            heartRateEnabled = heartRateEnabled,
            routePoints = emptyList()
        )

        startForeground(NOTIFICATION_ID, buildNotification())
        startTicker()
        if (gpsEnabled) startLocationUpdates()
        registerCadenceSensorIfAvailable()
        publishState()
    }

    private fun pauseRun(manualPause: Boolean) {
        activeRun = activeRun?.copy(paused = manualPause)
        publishState()
    }

    private fun resumeRun() {
        activeRun = activeRun?.copy(paused = false, autoPaused = false)
        publishState()
    }

    private fun markLap() {
        val previousLapCount = activeRun?.lapSplits?.size ?: 0
        activeRun = createLapSplit(activeRun)
        if ((activeRun?.lapSplits?.size ?: 0) > previousLapCount) {
            maybeSpeakLapProgressCue(activeRun, activeRun?.lapSplits?.size ?: previousLapCount + 1)
        }
        publishState()
    }

    private fun updateHeartRate(bpm: Int) {
        if (bpm <= 0) return
        val current = activeRun ?: return
        if (!current.heartRateEnabled) return
        activeRun = current.copy(heartRate = bpm)
        publishState()
    }

    private fun stopRun() {
        val run = finalizePendingLap(activeRun ?: return)
        val record = RunRecord(
            id = System.currentTimeMillis(),
            title = when (run.goal) {
                RunGoal.ENDLESS -> "Open run"
                RunGoal.DURATION -> "Duration run"
                RunGoal.DISTANCE -> "Distance run"
            },
            dateLabel = formatDateLabel(run.startedAtMillis),
            startedAtMillis = run.startedAtMillis,
            goal = run.goal,
            durationSeconds = run.elapsedSeconds,
            distanceKm = run.distanceKm,
            avgPaceMinPerKm = run.avgPaceMinPerKm,
            calories = run.calories,
            elevationGainM = run.elevationGainM,
            notes = if (run.gpsEnabled) "Tracked on-device with precise GPS." else "Tracked offline without GPS.",
            paceSeries = buildPaceSeries(run),
            elevationSeries = buildElevationSeries(run),
            lapSplits = run.lapSplits,
            routePoints = run.routePoints,
            hadGpsTracking = run.gpsEnabled,
            hadHeartRateTracking = run.heartRateEnabled
        )
        AppGraph.runRepository.addRun(record)
        activeRun = null
        AppGraph.trackingController.publish(TrackingSession(activeRun = null, completedRunId = record.id))
        timerJob?.cancel()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        unregisterCadenceSensor()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startTicker() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (true) {
                delay(1000)
                val current = activeRun ?: break
                if (!current.paused && !current.autoPaused) {
                    val nextElapsed = current.elapsedSeconds + 1
                    pruneOldStepSamples(System.currentTimeMillis())
                    val calorieIncrement = caloriesPerSecond(
                        weightKg = runnerWeightKg,
                        ageYears = runnerAgeYears,
                        heartRateBpm = current.heartRate.takeIf { current.heartRateEnabled },
                        currentPaceMinPerKm = current.currentPaceMinPerKm,
                        gpsEnabled = current.gpsEnabled
                    )
                    calorieRemainder += calorieIncrement
                    val wholeCalories = calorieRemainder.toInt()
                    calorieRemainder -= wholeCalories
                    activeRun = current.copy(
                        elapsedSeconds = nextElapsed,
                        calories = current.calories + wholeCalories,
                        cadence = calculateCadenceSpm(System.currentTimeMillis(), recentStepTimestamps.toList())
                    )
                    val completedLapCount = maybeRecordAutomaticLaps()
                    if (completedLapCount > 0) {
                        repeat(completedLapCount) { offset ->
                            val lapIndex = ((activeRun?.lapSplits?.size ?: 0) - completedLapCount + offset + 1).coerceAtLeast(1)
                            maybeSpeakLapProgressCue(activeRun, lapIndex)
                        }
                    }
                    maybeSpeakIntervalCue(previousRun = current, run = activeRun)
                    maybeSpeakTurnAroundCue(activeRun)
                    maybeSpeakTargetReachedCue(activeRun)
                    publishState()
                }
            }
        }
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, mainLooper)
    }

    private fun publishState() {
        AppGraph.trackingController.publish(TrackingSession(activeRun = activeRun, completedRunId = null))
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val current = activeRun ?: return
        if (event?.sensor?.type != Sensor.TYPE_STEP_DETECTOR) return
        if (current.paused || current.autoPaused) return
        val now = System.currentTimeMillis()
        repeat(event.values.firstOrNull()?.toInt()?.coerceAtLeast(1) ?: 1) {
            recentStepTimestamps.addLast(now)
        }
        pruneOldStepSamples(now)
        activeRun = current.copy(cadence = calculateCadenceSpm(now, recentStepTimestamps.toList()))
        publishState()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun registerCadenceSensorIfAvailable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) return
        val sensor = stepDetectorSensor ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun unregisterCadenceSensor() {
        sensorManager.unregisterListener(this)
        recentStepTimestamps.clear()
    }

    private fun pruneOldStepSamples(nowMillis: Long) {
        while (recentStepTimestamps.isNotEmpty() && nowMillis - recentStepTimestamps.first() > CADENCE_WINDOW_MS) {
            recentStepTimestamps.removeFirst()
        }
    }

    private fun maybeSpeakTurnAroundCue(run: ActiveRunState?) {
        val current = run ?: return
        refreshLiveVoiceCueSettings()
        if (!voiceCuesEnabled || turnAroundCueAnnounced || !textToSpeechReady) return
        if (!shouldTriggerTurnAroundCue(current)) return
        speakVoiceCue("Turn around now", TURN_AROUND_UTTERANCE_ID)
        turnAroundCueAnnounced = true
    }

    private fun maybeSpeakTargetReachedCue(run: ActiveRunState?) {
        val current = run ?: return
        refreshLiveVoiceCueSettings()
        if (!voiceCuesEnabled || targetReachedCueAnnounced || !textToSpeechReady) return
        if (!shouldTriggerTargetReachedCue(current)) return
        speakVoiceCue("Target reached", TARGET_REACHED_UTTERANCE_ID)
        targetReachedCueAnnounced = true
    }

    private fun speakVoiceCue(text: String, utteranceId: String) {
        if (!voiceCuesEnabled || !textToSpeechReady) return
        utteranceSequence += 1
        textToSpeech?.speak(text, TextToSpeech.QUEUE_ADD, null, "${utteranceId}_$utteranceSequence")
    }

    private fun maybeSpeakIntervalCue(previousRun: ActiveRunState, run: ActiveRunState?) {
        val current = run ?: return
        refreshLiveVoiceCueSettings()
        if (!voiceCuesEnabled || !textToSpeechReady) return
        when (voiceCueIntervalType) {
            VoiceCueIntervalType.TIME -> {
                if (!crossedTimeCueBoundary(previousRun.elapsedSeconds, current.elapsedSeconds, voiceCueTimeIntervalSeconds)) return
                speakProgressCue(
                    current,
                    ProgressCueEvent(
                        trigger = VoiceCueIntervalType.TIME,
                        elapsedSeconds = current.elapsedSeconds,
                        distanceKm = current.distanceKm
                    ),
                    INTERVAL_UTTERANCE_ID
                )
            }
            VoiceCueIntervalType.DISTANCE -> {
                if (!current.gpsEnabled || !crossedDistanceCueBoundary(previousRun.distanceKm, current.distanceKm, voiceCueDistanceIntervalKm)) return
                speakProgressCue(
                    current,
                    ProgressCueEvent(
                        trigger = VoiceCueIntervalType.DISTANCE,
                        elapsedSeconds = current.elapsedSeconds,
                        distanceKm = current.distanceKm
                    ),
                    INTERVAL_UTTERANCE_ID
                )
            }
            VoiceCueIntervalType.LAP -> Unit
        }
    }

    private fun maybeSpeakLapProgressCue(run: ActiveRunState?, lapNumber: Int) {
        val current = run ?: return
        refreshLiveVoiceCueSettings()
        if (voiceCueIntervalType != VoiceCueIntervalType.LAP) return
        speakProgressCue(
            current,
            ProgressCueEvent(
                trigger = VoiceCueIntervalType.LAP,
                lapNumber = lapNumber,
                elapsedSeconds = current.elapsedSeconds,
                distanceKm = current.distanceKm,
                lapDistanceKm = current.lapSplits.lastOrNull()?.distanceKm
            ),
            LAP_COMPLETED_UTTERANCE_ID
        )
    }

    private fun speakProgressCue(
        run: ActiveRunState,
        event: ProgressCueEvent,
        utteranceId: String
    ) {
        val text = buildProgressCueText(
            run = run,
            event = event,
            leadIn = voiceCueLeadIn,
            metricOrder = activeVoiceCueMetrics(),
            unitSystem = unitSystem
        )
        if (text.isNotBlank()) {
            speakVoiceCue(text, utteranceId)
        }
    }

    private fun activeVoiceCueMetrics(): List<VoiceCueMetric> =
        voiceCueMetricOrder.filter { it in voiceCueEnabledMetrics }

    private fun refreshLiveVoiceCueSettings() {
        val settings = AppGraph.settingsRepository.settings.value
        voiceCuesEnabled = settings.voiceCues
        voiceCueIntervalType = settings.voiceCueIntervalType
        voiceCueTimeIntervalSeconds = sanitizeVoiceCueTimeIntervalSeconds(settings.voiceCueTimeIntervalSeconds)
        voiceCueDistanceIntervalKm = sanitizeVoiceCueDistanceIntervalKm(settings.voiceCueDistanceIntervalKm)
        voiceCueLeadIn = settings.voiceCueLeadIn
        voiceCueMetricOrder = settings.voiceCueMetricOrder
        voiceCueEnabledMetrics = settings.voiceCueEnabledMetrics.toSet()
        unitSystem = settings.unitSystem
    }

    private fun maybeRecordAutomaticLaps(): Int {
        var completedLapCount = 0
        while (shouldCreateAutomaticLap(activeRun)) {
            activeRun = createLapSplit(activeRun)
            completedLapCount += 1
        }
        return completedLapCount
    }

    private fun buildNotification(): Notification {
        val run = activeRun
        val launchIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, RunTrackingService::class.java).setAction(if (run?.paused == true) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, RunTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Just Run tracking")
            .setContentText(
                run?.let {
                    val unitSystem = AppGraph.settingsRepository.settings.value.unitSystem
                    "Elapsed ${it.elapsedSeconds}s • ${formatNotificationDistance(it.distanceKm, unitSystem)}"
                } ?: "Preparing run"
            )
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .addAction(0, if (run?.paused == true) "Resume" else "Pause", pauseIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Run tracking", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.example.justrun.action.START"
        const val ACTION_PAUSE = "com.example.justrun.action.PAUSE"
        const val ACTION_RESUME = "com.example.justrun.action.RESUME"
        const val ACTION_MARK_LAP = "com.example.justrun.action.MARK_LAP"
        const val ACTION_UPDATE_HEART_RATE = "com.example.justrun.action.UPDATE_HEART_RATE"
        const val ACTION_STOP = "com.example.justrun.action.STOP"
        const val EXTRA_GOAL = "goal"
        const val EXTRA_DISTANCE_GOAL_KM = "distance_goal_km"
        const val EXTRA_DURATION_GOAL_SECONDS = "duration_goal_seconds"
        const val EXTRA_GPS_ENABLED = "gps_enabled"
        const val EXTRA_HR_ENABLED = "hr_enabled"
        const val EXTRA_AUTO_PAUSE_ENABLED = "auto_pause_enabled"
        const val EXTRA_LAP_MODE = "lap_mode"
        const val EXTRA_LAP_TRIGGER = "lap_trigger"
        const val EXTRA_LAP_DISTANCE_KM = "lap_distance_km"
        const val EXTRA_LAP_TIME_SECONDS = "lap_time_seconds"
        const val EXTRA_VOICE_CUES_ENABLED = "voice_cues_enabled"
        const val EXTRA_VOICE_CUE_INTERVAL_TYPE = "voice_cue_interval_type"
        const val EXTRA_VOICE_CUE_TIME_INTERVAL_SECONDS = "voice_cue_time_interval_seconds"
        const val EXTRA_VOICE_CUE_DISTANCE_INTERVAL_KM = "voice_cue_distance_interval_km"
        const val EXTRA_VOICE_CUE_LEAD_IN = "voice_cue_lead_in"
        const val EXTRA_VOICE_CUE_METRIC_ORDER = "voice_cue_metric_order"
        const val EXTRA_VOICE_CUE_ENABLED_METRICS = "voice_cue_enabled_metrics"
        const val EXTRA_UNIT_SYSTEM = "unit_system"
        const val EXTRA_WEIGHT_KG = "weight_kg"
        const val EXTRA_AGE_YEARS = "age_years"
        const val EXTRA_HEART_RATE_BPM = "heart_rate_bpm"
        private const val CHANNEL_ID = "run_tracking"
        private const val NOTIFICATION_ID = 1001
        private const val TURN_AROUND_UTTERANCE_ID = "turn_around"
        private const val AUTO_PAUSE_UTTERANCE_ID = "auto_pause"
        private const val AUTO_RESUME_UTTERANCE_ID = "auto_resume"
        private const val TARGET_REACHED_UTTERANCE_ID = "target_reached"
        private const val LAP_COMPLETED_UTTERANCE_ID = "lap_completed"
        private const val INTERVAL_UTTERANCE_ID = "interval"
        internal const val MIN_ACCEPTED_DISTANCE_METERS = 5f
        internal const val MAX_ACCEPTED_ACCURACY_METERS = 25f
        internal const val AUTO_PAUSE_AFTER_MS = 12_000L
        internal const val AUTO_PAUSE_MIN_MOVING_SPEED_METERS_PER_SECOND = 1.2f
        internal const val CADENCE_WINDOW_MS = 20_000L
        private const val DEFAULT_VOICE_CUE_TIME_INTERVAL_SECONDS = 5 * 60
        private const val DEFAULT_VOICE_CUE_DISTANCE_INTERVAL_KM = 1f
        private const val DEFAULT_VOICE_CUE_DISTANCE_INTERVAL_MILES = 1f
        private const val KM_PER_MILE = 1.60934f
    }
}

private fun distanceBetweenKm(start: LocationPoint, end: LocationPoint): Float {
    val result = FloatArray(1)
    android.location.Location.distanceBetween(
        start.latitude,
        start.longitude,
        end.latitude,
        end.longitude,
        result
    )
    return result[0] / 1000f
}

private fun formatDateLabel(startedAtMillis: Long): String =
    java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US).format(java.util.Date(startedAtMillis))

private fun formatNotificationDistance(distanceKm: Float, unitSystem: UnitSystem): String {
    val displayDistance = if (unitSystem == UnitSystem.SI) distanceKm else distanceKm * 0.621371f
    val unitLabel = if (unitSystem == UnitSystem.SI) "km" else "mi"
    return "${"%.2f".format(displayDistance)} $unitLabel"
}

private fun buildPaceSeries(run: ActiveRunState): List<Float> =
    run.routePoints
        .windowed(size = 2, step = 1, partialWindows = false)
        .mapNotNull { (start, end) ->
            val distanceKm = distanceBetweenKm(start, end)
            val durationSeconds = ((end.timestampMillis - start.timestampMillis) / 1000f).coerceAtLeast(1f)
            if (distanceKm <= 0f || durationSeconds > 15f) null else (durationSeconds / 60f) / distanceKm
        }
        .ifEmpty { listOfNotNull(run.avgPaceMinPerKm) }
        .evenlySampled(60)

private fun buildElevationSeries(run: ActiveRunState): List<Float> =
    if (run.routePoints.isEmpty()) listOf(0f) else run.routePoints.map { it.altitudeMeters.toFloat() }.evenlySampled(60)

private fun List<Float>.evenlySampled(maxSamples: Int): List<Float> {
    if (size <= maxSamples) return this
    if (maxSamples <= 1) return listOf(first())
    val sourceLastIndex = lastIndex.toFloat()
    return List(maxSamples) { sampleIndex ->
        val sourceIndex = ((sampleIndex.toFloat() / (maxSamples - 1)) * sourceLastIndex).toInt()
        this[sourceIndex.coerceIn(indices)]
    }
}

internal fun shouldAcceptPoint(previous: LocationPoint?, candidate: LocationPoint): Boolean {
    if (candidate.accuracyMeters > RunTrackingService.MAX_ACCEPTED_ACCURACY_METERS) return false
    if (previous == null) return true
    val distanceMeters = distanceBetweenKm(previous, candidate) * 1000f
    return distanceMeters >= RunTrackingService.MIN_ACCEPTED_DISTANCE_METERS
}

internal fun isMovementSample(candidate: LocationPoint, previous: LocationPoint?): Boolean {
    if (candidate.accuracyMeters > RunTrackingService.MAX_ACCEPTED_ACCURACY_METERS) return false
    if (candidate.speedMetersPerSecond >= RunTrackingService.AUTO_PAUSE_MIN_MOVING_SPEED_METERS_PER_SECOND) return true
    if (previous == null) return false
    val distanceMeters = distanceBetweenKm(previous, candidate) * 1000f
    val durationSeconds = ((candidate.timestampMillis - previous.timestampMillis) / 1000f).coerceAtLeast(1f)
    return (distanceMeters / durationSeconds) >= RunTrackingService.AUTO_PAUSE_MIN_MOVING_SPEED_METERS_PER_SECOND
}

internal fun caloriesPerSecond(
    weightKg: Float,
    ageYears: Float,
    heartRateBpm: Int?,
    currentPaceMinPerKm: Float?,
    gpsEnabled: Boolean
): Float {
    val fallbackMet = when {
        !gpsEnabled -> 6.0f
        currentPaceMinPerKm == null -> 3.0f
        currentPaceMinPerKm <= 4.5f -> 11.0f
        currentPaceMinPerKm <= 5.5f -> 9.8f
        currentPaceMinPerKm <= 7.0f -> 8.3f
        else -> 6.0f
    }
    val heartRateMet = heartRateBpm?.let { bpm ->
        estimatedMetFromHeartRate(
            heartRateBpm = bpm,
            ageYears = ageYears,
            gpsEnabled = gpsEnabled
        )
    }
    val met = when {
        heartRateMet == null -> fallbackMet
        gpsEnabled -> maxOf(fallbackMet * 0.85f, heartRateMet)
        else -> heartRateMet
    }
    return (met * 3.5f * weightKg / 200f) / 60f
}

internal fun calculateCadenceSpm(nowMillis: Long, stepTimestampsMillis: List<Long>): Int {
    if (stepTimestampsMillis.isEmpty()) return 0
    val recent = stepTimestampsMillis.filter { nowMillis - it <= RunTrackingService.CADENCE_WINDOW_MS }
    if (recent.isEmpty()) return 0
    val windowSeconds = (RunTrackingService.CADENCE_WINDOW_MS / 1000f)
    return ((recent.size * 60f) / windowSeconds).roundToInt()
}

internal fun estimatedMetFromHeartRate(
    heartRateBpm: Int,
    ageYears: Float,
    gpsEnabled: Boolean
): Float? {
    if (heartRateBpm <= 0) return null
    val maxHeartRate = (208f - (0.7f * ageYears)).coerceAtLeast(140f)
    val restingAnchor = if (gpsEnabled) 85f else 75f
    val intensity = ((heartRateBpm - restingAnchor) / (maxHeartRate - restingAnchor))
        .coerceIn(0f, 1.15f)
    val minMet = if (gpsEnabled) 4.0f else 2.0f
    val maxMet = if (gpsEnabled) 14.0f else 8.0f
    return (minMet + (maxMet - minMet) * intensity).coerceAtLeast(1.5f)
}

internal fun buildVoiceCueUpdate(
    run: ActiveRunState,
    metricOrder: List<VoiceCueMetric>,
    unitSystem: UnitSystem = UnitSystem.SI
): String {
    val details = buildVoiceCueMetricReport(run, metricOrder = metricOrder, unitSystem = unitSystem)
    return if (details.isBlank()) "Update" else "Update. $details"
}

internal data class ProgressCueEvent(
    val trigger: VoiceCueIntervalType,
    val lapNumber: Int? = null,
    val elapsedSeconds: Int,
    val distanceKm: Float,
    val lapDistanceKm: Float? = null
)

internal fun crossedTimeCueBoundary(
    previousElapsedSeconds: Int,
    currentElapsedSeconds: Int,
    intervalSeconds: Int
): Boolean {
    if (intervalSeconds <= 0) return false
    return currentElapsedSeconds / intervalSeconds > previousElapsedSeconds / intervalSeconds
}

internal fun crossedDistanceCueBoundary(
    previousDistanceKm: Float,
    currentDistanceKm: Float,
    intervalDistanceKm: Float
): Boolean {
    if (intervalDistanceKm <= 0f) return false
    return kotlin.math.floor(currentDistanceKm / intervalDistanceKm).toInt() >
        kotlin.math.floor(previousDistanceKm / intervalDistanceKm).toInt()
}

internal fun buildProgressCueText(
    run: ActiveRunState,
    event: ProgressCueEvent,
    leadIn: ProgressCueLeadIn,
    metricOrder: List<VoiceCueMetric>,
    unitSystem: UnitSystem = UnitSystem.SI
): String {
    val intro = buildProgressCueLeadIn(event, leadIn, unitSystem)
    val details = buildVoiceCueMetricReport(run, event = event, metricOrder = metricOrder, unitSystem = unitSystem)
    return when {
        intro.isBlank() && details.isBlank() -> "Update"
        intro.isBlank() -> details
        details.isBlank() -> intro
        else -> "$intro. $details"
    }
}

private fun buildProgressCueLeadIn(
    event: ProgressCueEvent,
    leadIn: ProgressCueLeadIn,
    unitSystem: UnitSystem
): String = when (leadIn) {
    ProgressCueLeadIn.NONE -> ""
    ProgressCueLeadIn.LAP_NUMBER -> event.lapNumber?.let { "Lap $it completed" }.orEmpty()
    ProgressCueLeadIn.DISTANCE_COMPLETED -> "${speakableDistance(event.distanceKm, unitSystem)} completed"
    ProgressCueLeadIn.TIME_COMPLETED -> "${speakableDuration(event.elapsedSeconds)} completed"
}

internal fun buildVoiceCueMetricReport(
    run: ActiveRunState,
    event: ProgressCueEvent? = null,
    metricOrder: List<VoiceCueMetric>,
    unitSystem: UnitSystem
): String =
    metricOrder.mapNotNull { metric ->
        when (metric) {
            VoiceCueMetric.ELAPSED_TIME -> "Elapsed time ${speakableDuration(run.elapsedSeconds)}"
            VoiceCueMetric.REMAINING_TIME -> speakableRemainingTime(run, unitSystem)
            VoiceCueMetric.AVERAGE_PACE -> run.avgPaceMinPerKm?.let { "Average pace ${speakablePace(it, unitSystem)}" }
            VoiceCueMetric.TOTAL_DISTANCE -> if (run.gpsEnabled) "Total distance ${speakableDistance(run.distanceKm, unitSystem)}" else null
            VoiceCueMetric.LAP_DISTANCE -> if (event?.trigger == VoiceCueIntervalType.LAP) {
                event.lapDistanceKm?.let { "Lap distance ${speakableDistance(it, unitSystem)}" }
            } else {
                null
            }
        }
    }.joinToString(". ")

private fun speakableRemainingTime(run: ActiveRunState, unitSystem: UnitSystem): String? = when (run.goal) {
    RunGoal.ENDLESS -> null
    RunGoal.DURATION -> {
        val remaining = ((run.targetDurationSeconds ?: 0) - run.elapsedSeconds).coerceAtLeast(0)
        "Remaining time ${speakableDuration(remaining)}"
    }
    RunGoal.DISTANCE -> {
        if (!run.gpsEnabled) null
        else {
            val remainingKm = ((run.targetDistanceKm ?: 0f) - run.distanceKm).coerceAtLeast(0f)
            "Remaining distance ${speakableDistance(remainingKm, unitSystem)}"
        }
    }
}

private fun speakableDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return buildList {
        if (hours > 0) add("$hours hour${if (hours == 1) "" else "s"}")
        if (minutes > 0) add("$minutes minute${if (minutes == 1) "" else "s"}")
        if (seconds > 0 || isEmpty()) add("$seconds second${if (seconds == 1) "" else "s"}")
    }.joinToString(" ")
}

private fun speakablePace(paceMinPerKm: Float, unitSystem: UnitSystem): String {
    val displayPace = if (unitSystem == UnitSystem.SI) paceMinPerKm else paceMinPerKm * 1.60934f
    val wholeMinutes = displayPace.toInt()
    val seconds = ((displayPace - wholeMinutes) * 60).toInt().coerceIn(0, 59)
    val unit = if (unitSystem == UnitSystem.SI) "kilometer" else "mile"
    return "$wholeMinutes minute${if (wholeMinutes == 1) "" else "s"} $seconds second${if (seconds == 1) "" else "s"} per $unit"
}

private fun speakableDistance(distanceKm: Float, unitSystem: UnitSystem): String {
    val displayDistance = if (unitSystem == UnitSystem.SI) distanceKm else distanceKm * 0.621371f
    val rounded = String.format(Locale.US, "%.1f", displayDistance)
    val unit = if (unitSystem == UnitSystem.SI) "kilometers" else "miles"
    return "$rounded $unit"
}

internal fun shouldTriggerTurnAroundCue(run: ActiveRunState): Boolean = when (run.goal) {
    RunGoal.ENDLESS -> false
    RunGoal.DURATION -> {
        val target = run.targetDurationSeconds ?: return false
        target > 0 && run.elapsedSeconds >= target / 2
    }
    RunGoal.DISTANCE -> {
        val target = run.targetDistanceKm ?: return false
        target > 0f && run.distanceKm >= target / 2f
    }
}

internal fun shouldTriggerTargetReachedCue(run: ActiveRunState): Boolean = when (run.goal) {
    RunGoal.ENDLESS -> false
    RunGoal.DURATION -> {
        val target = run.targetDurationSeconds ?: return false
        target > 0 && run.elapsedSeconds >= target
    }
    RunGoal.DISTANCE -> {
        val target = run.targetDistanceKm ?: return false
        target > 0f && run.distanceKm >= target
    }
}

private fun shouldCreateAutomaticLap(run: ActiveRunState?): Boolean {
    val current = run ?: return false
    if (current.lapMode != LapMode.AUTOMATIC) return false
    return when (current.lapTrigger) {
        LapTrigger.DISTANCE -> {
            val target = current.lapDistanceKm ?: return false
            target > 0f && current.distanceKm - completedLapDistanceKm(current) >= target
        }
        LapTrigger.TIME -> {
            val target = current.lapTimeSeconds ?: return false
            target > 0 && current.elapsedSeconds - completedLapDurationSeconds(current) >= target
        }
    }
}

private fun createLapSplit(run: ActiveRunState?): ActiveRunState? {
    val current = run ?: return null
    val lapDuration = current.elapsedSeconds - completedLapDurationSeconds(current)
    val lapDistance = current.distanceKm - completedLapDistanceKm(current)
    val lapCalories = current.calories - completedLapCalories(current)
    if (lapDuration <= 0 && lapDistance <= 0f && lapCalories <= 0) return current
    val lapPace = if (lapDistance > 0f && lapDuration > 0) lapDuration / 60f / lapDistance else null
    val lap = LapSplit(
        index = current.lapSplits.size + 1,
        elapsedSeconds = current.elapsedSeconds,
        durationSeconds = lapDuration,
        distanceKm = lapDistance,
        calories = lapCalories,
        avgPaceMinPerKm = lapPace
    )
    return current.copy(lapSplits = current.lapSplits + lap)
}

private fun finalizePendingLap(run: ActiveRunState): ActiveRunState =
    createLapSplit(run) ?: run

private fun completedLapDurationSeconds(run: ActiveRunState): Int =
    run.lapSplits.sumOf { it.durationSeconds }

private fun completedLapDistanceKm(run: ActiveRunState): Float =
    run.lapSplits.sumOf { it.distanceKm.toDouble() }.toFloat()

private fun completedLapCalories(run: ActiveRunState): Int =
    run.lapSplits.sumOf { it.calories }
