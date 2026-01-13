package com.brain.tracscript

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max


class FileHelper(
    private val context: Context,
    /** Колбэк для вывода сообщений пользователю (то, что раньше делал setDebug из сервиса) */
    private val debugCallback: ((String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "TracScript"

        private const val MAX_TEXT_JSON_SIZE = 1_000_000          // 1 MB
        private const val MAX_DEBUG_LOG_SIZE = 200_000L           // 200 KB
        private const val TRIM_DEBUG_LOG_TO = 100_000             // оставить последние 100 KB
    }

    fun listLogFiles(): List<String> {
        val dir = getTracScriptDir() ?: return emptyList()
        return dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(".log", ignoreCase = true) }
            ?.map { it.name }
            ?.sorted()
            ?.toList()
            ?: emptyList()
    }


    /** Вспомогательный вывод в overlay (если есть) */
    private fun setDebug(message: String) {
        debugCallback?.invoke(message)
    }

    /** Папка /sdcard/Documents/TracScript */
    private fun getTracScriptDir(): File? {
        //val docsDir = Environment.getExternalStoragePublicDirectory(
        //    Environment.DIRECTORY_DOCUMENTS
        //)

        val docsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)

        if (docsDir == null) {
            Log.w(TAG, "getTracScriptDir: DOCUMENTS dir is null")
            return null
        }

        val dir = File(docsDir, "TracScript")
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                Log.w(TAG, "getTracScriptDir: cannot create ${dir.absolutePath}")
            }
        }
        return dir
    }

    // ----------------- ПУБЛИЧНЫЕ МЕТОДЫ -----------------

    fun deleteFileFromDocuments(fileName: String) {
        try {
            val dir = getTracScriptDir()
            if (dir == null || !dir.exists()) {
                setDebug("DELETE_FILE: folder TracScript not found")
                Log.w(TAG, "DELETE_FILE: dir ${dir?.absolutePath} not exists")
                return
            }

            val file = File(dir, fileName)

            if (!file.exists()) {
                setDebug("DELETE_FILE: file '${file.name}' not exists")
                Log.d(TAG, "DELETE_FILE: file ${file.absolutePath} not exists")
                return
            }

            val ok = file.delete()
            if (ok) {
                setDebug("DELETE_FILE: deleted '${file.name}'")
                Log.d(TAG, "DELETE_FILE: deleted ${file.absolutePath}")
            } else {
                setDebug("DELETE_FILE: failed to delete '${file.name}'")
                Log.w(TAG, "DELETE_FILE: failed to delete ${file.absolutePath}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "deleteFileFromDocuments error", e)
            setDebug("DELETE_FILE: deleting error")
        }
    }

    fun readTextFromDocuments(fileName: String): String? {
        return try {
            val dir = getTracScriptDir()
            if (dir == null) {
                Log.w(TAG, "readTextFromDocuments: TracScript dir is null")
                null
            } else {
                val file = File(dir, fileName)
                if (!file.exists()) {
                    Log.w(TAG, "readTextFromDocuments: file '${file.absolutePath}' not found")
                    null
                } else {
                    file.readText(Charsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "readTextFromDocuments: read error '$fileName'", e)
            null
        }
    }

    /** Лог в указанный файл в /Documents/TracScript */
    fun appendDebugLog(fileName: String, message: String) {
        try {
            val dir = getTracScriptDir() ?: return
            val file = File(dir, fileName)

            if (file.exists() && file.length() > MAX_DEBUG_LOG_SIZE) {
                val text = file.readText()
                val keep = text.takeLast(TRIM_DEBUG_LOG_TO)
                file.writeText(keep)
            }

            // ВАЖНО: без timestamp тут
            file.appendText(message + "\n")

        } catch (e: Exception) {
            Log.e(TAG, "appendDebugLog($fileName) error", e)
        }
    }

    /** Запись дампа инспектора во внутреннее хранилище, лимит 1 МБ */
    fun appendInspectorDumpToFile(dump: String) {
        try {
            val file = File(context.filesDir, "inspector_dump.txt")
            val maxSize = 1024 * 1024L // 1 МБ
            val bytes = dump.toByteArray(Charsets.UTF_8)
            val toAdd = bytes.size.toLong()

            if (file.exists()) {
                val currentSize = file.length()
                if (currentSize + toAdd > maxSize) {
                    file.writeBytes(bytes)
                    Log.d(TAG, "appendInspectorDumpToFile: rewrited (limit 1 MB)")
                } else {
                    file.appendText("\n\n$dump")
                    Log.d(TAG, "appendInspectorDumpToFile: added $toAdd bytes")
                }
            } else {
                file.writeBytes(bytes)
                Log.d(TAG, "appendInspectorDumpToFile: new file created, $toAdd bites")
            }
        } catch (e: Exception) {
            Log.e(TAG, "appendInspectorDumpToFile: file write error", e)
        }
    }

    fun saveJsonFile(name: String, content: String) {
        try {
            val dir = getTracScriptDir() ?: run {
                setDebug("Error saving JSON: TracScript folder not found")
                return
            }

            val file = File(dir, name)

            var text = content
            if (text.length > MAX_TEXT_JSON_SIZE) {
                text = text.substring(0, MAX_TEXT_JSON_SIZE) + "\n---TRUNCATED---"
            }

            file.writeText(text)
            Log.d(TAG, "JSON saved to: ${file.absolutePath}")
            setDebug("JSON saved:\n${file.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "saveJsonFile error", e)
            setDebug("Error JSON saving")
        }
    }

    fun readTailTextFromDocuments(fileName: String, maxBytes: Int = 256 * 1024): String? {
        return try {
            val dir = getTracScriptDir() ?: return null
            val file = File(dir, fileName)
            if (!file.exists()) return null

            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                if (len <= 0L) return ""

                val start = max(0L, len - maxBytes.toLong())
                raf.seek(start)

                val bytes = ByteArray((len - start).toInt())
                raf.readFully(bytes)

                var text = bytes.toString(Charsets.UTF_8)

                // Если не с начала файла — отрезаем “обрубок” первой строки
                if (start > 0) {
                    val idx = text.indexOf('\n')
                    if (idx >= 0 && idx + 1 < text.length) {
                        text = text.substring(idx + 1)
                    }
                }

                text
            }
        } catch (e: Exception) {
            Log.e(TAG, "readTailTextFromDocuments: read error '$fileName'", e)
            null
        }
    }

}
