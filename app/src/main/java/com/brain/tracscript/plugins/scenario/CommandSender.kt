package com.brain.tracscript.plugins.scenario

import android.content.Context
import android.util.Log

object CommandSender {

    private const val TAG = "TracScript"

    fun send(context: Context, cmd: ControlCommand) {
        val raw = serialize(cmd)
        CommandStorage.saveRaw(context, raw)
        Log.d(TAG, "Команда записана в prefs: $raw")
    }

    private fun serialize(cmd: ControlCommand): String =
        when (cmd) {
            is ControlCommand.ClickLoginButton -> "click_login"
            is ControlCommand.InputText       -> "input:${cmd.value}"
            is ControlCommand.OpenTargetApp   -> "open_app"
            is ControlCommand.ExploreOn       -> "explore_on"
            is ControlCommand.ExploreOff      -> "explore_off"
            is ControlCommand.StartPeriodic   -> "start_periodic:${cmd.intervalMs}"
            is ControlCommand.StopPeriodic    -> "stop_periodic"
        }
}
