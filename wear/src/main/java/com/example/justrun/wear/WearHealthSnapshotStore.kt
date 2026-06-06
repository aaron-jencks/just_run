package com.example.justrun.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.roundToInt

object WearHealthSnapshotStore {
    private const val PREFS_NAME = "wear_health_snapshot"
    private const val KEY_DAY = "day"
    private const val KEY_LOCAL_STEPS = "local_steps"
    private const val KEY_LOCAL_STEPS_UPDATED_AT = "local_steps_updated_at"
    private const val KEY_REMOTE_STEPS = "remote_steps"
    private const val KEY_REMOTE_STEPS_UPDATED_AT = "remote_steps_updated_at"
    private const val KEY_LOCAL_CALORIES = "local_calories"
    private const val KEY_LOCAL_CALORIES_UPDATED_AT = "local_calories_updated_at"
    private const val KEY_REMOTE_CALORIES = "remote_calories"
    private const val KEY_REMOTE_CALORIES_UPDATED_AT = "remote_calories_updated_at"
    private const val KEY_LOCAL_HEART_RATE_BPM = "local_heart_rate_bpm"
    private const val KEY_LOCAL_HEART_RATE_UPDATED_AT = "local_heart_rate_updated_at"
    private const val KEY_REMOTE_HEART_RATE_BPM = "remote_heart_rate_bpm"
    private const val KEY_REMOTE_HEART_RATE_UPDATED_AT = "remote_heart_rate_updated_at"
    private const val KEY_STEP_GOAL_OVERRIDE = "step_goal_override"
    private const val KEY_CALORIE_GOAL_OVERRIDE = "calorie_goal_override"
    private const val KEY_WEIGHT_KG = "weight_kg"
    private const val KEY_AGE_YEARS = "age_years"
    private const val KEY_HEART_RATE_ENABLED = "heart_rate_enabled"
    private const val KEY_BACKGROUND_HEART_MONITORING_ENABLED = "background_heart_monitoring_enabled"
    private const val KEY_STEP_COUNTER_BASELINE = "step_counter_baseline"
    private const val KEY_RESOLVED_STEPS = "resolved_steps"
    private const val KEY_RESOLVED_CALORIES = "resolved_calories"
    private const val KEY_RESOLVED_HEART_RATE_BPM = "resolved_heart_rate_bpm"
    private const val KEY_RESOLVED_UPDATED_AT = "resolved_updated_at"
    private const val KEY_LAST_SYNC_TO_PHONE_AT = "last_sync_to_phone_at"
    private const val KEY_SEQUENCE_NUMBER = "sequence_number"
    private const val DEFAULT_DAILY_STEP_GOAL = 5_000f
    private const val DEFAULT_DAILY_CALORIE_GOAL = 2_000f
    private const val DEFAULT_HEART_RATE_MIN = 40f
    private const val DEFAULT_HEART_RATE_MAX = 200f
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    @Volatile
    private var cachedState: WearHealthState? = null
    private val syncCadence = WatchActivitySyncCadence()

    fun read(context: Context): WearHealthSnapshot = runBlocking {
        stateMutex.withLock { loadStateLocked(context).resolved }
    }

    fun readWatchDisplay(context: Context): WearHealthSnapshot = runBlocking {
        stateMutex.withLock { loadStateLocked(context).watchDisplaySnapshot() }
    }

    fun readLocal(context: Context): WearHealthSnapshot = runBlocking {
        stateMutex.withLock { loadStateLocked(context).localSnapshot() }
    }

    fun updateFromPassiveData(context: Context, dataPoints: DataPointContainer) {
        val heartRateSample = dataPoints.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value?.roundToInt() ?: return
        updateState(context, syncToPhone = true) { current ->
            current.copy(
                localHeartRateBpm = heartRateSample,
                localHeartRateUpdatedAtMillis = System.currentTimeMillis()
            )
        }
    }

    fun updateHeartRate(context: Context, heartRateBpm: Int) {
        updateState(context, syncToPhone = true) { current ->
            current.copy(
                localHeartRateBpm = heartRateBpm,
                localHeartRateUpdatedAtMillis = System.currentTimeMillis()
            )
        }
    }

