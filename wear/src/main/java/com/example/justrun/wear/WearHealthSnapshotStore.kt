package com.example.justrun.wear

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
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
import kotlin.math.roundToInt

object WearHealthSnapshotStore {
    private const val PREFS_NAME = "wear_health_snapshot"
    private const val KEY_DAILY_STEPS = "daily_steps"
    private const val KEY_DAILY_CALORIES = "daily_calories"
    private const val KEY_HEART_RATE_BPM = "heart_rate_bpm"
    private const val KEY_HEART_RATE_UPDATED_AT = "heart_rate_updated_at"
    private const val KEY_STEP_GOAL_OVERRIDE = "step_goal_override"
    private const val KEY_CALORIE_GOAL_OVERRIDE = "calorie_goal_override"
    private const val DEFAULT_DAILY_STEP_GOAL = 5_000f
    private const val DEFAULT_DAILY_CALORIE_GOAL = 2_000f
    private const val DEFAULT_HEART_RATE_MIN = 40f
    private const val DEFAULT_HEART_RATE_MAX = 200f
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun read(context: Context): WearHealthSnapshot {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return WearHealthSnapshot(
            dailySteps = prefs.getLong(KEY_DAILY_STEPS, 0L),
            dailyCalories = prefs.getFloat(KEY_DAILY_CALORIES, 0f),
            heartRateBpm = prefs.getInt(KEY_HEART_RATE_BPM, -1).takeIf { it > 0 },
            heartRateUpdatedAtMillis = prefs.getLong(KEY_HEART_RATE_UPDATED_AT, 0L),
            stepGoalOverride = prefs.getFloat(KEY_STEP_GOAL_OVERRIDE, -1f).takeIf { it > 0f },
            calorieGoalOverride = prefs.getFloat(KEY_CALORIE_GOAL_OVERRIDE, -1f).takeIf { it > 0f }
        )
    }

    fun updateFromPassiveData(context: Context, dataPoints: DataPointContainer) {
        val dailySteps = dataPoints.getData(DataType.STEPS_DAILY).lastOrNull()?.value
        val dailyCalories = dataPoints.getData(DataType.CALORIES_DAILY).lastOrNull()?.value?.toFloat()
        val heartRate = dataPoints.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value?.roundToInt()
        update(
            context = context,
            dailySteps = dailySteps,
            dailyCalories = dailyCalories,
            heartRateBpm = heartRate
        )
    }

    fun updateHeartRate(context: Context, heartRateBpm: Int) {
        update(context = context, heartRateBpm = heartRateBpm)
    }

    fun updateGoals(context: Context, dailyStepGoal: Int, dailyCalorieGoal: Int) {
        val current = read(context)
        val updated = current.copy(
            stepGoalOverride = dailyStepGoal.toFloat(),
            calorieGoalOverride = dailyCalorieGoal.toFloat()
        )
        if (updated == current) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_STEP_GOAL_OVERRIDE, updated.stepGoalOverride ?: -1f)
            .putFloat(KEY_CALORIE_GOAL_OVERRIDE, updated.calorieGoalOverride ?: -1f)
            .apply()
        requestComplicationRefresh(context)
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

    private fun update(
        context: Context,
        dailySteps: Long? = null,
        dailyCalories: Float? = null,
        heartRateBpm: Int? = null
    ) {
        val current = read(context)
        val updated = current.copy(
            dailySteps = dailySteps ?: current.dailySteps,
            dailyCalories = dailyCalories ?: current.dailyCalories,
            heartRateBpm = heartRateBpm ?: current.heartRateBpm,
            heartRateUpdatedAtMillis = if (heartRateBpm != null) System.currentTimeMillis() else current.heartRateUpdatedAtMillis
        )
        if (updated == current) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_DAILY_STEPS, updated.dailySteps)
            .putFloat(KEY_DAILY_CALORIES, updated.dailyCalories)
            .putInt(KEY_HEART_RATE_BPM, updated.heartRateBpm ?: -1)
            .putLong(KEY_HEART_RATE_UPDATED_AT, updated.heartRateUpdatedAtMillis)
            .putFloat(KEY_STEP_GOAL_OVERRIDE, updated.stepGoalOverride ?: -1f)
            .putFloat(KEY_CALORIE_GOAL_OVERRIDE, updated.calorieGoalOverride ?: -1f)
            .apply()
        requestComplicationRefresh(context)
        scope.launch { syncSnapshotToPhone(context.applicationContext, updated) }
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

    private fun syncSnapshotToPhone(context: Context, snapshot: WearHealthSnapshot) {
        val request = PutDataMapRequest.create(PATH_DAILY_HEALTH).apply {
            dataMap.putLong(KEY_DAILY_STEPS_DATA, snapshot.dailySteps)
            dataMap.putFloat(KEY_DAILY_CALORIES_DATA, snapshot.dailyCalories)
            dataMap.putInt(KEY_HEART_RATE_DATA, snapshot.heartRateBpm ?: -1)
            dataMap.putLong(KEY_UPDATED_AT_DATA, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        runCatching { Tasks.await(Wearable.getDataClient(context).putDataItem(request)) }
    }

    const val PATH_DAILY_HEALTH = "/daily_health"
    const val KEY_DAILY_STEPS_DATA = "daily_steps"
    const val KEY_DAILY_CALORIES_DATA = "daily_calories"
    const val KEY_HEART_RATE_DATA = "heart_rate_bpm"
    const val KEY_UPDATED_AT_DATA = "updated_at"
}

data class WearHealthSnapshot(
    val dailySteps: Long = 0L,
    val dailyCalories: Float = 0f,
    val heartRateBpm: Int? = null,
    val heartRateUpdatedAtMillis: Long = 0L,
    val stepGoalOverride: Float? = null,
    val calorieGoalOverride: Float? = null
)
