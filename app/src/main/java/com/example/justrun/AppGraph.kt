package com.example.justrun

import android.content.Context
import com.example.justrun.wear.WearSyncManager

object AppGraph {
    @Volatile
    private var initialized = false

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var runRepository: RunRepository
        private set

    lateinit var dailyActivityRepository: DailyActivityRepository
        private set

    lateinit var trackingController: TrackingController
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            settingsRepository = SettingsRepository(appContext)
            runRepository = RunRepository(appContext)
            dailyActivityRepository = DailyActivityRepository(appContext)
            trackingController = TrackingController(appContext, runRepository, settingsRepository)
            WearSyncManager.start(appContext, trackingController.trackingSession, settingsRepository.settings)
            initialized = true
        }
    }
}
