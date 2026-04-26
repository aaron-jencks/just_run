package com.example.justrun.wear

import android.os.Build
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.GoalProgressComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlin.math.roundToInt

private const val HEART_TITLE = "\u2665"

class DailyStepsComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        WearHealthPassiveMonitor.ensureRegistered(this)
        if (!hasActivityRecognitionPermission(this)) return NoDataComplicationData()
        val snapshot = WearHealthSnapshotStore.read(this)
        return buildGoalComplication(
            type = request.complicationType,
            label = "Steps",
            value = snapshot.dailySteps.toFloat(),
            goal = WearHealthSnapshotStore.stepGoal(snapshot),
            shortText = formatCompactCount(snapshot.dailySteps.toFloat())
        )
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        buildGoalPreview(type = type, label = "Steps", value = 6842f, goal = 10_000f, shortText = "6.8k")
}

class DailyCaloriesComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        WearHealthPassiveMonitor.ensureRegistered(this)
        if (!hasActivityRecognitionPermission(this)) return NoDataComplicationData()
        val snapshot = WearHealthSnapshotStore.read(this)
        return buildGoalComplication(
            type = request.complicationType,
            label = "Calories",
            value = snapshot.dailyCalories,
            goal = WearHealthSnapshotStore.calorieGoal(snapshot),
            shortText = snapshot.dailyCalories.roundToInt().toString()
        )
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        buildGoalPreview(type = type, label = "Calories", value = 1240f, goal = 2000f, shortText = "1240")
}

class HeartRateComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        WearHealthPassiveMonitor.ensureRegistered(this)
        if (!hasHeartRatePermission(this)) return NoDataComplicationData()
        val snapshot = WearHealthSnapshotStore.read(this)
        val heartRate = snapshot.heartRateBpm ?: return NoDataComplicationData()
        return buildHeartRateComplication(request.complicationType, heartRate)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        buildHeartRateComplication(type, 128)
}

private fun SuspendingComplicationDataSourceService.buildGoalComplication(
    type: ComplicationType,
    label: String,
    value: Float,
    goal: Float,
    shortText: String
): ComplicationData = when (type) {
    ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
        text = PlainComplicationText.Builder(shortText).build(),
        contentDescription = PlainComplicationText.Builder("$label $shortText").build()
    )
        .setTitle(PlainComplicationText.Builder(label).build())
        .setTapAction(WearHealthSnapshotStore.tapAction(this))
        .build()
    ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
        value,
        0f,
        goal.coerceAtLeast(1f),
        PlainComplicationText.Builder("$label $shortText").build()
    )
        .setText(PlainComplicationText.Builder(shortText).build())
        .setTitle(PlainComplicationText.Builder(label).build())
        .setTapAction(WearHealthSnapshotStore.tapAction(this))
        .build()
    ComplicationType.GOAL_PROGRESS -> {
        if (Build.VERSION.SDK_INT < 33) {
            NoDataComplicationData()
        } else {
            GoalProgressComplicationData.Builder(
                value,
                goal.coerceAtLeast(1f),
                PlainComplicationText.Builder("$label $shortText").build()
            )
                .setText(PlainComplicationText.Builder(shortText).build())
                .setTitle(PlainComplicationText.Builder(label).build())
                .setTapAction(WearHealthSnapshotStore.tapAction(this))
                .build()
        }
    }
    else -> NoDataComplicationData()
}

private fun DailyStepsComplicationService.buildGoalPreview(
    type: ComplicationType,
    label: String,
    value: Float,
    goal: Float,
    shortText: String
): ComplicationData = buildGoalComplication(type, label, value, goal, shortText)

private fun DailyCaloriesComplicationService.buildGoalPreview(
    type: ComplicationType,
    label: String,
    value: Float,
    goal: Float,
    shortText: String
): ComplicationData = buildGoalComplication(type, label, value, goal, shortText)

private fun SuspendingComplicationDataSourceService.buildHeartRateComplication(
    type: ComplicationType,
    heartRateBpm: Int
): ComplicationData = when (type) {
    ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
        text = PlainComplicationText.Builder(heartRateBpm.toString()).build(),
        contentDescription = PlainComplicationText.Builder("$heartRateBpm beats per minute").build()
    )
        .setTitle(PlainComplicationText.Builder(HEART_TITLE).build())
        .setTapAction(WearHealthSnapshotStore.tapAction(this))
        .build()
    ComplicationType.RANGED_VALUE -> {
        val heartRateRange = WearHealthSnapshotStore.heartRateRange()
        RangedValueComplicationData.Builder(
            heartRateBpm.toFloat().coerceIn(heartRateRange.start, heartRateRange.endInclusive),
            heartRateRange.start,
            heartRateRange.endInclusive,
            PlainComplicationText.Builder("$heartRateBpm beats per minute").build()
        )
            .setText(PlainComplicationText.Builder(heartRateBpm.toString()).build())
            .setTitle(PlainComplicationText.Builder(HEART_TITLE).build())
            .setTapAction(WearHealthSnapshotStore.tapAction(this))
            .build()
    }
    else -> NoDataComplicationData()
}

internal fun formatCompactCount(value: Float): String {
    if (value < 1000f) return value.roundToInt().toString()
    val compact = value / 1000f
    val rounded = if (compact >= 10f) compact.roundToInt().toString() else String.format("%.1f", compact)
    return "${rounded.trimEnd('0').trimEnd('.')}k"
}