    fun updateLocalCalories(context: Context, calories: Float, nowMillis: Long = System.currentTimeMillis()) {
        updateState(context, syncToPhone = true) { current ->
            if (abs(current.localCalories - calories) < 0.5f &&
                abs(nowMillis - current.localCaloriesUpdatedAtMillis) < 30_000L
            ) {
                current
            } else {
                current.copy(
                    localCalories = calories.coerceAtLeast(0f),
                    localCaloriesUpdatedAtMillis = nowMillis
                )
            }
        }
    }

    fun updateLocalStepsFromCounter(context: Context, counterValue: Float, nowMillis: Long = System.currentTimeMillis()) {
        updateState(context, syncToPhone = true, dayKeyOverride = wearTodayKey()) { current ->
            val baseline = when {
                current.stepCounterBaseline < 0f || counterValue < current.stepCounterBaseline -> counterValue
                else -> current.stepCounterBaseline
            }
            val stepsToday = (counterValue - baseline).coerceAtLeast(0f).roundToInt().toLong()
            current.copy(
                stepCounterBaseline = baseline,
                localSteps = stepsToday,
                localStepsUpdatedAtMillis = nowMillis
            )
        }
    }

    fun mergeRemoteSnapshot(context: Context, snapshot: WearDailySyncPayload) {
        updateState(context, syncToPhone = false, dayKeyOverride = snapshot.dayKey) { current ->
            current.copy(
                remoteSteps = snapshot.steps,
                remoteStepsUpdatedAtMillis = snapshot.stepsUpdatedAtMillis,
                remoteCalories = snapshot.calories,
                remoteCaloriesUpdatedAtMillis = snapshot.caloriesUpdatedAtMillis,
                remoteHeartRateBpm = snapshot.heartRateBpm,
                remoteHeartRateUpdatedAtMillis = snapshot.heartRateUpdatedAtMillis
            )
        }
    }

    fun syncCurrentSnapshotToPhone(context: Context) {
        val state = runBlocking {
            stateMutex.withLock { loadStateLocked(context) }
        }
        scope.launch { syncLocalSnapshotToPhone(context.applicationContext, state.toLocalSyncPayload(), force = true) }
    }

    fun updateGoals(context: Context, dailyStepGoal: Int, dailyCalorieGoal: Int) {
        updateState(context, syncToPhone = false) { current ->
            current.copy(
                stepGoalOverride = dailyStepGoal.toFloat(),
                calorieGoalOverride = dailyCalorieGoal.toFloat()
            )
        }
    }

    fun updateProfile(context: Context, weightKg: Float, ageYears: Float) {
        updateState(context, syncToPhone = false) { current ->
            current.copy(
                weightKg = weightKg,
                ageYears = ageYears
            )
        }
    }

    fun updateConfiguration(
        context: Context,
        heartRateEnabled: Boolean,
        backgroundHeartMonitoringEnabled: Boolean,
        dailyStepGoal: Int,
        dailyCalorieGoal: Int,
        weightKg: Float,
        ageYears: Float
    ) {
        updateState(context, syncToPhone = false) { current ->
            current.copy(
                heartRateEnabled = heartRateEnabled,
                backgroundHeartMonitoringEnabled = backgroundHeartMonitoringEnabled,
                stepGoalOverride = dailyStepGoal.toFloat(),
                calorieGoalOverride = dailyCalorieGoal.toFloat(),
                weightKg = weightKg,
                ageYears = ageYears
            )
        }
    }

    fun readMonitoringConfig(context: Context): WearMonitoringConfig = runBlocking {
        stateMutex.withLock {
            val state = loadStateLocked(context)
            WearMonitoringConfig(
                heartRateEnabled = state.heartRateEnabled,
                backgroundHeartMonitoringEnabled = state.backgroundHeartMonitoringEnabled
            )
        }
    }

    fun stepGoal(snapshot: WearHealthSnapshot = WearHealthSnapshot()): Float =
        snapshot.stepGoalOverride ?: DEFAULT_DAILY_STEP_GOAL

    fun calorieGoal(snapshot: WearHealthSnapshot = WearHealthSnapshot()): Float =
        snapshot.calorieGoalOverride ?: DEFAULT_DAILY_CALORIE_GOAL

    fun heartRateRange(): ClosedFloatingPointRange<Float> = DEFAULT_HEART_RATE_MIN..DEFAULT_HEART_RATE_MAX

