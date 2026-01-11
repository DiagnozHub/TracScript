package com.brain.tracscript.plugins.scenario

import android.content.Context

object CommandStorage {

    private const val PREFS_NAME = "TracScript_control"
    private const val KEY_LAST_COMMAND = "last_command"

    fun saveRaw(context: Context, raw: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_COMMAND, raw)
            .apply()
    }

    fun loadRaw(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_COMMAND, null)
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_LAST_COMMAND)
            .apply()
    }
}
