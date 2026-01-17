package com.brain.tracscript.plugins.gps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.brain.tracscript.R
import com.brain.tracscript.core.DataBusEvent
import com.brain.tracscript.core.PluginSettingsDefinition
import com.brain.tracscript.core.TracScriptApp
import com.brain.tracscript.telemetry.MotionBus
import com.brain.tracscript.telemetry.Position
import com.brain.tracscript.telemetry.RawGpsBus
import com.brain.tracscript.telemetry.RawNmeaBus
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.round

// ------------------------
// GPS
// ------------------------

data class GpsConfig(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val imei: String,
    val password: String,
    val gpsIntervalSec: Int,
    val gpsMinDistanceM: Float,
    val gpsMinAngleDeg: Float,
    val protocol: GpsProtocolType = GpsProtocolType.OSMAND
)

object GpsPluginSettingsDefinition : PluginSettingsDefinition {

    override val id: String = GpsPluginKey.ID

    @Composable
    override fun displayName(): String = "GPS"

    const val PREFS = GpsPluginKey.PREFS
    const val KEY_ENABLED = "enabled"
    const val KEY_HOST = "host"
    const val KEY_PORT = "port"
    const val KEY_IMEI = "imei"
    const val KEY_PASSWORD = "password"
    const val PLUGIN_ENABLED_CHANGED_EVENT = "plugin_enabled_changed"
    const val PLUGIN_ID = "pluginId"

    const val KEY_GPS_INTERVAL_SEC = "gps_interval_sec"
    const val KEY_GPS_MIN_DISTANCE_M = "gps_min_distance_m"
    const val KEY_GPS_MIN_ANGLE_DEG = "gps_min_angle_deg"
    const val KEY_MOTION_THRESHOLD = "motion_threshold"
    const val KEY_ACCEL_CONFIDENCE_MOVING = "accel_conf_moving"

    const val KEY_PROTOCOL = "protocol"
    val DEFAULT_PROTOCOL = GpsProtocolType.OSMAND

    const val DEFAULT_GPS_INTERVAL_SEC = 300
    const val DEFAULT_GPS_MIN_DISTANCE_M = 100f
    const val DEFAULT_GPS_MIN_ANGLE_DEG = 10f
    const val DEFAULT_MOTION_THRESHOLD = 0.80f
    const val DEFAULT_ACCEL_CONFIDENCE_MOVING = 0.10f

    private const val DEFAULT_ENABLED = false
    private const val DEFAULT_PASSWORD = "NA"

    private const val KEY_PIN_TO_MAIN = "pin_to_main"

    private fun filterDigits(s: String): String = s.filter { it.isDigit() }
    private fun isImeiOk(s: String): Boolean = s.length in 6..14

    private fun generateImei8(): String {
        val n = kotlin.random.Random.nextInt(0, 100_000_000)
        return String.format(Locale.US, "%08d", n)
    }

    private fun getOrCreateValidImei(prefs: android.content.SharedPreferences): String {
        val stored = prefs.getString(KEY_IMEI, null)?.trim().orEmpty()
        if (stored.isNotEmpty() && isImeiOk(stored)) return stored

        val gen = generateImei8()
        prefs.edit().putString(KEY_IMEI, gen).apply()
        return gen
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current

        val prefs = remember {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }

        var pinToMain by remember {
            mutableStateOf(prefs.getBoolean(KEY_PIN_TO_MAIN, false))
        }

        fun savePinToMain(v: Boolean) {
            prefs.edit().putBoolean(KEY_PIN_TO_MAIN, v).apply()
        }

        var cardUnlocked by remember { mutableStateOf(false) }

        // -------------------------
        // Live акселерометр / GPS / NMEA
        // -------------------------
        val motionDebug by MotionBus.debug.collectAsState(initial = null)

        val rawPos: Position? by RawGpsBus.lastRawPosition.collectAsState(initial = null)
        val rawErr: String? by RawGpsBus.lastRawError.collectAsState(initial = null)
        val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }

        val nmeaHealth by RawNmeaBus.lastHealth.collectAsState(initial = null)
        val nmeaSentence by RawNmeaBus.lastSentence.collectAsState(initial = null)
        val nmeaErr by RawNmeaBus.lastError.collectAsState(initial = null)

