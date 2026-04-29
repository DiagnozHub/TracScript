package com.brain.tracscript.plugins.gps

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.brain.tracscript.security.AdminAuth
import java.io.File

/**
 * Применение одиночных команд от Wialon (M-пакет / «Произвольное сообщение»).
 *
 * Формат команды — текстовый, "key=value" в одной строке.
 *
 * Whitelist ключей (всё остальное — отклоняется):
 *   - gps_interval_sec       (Int, 1..86400)
 *   - gps_min_distance_m     (Float, 0..100000)
 *   - gps_min_angle_deg      (Float, 0..360)
 *
 * Перед применением — снапшот предыдущих значений в previous_gps_config.properties,
 * чтобы watchdog мог откатить, если связь сломается на 10+ минут после применения.
 */
object RemoteConfigService {

    private const val TAG = "RemoteConfigService"

    private val ALLOWED_KEYS = setOf(
        GpsPluginSettingsDefinition.KEY_GPS_INTERVAL_SEC,
        GpsPluginSettingsDefinition.KEY_GPS_MIN_DISTANCE_M,
        GpsPluginSettingsDefinition.KEY_GPS_MIN_ANGLE_DEG,
        GpsPluginSettingsDefinition.KEY_MOTION_THRESHOLD
    )

    /**
     * Подмножество ALLOWED_KEYS, которое требует перезапуска GpsService для применения
     * (потому что значения читаются один раз при старте — например в positionProvider/gpsFilter).
     * Остальные ключи подхватываются на лету (motion_threshold через motionCfgJob).
     */
    private val KEYS_REQUIRING_RESTART = setOf(
        GpsPluginSettingsDefinition.KEY_GPS_INTERVAL_SEC,
        GpsPluginSettingsDefinition.KEY_GPS_MIN_DISTANCE_M,
        GpsPluginSettingsDefinition.KEY_GPS_MIN_ANGLE_DEG
    )

    const val CONFIG_APPLIED_AT_KEY = "config_applied_at"
    const val LAST_SEND_OK_AT_KEY = "last_send_ok_at"

    /**
     * Одноразовое сообщение, которое уйдёт параметром `text` в ближайшем D-пакете.
     * Удаляется после первой успешной отправки.
     */
    const val PENDING_TEXT_KEY = "pending_text_for_wialon"

    /** 10 минут — окно, в которое должна пройти хотя бы одна успешная отправка. */
    const val ROLLBACK_WINDOW_MS = 10L * 60L * 1000L

    private const val SNAPSHOT_FILE_NAME = "previous_gps_config.properties"

    sealed class Result {
        /**
         * @param requiresRestart нужно ли перезапускать GpsService для применения.
         *   true — для GPS-параметров (interval/distance/angle).
         *   false — для команд, не влияющих на GPS pipeline (set_password, get_version).
         */
        data class Applied(
            val applied: Map<String, String>,
            val requiresRestart: Boolean
        ) : Result()
        data class Rejected(val reason: String) : Result()
    }

    /**
     * Главная точка входа для команд из M-пакета. Различает:
     *   - set_password=...   → смена/снятие пароля администратора
     *   - get_version        → вернуть версию приложения в `text`
     *   - <ключ>=<значение>  → одиночное изменение GPS-параметра (старое поведение)
     */
    fun handleRemoteCommand(appContext: Context, command: String): Result {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return reject(appContext, "empty_command")

        val keyPart = trimmed.substringBefore('=', trimmed).trim()
        val hasEq = '=' in trimmed
        val valuePart = if (hasEq) trimmed.substringAfter('=', "").trim() else ""

        return when (keyPart) {
            "set_password" -> handleSetPassword(appContext, valuePart)
            "get_version" -> handleGetVersion(appContext)
            else -> applySingleCommand(appContext, trimmed)
        }
    }