    fun tapAction(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            301,
            Intent(context, WearMainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun loadStateLocked(context: Context): WearHealthState {
        cachedState?.let { return it }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val day = prefs.getString(KEY_DAY, wearTodayKey()) ?: wearTodayKey()
        return WearHealthState(
            dayKey = day,
            localSteps = prefs.getLong(KEY_LOCAL_STEPS, 0L),
            localStepsUpdatedAtMillis = prefs.getLong(KEY_LOCAL_STEPS_UPDATED_AT, 0L),
            remoteSteps = prefs.getLong(KEY_REMOTE_STEPS, 0L),
            remoteStepsUpdatedAtMillis = prefs.getLong(KEY_REMOTE_STEPS_UPDATED_AT, 0L),
            localCalories = prefs.getFloat(KEY_LOCAL_CALORIES, 0f),
            localCaloriesUpdatedAtMillis = prefs.getLong(KEY_LOCAL_CALORIES_UPDATED_AT, 0L),
            remoteCalories = prefs.getFloat(KEY_REMOTE_CALORIES, 0f),
            remoteCaloriesUpdatedAtMillis = prefs.getLong(KEY_REMOTE_CALORIES_UPDATED_AT, 0L),
            localHeartRateBpm = prefs.getInt(KEY_LOCAL_HEART_RATE_BPM, -1).takeIf { it > 0 },
            localHeartRateUpdatedAtMillis = prefs.getLong(KEY_LOCAL_HEART_RATE_UPDATED_AT, 0L),
            remoteHeartRateBpm = prefs.getInt(KEY_REMOTE_HEART_RATE_BPM, -1).takeIf { it > 0 },
            remoteHeartRateUpdatedAtMillis = prefs.getLong(KEY_REMOTE_HEART_RATE_UPDATED_AT, 0L),
            stepGoalOverride = prefs.getFloat(KEY_STEP_GOAL_OVERRIDE, -1f).takeIf { it > 0f },
            calorieGoalOverride = prefs.getFloat(KEY_CALORIE_GOAL_OVERRIDE, -1f).takeIf { it > 0f },
            weightKg = prefs.getFloat(KEY_WEIGHT_KG, 72f),
            ageYears = prefs.getFloat(KEY_AGE_YEARS, 31f),
            heartRateEnabled = prefs.getBoolean(KEY_HEART_RATE_ENABLED, false),
            backgroundHeartMonitoringEnabled = prefs.getBoolean(KEY_BACKGROUND_HEART_MONITORING_ENABLED, true),
            stepCounterBaseline = prefs.getFloat(KEY_STEP_COUNTER_BASELINE, -1f),
            sequenceNumber = prefs.getLong(KEY_SEQUENCE_NUMBER, 0L),
            lastSyncToPhoneAtMillis = prefs.getLong(KEY_LAST_SYNC_TO_PHONE_AT, 0L),
            resolved = WearHealthSnapshot(
                dailySteps = prefs.getLong(KEY_RESOLVED_STEPS, 0L),
                dailyCalories = prefs.getFloat(KEY_RESOLVED_CALORIES, 0f),
                heartRateBpm = prefs.getInt(KEY_RESOLVED_HEART_RATE_BPM, -1).takeIf { it > 0 },
                heartRateUpdatedAtMillis = prefs.getLong(KEY_RESOLVED_UPDATED_AT, 0L),
                stepGoalOverride = prefs.getFloat(KEY_STEP_GOAL_OVERRIDE, -1f).takeIf { it > 0f },
                calorieGoalOverride = prefs.getFloat(KEY_CALORIE_GOAL_OVERRIDE, -1f).takeIf { it > 0f },
                weightKg = prefs.getFloat(KEY_WEIGHT_KG, 72f),
                ageYears = prefs.getFloat(KEY_AGE_YEARS, 31f)
            )
        ).also { cachedState = it }
    }

    private fun persistStateLocked(context: Context, state: WearHealthState) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_DAY, state.dayKey)
            .putLong(KEY_LOCAL_STEPS, state.localSteps)
            .putLong(KEY_LOCAL_STEPS_UPDATED_AT, state.localStepsUpdatedAtMillis)
            .putLong(KEY_REMOTE_STEPS, state.remoteSteps)
            .putLong(KEY_REMOTE_STEPS_UPDATED_AT, state.remoteStepsUpdatedAtMillis)
            .putFloat(KEY_LOCAL_CALORIES, state.localCalories)
            .putLong(KEY_LOCAL_CALORIES_UPDATED_AT, state.localCaloriesUpdatedAtMillis)
            .putFloat(KEY_REMOTE_CALORIES, state.remoteCalories)
            .putLong(KEY_REMOTE_CALORIES_UPDATED_AT, state.remoteCaloriesUpdatedAtMillis)
            .putInt(KEY_LOCAL_HEART_RATE_BPM, state.localHeartRateBpm ?: -1)
            .putLong(KEY_LOCAL_HEART_RATE_UPDATED_AT, state.localHeartRateUpdatedAtMillis)
            .putInt(KEY_REMOTE_HEART_RATE_BPM, state.remoteHeartRateBpm ?: -1)
            .putLong(KEY_REMOTE_HEART_RATE_UPDATED_AT, state.remoteHeartRateUpdatedAtMillis)
            .putFloat(KEY_STEP_GOAL_OVERRIDE, state.stepGoalOverride ?: -1f)
            .putFloat(KEY_CALORIE_GOAL_OVERRIDE, state.calorieGoalOverride ?: -1f)
            .putFloat(KEY_WEIGHT_KG, state.weightKg)
            .putFloat(KEY_AGE_YEARS, state.ageYears)
            .putBoolean(KEY_HEART_RATE_ENABLED, state.heartRateEnabled)
            .putBoolean(KEY_BACKGROUND_HEART_MONITORING_ENABLED, state.backgroundHeartMonitoringEnabled)
            .putFloat(KEY_STEP_COUNTER_BASELINE, state.stepCounterBaseline)
            .putLong(KEY_SEQUENCE_NUMBER, state.sequenceNumber)
            .putLong(KEY_RESOLVED_STEPS, state.resolved.dailySteps)
            .putFloat(KEY_RESOLVED_CALORIES, state.resolved.dailyCalories)
            .putInt(KEY_RESOLVED_HEART_RATE_BPM, state.resolved.heartRateBpm ?: -1)
            .putLong(KEY_RESOLVED_UPDATED_AT, state.resolved.heartRateUpdatedAtMillis)
            .putLong(KEY_LAST_SYNC_TO_PHONE_AT, state.lastSyncToPhoneAtMillis)
            .apply()
        cachedState = state
    }

