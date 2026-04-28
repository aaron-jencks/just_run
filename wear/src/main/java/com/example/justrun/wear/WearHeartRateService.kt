package com.example.justrun.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.max

class WearHeartRateService : Service(), SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var measureClient: MeasureClient
    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private var backgroundHeartRateSensor: Sensor? = null
    private var isWorkoutHeartRateMonitoring = false
    private var isBackgroundHeartRateMonitoring = false
    private var isStepMonitoring = false
    private var lastSentAtMillis: Long = 0L
    private var lastCalorieSampleAtMillis: Long = 0L
    private val backgroundCadenceTracker = BackgroundHeartRateCadenceTracker()

    private val callback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) = Unit

        override fun onDataReceived(data: DataPointContainer) {
            val bpm = data.getData(DataType.HEART_RATE_BPM)
                .lastOrNull()
                ?.value
                ?.toInt()
                ?: return
            val now = System.currentTimeMillis()
            if (now - lastSentAtMillis < currentHeartRateIntervalMillis(now)) return
            lastSentAtMillis = now
            val state = WearSyncStore.state.value
            updateBackgroundHeartRateCadence(state, bpm)
            WearSyncStore.publish(state.copy(heartRate = bpm))
            WearHealthSnapshotStore.updateHeartRate(applicationContext, bpm)
            accumulateCaloriesFromHeartRate(bpm, now)
            if (state.active && !state.paused && !state.autoPaused) {
                scope.launch { sendHeartRateToPhone(bpm) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        measureClient = HealthServices.getClient(this).measureClient
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        backgroundHeartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = WearSyncStore.state.value
        val config = WearHealthSnapshotStore.readMonitoringConfig(applicationContext)
        val canTrackSteps = hasDailyActivityPermission(this) && stepCounterSensor != null
        if (!config.heartRateEnabled && !canTrackSteps) {
            stopSelf()
            return START_NOT_STICKY
        }
        val shouldMonitorWorkoutHeartRate = config.heartRateEnabled &&
            hasHeartRatePermission(this) &&
            state.active
        val shouldMonitorBackgroundHeartRate = config.heartRateEnabled &&
            hasHeartRatePermission(this) &&
            !state.active &&
            config.backgroundHeartMonitoringEnabled &&
            backgroundHeartRateSensor != null
        if (!shouldMonitorWorkoutHeartRate && !shouldMonitorBackgroundHeartRate && !canTrackSteps) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())

        if (shouldMonitorWorkoutHeartRate) {
            stopBackgroundHeartRateMonitoring()
        }
        if (shouldMonitorBackgroundHeartRate) {
            stopWorkoutHeartRateMonitoring()
        }

        if (shouldMonitorWorkoutHeartRate && !isWorkoutHeartRateMonitoring) {
            runCatching {
                measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
                isWorkoutHeartRateMonitoring = true
            }.onFailure {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (!shouldMonitorWorkoutHeartRate) {
            stopWorkoutHeartRateMonitoring()
        }
        if (shouldMonitorBackgroundHeartRate && !isBackgroundHeartRateMonitoring) {
            sensorManager.registerListener(
                this,
                backgroundHeartRateSensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            isBackgroundHeartRateMonitoring = true
        }
        if (!shouldMonitorBackgroundHeartRate) {
            stopBackgroundHeartRateMonitoring()
        }
        if (canTrackSteps && !isStepMonitoring) {
            sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_NORMAL)
            isStepMonitoring = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopWorkoutHeartRateMonitoring()
        stopBackgroundHeartRateMonitoring()
        if (isStepMonitoring) {
            sensorManager.unregisterListener(this)
        }
        isStepMonitoring = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent?) {
        val now = System.currentTimeMillis()
        when (event?.sensor?.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val counterValue = event.values.firstOrNull() ?: return
                WearHealthSnapshotStore.updateLocalStepsFromCounter(applicationContext, counterValue, now)
            }
            Sensor.TYPE_HEART_RATE -> {
                val bpm = event.values.firstOrNull()?.toInt() ?: return
                processHeartRateSample(bpm, now)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private suspend fun sendHeartRateToPhone(bpm: Int) {
        val nodes = runCatching { Tasks.await(Wearable.getNodeClient(applicationContext).connectedNodes) }.getOrDefault(emptyList())
        val payload = bpm.toString().toByteArray()
        nodes.forEach { node ->
            runCatching {
                Tasks.await(Wearable.getMessageClient(applicationContext).sendMessage(node.id, PATH_HEART_RATE, payload))
            }
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Just Run")
            .setContentText(
                if (WearSyncStore.state.value.active && !WearSyncStore.state.value.paused && !WearSyncStore.state.value.autoPaused) {
                    "Monitoring heart rate during workout"
                } else {
                    "Monitoring background heart rate"
                }
            )
            .setOngoing(true)
            .build()

    private fun processHeartRateSample(bpm: Int, now: Long) {
        if (now - lastSentAtMillis < currentHeartRateIntervalMillis(now)) return
        lastSentAtMillis = now
        val state = WearSyncStore.state.value
        updateBackgroundHeartRateCadence(state, bpm)
        WearSyncStore.publish(state.copy(heartRate = bpm))
        WearHealthSnapshotStore.updateHeartRate(applicationContext, bpm)
        accumulateCaloriesFromHeartRate(bpm, now)
        if (state.active && !state.paused && !state.autoPaused) {
            scope.launch { sendHeartRateToPhone(bpm) }
        }
    }

    private fun currentHeartRateIntervalMillis(nowMillis: Long): Long {
        val state = WearSyncStore.state.value
        return if (state.active && !state.paused && !state.autoPaused) {
            ACTIVE_HEART_RATE_SEND_INTERVAL_MS
        } else {
            backgroundCadenceTracker.currentIntervalMillis
        }
    }

    private fun updateBackgroundHeartRateCadence(state: WearRunState, bpm: Int) {
        if (state.active && !state.paused && !state.autoPaused) {
            backgroundCadenceTracker.reset(bpm)
            return
        }
        backgroundCadenceTracker.updateAndGetInterval(bpm)
    }

    private fun stopWorkoutHeartRateMonitoring() {
        if (!isWorkoutHeartRateMonitoring) return
        runCatching {
            measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback)
        }
        isWorkoutHeartRateMonitoring = false
    }

    private fun stopBackgroundHeartRateMonitoring() {
        if (!isBackgroundHeartRateMonitoring) return
        sensorManager.unregisterListener(this, backgroundHeartRateSensor)
        isBackgroundHeartRateMonitoring = false
    }

    private fun accumulateCaloriesFromHeartRate(bpm: Int, nowMillis: Long) {
        val state = WearSyncStore.state.value
        val snapshot = WearHealthSnapshotStore.read(applicationContext)
        if (lastCalorieSampleAtMillis == 0L) {
            lastCalorieSampleAtMillis = nowMillis
            return
        }
        val elapsedSeconds = ((nowMillis - lastCalorieSampleAtMillis) / 1000f).coerceAtLeast(1f)
        lastCalorieSampleAtMillis = nowMillis
        val caloriesPerSecond = backgroundCaloriesPerSecond(
            weightKg = snapshot.weightKg,
            ageYears = snapshot.ageYears,
            heartRateBpm = bpm,
            activeWorkout = state.active && !state.paused && !state.autoPaused
        )
        WearHealthSnapshotStore.updateLocalCalories(
            applicationContext,
            calories = snapshot.dailyCalories + caloriesPerSecond * elapsedSeconds,
            nowMillis = nowMillis
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Heart rate", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "wear_heart_rate"
        private const val NOTIFICATION_ID = 2001
        private const val ACTIVE_HEART_RATE_SEND_INTERVAL_MS = 1_000L
    }
}

internal class BackgroundHeartRateCadenceTracker {
    var currentIntervalMillis: Long = 5_000L
        private set

    private var lastBackgroundHeartRateBpm: Int? = null
    private var stableBackgroundSampleCount: Int = 0

    fun reset(bpm: Int? = null) {
        currentIntervalMillis = 5_000L
        lastBackgroundHeartRateBpm = bpm
        stableBackgroundSampleCount = 0
    }

    fun updateAndGetInterval(bpm: Int): Long {
        val previous = lastBackgroundHeartRateBpm
        lastBackgroundHeartRateBpm = bpm
        if (previous == null) {
            reset(bpm)
            return currentIntervalMillis
        }

        val delta = kotlin.math.abs(bpm - previous)
        currentIntervalMillis = when {
            delta >= 8 -> {
                stableBackgroundSampleCount = 0
                5_000L
            }
            delta >= 4 -> {
                stableBackgroundSampleCount = 0
                10_000L
            }
            else -> {
                stableBackgroundSampleCount += 1
                when {
                    stableBackgroundSampleCount >= 4 -> 20_000L
                    stableBackgroundSampleCount >= 2 -> 10_000L
                    else -> 5_000L
                }
            }
        }
        return currentIntervalMillis
    }
}

private fun backgroundCaloriesPerSecond(
    weightKg: Float,
    ageYears: Float,
    heartRateBpm: Int,
    activeWorkout: Boolean
): Float {
    val maxHeartRate = max(140f, 208f - (0.7f * ageYears))
    val restingAnchor = if (activeWorkout) 85f else 70f
    val intensity = ((heartRateBpm - restingAnchor) / (maxHeartRate - restingAnchor)).coerceIn(0f, 1.15f)
    val minMet = if (activeWorkout) 4.0f else 1.2f
    val maxMet = if (activeWorkout) 14.0f else 5.0f
    val met = (minMet + (maxMet - minMet) * intensity).coerceAtLeast(1.1f)
    return (met * 3.5f * weightKg / 200f) / 60f
}
