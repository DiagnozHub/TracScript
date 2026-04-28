package com.brain.tracscript.plugins.gps.wialon

import com.brain.tracscript.plugins.gps.GpsSendBlockedException
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Навигационные данные для D-пакета.
 */
data class NavData(
    val lat1: String,
    val lat2: String,
    val lon1: String,
    val lon2: String,
    val speed: String,
    val course: String,
    val alt: String,
    val sats: String,
    val timeMillis: Long
)

/**
 * Доп. данные для D-пакета.
 */
data class DExtras(
    val hdop: String,
    val inputs: String,
    val outputs: String,
    val adc: String,
    val ibutton: String
)

/**
 * Один параметр для секции Params в D-пакете.
 * name:type:value
 *
 * type:
 *  1 – целое, 2 – вещественное, 3 – строка
 */
data class IpsParam(
    val name: String,
    val type: Int = 3,
    val value: String
)

/**
 * Входящие события от сервера.
 */
sealed class WialonInbound {
    data class Ack(val type: String, val body: String) : WialonInbound()
    /** Произвольное M-сообщение от сервера. */
    data class Message(val text: String) : WialonInbound()
    data class Disconnected(val reason: String?) : WialonInbound()
    /** Отладочные сообщения (любая входящая строка, неизвестные типы и т.п.). */
    data class Trace(val message: String) : WialonInbound()
}

