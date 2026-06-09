package com.example.justrun

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.aaronjencks.justrun.R
import kotlin.math.roundToInt

enum class DailyMetricType {
    STEPS,
    CALORIES
}

enum class DailyWidgetVariant {
    PROGRESS,
    TEXT
}

object DailyActivityWidgetUpdater {
    fun updateAll(context: Context) {
        AppGraph.init(context)
        val widgetManager = AppWidgetManager.getInstance(context)
        updateComponent(context, widgetManager, StepsProgressWidgetProvider::class.java, DailyMetricType.STEPS, DailyWidgetVariant.PROGRESS)
        updateComponent(context, widgetManager, StepsTextWidgetProvider::class.java, DailyMetricType.STEPS, DailyWidgetVariant.TEXT)
        updateComponent(context, widgetManager, CaloriesProgressWidgetProvider::class.java, DailyMetricType.CALORIES, DailyWidgetVariant.PROGRESS)
        updateComponent(context, widgetManager, CaloriesTextWidgetProvider::class.java, DailyMetricType.CALORIES, DailyWidgetVariant.TEXT)
    }

    private fun updateComponent(
        context: Context,
        widgetManager: AppWidgetManager,
        providerClass: Class<out AppWidgetProvider>,
        metricType: DailyMetricType,
        variant: DailyWidgetVariant
    ) {
        val component = ComponentName(context, providerClass)
        val appWidgetIds = widgetManager.getAppWidgetIds(component)
        if (appWidgetIds.isEmpty()) return
        appWidgetIds.forEach { appWidgetId ->
            widgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context, metricType, variant))
        }
    }

    internal fun buildRemoteViews(
        context: Context,
        metricType: DailyMetricType,
        variant: DailyWidgetVariant
    ): RemoteViews {
        AppGraph.init(context)
        val settings = AppGraph.settingsRepository.settings.value
        val snapshot = AppGraph.dailyActivityRepository.snapshot.value
        val layoutId = when (variant) {
            DailyWidgetVariant.PROGRESS -> R.layout.widget_daily_progress
            DailyWidgetVariant.TEXT -> R.layout.widget_daily_text
        }
        val title = when (metricType) {
            DailyMetricType.STEPS -> context.getString(R.string.widget_steps_title)
            DailyMetricType.CALORIES -> context.getString(R.string.widget_calories_title)
        }
        val totalValue = when (metricType) {
            DailyMetricType.STEPS -> snapshot.steps.toFloat()
            DailyMetricType.CALORIES -> snapshot.calories
        }
        val goalValue = when (metricType) {
            DailyMetricType.STEPS -> settings.dailyStepGoal.toFloat()
            DailyMetricType.CALORIES -> settings.dailyCalorieGoal.toFloat()
        }.coerceAtLeast(1f)
        val valueText = when (metricType) {
            DailyMetricType.STEPS -> "${snapshot.steps} steps"
            DailyMetricType.CALORIES -> "${snapshot.calories.roundToInt()} kcal"
        }
        val goalText = when (metricType) {
            DailyMetricType.STEPS -> "${settings.dailyStepGoal} goal"
            DailyMetricType.CALORIES -> "${settings.dailyCalorieGoal} goal"
        }
        val cycleProgress = progressCycle(totalValue, goalValue)

        return RemoteViews(context.packageName, layoutId).apply {
            setTextViewText(R.id.widget_title, title)
            setTextViewText(R.id.widget_value, valueText)
            if (variant == DailyWidgetVariant.PROGRESS) {
                setTextViewText(R.id.widget_goal, goalText)
                setProgressBar(R.id.widget_progress, WIDGET_PROGRESS_MAX, cycleProgress, false)
            } else {
                setTextViewText(R.id.widget_subtitle, goalText)
            }
            setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context,
                    metricType.ordinal * 10 + variant.ordinal,
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
    }

    internal fun progressCycle(totalValue: Float, goalValue: Float): Int {
        if (goalValue <= 0f) return 0
        if (totalValue <= 0f) return 0
        val cycle = totalValue % goalValue
        val normalized = when {
            cycle == 0f && totalValue >= goalValue -> 1f
            else -> cycle / goalValue
        }.coerceIn(0f, 1f)
        return (normalized * WIDGET_PROGRESS_MAX).roundToInt()
    }

    private const val WIDGET_PROGRESS_MAX = 1000
}

abstract class DailyActivityWidgetProvider(
    private val metricType: DailyMetricType,
    private val variant: DailyWidgetVariant
) : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        DailyActivityWidgetUpdater.updateAll(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        appWidgetIds.forEach { appWidgetId ->
            appWidgetManager.updateAppWidget(
                appWidgetId,
                DailyActivityWidgetUpdater.buildRemoteViews(context, metricType, variant)
            )
        }
    }
}

class StepsProgressWidgetProvider : DailyActivityWidgetProvider(DailyMetricType.STEPS, DailyWidgetVariant.PROGRESS)
class StepsTextWidgetProvider : DailyActivityWidgetProvider(DailyMetricType.STEPS, DailyWidgetVariant.TEXT)
class CaloriesProgressWidgetProvider : DailyActivityWidgetProvider(DailyMetricType.CALORIES, DailyWidgetVariant.PROGRESS)
class CaloriesTextWidgetProvider : DailyActivityWidgetProvider(DailyMetricType.CALORIES, DailyWidgetVariant.TEXT)
