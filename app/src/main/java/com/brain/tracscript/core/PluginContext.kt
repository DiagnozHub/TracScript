package com.brain.tracscript.core
import android.content.Context
import kotlinx.coroutines.CoroutineScope

interface PluginContext {
    val appContext: Context
    val dataBus: DataBus
    val coroutineScope: CoroutineScope

    fun log(tag: String, message: String)

}