    private suspend fun syncLocalSnapshotToPhone(
        context: Context,
        payload: WearDailySyncPayload,
        force: Boolean
    ) {
        val lastSyncAt = stateMutex.withLock { loadStateLocked(context).lastSyncToPhoneAtMillis }
        val now = System.currentTimeMillis()
        if (!force && !syncCadence.shouldSync(payload, now, lastSyncAt)) return
        val request = PutDataMapRequest.create(PATH_DAILY_HEALTH).apply {
            dataMap.putString(KEY_DAY_DATA, payload.dayKey)
            dataMap.putLong(KEY_DAILY_STEPS_DATA, payload.steps)
            dataMap.putLong(KEY_DAILY_STEPS_UPDATED_AT_DATA, payload.stepsUpdatedAtMillis)
            dataMap.putFloat(KEY_DAILY_CALORIES_DATA, payload.calories)
            dataMap.putLong(KEY_DAILY_CALORIES_UPDATED_AT_DATA, payload.caloriesUpdatedAtMillis)
            dataMap.putInt(KEY_HEART_RATE_DATA, payload.heartRateBpm ?: -1)
            dataMap.putLong(KEY_HEART_RATE_UPDATED_AT_DATA, payload.heartRateUpdatedAtMillis)
            dataMap.putLong(KEY_UPDATED_AT_DATA, now)
            dataMap.putLong(KEY_SEQUENCE_NUMBER_DATA, payload.sequenceNumber)
        }.asPutDataRequest().setUrgent()
        runCatching { Tasks.await(Wearable.getDataClient(context).putDataItem(request)) }
        syncCadence.markSynced(payload)
        WearDiagnostics.log("daily activity sync sent seq=${payload.sequenceNumber} steps=${payload.steps} calories=${"%.1f".format(payload.calories)}")
        stateMutex.withLock {
            val current = loadStateLocked(context)
            persistStateLocked(context, current.copy(lastSyncToPhoneAtMillis = now))
        }
    }

