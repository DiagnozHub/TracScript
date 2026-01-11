package com.brain.tracscript.plugins.gps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.brain.tracscript.core.PluginSettingsDefinition

import androidx.compose.ui.draw.alpha
import com.brain.tracscript.telemetry.MotionBus

import com.brain.tracscript.telemetry.RawGpsBus
import com.brain.tracscript.telemetry.Position
import com.brain.tracscript.telemetry.RawNmeaBus
import java.text.SimpleDateFormat
import java.util.Locale

import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.brain.tracscript.R
import com.brain.tracscript.core.TracScriptApp
import com.brain.tracscript.core.DataBusEvent

import androidx.compose.ui.focus.onFocusChanged

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
    val protocol: GpsProtocolType = GpsProtocolType.WIALON
)

object GpsPluginSettingsDefinition : PluginSettingsDefinition {

    override val id: String = "gps_wialon"

    @Composable
    override fun displayName(): String = "GPS"

    private const val PREFS = "plugin_gps_wialon"
    const val KEY_ENABLED = "enabled"
    const val KEY_HOST = "host"
    const val KEY_PORT = "port"
    const val KEY_IMEI = "imei"
    const val KEY_PASSWORD = "password"

    const val KEY_GPS_INTERVAL_SEC = "gps_interval_sec"
    const val KEY_GPS_MIN_DISTANCE_M = "gps_min_distance_m"
    const val KEY_GPS_MIN_ANGLE_DEG = "gps_min_angle_deg"
    const val KEY_MOTION_THRESHOLD = "motion_threshold"
    const val KEY_ACCEL_CONFIDENCE_MOVING = "accel_conf_moving"

    const val KEY_PROTOCOL = "protocol"
    val DEFAULT_PROTOCOL = GpsProtocolType.WIALON



    const val DEFAULT_GPS_INTERVAL_SEC = 300
    const val DEFAULT_GPS_MIN_DISTANCE_M = 100f   // 100 meters
    const val DEFAULT_GPS_MIN_ANGLE_DEG = 10f     // 10 degrees
    const val DEFAULT_MOTION_THRESHOLD = 0.80f
    const val DEFAULT_ACCEL_CONFIDENCE_MOVING = 0.10f

    const val DEFAULT_ENABLED = false
    const val DEFAULT_PASSWORD = "NA"

    private const val KEY_PIN_TO_MAIN = "pin_to_main"

    private fun filterDigits(s: String): String = s.filter { it.isDigit() }
    private fun isImeiOk(s: String): Boolean = s.length in 6..14

    private fun generateImei8(): String {
        val n = kotlin.random.Random.nextInt(0, 100_000_000)
        return String.format(Locale.US, "%08d", n) // 8 цифр — попадает в 6..14
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
        // Live акселерометр (едем/стоим/неизвестно)
        // -------------------------
        val motionDebug by MotionBus.debug.collectAsState(initial = null)

        val rawPos: Position? by RawGpsBus.lastRawPosition.collectAsState(initial = null)
        val rawErr: String? by RawGpsBus.lastRawError.collectAsState(initial = null)
        val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }

        val nmeaHealth by RawNmeaBus.lastHealth.collectAsState(initial = null)
        val nmeaSentence by RawNmeaBus.lastSentence.collectAsState(initial = null)
        val nmeaErr by RawNmeaBus.lastError.collectAsState(initial = null)


        var pendingLocationPermissionFlow by remember {
            mutableStateOf(false)
        }

