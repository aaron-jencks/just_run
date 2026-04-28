package com.example.justrun

import android.content.Context
import com.example.justrun.wear.WearSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

object AppGraph {
    @Volatile
    private var initialized = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
            dailyActivityRepository.startMonitoring()
            trackingController = TrackingController(appContext, runRepository, settingsRepository)
            WearSyncManager.start(appContext, trackingController.trackingSession, settingsRepository.settings, dailyActivityRepository.localSyncPayload)
            startDailyCalorieRefreshLoop()
            initialized = true
        }
    }

    private fun startDailyCalorieRefreshLoop() {
        scope.launch {
            while (true) {
                refreshDailyCalories()
                delay(60_000L)
            }
        }
    }

    private fun refreshDailyCalories(nowMillis: Long = System.currentTimeMillis()) {
        val settings = settingsRepository.settings.value
        val runs = runRepository.runs.value
        val activeRun = trackingController.trackingSession.value.activeRun
        val restingCalories = restingCaloriesForToday(settings.weightKg, nowMillis)
        val todayStartMillis = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val completedRunCalories = runs
            .filter { it.startedAtMillis >= todayStartMillis }
            .sumOf { it.calories }
            .toFloat()
        val activeRunCalories = activeRun?.calories?.toFloat() ?: 0f
        dailyActivityRepository.updateDerivedCalories(
            calories = restingCalories + completedRunCalories + activeRunCalories,
            nowMillis = nowMillis
        )
    }
}
