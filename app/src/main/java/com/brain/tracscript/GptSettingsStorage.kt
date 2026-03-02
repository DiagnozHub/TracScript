package com.brain.tracscript

import android.content.Context
import android.content.SharedPreferences

object GptSettingsStorage {

    private const val PREF = "gpt_settings"

    const val KEY_ENABLED = "enabled"
    const val KEY_API_URL = "api_url"
    const val KEY_API_TOKEN = "api_token"
    const val KEY_MODEL = "model"

    const val KEY_PROXY_ENABLED = "proxy_enabled"
    const val KEY_PROXY_HOST = "proxy_host"
    const val KEY_PROXY_PORT = "proxy_port"
    const val KEY_PROXY_USER = "proxy_user"
    const val KEY_PROXY_PASS = "proxy_pass"
    const val KEY_PROXY_TYPE = "proxy_type" // "HTTP" or "SOCKS"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_ENABLED, v).apply()

    fun apiUrl(ctx: Context) = prefs(ctx).getString(KEY_API_URL, "")!!.trim()
    fun setApiUrl(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_API_URL, v.trim()).apply()

    fun apiToken(ctx: Context) = prefs(ctx).getString(KEY_API_TOKEN, "")!!.trim()
    fun setApiToken(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_API_TOKEN, v.trim()).apply()

    fun model(ctx: Context) = prefs(ctx).getString(KEY_MODEL, "gpt-4o-mini")!!.trim()
    fun setModel(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_MODEL, v.trim()).apply()

    fun proxyEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_PROXY_ENABLED, false)
    fun setProxyEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_PROXY_ENABLED, v).apply()

    fun proxyHost(ctx: Context) = prefs(ctx).getString(KEY_PROXY_HOST, "")!!.trim()
    fun setProxyHost(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_PROXY_HOST, v.trim()).apply()

    fun proxyPort(ctx: Context) = prefs(ctx).getInt(KEY_PROXY_PORT, 0)
    fun setProxyPort(ctx: Context, v: Int) = prefs(ctx).edit().putInt(KEY_PROXY_PORT, v).apply()

    fun proxyUser(ctx: Context) = prefs(ctx).getString(KEY_PROXY_USER, "")!!.trim()
    fun setProxyUser(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_PROXY_USER, v.trim()).apply()

    fun proxyPass(ctx: Context) = prefs(ctx).getString(KEY_PROXY_PASS, "")!!.trim()
    fun setProxyPass(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_PROXY_PASS, v).apply()

    fun proxyType(ctx: Context) = prefs(ctx).getString(KEY_PROXY_TYPE, "HTTP")!!.trim()
    fun setProxyType(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_PROXY_TYPE, v.trim()).apply()

    fun isConfigured(ctx: Context): Boolean =
        isEnabled(ctx) && apiUrl(ctx).isNotBlank() && apiToken(ctx).isNotBlank()
}
