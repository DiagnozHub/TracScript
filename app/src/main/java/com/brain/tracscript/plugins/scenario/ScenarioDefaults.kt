package com.brain.tracscript.plugins.scenario

object ScenarioDefaults {
    val DEFAULT_SCENARIO = """
        # Example scenario: light ON, delay, light OFF

        light 1
        delay 3000
        light 0
    """.trimIndent()
}
