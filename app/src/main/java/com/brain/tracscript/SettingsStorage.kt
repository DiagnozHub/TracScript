package com.brain.tracscript

import android.content.Context

object SettingsStorage {

    private const val PREFS = "TracScript_settings"
    private const val KEY_AUTO_START = "auto_start_enabled"

    private const val KEY_PERIODIC_ENABLED = "periodic_enabled"
    private const val KEY_PERIODIC_INTERVAL_SEC = "periodic_interval_sec"

    private const val KEY_PREVENT_SCREEN_OFF = "prevent_screen_off"

    fun isAutoStartEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_START, false)
    }

    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_AUTO_START, enabled)
            .apply()
    }

    // --- Периодический запуск ---

    //fun isPeriodicEnabled(context: Context): Boolean {
    //    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    //    return prefs.getBoolean(KEY_PERIODIC_ENABLED, false)
    //}

    /**
     * Интервал в секундах, по умолчанию 600 (10 минут).
     */

    /*
    //fun getPeriodicIntervalSec(context: Context): Int {
    //    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    //    return prefs.getInt(KEY_PERIODIC_INTERVAL_SEC, 600)
    //}

    fun setPeriodicEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_PERIODIC_ENABLED, enabled)
            .apply()
    }

    fun setPeriodicIntervalSec(context: Context, seconds: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_PERIODIC_INTERVAL_SEC, seconds)
            .apply()
    }
    */

    fun isPreventScreenOffEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_PREVENT_SCREEN_OFF, false)
    }

    fun setPreventScreenOffEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_PREVENT_SCREEN_OFF, enabled)
            .apply()
    }
}