    private fun requestComplicationRefresh(context: Context) {
        listOf(
            DailyStepsComplicationService::class.java,
            DailyCaloriesComplicationService::class.java,
            HeartRateComplicationService::class.java
        ).forEach { serviceClass ->
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, serviceClass))
                .requestUpdateAll()
        }
    }

    private fun updateState(
        context: Context,
        syncToPhone: Boolean,
        dayKeyOverride: String? = null,
        transform: (WearHealthState) -> WearHealthState
    ) {
        val (stateToPersist, payloadToSync) = runBlocking {
            stateMutex.withLock {
                val current = resetIfNeeded(loadStateLocked(context), dayKeyOverride)
                val transformed = transform(current)
                if (transformed == current) {
                    return@withLock current to null
                }
                val sequenced = if (syncToPhone) {
                    transformed.copy(sequenceNumber = current.sequenceNumber + 1L)
                } else {
                    transformed
                }
                val resolved = sequenced.withResolved(previousResolved = current.resolved)
                persistStateLocked(context, resolved)
                resolved to if (syncToPhone) resolved.toLocalSyncPayload() else null
            }
        }
        requestComplicationRefresh(context)
        payloadToSync?.let { payload ->
            scope.launch { syncLocalSnapshotToPhone(context.applicationContext, payload, force = false) }
        }
    }

    private fun resetIfNeeded(current: WearHealthState, targetDayKey: String?): WearHealthState {
        val desiredDayKey = targetDayKey ?: current.dayKey
        return if (current.dayKey == desiredDayKey) current else WearHealthState(dayKey = desiredDayKey)
    }

    const val PATH_DAILY_HEALTH = "/daily_health"
    const val KEY_DAY_DATA = "day"
    const val KEY_DAILY_STEPS_DATA = "daily_steps"
    const val KEY_DAILY_STEPS_UPDATED_AT_DATA = "daily_steps_updated_at"
    const val KEY_DAILY_CALORIES_DATA = "daily_calories"
    const val KEY_DAILY_CALORIES_UPDATED_AT_DATA = "daily_calories_updated_at"
    const val KEY_HEART_RATE_DATA = "heart_rate_bpm"
    const val KEY_HEART_RATE_UPDATED_AT_DATA = "heart_rate_updated_at"
    const val KEY_UPDATED_AT_DATA = "updated_at"
    const val KEY_SEQUENCE_NUMBER_DATA = "sequence_number"
}

data class WearHealthSnapshot(
    val dailySteps: Long = 0L,
    val dailyCalories: Float = 0f,
    val heartRateBpm: Int? = null,
    val heartRateUpdatedAtMillis: Long = 0L,
    val stepGoalOverride: Float? = null,
    val calorieGoalOverride: Float? = null,
    val weightKg: Float = 72f,
    val ageYears: Float = 31f
)

data class WearDailySyncPayload(
    val dayKey: String,
    val steps: Long,
    val stepsUpdatedAtMillis: Long,
    val calories: Float,
    val caloriesUpdatedAtMillis: Long,
    val heartRateBpm: Int?,
    val heartRateUpdatedAtMillis: Long,
    val sequenceNumber: Long = 0L
)

data class WearMonitoringConfig(
    val heartRateEnabled: Boolean,
    val backgroundHeartMonitoringEnabled: Boolean
)