        // --- состояние из префов ---
        var enabled by remember {
            mutableStateOf(prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED))
        }

        var host by remember {
            mutableStateOf(prefs.getString(KEY_HOST, "") ?: "")
        }

        var portText by remember {
            mutableStateOf(
                prefs.getInt(KEY_PORT, -1).let { if (it > 0) it.toString() else "" }
            )
        }

        // всегда берём валидный из prefs (или генерим и сохраняем)
        val savedImei = remember { getOrCreateValidImei(prefs) }

        // поле ввода - отдельное, может быть невалидным во время редактирования
        var imeiInput by remember { mutableStateOf(savedImei) }
        var imeiTouched by remember { mutableStateOf(false) }

        // при любом пересоздании экрана/возврате - восстанавливаем именно savedImei в поле
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
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
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
            // UI state
            //enabled = DEFAULT_ENABLED
            //host = DEFAULT_HOST
            //portText = DEFAULT_PORT.toString()
            //imei = DEFAULT_IMEI
            password = DEFAULT_PASSWORD

            intervalSecText = DEFAULT_GPS_INTERVAL_SEC.toString()
            minDistanceText = DEFAULT_GPS_MIN_DISTANCE_M.toString().removeSuffix(".0")
            minAngleText = DEFAULT_GPS_MIN_ANGLE_DEG.toString().removeSuffix(".0")

            motionThreshold = DEFAULT_MOTION_THRESHOLD
            confPct = DEFAULT_ACCEL_CONFIDENCE_MOVING * 100f

            // prefs
            prefs.edit()
                .putString(KEY_PASSWORD, DEFAULT_PASSWORD)
                .putInt(KEY_GPS_INTERVAL_SEC, DEFAULT_GPS_INTERVAL_SEC)
                .putFloat(KEY_GPS_MIN_DISTANCE_M, DEFAULT_GPS_MIN_DISTANCE_M)
                .putFloat(KEY_GPS_MIN_ANGLE_DEG, DEFAULT_GPS_MIN_ANGLE_DEG)
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

        fun requestIgnoreBatteryOptimizations() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }

        fun notifyEnabledChanged(enabled: Boolean) {
            val bus = (context.applicationContext as TracScriptApp)
                .pluginRuntime.dataBus

            bus.post(
                DataBusEvent(
                    type = "plugin_enabled_changed",
                    payload = mapOf(
                        "pluginId" to "gps_wialon",
                        "enabled" to enabled
                    )
                )
            )
        }


        // --- launchers для разрешений ---

        // BACKGROUND LOCATION (API >= 29)
        val backgroundLocationPermissionLauncher =
            rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    if (hasIgnoreBatteryOptimizations()) {
                        enabled = true
                        saveEnabled(true)
                        Toast.makeText(
                            context,
                            context.getString(R.string.background_location_granted),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        enabled = false
                        saveEnabled(false)

                        Toast.makeText(
                            context,
                            context.getString(R.string.battery_optimization_warning),
                            Toast.LENGTH_LONG
                        ).show()

                        requestIgnoreBatteryOptimizations()
                    }
                } else {
                    enabled = false
                    saveEnabled(false)
                    Toast.makeText(
                        context,
                        context.getString(R.string.background_location_required_warning),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

        // FINE LOCATION
        val fineLocationPermissionLauncher =
            rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (!granted) {
                    enabled = false
                    saveEnabled(false)
                    Toast.makeText(
                        context,
                        context.getString(R.string.location_permission_required),
                        Toast.LENGTH_LONG
                    ).show()
                    return@rememberLauncherForActivityResult
                }

                // FINE уже выдано — проверяем/запрашиваем BACKGROUND, если нужно
                ensureBackgroundLocation(
                    context = context,
                    onBackgroundGranted = {
                        if (hasIgnoreBatteryOptimizations()) {
                            enabled = true
                            saveEnabled(true)
                            Toast.makeText(
                                context,
                                context.getString(R.string.gps_unrestricted),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            enabled = false
                            saveEnabled(false)
                            Toast.makeText(
                                context,
                                context.getString(R.string.battery_optimization_required),
                                Toast.LENGTH_LONG
                            ).show()
                            requestIgnoreBatteryOptimizations()
                        }
                    },
                    onBackgroundNeedRequest = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            backgroundLocationPermissionLauncher.launch(
                                Manifest.permission.ACCESS_BACKGROUND_LOCATION
                            )
                        } else {
                            // до Android 10 BACKGROUND нет — но батарею всё равно проверяем
                            if (hasIgnoreBatteryOptimizations()) {
                                enabled = true
                                saveEnabled(true)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.gps_unrestricted),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                enabled = false
                                saveEnabled(false)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.battery_optimization_warning),
                                    Toast.LENGTH_LONG
                                ).show()
                                requestIgnoreBatteryOptimizations()
                            }
                        }
                    }
                )
            }

        fun hasFineLocation(): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        fun hasBackgroundLocation(): Boolean =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) true
            else ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        fun hasNotificationPermission(): Boolean =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                true
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            }


        fun startLocationPermissionFlow() {
            // 1. Сначала FINE
            if (!hasFineLocation()) {
                enabled = false
                saveEnabled(false)
                fineLocationPermissionLauncher.launch(
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
                return
            }

            // 2. FINE уже есть — проверяем BACKGROUND
            if (hasBackgroundLocation()) {

                if (hasIgnoreBatteryOptimizations()) {
                    enabled = true
                    saveEnabled(true)
                    Toast.makeText(
                        context,
                        context.getString(R.string.gps_unrestricted),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    enabled = false
                    saveEnabled(false)

                    Toast.makeText(
                        context,
                        context.getString(R.string.battery_optimization_required),
                        Toast.LENGTH_LONG
                    ).show()

                    requestIgnoreBatteryOptimizations()
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    enabled = false
                    saveEnabled(false)
                    backgroundLocationPermissionLauncher.launch(
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    )
                } else {
                    enabled = true
                    saveEnabled(true)
                }
            }
        }


        // POST_NOTIFICATIONS (Android 13+)
        val notificationsPermissionLauncher =
            rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (!granted) {
                    // Пользователь отказал в уведомлениях — плагин считаем не включённым
                    pendingLocationPermissionFlow = false
                    enabled = false
                    saveEnabled(false)

                    Toast.makeText(
                        context,
                        context.getString(R.string.notification_permission_required),
                        Toast.LENGTH_LONG
                    ).show()
                    return@rememberLauncherForActivityResult
                }

                // Разрешение на уведомления выдали — продолжаем цепочку с гео-разрешениями,
                // если включение плагина было инициировано пользователем.
                if (pendingLocationPermissionFlow) {
                    pendingLocationPermissionFlow = false
                    startLocationPermissionFlow()
                }
            }


        fun requestEnableWithPermissions() {
            // Сначала — уведомления (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasNotificationPermission()
            ) {
                pendingLocationPermissionFlow = true
                notificationsPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
                return
            }

            // Уведомления уже есть или API < 33 — сразу идём в гео
            startLocationPermissionFlow()
        }


        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.enabled), modifier = Modifier.weight(1f))
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        if (!checked) {
                            enabled = false
                            saveEnabled(false)
                            notifyEnabledChanged(false)
                        } else {
                            requestEnableWithPermissions()
                            notifyEnabledChanged(true)
                        }
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
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline
                ),
                colors = CardDefaults.cardColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            protocol = GpsProtocolType.WIALON
                            saveProtocol(protocol)
                        },
                        border = if (protocol == GpsProtocolType.WIALON)
                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        else null
                    ) {
                        Text("Wialon IPS")
                    }

                    OutlinedButton(
                        onClick = {
                            protocol = GpsProtocolType.OSMAND
                            saveProtocol(protocol)
                        },
                        border = if (protocol == GpsProtocolType.OSMAND)
                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        else null
                    ) {
                        Text("OsmAnd")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
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
                value = portText,
                onValueChange = { new ->
                    val filtered = new.filter { it.isDigit() }
                    portText = filtered

                    val p = filtered.toIntOrNull()
                    if (p != null && p in 1..65535) {
                        savePort(p)
                    } else if (filtered.isEmpty()) {
                        // если очистили поле — удаляем настройку порта
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
                value = imeiInput,
                onValueChange = { new ->
                    imeiTouched = true
                    imeiInput = filterDigits(new)

                    // сохраняем только валидное
                    if (isImeiOk(imeiInput)) {
                        saveImei(imeiInput)
                    }
                },
                label = { Text(stringResource(R.string.device_imei)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { f ->
                        if (!f.isFocused) {
                            // если ушли с поля и оно невалидно - откатываем к последнему сохранённому
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
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = if (showImeiError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                )
            )

            //Spacer(modifier = Modifier.height(8.dp))

            if (protocol == GpsProtocolType.WIALON) {

                //Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
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

            //Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.gps_filter_settings),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = intervalSecText,
                onValueChange = { new ->
                    val filtered = new.filter { it.isDigit() }
                    intervalSecText = filtered
                    val v = filtered.toIntOrNull()
                    if (v != null && v > 0) {
                        saveIntervalSec(v)
                    }
                },
                label = { Text(stringResource(R.string.interval_seconds)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = minDistanceText,
                onValueChange = { new ->
                    val filtered = new.filter { it.isDigit() || it == '.' }
                    minDistanceText = filtered
                    val v = filtered.toFloatOrNull()
                    if (v != null && v >= 0f) {
                        saveMinDistance(v)
                    }
                },
                label = { Text(stringResource(R.string.min_distance_meters)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = minAngleText,
                onValueChange = { new ->
                    val filtered = new.filter { it.isDigit() || it == '.' }
                    minAngleText = filtered
                    val v = filtered.toFloatOrNull()
                    if (v != null && v >= 0f) {
                        saveMinAngle(v)
                    }
                },
                label = { Text(stringResource(R.string.min_course_change)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal
                )
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
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline
                ),
                colors = CardDefaults.cardColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp) // ОТСТУП ВНУТРИ
                )
                {
                    if (motionDebug == null) {
                        Text(stringResource(R.string.no_data), style = MaterialTheme.typography.bodySmall)
                    } else {
                        val d = motionDebug!!
                        val confPct = d.confidence * 100f

                        val stateText = when {
                            d.confidence >= 0.7f -> stringResource(R.string.recent_motion)
                            d.confidence >= 0.2f -> stringResource(R.string.fading)
                            else -> stringResource(R.string.inactive)
                        }

                        val confText = "%.0f".format(confPct)

                        val secondsText = d.secondsSinceMotion
                            ?.let { "%.1f".format(it) }
                            ?: "—"

                        Text(
                            text = stringResource(
                                R.string.motion_info,
                                confText,
                                secondsText,
                                stateText
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )

                        val rmsEmaText = String.format(Locale.US, "%.4f", d.rmsEma)
                        Text(
                            text = "RMS_EMA: $rmsEmaText",
                            style = MaterialTheme.typography.bodySmall
                        )

                        /*
                        Text(
                            text =
                            "Уверенность: ${"%.0f".format(confPct)}%\n" +
                                    "Секунд с события: ${
                                        d.secondsSinceMotion?.let {
                                            "%.1f".format(
                                                it
                                            )
                                        } ?: "—"
                                    }\n" +
                                    "Интерпретация: $stateText",
                            style = MaterialTheme.typography.bodySmall
                        )
                        */
                    }

                    val motionThresholdText = "%.2f".format(motionThreshold)
                    Text(
                        text = stringResource(
                            R.string.motion_sensitivity,
                            motionThresholdText
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )

                    /*
                    Text(
                        text = "Чувствительность (motionThreshold): ${"%.2f".format(motionThreshold)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    */

                    val minT = 0.20f
                    val maxT = 10.00f
                    val step = 0.10f
                    val steps = ((maxT - minT) / step).toInt() - 1

                    Slider(
                        value = motionThreshold.coerceIn(minT, maxT),
                        onValueChange = { raw ->
                            val snapped = (kotlin.math.round((raw - minT) / step) * step + minT)
                                .coerceIn(minT, maxT)
                            motionThreshold = snapped
                            saveMotionThreshold(snapped)
                        },
                        valueRange = minT..maxT,
                        steps = steps,
                        enabled = cardUnlocked
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    //Text("Порог прохождения Уверенности: ${"%.0f".format(confPct)}%")

                    val confPctText = "%.0f".format(confPct)
                    Text(
                        text = stringResource(
                            R.string.confidence_threshold,
                            confPctText
                        )
                    )

                    Slider(
                        value = confPct,
                        onValueChange = { v ->
                            confPct = v
                            saveConfPct(v)
                        },
                        valueRange = 1f..100f,
                        steps = 99,
                        enabled = cardUnlocked
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { resetToDefaults() },
                            modifier = Modifier.weight(1f),
                            enabled = cardUnlocked
                        ) {
                            Text(stringResource(R.string.basic), maxLines = 2)
                        }

                        OutlinedButton(
                            onClick = { cardUnlocked = !cardUnlocked }
                        ) {
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
                    text = stringResource(
                        R.string.nmea_error_with_details,
                        nmeaErr.toString()
                    ),
                    style = MaterialTheme.typography.bodySmall
                )

                /*
                Text(
                    text = "Ошибка: $rawErr",
                    style = MaterialTheme.typography.bodySmall
                )
                */
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
                    /*
                    Text(
                        text =
                        "lat: ${"%.6f".format(Locale.US, p.latitude)}\n" +
                                "lon: ${"%.6f".format(Locale.US, p.longitude)}\n" +
                                "Спутники: ${p.sats}\n" +
                                "Время: ${timeFmt.format(p.time)}\n" +
                                "Точность: ${"%.1f".format(Locale.US, p.accuracy)} m\n" +
                                "Скорость: ${"%.1f".format(Locale.US, p.speed)} km/h",
                        style = MaterialTheme.typography.bodySmall
                    )
                    */
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
                /*
                Text(
                    text = "Ошибка NMEA: $nmeaErr",
                    style = MaterialTheme.typography.bodySmall
                )
                */
                Text(
                    text = stringResource(
                        R.string.nmea_error_with_details,
                        nmeaErr.toString()
                    ),
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

                    val nmeaStatus = if (nmeaAlive)
                        stringResource(R.string.status_ok)
                    else
                        stringResource(R.string.status_no)

                    val fixStatus = if (fixAlive)
                        stringResource(R.string.status_yes)
                    else
                        stringResource(R.string.status_no)

                    val satsText = h.sats?.toString()
                        ?: stringResource(R.string.not_available)

                    val hdopText = h.hdop?.toString()
                        ?: stringResource(R.string.not_available)

                    val qualityText = h.fixQuality?.toString()
                        ?: stringResource(R.string.not_available)

                    val rmcValidText = h.rmcValid?.toString()
                        ?: stringResource(R.string.not_available)

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

                    /*
                    Text(
                        text =
                        "NMEA поток: ${if (nmeaAlive) "OK" else "нет"}\n" +
                                "FIX: ${if (fixAlive) "есть" else "нет"}\n" +
                                "Спутники(GGA): ${h.sats ?: "NA"}\n" +
                                "HDOP: ${h.hdop ?: "NA"}\n" +
                                "Качество: ${h.fixQuality ?: "NA"}\n" +
                                "RMC valid: ${h.rmcValid ?: "NA"}\n",
                        style = MaterialTheme.typography.bodySmall
                    )*/

                    if (!nmeaSentence.isNullOrBlank()) {
                        val lastSentence = nmeaSentence
                            ?: stringResource(R.string.not_available)

                        Text(
                            text = stringResource(
                                R.string.last_nmea_sentence,
                                lastSentence
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 40.dp)
                        )

                        /*
                        Text(
                            text = "Последнее: $nmeaSentence",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,                       // сколько строк ты готов показывать
                            overflow = TextOverflow.Ellipsis,   // обрезка
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 40.dp)          // зарезервировать место (2 строки ~ 32-40dp)
                        )
                        */
                        Spacer(Modifier.height(6.dp))
                    } else {
                        // чтобы высота была такой же даже когда пусто
                        Spacer(Modifier.height(40.dp))
                        Spacer(Modifier.height(6.dp))
                    }


                }
            }

        }
    }

    /**
     * Помощник: после выдачи FINE решаем, что делать с BACKGROUND.
     */
    private fun ensureBackgroundLocation(
        context: Context,
        onBackgroundGranted: () -> Unit,
        onBackgroundNeedRequest: () -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // до Android 10 фонового разрешения нет — считаем, что всё ок
            onBackgroundGranted()
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            onBackgroundGranted()
        } else {
            onBackgroundNeedRequest()
        }
    }
}