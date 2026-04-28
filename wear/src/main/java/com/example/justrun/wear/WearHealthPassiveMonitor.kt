package com.example.justrun.wear

import android.content.Context
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig

object WearHealthPassiveMonitor {
    fun ensureRegistered(context: Context) {
        val dataTypes = buildSet {
            if (hasHeartRatePermission(context)) {
                add(DataType.HEART_RATE_BPM)
            }
        }
        if (dataTypes.isEmpty()) return
        val config = PassiveListenerConfig.builder()
            .setDataTypes(dataTypes)
            .build()
        HealthServices
            .getClient(context)
            .passiveMonitoringClient
            .setPassiveListenerServiceAsync(WearPassiveHealthService::class.java, config)
    }
}

class WearPassiveHealthService : PassiveListenerService() {
    override fun onNewDataPointsReceived(dataPoints: androidx.health.services.client.data.DataPointContainer) {
        WearHealthSnapshotStore.updateFromPassiveData(applicationContext, dataPoints)
    }

    override fun onPermissionLost() {
        WearHealthPassiveMonitor.ensureRegistered(applicationContext)
    }
}
