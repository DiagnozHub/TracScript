package com.brain.tracscript

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class GptClient
    (
    private val appContext: android.content.Context,
    private val logger: ((level: String, tag: String, message: String, tr: Throwable?) -> Unit)? = null
) {

    @Volatile private var httpClient: OkHttpClient? = null
    private val httpClientLock = Any()

    private fun logI(msg: String) = logger?.invoke("I", "GPT", msg, null)
    private fun logW(msg: String) = logger?.invoke("W", "GPT", msg, null)
    private fun logE(msg: String, tr: Throwable? = null) = logger?.invoke("E", "GPT", msg, tr)

    private val cacheLock = Any()

    private val memCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 1000 // лимит
        }
    }

    private fun getHttpClient(): OkHttpClient {
        httpClient?.let { return it }
        synchronized(httpClientLock) {
            httpClient?.let { return it }
            val c = buildHttpClient()
            httpClient = c
            return c
        }
    }

    private fun cacheKey(err: String): String {
        val apiUrl = GptSettingsStorage.apiUrl(appContext)
        val model = GptSettingsStorage.model(appContext)
        val norm = err.trim().replace(Regex("\\s+"), " ").lowercase()
        return "$apiUrl|$model|$norm"
    }

    private fun cacheGet(key: String): String? = synchronized(cacheLock) { memCache[key] }
    private fun cachePut(key: String, value: String) = synchronized(cacheLock) { memCache[key] = value }


    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun isConfigured(): Boolean = GptSettingsStorage.isConfigured(appContext)

    private fun buildHttpClient(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(25, java.util.concurrent.TimeUnit.SECONDS)

        if (GptSettingsStorage.proxyEnabled(appContext)) {
            val host = GptSettingsStorage.proxyHost(appContext)
            val port = GptSettingsStorage.proxyPort(appContext)

            if (host.isNotBlank() && port > 0) {
                val type = GptSettingsStorage.proxyType(appContext).uppercase()
                val proxyType =
                    if (type == "SOCKS") java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP

                b.proxy(java.net.Proxy(proxyType, java.net.InetSocketAddress(host, port)))

                if (proxyType == java.net.Proxy.Type.SOCKS) {
                    // SOCKS5 auth через java.net.Authenticator
                    Socks5Auth.update(
                        GptSettingsStorage.proxyUser(appContext),
                        GptSettingsStorage.proxyPass(appContext)
                    )
                } else {
                    // HTTP proxy auth
                    val user = GptSettingsStorage.proxyUser(appContext)
                    val pass = GptSettingsStorage.proxyPass(appContext)
                    if (user.isNotBlank() || pass.isNotBlank()) {
                        b.proxyAuthenticator { _, response ->
                            response.request.newBuilder()
                                .header(
                                    "Proxy-Authorization",
                                    okhttp3.Credentials.basic(user, pass)
                                )
                                .build()
                        }
                    }
                }
            }
        } else {
            // прокси выключен — очищаем креды (чтобы не мешали)
            Socks5Auth.clear()
        }

        return b.build()
    }


    /**
     * Возвращает короткую расшифровку на русском или null (если GPT недоступен/ошибка/не настроен).
     *
     * ВАЖНО: apiUrl должен быть полноценным URL к endpoint'у.
     * Для OpenAI-compatible это обычно .../v1/chat/completions
     */
    fun explainErrRuShort(err: String): String? {
        if (!isConfigured()) return null

        val apiUrl = GptSettingsStorage.apiUrl(appContext)
        val token = GptSettingsStorage.apiToken(appContext)
        val model = GptSettingsStorage.model(appContext)

        val key = cacheKey(err)
        cacheGet(key)?.let {
            logI("cache hit")
            return it
        }

        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put(
                        "content",
                        "Ты авто-диагност. Ответь строго ОДНИМ коротким предложением по-русски (СТРОГО ≤78 символов): что значит ошибка и что вероятно неисправно. Делай сокращения слов (например, неисп., некор., и т.д.). Без воды, без списков, без двоеточий и переводов строк. Обязательно включи полный код ошибки как дано в начало текста ответа."
                    )
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", err)
                })
            })
        }

        val req = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        val client = getHttpClient()

        return try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string()

                if (!resp.isSuccessful) {
                    // ВАЖНО: покажем код и кусок ответа (часто там текст ошибки/подсказка)
                    logW("HTTP ${resp.code} ${resp.message}; url=$apiUrl; body=${raw?.take(800)}")
                    return null
                }

                if (raw.isNullOrBlank()) {
                    logW("response body is empty; url=$apiUrl")
                    return null
                }

                val root = JSONObject(raw)

                // Иногда API возвращает error вместо choices
                root.optJSONObject("error")?.let { errObj ->
                    logW("error object: ${errObj.toString().take(800)}")
                    return null
                }

                val choices = root.optJSONArray("choices")
                if (choices == null || choices.length() == 0) {
                    logW("no choices. raw=${raw.take(800)}")
                    return null
                }

                val msg = choices.optJSONObject(0)?.optJSONObject("message")
                val content = msg?.optString("content")?.trim()

                if (content.isNullOrBlank()) {
                    logW("empty content. raw=${raw.take(800)}")
                    return null
                }

                cachePut(key, content)
                return content
            }
        } catch (e: Exception) {
            logE("request failed; url=$apiUrl; err='${err.take(200)}'", e)
            null
        }
    }

}
