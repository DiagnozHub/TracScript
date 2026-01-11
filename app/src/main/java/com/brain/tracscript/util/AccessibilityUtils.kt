package com.brain.tracscript.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.brain.tracscript.plugins.scenario.MyAccessibilityService

fun isMyAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedComponent =
        ComponentName(context, MyAccessibilityService::class.java)

    val accessibilityEnabled = try {
        Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED
        )
    } catch (_: Exception) {
        0
    }

    if (accessibilityEnabled != 1) return false

    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    return enabledServices
        .split(':')
        .any { it.equals(expectedComponent.flattenToString(), ignoreCase = true) }
}
