package com.brain.tracscript.telemetry

import android.util.Log
import com.brain.tracscript.FileHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppLog(
    private val fileHelper: FileHelper,
    scope: CoroutineScope,
    private val fileName: String = "debug1.log" // <-- новое
) {
    private val ioScope = scope + SupervisorJob() + Dispatchers.IO
    private val ch = Channel<String>(capacity = Channel.BUFFERED)
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var closed = false


    init {
        ioScope.launch {
            for (line in ch) {
                runCatching { fileHelper.appendDebugLog(fileName, line) } // <-- изменили
            }
        }
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        enqueue("D", tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        enqueue("I", tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        Log.w(tag, msg, tr)
        enqueue("W", tag, msg + trSuffix(tr))
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr)
        enqueue("E", tag, msg + trSuffix(tr))
    }

    private fun enqueue(level: String, tag: String, msg: String) {

        if (closed) return

        val ts = tsFmt.format(Date())
        //ch.trySend("$ts [$level] $tag: $msg")
        ch.trySend("[$ts] [$level] $msg")
    }

    private fun trSuffix(tr: Throwable?): String {
        if (tr == null) return ""

        val maxLines = 5

        val lines = tr.stackTraceToString()
            .lineSequence()
            .take(maxLines)
            .joinToString("\n")

        return " | ${tr.javaClass.simpleName}: ${tr.message}\n$lines"
    }


    fun close() {
        closed = true
        ch.close()
    }
}