class WialonIpsClient(
    private val host: String,
    private val port: Int,
    private val protocolVersion: String = "2.0",
    private val connectTimeoutMs: Int = 5000,
    private val ackTimeoutMs: Long = 15_000L,
    // 25 секунд — безопасное значение для мобильных NAT (типичный таймаут 30–60с).
    // Если делать реже, при больших gps_interval_sec входящие команды от Wialon будут
    // задерживаться: NAT-сессия закрывается, и пакет от сервера теряется до момента,
    // когда устройство само пошлёт следующий D-пакет.
    private val pingIntervalMs: Long = 25_000L
) {

    private val WIALON_CHARSET: Charset = Charsets.US_ASCII

    @Volatile private var socket: Socket? = null
    @Volatile private var inputStream: BufferedInputStream? = null
    @Volatile private var outputStream: OutputStream? = null
    @Volatile private var clientScope: CoroutineScope? = null
    @Volatile private var readerJob: Job? = null
    @Volatile private var pingJob: Job? = null

    private val sendMutex = Mutex()
    private val ackChannel = Channel<Pair<String, String>>(capacity = 32)

    private val _events = MutableSharedFlow<WialonInbound>(
        replay = 0,
        extraBufferCapacity = 16
    )
    val events: SharedFlow<WialonInbound> = _events

    val isConnected: Boolean
        get() {
            val s = socket ?: return false
            return s.isConnected && !s.isClosed
        }

    // ==================== ПУБЛИЧНЫЕ МЕТОДЫ ====================

    /**
     * Установить TCP-соединение и пройти L-логин.
     * Бросает GpsSendBlockedException при отказе сервера.
     * При сетевой ошибке — IOException.
     */
    suspend fun connect(imei: String, password: String) = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext

        disconnectInternal("reconnect")

        val s = openSocketWithFallback()
        s.soTimeout = 0

        socket = s
        inputStream = BufferedInputStream(s.getInputStream())
        outputStream = s.getOutputStream()

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        clientScope = scope

        readerJob = scope.launch { runReader() }

        val loginPacket = buildLoginPacket(imei, password)
        try {
            sendMutex.withLock {
                writeRaw(loginPacket.toByteArray(WIALON_CHARSET))
            }
        } catch (e: Exception) {
            disconnectInternal("login_write_failed: ${e.message}")
            throw e
        }

        val ack = waitFor("AL")
            ?: run {
                disconnectInternal("login_timeout")
                throw GpsSendBlockedException("Wialon login timeout")
            }

        if (ack.trim() != "1") {
            disconnectInternal("login_rejected")
            throw GpsSendBlockedException("Wialon login failed: resp=#AL#$ack")
        }

        pingJob = scope.launch { runPing() }
    }

    /**
     * Закрыть соединение.
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        disconnectInternal("explicit")
    }

    /**
     * Отправить один D-пакет с параметрами и дождаться #AD#.
     * При отсутствии соединения — выполнит connect.
     * Возвращает строку ответа в формате "#AD#<code>" или null, если не дождались.
     */
    suspend fun sendParams(
        imei: String,
        password: String,
        params: List<IpsParam>,
        nav: NavData,
        extras: DExtras
    ): String? = withContext(Dispatchers.IO) {

        if (!isConnected) connect(imei, password)

        val dPacket = buildDPacketWithParams(params, nav, extras)

        try {
            sendMutex.withLock {
                writeRaw(dPacket.toByteArray(WIALON_CHARSET))
            }
        } catch (e: Exception) {
            disconnectInternal("d_write_failed: ${e.message}")
            throw e
        }

        val ack = waitFor("AD")
            ?: throw IOException("Wialon AD timeout")

        "#AD#$ack"
    }

    /**
     * Резолвит host в список адресов и пробует подключиться по очереди:
     * сначала все IPv4, затем все IPv6. Это спасает от ситуаций,
     * когда DNS отдаёт NAT64-адрес IPv6 (`64:ff9b::...`), а оператор его блэкхолит.
     */
    private fun openSocketWithFallback(): Socket {
        val addrs: Array<InetAddress> = try {
            InetAddress.getAllByName(host)
        } catch (e: Exception) {
            throw IOException("DNS resolve failed for '$host': ${e.message}", e)
        }
        if (addrs.isEmpty()) throw IOException("DNS returned no addresses for '$host'")

        val sorted = addrs.sortedBy { if (it is Inet4Address) 0 else 1 }
        val perAddrTimeout = if (sorted.size > 1) {
            (connectTimeoutMs / sorted.size).coerceAtLeast(2000)
        } else {
            connectTimeoutMs
        }

        var lastErr: Exception? = null
        for (addr in sorted) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(addr, port), perAddrTimeout)
                try { s.keepAlive = true } catch (_: Throwable) {}
                Log.i(TAG, "connected to ${addr.hostAddress}:$port")
                return s
            } catch (e: Exception) {
                Log.w(TAG, "connect failed to ${addr.hostAddress}:$port — ${e.javaClass.simpleName}: ${e.message}")
                try { s.close() } catch (_: Throwable) {}
                lastErr = e
            }
        }
        throw IOException(
            "All ${sorted.size} addresses failed for '$host:$port'",
            lastErr
        )
    }

    // ==================== ВНУТРЕННЕЕ ====================

    private suspend fun waitFor(expectedType: String): String? {
        return withTimeoutOrNull(ackTimeoutMs) {
            var found: String? = null
            while (found == null) {
                val (type, body) = ackChannel.receive()
                if (type == expectedType) {
                    found = body
                }
            }
            found
        }
    }

    private suspend fun runReader() {
        val ins = inputStream ?: return
        try {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val line = readAsciiLine(ins) ?: break
                if (line.isEmpty()) continue

                _events.tryEmit(WialonInbound.Trace("rx: $line"))

                if (!line.startsWith("#")) {
                    // Wialon-овская «Произвольная команда»: сырой текст без #M#...#
                    // По спеке IPS 2.0 (раздел «Команда "Отправить произвольное сообщение"»)
                    // формат — просто Msg без обёртки. Ответ серверу не требуется.
                    _events.tryEmit(WialonInbound.Message(line))
                    continue
                }

                // #PT#body
                val rest = line.substring(1)
                val sep = rest.indexOf('#')
                if (sep <= 0) {
                    _events.tryEmit(WialonInbound.Trace("rx: no second '#', dropped"))
                    continue
                }

                val type = rest.substring(0, sep)
                val body = rest.substring(sep + 1)

                when (type) {
                    "UC" -> handleUcPacket(ins, body)
                    "M" -> handleMPacket(body)
                    "AL", "AD", "AP", "AM" -> {
                        ackChannel.trySend(type to body)
                    }
                    else -> {
                        _events.tryEmit(WialonInbound.Trace("rx: unknown type='$type' body='$body'"))
                        ackChannel.trySend(type to body)
                    }
                }
            }
        } catch (t: Throwable) {
            _events.tryEmit(WialonInbound.Trace("reader exception: ${t.javaClass.simpleName}: ${t.message}"))
        } finally {
            _events.tryEmit(WialonInbound.Disconnected("reader_stopped"))
        }
    }

    private suspend fun runPing() {
        try {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                kotlinx.coroutines.delay(pingIntervalMs)
                if (!isConnected) break
                try {
                    sendMutex.withLock {
                        writeRaw("#P#\r\n".toByteArray(WIALON_CHARSET))
                    }
                } catch (e: Exception) {
                    disconnectInternal("ping_failed: ${e.message}")
                    break
                }
            }
        } catch (_: Throwable) {
            // cancelled
        }
    }

    /**
     * UC-пакет (загрузка файла конфигурации) больше не используется приложением.
     * Если оператор случайно нажмёт «Загрузить конфигурацию» в Wialon CMS —
     * сервер пришлёт `#UC#Sz;CRC\r\n` + Sz бинарных байт. Без этого drain-а
     * Sz байт остались бы в потоке и reader попытался бы интерпретировать их
     * как ASCII-строки, что засорило бы лог и в худшем случае случайно
     * выполнило псевдо-M-команду, склеенную из мусора.
     *
     * Поэтому: парсим Sz, дочитываем ровно Sz байт и выкидываем. Без CRC,
     * без публикации, без размерного лимита.
     */
    private fun handleUcPacket(ins: BufferedInputStream, header: String) {
        val sz = header.split(';').firstOrNull()?.trim()?.toIntOrNull()
        if (sz == null || sz < 0) {
            Log.w(TAG, "UC drain: bad header '$header' — stream may desync")
            return
        }
        Log.i(TAG, "UC drain: discarding $sz bytes (config upload disabled)")
        var left = sz
        val buf = ByteArray(512)
        while (left > 0) {
            val r = ins.read(buf, 0, minOf(buf.size, left))
            if (r <= 0) return
            left -= r
        }
    }

    /**
     * Обработка M-пакета (произвольное сообщение от сервера).
     * Формат body: "Msg;CRC16" — Msg извлекаем как всё до последнего ';'.
     * По спеке надо ответить #AM#1 — иначе Wialon будет ретраить.
     */
    private suspend fun handleMPacket(body: String) {
        val lastSep = body.lastIndexOf(';')
        val msg = if (lastSep > 0) body.substring(0, lastSep) else body

        Log.i(TAG, "M-packet received: '$msg'")

        try {
            sendMutex.withLock {
                writeRaw("#AM#1\r\n".toByteArray(WIALON_CHARSET))
            }
        } catch (e: Exception) {
            Log.w(TAG, "M-ack write failed: ${e.message}")
        }

        _events.tryEmit(WialonInbound.Message(msg))
    }

    companion object {
        private const val TAG = "WialonIpsClient"
    }

    private fun readAsciiLine(ins: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream(64)
        while (true) {
            val b = ins.read()
            if (b == -1) {
                return if (buf.size() == 0) null else buf.toString(Charsets.US_ASCII.name())
            }
            if (b == 0x0D) {
                val nxt = ins.read()
                if (nxt == 0x0A || nxt == -1) {
                    return buf.toString(Charsets.US_ASCII.name())
                }
                buf.write(nxt)
            } else if (b == 0x0A) {
                return buf.toString(Charsets.US_ASCII.name())
            } else {
                buf.write(b)
            }
        }
    }

    private fun writeRaw(bytes: ByteArray) {
        val os = outputStream ?: throw IOException("not connected")
        os.write(bytes)
        os.flush()
    }

    private fun disconnectInternal(reason: String?) {
        try { pingJob?.cancel() } catch (_: Throwable) {}
        pingJob = null
        try { readerJob?.cancel() } catch (_: Throwable) {}
        readerJob = null
        try { clientScope?.cancel() } catch (_: Throwable) {}
        clientScope = null
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        inputStream = null
        outputStream = null
        while (ackChannel.tryReceive().isSuccess) { /* drain */ }
        if (reason != null) {
            _events.tryEmit(WialonInbound.Disconnected(reason))
        }
    }

    // ==================== СБОРКА ПАКЕТОВ ====================

    /**
     * #L#Protocol_version;IMEI;Password;CRC16\r\n
     */
    fun buildLoginPacket(imei: String, password: String): String {
        val body = "$protocolVersion;$imei;$password;"
        val crc = crc16String(body)
        val crcHex = String.format(Locale.US, "%04X", crc)
        return "#L#$body$crcHex\r\n"
    }

    /**
     * Запрещённые символы Wialon IPS.
     */
    private fun sanitizeValueForWialon(value: String): String {
        return value.replace("#", "N. ").replace(",", ".")
    }

    /**
     * D-пакет:
     *
     * #D#Date;Time;Lat1;Lat2;Lon1;Lon2;Speed;Course;Alt;Sats;
     *    HDOP;Inputs;Outputs;ADC;Ibutton;
     *    name1:type1:value1,name2:type2:value2,...;CRC16\r\n
     */
    fun buildDPacketWithParams(
        params: List<IpsParam>,
        nav: NavData,
        extras: DExtras
    ): String {
        val (dateStr, timeStr) = resolveDateTime(nav)

        val paramsStr = params.joinToString(",") { p ->
            val safeName = toAsciiSafe(p.name)
            val cleanedValue = sanitizeValueForWialon(p.value)
            val safeValue = toAsciiSafe(cleanedValue)
            "$safeName:${p.type}:$safeValue"
        }

        val body = buildString {
            append(dateStr).append(';')
            append(timeStr).append(';')
            append(nav.lat1).append(';')
            append(nav.lat2).append(';')
            append(nav.lon1).append(';')
            append(nav.lon2).append(';')
            append(nav.speed).append(';')
            append(nav.course).append(';')
            append(nav.alt).append(';')
            append(nav.sats).append(';')
            append(extras.hdop).append(';')
            append(extras.inputs).append(';')
            append(extras.outputs).append(';')
            append(extras.adc).append(';')
            append(extras.ibutton).append(';')
            append(paramsStr).append(';')
        }

        val crc = crc16String(body)
        val crcHex = String.format(Locale.US, "%04X", crc)

        return "#D#$body$crcHex\r\n"
    }

    // ==================== УТИЛИТЫ ====================

    private fun toAsciiSafe(input: String): String =
        buildString {
            for (ch in input) {
                val code = ch.code
                if (code in 0x20..0x7E) {
                    append(ch)
                } else {
                    append('?')
                }
            }
        }

    private fun resolveDateTime(nav: NavData): Pair<String, String> {
        val ts = nav.timeMillis
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ts

        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val year = cal.get(java.util.Calendar.YEAR) % 100

        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val min = cal.get(java.util.Calendar.MINUTE)
        val sec = cal.get(java.util.Calendar.SECOND)

        val dateStr = String.format(Locale.US, "%02d%02d%02d", day, month, year)
        val timeStr = String.format(Locale.US, "%02d%02d%02d", hour, min, sec)

        return dateStr to timeStr
    }

    // ==================== CRC16 (0xA001, init 0x0000) ====================

    private fun crc16(bytes: ByteArray): Int {
        var crc = 0x0000
        for (b in bytes) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x0001) != 0) {
                    (crc ushr 1) xor 0xA001
                } else {
                    crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    private fun crc16String(data: String): Int {
        val bytes = data.toByteArray(WIALON_CHARSET)
        return crc16(bytes)
    }
}
