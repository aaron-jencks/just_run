package com.example.justrun.wear

import android.graphics.drawable.Icon
import android.os.Build
import com.aaronjencks.justrun.R
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.GoalProgressComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlin.math.roundToInt

class DailyStepsComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        WearHealthPassiveMonitor.ensureRegistered(this)
        if (!hasActivityRecognitionPermission(this)) return NoDataComplicationData()
        val snapshot = WearHealthSnapshotStore.readWatchDisplay(this)
        return buildGoalComplication(
            type = request.complicationType,
            label = null,
            value = snapshot.dailySteps.toFloat(),
            goal = WearHealthSnapshotStore.stepGoal(snapshot),
            shortText = formatCompactCount(snapshot.dailySteps.toFloat()),
            iconRes = R.drawable.ic_complication_steps
        )
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        buildGoalPreview(type = type, label = null, value = 6842f, goal = 10_000f, shortText = "6.8k", iconRes = R.drawable.ic_complication_steps)
}

class DailyCaloriesComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        WearHealthPassiveMonitor.ensureRegistered(this)
        if (!hasActivityRecognitionPermission(this)) return NoDataComplicationData()
        val snapshot = WearHealthSnapshotStore.readWatchDisplay(this)
        return buildGoalComplication(
            type = request.complicationType,
            label = null,
            value = snapshot.dailyCalories,
            goal = WearHealthSnapshotStore.calorieGoal(snapshot),
            shortText = formatCompactCount(snapshot.dailyCalories),
            iconRes = R.drawable.ic_complication_calories
        )
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        buildGoalPreview(type = type, label = null, value = 1240f, goal = 2000f, shortText = formatCompactCount(1240f), iconRes = R.drawable.ic_complication_calories)
}

class HeartRateComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        WearHealthPassiveMonitor.ensureRegistered(this)
        if (!hasHeartRatePermission(this)) return NoDataComplicationData()
        val snapshot = WearHealthSnapshotStore.readWatchDisplay(this)
        val heartRate = snapshot.heartRateBpm ?: return NoDataComplicationData()
        return buildHeartRateComplication(request.complicationType, heartRate, R.drawable.ic_complication_heart)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        buildHeartRateComplication(type, 128, R.drawable.ic_complication_heart)
}

private fun SuspendingComplicationDataSourceService.buildGoalComplication(
    type: ComplicationType,
    label: String?,
    value: Float,
    goal: Float,
    shortText: String,
    iconRes: Int
): ComplicationData = when (type) {
    ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
        text = PlainComplicationText.Builder(shortText).build(),
        contentDescription = PlainComplicationText.Builder("$label $shortText").build()
    )
        .setMonochromaticImage(complicationIcon(iconRes))
        .apply {
            if (label != null) {
                setTitle(PlainComplicationText.Builder(label).build())
            }
        }
        .setTapAction(WearHealthSnapshotStore.tapAction(this))
        .build()
    ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
        value,
        0f,
        goal.coerceAtLeast(1f),
        PlainComplicationText.Builder("$label $shortText").build()
    )
        .setText(PlainComplicationText.Builder(shortText).build())
        .setMonochromaticImage(complicationIcon(iconRes))
        .apply {
            if (label != null) {
                setTitle(PlainComplicationText.Builder(label).build())
            }
        }
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
                .setMonochromaticImage(complicationIcon(iconRes))
                .apply {
                    if (label != null) {
                        setTitle(PlainComplicationText.Builder(label).build())
                    }
                }
                .setTapAction(WearHealthSnapshotStore.tapAction(this))
                .build()
        }
    }
    else -> NoDataComplicationData()
}

private fun DailyStepsComplicationService.buildGoalPreview(
    type: ComplicationType,
    label: String?,
    value: Float,
    goal: Float,
    shortText: String,
    iconRes: Int
): ComplicationData = buildGoalComplication(type, label, value, goal, shortText, iconRes)

private fun DailyCaloriesComplicationService.buildGoalPreview(
    type: ComplicationType,
    label: String?,
    value: Float,
    goal: Float,
    shortText: String,
    iconRes: Int
): ComplicationData = buildGoalComplication(type, label, value, goal, shortText, iconRes)

private fun SuspendingComplicationDataSourceService.buildHeartRateComplication(
    type: ComplicationType,
    heartRateBpm: Int,
    iconRes: Int
): ComplicationData = when (type) {
    ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
        text = PlainComplicationText.Builder(heartRateBpm.toString()).build(),
        contentDescription = PlainComplicationText.Builder("$heartRateBpm beats per minute").build()
    )
        .setMonochromaticImage(complicationIcon(iconRes))
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
            .setMonochromaticImage(complicationIcon(iconRes))
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

private fun SuspendingComplicationDataSourceService.complicationIcon(iconRes: Int): MonochromaticImage =
    MonochromaticImage.Builder(Icon.createWithResource(this, iconRes)).build()
