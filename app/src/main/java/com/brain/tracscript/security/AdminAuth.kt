package com.brain.tracscript.security

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Защита настроек паролем администратора.
 *
 * Хранение: PBKDF2-HMAC-SHA1, salt 16 байт, hash 32 байта, 100k итераций.
 * Если пароля не задано — приложение работает как раньше, без всяких диалогов.
 *
 * Сессия разблокировки живёт в памяти процесса — после kill / cold start
 * приходится вводить пароль заново.
 */
object AdminAuth {

    private const val PREFS = "admin_auth"
    private const val KEY_SALT = "salt_b64"
    private const val KEY_HASH = "hash_b64"
    private const val KEY_ITERS = "iters"

    private const val ITERATIONS_DEFAULT = 100_000
    private const val SALT_BYTES = 16
    private const val HASH_BITS = 256

    private val _sessionUnlocked = MutableStateFlow(false)
    val sessionUnlocked: StateFlow<Boolean> = _sessionUnlocked

    fun isPasswordSet(ctx: Context): Boolean {
        val p = prefs(ctx)
        return p.contains(KEY_SALT) && p.contains(KEY_HASH)
    }

    /**
     * Установить или снять пароль.
     *  - blank/empty → защита снимается, sessionUnlocked сбрасывается.
     *  - непустая строка → пароль сохраняется, сессия становится разблокированной
     *    (пользователь только что доказал владение).
     */
    fun setPassword(ctx: Context, plain: String) {
        val p = prefs(ctx)
        if (plain.isBlank()) {
            p.edit()
                .remove(KEY_SALT)
                .remove(KEY_HASH)
                .remove(KEY_ITERS)
                .apply()
            _sessionUnlocked.value = false
            return
        }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(plain, salt, ITERATIONS_DEFAULT)
        p.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putInt(KEY_ITERS, ITERATIONS_DEFAULT)
            .apply()
        _sessionUnlocked.value = true
    }

    /**
     * Проверить пароль. Не меняет sessionUnlocked — для этого вызывай markUnlocked().
     *
     * ВАЖНО: пустая строка считается заведомо неверной. PBEKeySpec на Android
     * с пустым char[] бросает IllegalArgumentException("empty password not allowed"),
     * поэтому до pbkdf2 такой ввод не доводим.
     */
    fun verifyPassword(ctx: Context, plain: String): Boolean {
        if (!isPasswordSet(ctx)) return true
        if (plain.isEmpty()) return false
        val p = prefs(ctx)
        val salt = Base64.decode(p.getString(KEY_SALT, null) ?: return false, Base64.NO_WRAP)
        val expected = Base64.decode(p.getString(KEY_HASH, null) ?: return false, Base64.NO_WRAP)
        val iters = p.getInt(KEY_ITERS, ITERATIONS_DEFAULT)
        val actual = try {
            pbkdf2(plain, salt, iters)
        } catch (_: Exception) {
            return false
        }
        return constantTimeEquals(expected, actual)
    }

    fun markUnlocked() {
        _sessionUnlocked.value = true
    }

    fun lock() {
        _sessionUnlocked.value = false
    }

    /**
     * Можно ли сейчас выполнять защищённые действия без диалога:
     *   - либо пароль не задан,
     *   - либо текущая сессия уже разблокирована.
     */
    fun isOpenAccess(ctx: Context): Boolean {
        return !isPasswordSet(ctx) || _sessionUnlocked.value
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pbkdf2(password: String, salt: ByteArray, iters: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iters, HASH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        return factory.generateSecret(spec).encoded
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }
}