    /**
     * Смена/снятие/установка пароля. Формат значения:
     *   "OLD:NEW" — обязательно с двоеточием.
     *
     *   "oldpass:newpass" — смена (нужен старый),
     *   "oldpass:"        — снять защиту (нужен старый),
     *   ":newpass"        — первичная установка, когда пароля ещё нет.
     *
     * Без двоеточия — отклоняется. Это защита от случая, когда у оператора
     * Wialon-канал скомпрометирован: без знания текущего пароля сменить нельзя.
     */
    private fun handleSetPassword(appContext: Context, value: String): Result {
        val sep = value.indexOf(':')
        if (sep < 0) {
            return reject(appContext, "set_password requires 'OLD:NEW' format")
        }
        val oldPart = value.substring(0, sep)
        val newPart = value.substring(sep + 1)

        if (!AdminAuth.verifyPassword(appContext, oldPart)) {
            return reject(appContext, "set_password: bad current password")
        }

        AdminAuth.setPassword(appContext, newPart)
        val msg = if (newPart.isBlank()) "Password cleared" else "Password updated"
        val prefs = appContext.getSharedPreferences(
            GpsPluginSettingsDefinition.PREFS,
            Context.MODE_PRIVATE
        )
        prefs.edit().putString(PENDING_TEXT_KEY, msg).apply()
        Log.i(TAG, "remote: $msg (cleared=${newPart.isBlank()})")
        return Result.Applied(applied = mapOf("set_password" to "***"), requiresRestart = false)
    }

