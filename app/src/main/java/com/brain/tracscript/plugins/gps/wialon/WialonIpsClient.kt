package com.brain.tracscript.plugins.gps.wialon

import com.brain.tracscript.plugins.gps.GpsSendBlockedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
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
    val lat1: String,   // "5544.6025"
    val lat2: String,   // "N" / "S"
    val lon1: String,   // "03739.6834"
    val lon2: String,   // "E" / "W"
    val speed: String,  // "0"
    val course: String, // "0"
    val alt: String,    // "0"
    val sats: String,   // "5"
    //val date: String? = null, // "ddMMyy" или null → текущее UTC
    val timeMillis: Long
    //val time: String? = null  // "HHmmss" или null → текущее UTC
)

/**
 * Доп. данные для D-пакета.
 */
data class DExtras(
    val hdop: String,    // "1.0"
    val inputs: String,  // "0"
    val outputs: String, // "0"
    val adc: String,     // "0,0,0,0" или ""
    val ibutton: String  // "NA"
)

/**
 * Один параметр для секции Params в D-пакете.
 * name:type:value
 *
 * type:
 *  1 – целое, 2 – вещественное, 3 – строка, 4 – bool, 5 – hex и т.д.
 */
data class IpsParam(
    val name: String,
    val type: Int = 3,
    val value: String
)

class WialonIpsClient(
    private val host: String,
    private val port: Int,
    private val protocolVersion: String = "2.0",
    private val socketTimeoutMs: Int = 5000
) {
    /**
     * Всё в ASCII.
     * Любые не-ASCII символы (русский, emoji) превратятся в '?'.
     * CRC считаем по тем же байтам, что реально уходят в сокет.
     */
    private val WIALON_CHARSET: Charset = Charsets.US_ASCII

    // ==================== ПУБЛИЧНЫЕ МЕТОДЫ ====================

    /**
     * Отправить один текст как параметр с именем "msg", типом 3 (строка).
     */
    /*
    suspend fun sendText(
        imei: String,
        password: String,
        message: String,
        nav: NavData,
        extras: DExtras
    ): String? = sendParams(
        imei = imei,
        password = password,
        params = listOf(IpsParam(name = "msg", type = 3, value = message)),
        nav = nav,
        extras = extras
    )
    */

    /**
     * Логинимся и отправляем один D-пакет с ЛЮБЫМ количеством параметров.
     * Каждый параметр: name:type:value
     */
    suspend fun sendParams(
        imei: String,
        password: String,
        params: List<IpsParam>,
        nav: NavData,
        extras: DExtras
    ): String? = withContext(Dispatchers.IO) {

        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), socketTimeoutMs)
            socket.soTimeout = socketTimeoutMs   // таймаут на readLine

            val writer = BufferedWriter(
                OutputStreamWriter(socket.getOutputStream(), WIALON_CHARSET)
            )
            val reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), WIALON_CHARSET)
            )

            // 1) Login
            val loginPacket = buildLoginPacket(imei, password)
            writer.write(loginPacket)
            writer.flush()

            val loginResp = reader.readLine()
            val loginOk = loginResp?.trim() == "#AL#1"

            if (!loginOk) {
                // Критично: логин не прошёл -> НЕ шлём D-пакет
                // и даём наружу явную ошибку, чтобы пакет не удалили из БД
                throw GpsSendBlockedException("Wialon login failed: resp=$loginResp")
            }

            // 2) D-пакет с параметрами
            val dPacket = buildDPacketWithParams(params, nav, extras)
            writer.write(dPacket)
            writer.flush()

            val dResp = reader.readLine()
            dResp
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }


    /**
     * Построение эталонного тестового D-пакета.
     * Используется для диагностики CRC / структуры пакета.
     *
     * Примерный состав:
     *  - дата/время текущие UTC
     *  - фиктивные координаты
     *  - 3 коротких параметра test_0, test_1, test_2
     *
     * Возвращает строку полного пакета #D#...CRC\r\n
     */
    /*
    fun buildTestPacket(): String {
        // Навигация — статические тестовые значения
        val dateTime = getUtcDateTime()
        val dateStr = dateTime.first
        val timeStr = dateTime.second

        val lat1 = "5544.0000"
        val lat2 = "N"
        val lon1 = "03739.0000"
        val lon2 = "E"
        val speed = "0"
        val course = "0"
        val alt = "0"
        val sats = "5"

        // Простые extras
        val hdop = "1.0"
        val inputs = "0"
        val outputs = "0"
        val adc = ""
        val ibutton = "NA"

        // Короткие эталонные текстовые параметры
        val paramsStr = listOf(
            "test_0:3:hello",
            "test_1:3:world",
            "test_2:3:3:Retarder, Exhaust, Engine #1 | Normalno"
        ).joinToString(",")

        // Формирование тела пакета (как в IPS 2.0)
        val body = buildString {
            append(dateStr).append(';')
            append(timeStr).append(';')
            append(lat1).append(';')
            append(lat2).append(';')
            append(lon1).append(';')
            append(lon2).append(';')
            append(speed).append(';')
            append(course).append(';')
            append(alt).append(';')
            append(sats).append(';')
            append(hdop).append(';')
            append(inputs).append(';')
            append(outputs).append(';')
            append(adc).append(';')
            append(ibutton).append(';')
            append(paramsStr).append(';')
        }

        // CRC по ASCII-байтам body
        val crc = crc16String(body)
        val crcHex = String.format(Locale.US, "%04X", crc)

        return "#D#$body$crcHex\r\n"
    }
    */

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
     * Запрещённые символы Wialon IPS:
     * - '#' — ломает структуру пакета, сервер считает это началом нового пакета.
     *
     * Заменяем '#' на '№'.
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

            // убираем запрещённый символ
            val cleanedValue = sanitizeValueForWialon(p.value)

            // затем в ASCII
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

    /**
     * Обрезаем строку до печатного ASCII.
     * Всё, что вне диапазона 0x20..0x7E, заменяем на '?'.
     */
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

        val day   = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val year  = cal.get(java.util.Calendar.YEAR) % 100

        val hour  = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val min   = cal.get(java.util.Calendar.MINUTE)
        val sec   = cal.get(java.util.Calendar.SECOND)

        val dateStr = String.format(Locale.US, "%02d%02d%02d", day, month, year)
        val timeStr = String.format(Locale.US, "%02d%02d%02d", hour, min, sec)

        return dateStr to timeStr
    //val d = nav.date
        //val t = nav.time
        //return if (d != null && t != null) d to t else getUtcDateTime()
    }

    private fun getUtcDateTime(): Pair<String, String> {
        val nowUtc = Date()
        val dateFmt = SimpleDateFormat("ddMMyy", Locale.US)
        val timeFmt = SimpleDateFormat("HHmmss", Locale.US)
        val tz = TimeZone.getTimeZone("UTC")
        dateFmt.timeZone = tz
        timeFmt.timeZone = tz
        return dateFmt.format(nowUtc) to timeFmt.format(nowUtc)
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