private data class WearHealthState(
    val dayKey: String = wearTodayKey(),
    val localSteps: Long = 0L,
    val localStepsUpdatedAtMillis: Long = 0L,
    val remoteSteps: Long = 0L,
    val remoteStepsUpdatedAtMillis: Long = 0L,
    val localCalories: Float = 0f,
    val localCaloriesUpdatedAtMillis: Long = 0L,
    val remoteCalories: Float = 0f,
    val remoteCaloriesUpdatedAtMillis: Long = 0L,
    val localHeartRateBpm: Int? = null,
    val localHeartRateUpdatedAtMillis: Long = 0L,
    val remoteHeartRateBpm: Int? = null,
    val remoteHeartRateUpdatedAtMillis: Long = 0L,
    val stepGoalOverride: Float? = null,
    val calorieGoalOverride: Float? = null,
    val weightKg: Float = 72f,
    val ageYears: Float = 31f,
    val heartRateEnabled: Boolean = false,
    val backgroundHeartMonitoringEnabled: Boolean = true,
    val stepCounterBaseline: Float = -1f,
    val sequenceNumber: Long = 0L,
    val lastSyncToPhoneAtMillis: Long = 0L,
    val resolved: WearHealthSnapshot = WearHealthSnapshot()
) {
    fun withResolved(previousResolved: WearHealthSnapshot): WearHealthState {
        val steps = resolveWearStepsMetric(
            localValue = localSteps,
            localUpdatedAtMillis = localStepsUpdatedAtMillis,
            remoteValue = remoteSteps,
            remoteUpdatedAtMillis = remoteStepsUpdatedAtMillis,
            previousResolved = previousResolved.dailySteps
        )
        val calories = resolveWearMetric(
            localValue = localCalories,
            localUpdatedAtMillis = localCaloriesUpdatedAtMillis,
            remoteValue = remoteCalories,
            remoteUpdatedAtMillis = remoteCaloriesUpdatedAtMillis,
            preferLocalWhenClose = true,
            previousResolved = previousResolved.dailyCalories
        )
        val heartRate = if (abs(localHeartRateUpdatedAtMillis - remoteHeartRateUpdatedAtMillis) <= 90_000L) {
            localHeartRateBpm to localHeartRateUpdatedAtMillis
        } else if (localHeartRateUpdatedAtMillis >= remoteHeartRateUpdatedAtMillis) {
            localHeartRateBpm to localHeartRateUpdatedAtMillis
        } else {
            remoteHeartRateBpm to remoteHeartRateUpdatedAtMillis
        }
        return copy(
            resolved = WearHealthSnapshot(
                dailySteps = steps.value,
                dailyCalories = calories.value,
                heartRateBpm = heartRate.first,
                heartRateUpdatedAtMillis = maxOf(steps.updatedAtMillis, calories.updatedAtMillis, heartRate.second),
                stepGoalOverride = stepGoalOverride,
                calorieGoalOverride = calorieGoalOverride,
                weightKg = weightKg,
                ageYears = ageYears
            )
        )
    }

    fun toLocalSyncPayload(): WearDailySyncPayload =
        WearDailySyncPayload(
            dayKey = dayKey,
            steps = localSteps,
            stepsUpdatedAtMillis = localStepsUpdatedAtMillis,
            calories = localCalories,
            caloriesUpdatedAtMillis = localCaloriesUpdatedAtMillis,
            heartRateBpm = localHeartRateBpm,
            heartRateUpdatedAtMillis = localHeartRateUpdatedAtMillis,
            sequenceNumber = sequenceNumber
        )

    fun localSnapshot(): WearHealthSnapshot =
        WearHealthSnapshot(
            dailySteps = localSteps,
            dailyCalories = localCalories,
            heartRateBpm = localHeartRateBpm,
            heartRateUpdatedAtMillis = localHeartRateUpdatedAtMillis,
            stepGoalOverride = stepGoalOverride,
            calorieGoalOverride = calorieGoalOverride,
            weightKg = weightKg,
            ageYears = ageYears
        )

    fun watchDisplaySnapshot(): WearHealthSnapshot =
        WearHealthSnapshot(
            dailySteps = localSteps.takeIf { localStepsUpdatedAtMillis > 0L } ?: resolved.dailySteps,
            dailyCalories = localCalories.takeIf { localCaloriesUpdatedAtMillis > 0L } ?: resolved.dailyCalories,
            heartRateBpm = localHeartRateBpm ?: resolved.heartRateBpm,
            heartRateUpdatedAtMillis = maxOf(
                localStepsUpdatedAtMillis,
                localCaloriesUpdatedAtMillis,
                localHeartRateUpdatedAtMillis,
                resolved.heartRateUpdatedAtMillis
            ),
            stepGoalOverride = stepGoalOverride,
            calorieGoalOverride = calorieGoalOverride,
            weightKg = weightKg,
            ageYears = ageYears
        )
}

private data class WearResolvedMetric<T>(
    val value: T,
    val updatedAtMillis: Long
)

private fun resolveWearStepsMetric(
    localValue: Long,
    localUpdatedAtMillis: Long,
    remoteValue: Long,
    remoteUpdatedAtMillis: Long,
    previousResolved: Long
): WearResolvedMetric<Long> {
    val chosen = chooseWearMetric(localValue, localUpdatedAtMillis, remoteValue, remoteUpdatedAtMillis, preferLocalWhenClose = true)
    return WearResolvedMetric(maxOf(previousResolved, chosen.value), chosen.updatedAtMillis)
}

private fun resolveWearMetric(
    localValue: Long,
    localUpdatedAtMillis: Long,
    remoteValue: Long,
    remoteUpdatedAtMillis: Long,
    preferLocalWhenClose: Boolean,
    previousResolved: Long
): WearResolvedMetric<Long> {
    val chosen = chooseWearMetric(localValue, localUpdatedAtMillis, remoteValue, remoteUpdatedAtMillis, preferLocalWhenClose)
    return WearResolvedMetric(maxOf(previousResolved, chosen.value), chosen.updatedAtMillis)
}