        // --- состояние из префов ---
        var enabled by remember {
            mutableStateOf(prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED))
        }

        var uiEnabled by remember { mutableStateOf(enabled) }

        var host by remember {
            mutableStateOf(prefs.getString(KEY_HOST, "") ?: "")
        }

        var portText by remember {
            mutableStateOf(
                prefs.getInt(KEY_PORT, -1).let { if (it > 0) it.toString() else "" }
            )
        }

        val savedImei = remember { getOrCreateValidImei(prefs) }
        var imeiInput by remember { mutableStateOf(savedImei) }
        var imeiTouched by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            imeiInput = getOrCreateValidImei(prefs)
            imeiTouched = false
        }

        var password by remember {
            mutableStateOf(prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD)
        }

        var intervalSecText by remember {
            mutableStateOf(prefs.getInt(KEY_GPS_INTERVAL_SEC, DEFAULT_GPS_INTERVAL_SEC).toString())
        }
        var minDistanceText by remember {
            mutableStateOf(
                prefs.getFloat(KEY_GPS_MIN_DISTANCE_M, DEFAULT_GPS_MIN_DISTANCE_M)
                    .toString().removeSuffix(".0")
            )
        }
        var minAngleText by remember {
            mutableStateOf(
                prefs.getFloat(KEY_GPS_MIN_ANGLE_DEG, DEFAULT_GPS_MIN_ANGLE_DEG)
                    .toString().removeSuffix(".0")
            )
        }

        var protocol by remember {
            mutableStateOf(
                GpsProtocolType.valueOf(
                    prefs.getString(KEY_PROTOCOL, DEFAULT_PROTOCOL.name)
                        ?: DEFAULT_PROTOCOL.name
                )
            )
        }

        fun saveProtocol(v: GpsProtocolType) {
            prefs.edit().putString(KEY_PROTOCOL, v.name).apply()
        }

        fun saveEnabled(value: Boolean) {
            // КРИТИЧНО: commit(), не apply()
            prefs.edit().putBoolean(KEY_ENABLED, value).commit()
        }

        fun saveHost(value: String) {
            val v = value.trim()
            if (v.isEmpty()) prefs.edit().remove(KEY_HOST).apply()
            else prefs.edit().putString(KEY_HOST, v).apply()
        }

        fun savePort(value: Int) {
            prefs.edit().putInt(KEY_PORT, value).apply()
        }

        fun saveImei(value: String) {
            val v = value.trim()
            if (!isImeiOk(v)) return
            prefs.edit().putString(KEY_IMEI, v).apply()
        }

        fun savePassword(value: String) {
            prefs.edit().putString(KEY_PASSWORD, value).apply()
        }

        fun saveIntervalSec(value: Int) {
            prefs.edit().putInt(KEY_GPS_INTERVAL_SEC, value).apply()
        }

        fun saveMinDistance(value: Float) {
            prefs.edit().putFloat(KEY_GPS_MIN_DISTANCE_M, value).apply()
        }

        fun saveMinAngle(value: Float) {
            prefs.edit().putFloat(KEY_GPS_MIN_ANGLE_DEG, value).apply()
        }

        var motionThreshold by remember {
            mutableStateOf(prefs.getFloat(KEY_MOTION_THRESHOLD, DEFAULT_MOTION_THRESHOLD))
        }

        fun saveMotionThreshold(value: Float) {
            prefs.edit().putFloat(KEY_MOTION_THRESHOLD, value).apply()
        }

        var confPct by remember {
            mutableStateOf(
                prefs.getFloat(KEY_ACCEL_CONFIDENCE_MOVING, DEFAULT_ACCEL_CONFIDENCE_MOVING) * 100f
            )
        }

        fun saveConfPct(pct: Float) {
            prefs.edit().putFloat(KEY_ACCEL_CONFIDENCE_MOVING, pct / 100f).apply()
        }

        fun resetToDefaults() {
            //password = DEFAULT_PASSWORD
            //intervalSecText = DEFAULT_GPS_INTERVAL_SEC.toString()
            //minDistanceText = DEFAULT_GPS_MIN_DISTANCE_M.toString().removeSuffix(".0")
            //minAngleText = DEFAULT_GPS_MIN_ANGLE_DEG.toString().removeSuffix(".0")
            motionThreshold = DEFAULT_MOTION_THRESHOLD
            confPct = DEFAULT_ACCEL_CONFIDENCE_MOVING * 100f

            prefs.edit()
                //.putString(KEY_PASSWORD, DEFAULT_PASSWORD)
                //.putInt(KEY_GPS_INTERVAL_SEC, DEFAULT_GPS_INTERVAL_SEC)
                //.putFloat(KEY_GPS_MIN_DISTANCE_M, DEFAULT_GPS_MIN_DISTANCE_M)
                //.putFloat(KEY_GPS_MIN_ANGLE_DEG, DEFAULT_GPS_MIN_ANGLE_DEG)
                .putFloat(KEY_MOTION_THRESHOLD, DEFAULT_MOTION_THRESHOLD)
                .putFloat(KEY_ACCEL_CONFIDENCE_MOVING, DEFAULT_ACCEL_CONFIDENCE_MOVING)
                .apply()

            Toast.makeText(
                context,
                context.getString(R.string.settings_reset_to_default),
                Toast.LENGTH_SHORT
            ).show()
        }

        fun hasIgnoreBatteryOptimizations(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }

        fun notifyEnabledChanged(isEnabled: Boolean) {
            val bus = (context.applicationContext as TracScriptApp).pluginRuntime.dataBus

            bus.post(
                DataBusEvent(
                    type = PLUGIN_ENABLED_CHANGED_EVENT,
                    payload = mapOf(
                        PLUGIN_ID to id,
                        KEY_ENABLED to isEnabled
                    )
                )
            )
        }


        // ============================================================
        // Enable flow (state-driven): notif -> fine -> background -> battery -> SUCCESS
        // ============================================================

        fun hasPerm(p: String): Boolean =
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

        fun needNotifications(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !hasPerm(Manifest.permission.POST_NOTIFICATIONS)

        fun bgSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q // 29+

        fun needFine(): Boolean =
            !hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)

        fun needBackground(): Boolean =
            bgSupported() && !hasPerm(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        var pendingEnable by rememberSaveable { mutableStateOf(false) }
        var batteryAsked by rememberSaveable { mutableStateOf(false) }
        var batteryWaiting by rememberSaveable { mutableStateOf(false) }
        var flowTick by rememberSaveable { mutableStateOf(0) }

        var appSettingsWaiting by rememberSaveable { mutableStateOf(false) }

        var bgRequestTried by rememberSaveable { mutableStateOf(false) }

        val appSettingsLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (!appSettingsWaiting) return@rememberLauncherForActivityResult
            appSettingsWaiting = false
            flowTick++
        }

        fun openAppSettings(): Boolean {
            return try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appSettingsLauncher.launch(intent)
                true
            } catch (_: Throwable) {
                false
            }
        }


        // uiEnabled всегда отражает enabled (и не спорит с flow)
        LaunchedEffect(enabled) {
            uiEnabled = enabled
        }

        fun finalizeEnabled() {
            pendingEnable = false
            batteryAsked = false
            batteryWaiting = false

            enabled = true
            saveEnabled(true)
            uiEnabled = true
            notifyEnabledChanged(true)
        }

        fun finalizeDisabled(reason: String) {
            pendingEnable = false
            batteryAsked = false
            batteryWaiting = false

            enabled = false
            saveEnabled(false)
            uiEnabled = false
            notifyEnabledChanged(false)

            Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
        }


        val batteryLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (!batteryWaiting) return@rememberLauncherForActivityResult
            batteryWaiting = false
        }

        fun launchBatteryScreenIfPossible(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false

            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            return try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    batteryLauncher.launch(intent)
                    true
                } else false
            } catch (_: Throwable) {
                false
            }
        }

        val notifLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted) {
                if (pendingEnable) finalizeDisabled(context.getString(R.string.notification_permission_required))
                else uiEnabled = false
                return@rememberLauncherForActivityResult
            }
            flowTick++
        }

        val fineLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            // ВАЖНО: на старых Android коллбэк может прийти, когда pendingEnable ещё false
            if (!granted) {
                if (pendingEnable) {
                    finalizeDisabled(context.getString(R.string.location_permission_required))
                } else {
                    // если flow не активен — просто вернуть свитч
                    uiEnabled = false
                }
                return@rememberLauncherForActivityResult
            }

            // разрешение дали — просто пинаем “двигатель”
            flowTick++
        }


        val bgLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { _ ->
            if (!pendingEnable) return@rememberLauncherForActivityResult
            flowTick++ // дальше движок сам проверит, дали или нет
        }

        // “двигатель” — всегда один, живёт в LaunchedEffect
        LaunchedEffect(pendingEnable, flowTick) {
            if (!pendingEnable) {
                uiEnabled = enabled
                return@LaunchedEffect
            }

            // если ждём возврата с экрана батареи — просто не дёргаем ничего
            if (batteryWaiting) return@LaunchedEffect

            // 1) REQUIRED permissions
            if (needNotifications()) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return@LaunchedEffect
            }

            if (needFine()) {
                fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                return@LaunchedEffect
            }

            // Background location — НЕ блокируем включение, только пытаемся запросить 1 раз
            if (needBackground() && !bgRequestTried) {
                bgRequestTried = true
                bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                return@LaunchedEffect
            }


            /*
            if (needBackground()) {
                if (!bgRequestTried) {
                    bgRequestTried = true
                    bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    return@LaunchedEffect
                }

                val launched = openAppSettings()
                if (launched) {
                    appSettingsWaiting = true
                    return@LaunchedEffect
                }

                finalizeDisabled(context.getString(R.string.bg_location_required))
                return@LaunchedEffect
            }
            */

            // 2) Всё нужное есть -> включаемся СРАЗУ
            finalizeEnabled()

            if (bgSupported() && !hasPerm(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                Toast.makeText(
                    context,
                    context.getString(R.string.bg_location_recommended),
                    Toast.LENGTH_LONG
                ).show()
            }

            // 3) OPTIONAL: батарея — просто спросить 1 раз, но не выключать если отказ
            if (!hasIgnoreBatteryOptimizations() && !batteryAsked) {
                batteryAsked = true

                val launched = launchBatteryScreenIfPossible()
                if (launched) {
                    batteryWaiting = true
                } else {
                    // не фейлим, просто информируем
                    Toast.makeText(
                        context,
                        context.getString(R.string.battery_optimization_required),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        fun toast(@androidx.annotation.StringRes id: Int) {
            Toast.makeText(context, context.getString(id), Toast.LENGTH_LONG).show()
        }

        fun validateBeforeEnable(): Boolean {
            val h = host.trim()
            if (h.isEmpty()) {
                toast(R.string.server_required)      // "Заполни сервер"
                return false
            }

            val p = portText.trim().toIntOrNull()
            if (p == null || p !in 1..65535) {
                toast(R.string.port_required)        // "Заполни порт"
                return false
            }

            val im = imeiInput.trim()
            if (!isImeiOk(im)) {
                toast(R.string.imei_required)        // "Заполни IMEI"
                return false
            }

            return true
        }

        fun onUserToggleEnable(wantEnable: Boolean) {
            if (!wantEnable) {
                finalizeDisabled(context.getString(R.string.turned_off))
                return
            }

            if (!validateBeforeEnable()) {
                // важно: не трогаем enabled/uiEnabled/pendingEnable
                uiEnabled = false // чтобы свитч визуально вернулся назад (если он уже успел щёлкнуть)
                return
            }

            pendingEnable = true
            batteryAsked = false
            batteryWaiting = false
            uiEnabled = true
            bgRequestTried=false
            flowTick++
        }


        fun stopGpsServiceIfRunning() {
            // если плагин выключен - и так ничего не должно работать
            if (!enabled) return

            // отменяем возможный enable-flow, чтобы он не “включил назад” после переключения
            pendingEnable = false
            batteryWaiting = false
            batteryAsked = false
            bgRequestTried = false

            // фактически выключаем плагин -> runtime должен остановить сервис
            enabled = false
            saveEnabled(false)
            uiEnabled = false
            notifyEnabledChanged(false)

            Toast.makeText(
                context,
                context.getString(R.string.service_stopped_due_to_protocol_change),
                Toast.LENGTH_SHORT
            ).show()
        }

        // Сервис “считаем запущенным”, если плагин включен.
        // На время enable-flow тоже блокируем, чтобы не менять конфиг в процессе выдачи прав.
        val configLocked = enabled || pendingEnable
        val canEdit = !configLocked


        // ============================================================
        // UI
        // ============================================================

        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.enabled), modifier = Modifier.weight(1f))
                Switch(
                    checked = uiEnabled,
                    enabled = !pendingEnable,
                    onCheckedChange = { checked ->
                        onUserToggleEnable(checked)
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.show_on_main_screen), modifier = Modifier.weight(1f))
                Switch(
                    checked = pinToMain,
                    onCheckedChange = { v ->
                        pinToMain = v
                        savePinToMain(v)
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.protocol),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        enabled = canEdit,
                        onClick = {
                            if (protocol != GpsProtocolType.OSMAND) {
                                stopGpsServiceIfRunning()
                                protocol = GpsProtocolType.OSMAND
                                saveProtocol(protocol)
                            }
                        },
                        border = if (protocol == GpsProtocolType.OSMAND)
                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        else null
                    ) { Text("Traccar") }
                    OutlinedButton(
                        enabled = canEdit,
                        onClick = {
                            if (protocol != GpsProtocolType.WIALON) {
                                stopGpsServiceIfRunning()
                                protocol = GpsProtocolType.WIALON
                                saveProtocol(protocol)
                            }
                        },
                        border = if (protocol == GpsProtocolType.WIALON)
                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        else null
                    ) { Text("Wialon IPS") }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                enabled = canEdit,
                value = host,
                onValueChange = { new ->
                    host = new
                    saveHost(new)
                },
                label = { Text(stringResource(R.string.server_address)) },
                placeholder = { Text(stringResource(R.string.server_host_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                enabled = canEdit,
                value = portText,
                onValueChange = { new ->
                    val filtered = new.filter { it.isDigit() }
                    portText = filtered

                    val p = filtered.toIntOrNull()
                    if (p != null && p in 1..65535) {
                        savePort(p)
                    } else if (filtered.isEmpty()) {
                        prefs.edit().remove(KEY_PORT).apply()
                    }
                },
                label = { Text(stringResource(R.string.server_port)) },
                placeholder = { Text(stringResource(R.string.server_port_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Spacer(modifier = Modifier.height(8.dp))

            val imeiValidNow = isImeiOk(imeiInput)
            val showImeiError = imeiTouched && !imeiValidNow

            OutlinedTextField(
                enabled = canEdit,
                value = imeiInput,
                onValueChange = { new ->
                    imeiTouched = true
                    imeiInput = filterDigits(new)
                    if (isImeiOk(imeiInput)) saveImei(imeiInput)
                },
                label = { Text(stringResource(R.string.device_imei)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { f ->
                        if (!f.isFocused) {
                            if (!isImeiOk(imeiInput)) {
                                imeiInput = getOrCreateValidImei(prefs)
                                imeiTouched = false
                            }
                        }
                    },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = showImeiError,
                supportingText = {
                    if (showImeiError) Text(stringResource(R.string.imei_length_error))
                },
                textStyle = TextStyle(
                    color = if (showImeiError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                )
            )

            if (protocol == GpsProtocolType.WIALON) {
                OutlinedTextField(
                    enabled = canEdit,
                    value = password,
                    onValueChange = { new ->
                        password = new
                        savePassword(new)
                    },
                    label = { Text(stringResource(R.string.device_password)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                text = stringResource(R.string.gps_filter_settings),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                enabled = canEdit,
                value = intervalSecText,
                onValueChange = { new ->
                    val filtered = new.filter { it.isDigit() }
                    intervalSecText = filtered
                    val v = filtered.toIntOrNull()
                    if (v != null && v > 0) saveIntervalSec(v)
                },
                label = { Text(stringResource(R.string.interval_seconds)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                enabled = canEdit,
                value = minDistanceText,
                onValueChange = { new ->
                    val filtered = new.filter { it.isDigit() || it == '.' }
                    minDistanceText = filtered
                    val v = filtered.toFloatOrNull()
                    if (v != null && v >= 0f) saveMinDistance(v)
                },
                label = { Text(stringResource(R.string.min_distance_meters)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                enabled = canEdit,
                value = minAngleText,
                onValueChange = { new ->
                    val filtered = new.filter { it.isDigit() || it == '.' }
                    minAngleText = filtered
                    val v = filtered.toFloatOrNull()
                    if (v != null && v >= 0f) saveMinAngle(v)
                },
                label = { Text(stringResource(R.string.min_course_change)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.accelerometer),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .alpha(if (cardUnlocked) 1f else 0.6f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (motionDebug == null) {
                        Text(stringResource(R.string.no_data), style = MaterialTheme.typography.bodySmall)
                    } else {
                        val d = motionDebug!!
                        val confPctLocal = d.confidence * 100f

                        val stateText = when {
                            d.confidence >= 0.7f -> stringResource(R.string.recent_motion)
                            d.confidence >= 0.2f -> stringResource(R.string.fading)
                            else -> stringResource(R.string.inactive)
                        }

                        val confText = "%.0f".format(confPctLocal)
                        val secondsText = d.secondsSinceMotion?.let { "%.1f".format(it) } ?: "—"

                        Text(
                            text = stringResource(R.string.motion_info, confText, secondsText, stateText),
                            style = MaterialTheme.typography.bodySmall
                        )

                        val rmsEmaText = String.format(Locale.US, "%.4f", d.rmsEma)
                        Text(text = "RMS_EMA: $rmsEmaText", style = MaterialTheme.typography.bodySmall)
                    }

                    val motionThresholdText = "%.2f".format(motionThreshold)
                    Text(
                        text = stringResource(R.string.motion_sensitivity, motionThresholdText),
                        style = MaterialTheme.typography.bodySmall
                    )

                    val minT = 0.20f
                    val maxT = 10.00f
                    val step = 0.10f
                    val steps = ((maxT - minT) / step).toInt() - 1

                    Slider(
                        value = motionThreshold.coerceIn(minT, maxT),
                        onValueChange = { raw ->
                            val snapped = (round((raw - minT) / step) * step + minT).coerceIn(minT, maxT)
                            motionThreshold = snapped
                            saveMotionThreshold(snapped)
                        },
                        valueRange = minT..maxT,
                        steps = steps,
                        enabled = cardUnlocked && canEdit
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    val confPctText = "%.0f".format(confPct)
                    Text(text = stringResource(R.string.confidence_threshold, confPctText))

                    Slider(
                        value = confPct,
                        onValueChange = { v ->
                            confPct = v
                            saveConfPct(v)
                        },
                        valueRange = 1f..100f,
                        steps = 99,
                        enabled = cardUnlocked && canEdit
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { resetToDefaults() },
                            modifier = Modifier.weight(1f),
                            enabled = cardUnlocked && canEdit
                        ) {
                            Text(stringResource(R.string.basic), maxLines = 2)
                        }

                        OutlinedButton(onClick = { cardUnlocked = !cardUnlocked }, enabled = canEdit) {
                            Text(
                                if (cardUnlocked) stringResource(R.string.lock) else stringResource(R.string.unlock),
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.gps_android_data),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))

            if (rawErr != null) {
                Text(
                    text = stringResource(R.string.nmea_error_with_details, nmeaErr.toString()),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                val p = rawPos
                if (p == null) {
                    Text(stringResource(R.string.no_data), style = MaterialTheme.typography.bodySmall)
                } else {
                    val latText = "%.6f".format(Locale.US, p.latitude)
                    val lonText = "%.6f".format(Locale.US, p.longitude)
                    val satsText = p.sats.toString()
                    val timeText = timeFmt.format(p.time)
                    val accText = "%.1f".format(Locale.US, p.accuracy)
                    val speedText = "%.1f".format(Locale.US, p.speed)

                    Text(
                        text = stringResource(
                            R.string.gps_debug_info,
                            latText,
                            lonText,
                            satsText,
                            timeText,
                            accText,
                            speedText
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.nmea_raw_data),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))

            if (nmeaErr != null) {
                Text(
                    text = stringResource(R.string.nmea_error_with_details, nmeaErr.toString()),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                val h = nmeaHealth
                if (h == null) {
                    Text(stringResource(R.string.no_data), style = MaterialTheme.typography.bodySmall)
                } else {
                    val now = System.currentTimeMillis()
                    val nmeaAlive = h.isNmeaAlive(now)
                    val fixAlive = h.isFixAlive(now)

                    val nmeaStatus = if (nmeaAlive) stringResource(R.string.status_ok) else stringResource(R.string.status_no)
                    val fixStatus = if (fixAlive) stringResource(R.string.status_yes) else stringResource(R.string.status_no)

                    val satsText = h.sats?.toString() ?: stringResource(R.string.not_available)
                    val hdopText = h.hdop?.toString() ?: stringResource(R.string.not_available)
                    val qualityText = h.fixQuality?.toString() ?: stringResource(R.string.not_available)
                    val rmcValidText = h.rmcValid?.toString() ?: stringResource(R.string.not_available)

                    Text(
                        text = stringResource(
                            R.string.gps_health_info,
                            nmeaStatus,
                            fixStatus,
                            satsText,
                            hdopText,
                            qualityText,
                            rmcValidText
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (!nmeaSentence.isNullOrBlank()) {
                        val lastSentence = nmeaSentence ?: stringResource(R.string.not_available)

                        Text(
                            text = stringResource(R.string.last_nmea_sentence, lastSentence),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 40.dp)
                        )

                        Spacer(Modifier.height(6.dp))
                    } else {
                        Spacer(Modifier.height(40.dp))
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}
