package com.example.justrun.wear

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

fun requiredDailyActivityPermissions(): Array<String> =
    arrayOf(android.Manifest.permission.ACTIVITY_RECOGNITION)

fun requiredHeartRatePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 36) {
        arrayOf(
            "android.permission.health.READ_HEART_RATE",
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
            android.Manifest.permission.ACTIVITY_RECOGNITION
        )
    } else {
        arrayOf(
            android.Manifest.permission.BODY_SENSORS,
            android.Manifest.permission.BODY_SENSORS_BACKGROUND,
            android.Manifest.permission.ACTIVITY_RECOGNITION
        )
    }

fun hasActivityRecognitionPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

fun hasDailyActivityPermission(context: Context): Boolean =
    requiredDailyActivityPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

fun hasHeartRatePermission(context: Context): Boolean =
    requiredHeartRatePermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