private fun resolveWearMetric(
    localValue: Float,
    localUpdatedAtMillis: Long,
    remoteValue: Float,
    remoteUpdatedAtMillis: Long,
    preferLocalWhenClose: Boolean,
    previousResolved: Float
): WearResolvedMetric<Float> {
    val chosen = chooseWearMetric(localValue, localUpdatedAtMillis, remoteValue, remoteUpdatedAtMillis, preferLocalWhenClose)
    return WearResolvedMetric(maxOf(previousResolved, chosen.value), chosen.updatedAtMillis)
}

private fun chooseWearMetric(
    localValue: Long,
    localUpdatedAtMillis: Long,
    remoteValue: Long,
    remoteUpdatedAtMillis: Long,
    preferLocalWhenClose: Boolean
): WearResolvedMetric<Long> {
    if (localUpdatedAtMillis <= 0L) return WearResolvedMetric(remoteValue, remoteUpdatedAtMillis)
    if (remoteUpdatedAtMillis <= 0L) return WearResolvedMetric(localValue, localUpdatedAtMillis)
    return if (abs(localUpdatedAtMillis - remoteUpdatedAtMillis) <= 90_000L) {
        if (preferLocalWhenClose) WearResolvedMetric(localValue, localUpdatedAtMillis)
        else WearResolvedMetric(remoteValue, remoteUpdatedAtMillis)
    } else if (localUpdatedAtMillis >= remoteUpdatedAtMillis) {
        WearResolvedMetric(localValue, localUpdatedAtMillis)
    } else {
        WearResolvedMetric(remoteValue, remoteUpdatedAtMillis)
    }
}

private fun chooseWearMetric(
    localValue: Float,
    localUpdatedAtMillis: Long,
    remoteValue: Float,
    remoteUpdatedAtMillis: Long,
    preferLocalWhenClose: Boolean
): WearResolvedMetric<Float> {
    if (localUpdatedAtMillis <= 0L) return WearResolvedMetric(remoteValue, remoteUpdatedAtMillis)
    if (remoteUpdatedAtMillis <= 0L) return WearResolvedMetric(localValue, localUpdatedAtMillis)
    return if (abs(localUpdatedAtMillis - remoteUpdatedAtMillis) <= 90_000L) {
        if (preferLocalWhenClose) WearResolvedMetric(localValue, localUpdatedAtMillis)
        else WearResolvedMetric(remoteValue, remoteUpdatedAtMillis)
    } else if (localUpdatedAtMillis >= remoteUpdatedAtMillis) {
        WearResolvedMetric(localValue, localUpdatedAtMillis)
    } else {
        WearResolvedMetric(remoteValue, remoteUpdatedAtMillis)
    }
}

private class WatchActivitySyncCadence {
    private var lastSyncedPayload: WearDailySyncPayload? = null
    private var stableSyncCount = 0

    fun shouldSync(payload: WearDailySyncPayload, nowMillis: Long, lastSyncAtMillis: Long): Boolean {
        val minimumInterval = currentIntervalMillis(payload)
        return nowMillis - lastSyncAtMillis >= minimumInterval
    }

    fun markSynced(payload: WearDailySyncPayload) {
        val previous = lastSyncedPayload
        stableSyncCount = if (previous != null && isStable(previous, payload)) stableSyncCount + 1 else 0
        lastSyncedPayload = payload
    }

    private fun currentIntervalMillis(payload: WearDailySyncPayload): Long {
        val previous = lastSyncedPayload ?: return 5_000L
        if (!isStable(previous, payload)) return 5_000L
        return when {
            stableSyncCount >= 4 -> 20_000L
            stableSyncCount >= 2 -> 10_000L
            else -> 5_000L
        }
    }

    private fun isStable(previous: WearDailySyncPayload, current: WearDailySyncPayload): Boolean {
        val heartRateDelta = kotlin.math.abs((current.heartRateBpm ?: 0) - (previous.heartRateBpm ?: 0))
        return current.dayKey == previous.dayKey &&
            current.steps == previous.steps &&
            kotlin.math.abs(current.calories - previous.calories) < 1f &&
            heartRateDelta < 4
    }
}

private fun wearTodayKey(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