    private fun handleGetVersion(appContext: Context): Result {
        val pm: PackageManager = appContext.packageManager
        val pkg = appContext.packageName
        val info = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        } catch (e: Exception) {
            return reject(appContext, "version_lookup: ${e.message}")
        }
        val name = info.versionName ?: "?"
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toString()
        }
        val msg = "App version $name (build $code)"

        val prefs = appContext.getSharedPreferences(
            GpsPluginSettingsDefinition.PREFS,
            Context.MODE_PRIVATE
        )
        prefs.edit().putString(PENDING_TEXT_KEY, msg).apply()
        Log.i(TAG, "remote: $msg")
        return Result.Applied(applied = mapOf("get_version" to name), requiresRestart = false)
    }

    /**
     * Применить одну команду вида "key=value", пришедшую через Wialon M-пакет
     * («Произвольное сообщение»).
     */
    fun applySingleCommand(appContext: Context, command: String): Result {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return reject(appContext, "empty_command")

        val eq = trimmed.indexOf('=')
        if (eq <= 0) return reject(appContext, "bad_command no '='")
        val key = trimmed.substring(0, eq).trim()
        val value = trimmed.substring(eq + 1).trim()

        if (key !in ALLOWED_KEYS) {
            return reject(appContext, "unknown_key '$key'")
        }
        val typed = validate(key, value)
            ?: return reject(appContext, "bad_value $key='$value'")

        snapshotCurrent(appContext)

        val prefs = appContext.getSharedPreferences(
            GpsPluginSettingsDefinition.PREFS,
            Context.MODE_PRIVATE
        )
        val editor = prefs.edit()
        when (typed) {
            is Int -> editor.putInt(key, typed)
            is Float -> editor.putFloat(key, typed)
            else -> return reject(appContext, "internal_typing $key")
        }
        editor.putLong(CONFIG_APPLIED_AT_KEY, System.currentTimeMillis())
        editor.putString(PENDING_TEXT_KEY, "Param $key=$value applied")
        editor.apply()

        val needsRestart = key in KEYS_REQUIRING_RESTART
        Log.i(TAG, "single command applied: $key=$typed restart=$needsRestart")
        return Result.Applied(applied = mapOf(key to typed.toString()), requiresRestart = needsRestart)
    }

    private fun reject(appContext: Context, reason: String): Result {
        Log.w(TAG, "rejected: $reason")
        val prefs = appContext.getSharedPreferences(
            GpsPluginSettingsDefinition.PREFS,
            Context.MODE_PRIVATE
        )
        prefs.edit()
            .putString(PENDING_TEXT_KEY, "Cmd rejected: $reason")
            .apply()
        return Result.Rejected(reason)
    }

    private class ParseException(msg: String) : Exception(msg)

    /** Парсер snapshot-файла. Используется только в rollbackFromSnapshot. */
    private fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var lineNo = 0
        for (rawLine in text.lineSequence()) {
            lineNo++
            val noComment = stripComment(rawLine)
            val line = noComment.trim()
            if (line.isEmpty()) continue
            val eq = line.indexOf('=')
            if (eq <= 0) {
                throw ParseException("line $lineNo: no '='")
            }
            val k = line.substring(0, eq).trim()
            val v = line.substring(eq + 1).trim()
            if (k.isEmpty()) {
                throw ParseException("line $lineNo: empty key")
            }
            out[k] = v
        }
        return out
    }

    private fun stripComment(line: String): String {
        val hash = line.indexOf('#')
        return if (hash < 0) line else line.substring(0, hash)
    }

    private fun validate(key: String, value: String): Any? {
        return when (key) {
            GpsPluginSettingsDefinition.KEY_GPS_INTERVAL_SEC -> {
                val i = value.toIntOrNull() ?: return null
                if (i < 1 || i > 86400) return null
                i
            }
            GpsPluginSettingsDefinition.KEY_GPS_MIN_DISTANCE_M -> {
                val f = value.toFloatOrNull() ?: return null
                if (f < 0f || f > 100000f) return null
                f
            }
            GpsPluginSettingsDefinition.KEY_GPS_MIN_ANGLE_DEG -> {
                val f = value.toFloatOrNull() ?: return null
                if (f < 0f || f > 360f) return null
                f
            }
            GpsPluginSettingsDefinition.KEY_MOTION_THRESHOLD -> {
                val f = value.toFloatOrNull() ?: return null
                if (f < 0f || f > 10f) return null
                f
            }
            else -> null
        }
    }

    private fun snapshotCurrent(appContext: Context) {
        val prefs = appContext.getSharedPreferences(
            GpsPluginSettingsDefinition.PREFS,
            Context.MODE_PRIVATE
        )
        val sb = StringBuilder()
        sb.append("# auto-generated snapshot before remote command apply\n")
        sb.append("# created_at=").append(System.currentTimeMillis()).append('\n')
        sb.append(GpsPluginSettingsDefinition.KEY_GPS_INTERVAL_SEC).append('=')
            .append(prefs.getInt(
                GpsPluginSettingsDefinition.KEY_GPS_INTERVAL_SEC,
                GpsPluginSettingsDefinition.DEFAULT_GPS_INTERVAL_SEC
            )).append('\n')
        sb.append(GpsPluginSettingsDefinition.KEY_GPS_MIN_DISTANCE_M).append('=')
            .append(prefs.getFloat(
                GpsPluginSettingsDefinition.KEY_GPS_MIN_DISTANCE_M,
                GpsPluginSettingsDefinition.DEFAULT_GPS_MIN_DISTANCE_M
            )).append('\n')
        sb.append(GpsPluginSettingsDefinition.KEY_GPS_MIN_ANGLE_DEG).append('=')
            .append(prefs.getFloat(
                GpsPluginSettingsDefinition.KEY_GPS_MIN_ANGLE_DEG,
                GpsPluginSettingsDefinition.DEFAULT_GPS_MIN_ANGLE_DEG
            )).append('\n')
        sb.append(GpsPluginSettingsDefinition.KEY_MOTION_THRESHOLD).append('=')
            .append(prefs.getFloat(
                GpsPluginSettingsDefinition.KEY_MOTION_THRESHOLD,
                GpsPluginSettingsDefinition.DEFAULT_MOTION_THRESHOLD
            )).append('\n')

        try {
            File(appContext.filesDir, SNAPSHOT_FILE_NAME).writeText(sb.toString())
        } catch (e: Exception) {
            Log.w(TAG, "snapshot write failed: ${e.message}")
        }
    }

    /**
     * Проверить, не пора ли откатить применённую команду.
     * Откат, если:
     *   - есть применённая команда (CONFIG_APPLIED_AT_KEY > 0),
     *   - прошло больше ROLLBACK_WINDOW_MS с момента применения,
     *   - после применения не было НИ одной успешной отправки.
     *
     * Возвращает true, если откат был выполнен.
     */
    fun checkAndRollback(appContext: Context): Boolean {
        val prefs = appContext.getSharedPreferences(
            GpsPluginSettingsDefinition.PREFS,
            Context.MODE_PRIVATE
        )
        val appliedAt = prefs.getLong(CONFIG_APPLIED_AT_KEY, 0L)
        if (appliedAt <= 0L) return false

        val now = System.currentTimeMillis()
        if (now - appliedAt < ROLLBACK_WINDOW_MS) return false

        val lastSendOkAt = prefs.getLong(LAST_SEND_OK_AT_KEY, 0L)
        if (lastSendOkAt >= appliedAt) {
            // Связь жива — снимаем "наблюдение", чтобы повторно не проверять зря
            prefs.edit().remove(CONFIG_APPLIED_AT_KEY).apply()
            return false
        }

        Log.w(TAG, "rollback triggered: no successful send within ${ROLLBACK_WINDOW_MS}ms after apply")
        val ok = rollbackFromSnapshot(appContext)
        prefs.edit()
            .remove(CONFIG_APPLIED_AT_KEY)
            .putString(PENDING_TEXT_KEY, "Cmd rolled back: no ack within ${ROLLBACK_WINDOW_MS / 60000} min")
            .apply()
        return ok
    }

    fun markSendOk(appContext: Context) {
        val prefs = appContext.getSharedPreferences(
            GpsPluginSettingsDefinition.PREFS,
            Context.MODE_PRIVATE
        )
        prefs.edit().putLong(LAST_SEND_OK_AT_KEY, System.currentTimeMillis()).apply()
    }

    fun peekPendingText(appContext: Context): String? {
        val prefs = appContext.getSharedPreferences(
            GpsPluginSettingsDefinition.PREFS,
            Context.MODE_PRIVATE
        )
        return prefs.getString(PENDING_TEXT_KEY, null)?.takeIf { it.isNotBlank() }
    }

    fun clearPendingText(appContext: Context) {
        val prefs = appContext.getSharedPreferences(
            GpsPluginSettingsDefinition.PREFS,
            Context.MODE_PRIVATE
        )
        prefs.edit().remove(PENDING_TEXT_KEY).apply()
    }

    /**
     * Откатить prefs к snapshot.
     */
    fun rollbackFromSnapshot(appContext: Context): Boolean {
        val file = File(appContext.filesDir, SNAPSHOT_FILE_NAME)
        if (!file.exists()) {
            Log.w(TAG, "rollback: snapshot file missing")
            return false
        }
        val text = try {
            file.readText()
        } catch (e: Exception) {
            Log.w(TAG, "rollback: read failed: ${e.message}")
            return false
        }

        val parsed = try {
            parse(text)
        } catch (e: Exception) {
            Log.w(TAG, "rollback: parse failed: ${e.message}")
            return false
        }

        val prefs = appContext.getSharedPreferences(
            GpsPluginSettingsDefinition.PREFS,
            Context.MODE_PRIVATE
        )
        val editor = prefs.edit()
        for ((k, v) in parsed) {
            when (k) {
                GpsPluginSettingsDefinition.KEY_GPS_INTERVAL_SEC ->
                    v.toIntOrNull()?.let { editor.putInt(k, it) }
                GpsPluginSettingsDefinition.KEY_GPS_MIN_DISTANCE_M ->
                    v.toFloatOrNull()?.let { editor.putFloat(k, it) }
                GpsPluginSettingsDefinition.KEY_GPS_MIN_ANGLE_DEG ->
                    v.toFloatOrNull()?.let { editor.putFloat(k, it) }
                GpsPluginSettingsDefinition.KEY_MOTION_THRESHOLD ->
                    v.toFloatOrNull()?.let { editor.putFloat(k, it) }
            }
        }
        editor.apply()
        Log.i(TAG, "rollback applied")
        return true
    }
}
