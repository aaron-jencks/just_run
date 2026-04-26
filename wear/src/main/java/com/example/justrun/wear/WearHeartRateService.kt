package com.example.justrun.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
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

class WearHeartRateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var measureClient: MeasureClient
    private var isMonitoring = false
    private var lastSentAtMillis: Long = 0L

    private val callback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) = Unit

        override fun onDataReceived(data: DataPointContainer) {
            val bpm = data.getData(DataType.HEART_RATE_BPM)
                .lastOrNull()
                ?.value
                ?.toInt()
                ?: return
            val now = System.currentTimeMillis()
            if (now - lastSentAtMillis < HEART_RATE_SEND_INTERVAL_MS) return
            lastSentAtMillis = now
            WearSyncStore.publish(WearSyncStore.state.value.copy(heartRate = bpm))
            WearHealthSnapshotStore.updateHeartRate(applicationContext, bpm)
            scope.launch { sendHeartRateToPhone(bpm) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        measureClient = HealthServices.getClient(this).measureClient
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = WearSyncStore.state.value
        if (!state.active || !state.heartRateEnabled || state.paused || state.autoPaused || !hasHeartRatePermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        if (isMonitoring) {
            return START_STICKY
        }
        runCatching {
            measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
            isMonitoring = true
        }.onFailure {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching {
            if (isMonitoring) {
                measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback)
            }
        }
        isMonitoring = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
            .setContentText("Monitoring heart rate")
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Heart rate", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "wear_heart_rate"
        private const val NOTIFICATION_ID = 2001
        private const val HEART_RATE_SEND_INTERVAL_MS = 1_000L
    }
}
