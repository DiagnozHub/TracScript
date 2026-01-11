package com.brain.tracscript.plugins.scenario

sealed class ControlCommand {
    object ClickLoginButton : ControlCommand()
    data class InputText(val value: String) : ControlCommand()
    object OpenTargetApp : ControlCommand()

    object ExploreOn : ControlCommand()
    object ExploreOff : ControlCommand()

    data class StartPeriodic(val intervalMs: Long) : ControlCommand()
    object StopPeriodic : ControlCommand()
}
