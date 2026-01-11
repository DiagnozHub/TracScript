package com.brain.tracscript.plugins.scenario

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.brain.tracscript.R

/**
 * Отвечает за:
 *  - убийство целевого приложения через экран Recents
 *  - запуск целевого приложения
 *  - ожидание, пока активным станет нужный пакет
 *  - вызов переданного колбэка, когда app готов
 */
class TargetAppController(
    private val service: AccessibilityService,
    initialTargetPackage: String,
    private val debug: (String) -> Unit,
    private val findNodeByTextExact: (AccessibilityNodeInfo?, String) -> AccessibilityNodeInfo?
) {

    companion object {
        private const val TAG = "TracScript.TargetCtl"

        private const val WAIT_PKG_TIMEOUT_MS = 20_000L
        private const val WAIT_PKG_INTERVAL_MS = 200L

        private const val RECENTS_FALLBACK_TIMEOUT_MS = 3_000L
        private const val AFTER_KILL_HOME_DELAY_MS = 1_200L
        private const val AFTER_HOME_OPEN_DELAY_MS = 400L

        private const val RECENTS_SWIPE_DISTANCE_PX = 800
    }

    private val handler = Handler(Looper.getMainLooper())

    /** текущий целевой пакет */
    private var targetPackage: String = initialTargetPackage

    /** колбэк, который надо вызвать, когда целевое приложение стало активным */
    private var pendingOnReady: (() -> Unit)? = null

    /** флаг: ждём ли сейчас работу с Recents для убийства приложения */
    private var pendingKillFromRecents = false

    /** флаг: идёт ли сейчас процесс перезапуска целевого приложения */
    private var isRestartingTarget = false

    /** колбэк, когда приложение закрыто (для CLOSE_APP) */
    private var pendingOnClosed: (() -> Unit)? = null

    /** флаг: идёт ли сейчас процесс "только закрыть" через Recents */
    private var isClosingOnly = false

    /** label целевого приложения по текущему пакету */
    private val targetAppLabel: String
        get() = try {
            val ai = service.packageManager.getApplicationInfo(targetPackage, 0)
            service.packageManager.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось получить label для $targetPackage", e)
            // запасной вариант
            targetPackage
        }

    /** Можно менять целевой пакет на лету (для разных шагов сценария) */
    fun setTargetPackage(pkg: String) {
        Log.d(TAG, "setTargetPackage: $pkg")
        targetPackage = pkg
    }

    // ================== ПУБЛИЧНЫЙ API ==================

    /**
     * Внешний вход: "перезапусти текущее targetPackage и потом вызови onTargetReady".
     */
    fun requestRestartAndRunScenario(onTargetReady: () -> Unit) {
        if (isRestartingTarget || isClosingOnly || pendingKillFromRecents) {
            Log.d(TAG, "requestRestartAndRunScenario: уже выполняется другая операция, игнорирую")
            return
        }

        isRestartingTarget = true
        isClosingOnly = false
        pendingOnClosed = null
        pendingKillFromRecents = true
        pendingOnReady = onTargetReady

        debug("Перезапускаю $targetAppLabel через Recents...")
        Log.d(TAG, "requestRestartAndRunScenario: GLOBAL_ACTION_RECENTS")

        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)

        // Фолбэк, если Recents так и не обработаются
        handler.postDelayed({
            if (pendingKillFromRecents) {
                Log.d(
                    TAG,
                    "Recents TIMEOUT, фолбэк: HOME + запуск $targetPackage без явного смахивания"
                )
                pendingKillFromRecents = false
                launchTargetAndRunScenario()
            }
        }, RECENTS_FALLBACK_TIMEOUT_MS)
    }

    /**
     * Вызывать из onAccessibilityEvent сервиса.
     * Тут мы отслеживаем, когда появился экран Recents / лаунчер и можно попытаться смахнуть карточку приложения.
     */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!pendingKillFromRecents) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return

        val root = service.rootInActiveWindow ?: return
        val activePkg = root.packageName?.toString()
        val cls = root.className?.toString()
        Log.d(
            TAG,
            "onAccessibilityEvent[RecentsCheck]: activePkg=$activePkg cls=$cls eventPkg=${event.packageName}"
        )

        // Если всё ещё наше окно или целевое приложение — Recents ещё не показаны
        if (activePkg == service.packageName || activePkg == targetPackage) {
            Log.d(TAG, "onAccessibilityEvent[RecentsCheck]: ещё не Recents (activePkg=$activePkg)")
            return
        }

        // Любой другой пакет считаем Recents/лаунчером
        val killed = try {
            killFromRecentsInternal(root)
        } catch (e: Exception) {
            Log.e(TAG, "onAccessibilityEvent: ошибка в killFromRecentsInternal", e)
            false
        }

        Log.d(TAG, "killFromRecentsInternal result = $killed")

        pendingKillFromRecents = false

        handler.postDelayed({
            when {
                isRestartingTarget -> {
                    // Режим рестарта: HOME + запуск + ожидание
                    launchTargetAndRunScenario()
                }

                isClosingOnly -> {
                    debug("CLOSE_APP: возвращаюсь на рабочий стол после свайпа...")
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

                    isClosingOnly = false
                    val cb = pendingOnClosed
                    pendingOnClosed = null
                    cb?.invoke()
                }

                else -> {
                    Log.d(TAG, "onAccessibilityEvent: ни рестарт, ни closeOnly — ничего не делаю")
                }
            }
        }, AFTER_KILL_HOME_DELAY_MS)

    }

    // ================== ВНУТРЕННЯЯ ЛОГИКА ==================

    private fun launchTargetAndRunScenario() {
        debug(service.getString(R.string.returning_to_home))
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

        handler.postDelayed({
            debug(service.getString(R.string.launching_app, targetAppLabel))
            openTargetApp()

            waitForPackage(targetPackage) {
                debug(service.getString(R.string.app_active, targetAppLabel))
                Log.d(TAG, "launchTargetAndRunScenario: $targetPackage is active")

                isRestartingTarget = false
                val cb = pendingOnReady
                pendingOnReady = null
                cb?.invoke()
            }
        }, AFTER_HOME_OPEN_DELAY_MS)
    }

    private fun openTargetApp() {
        TargetAppLauncher.launch(service, targetPackage)
    }

    private fun waitForPackage(
        pkg: String,
        timeoutMs: Long = WAIT_PKG_TIMEOUT_MS,
        intervalMs: Long = WAIT_PKG_INTERVAL_MS,
        onReady: () -> Unit
    ) {
        val start = System.currentTimeMillis()

        fun check() {
            val current = service.rootInActiveWindow?.packageName?.toString()
            Log.d(TAG, "waitForPackage: current=$current, target=$pkg")

            if (current == pkg) {
                Log.d(TAG, "waitForPackage: $pkg is active")
                onReady()
                return
            }

            val elapsed = System.currentTimeMillis() - start
            if (elapsed >= timeoutMs) {
                Log.d(TAG, "waitForPackage: TIMEOUT for $pkg")
                debug(service.getString(R.string.app_launch_timeout, targetAppLabel))
                isRestartingTarget = false
                // не вызываем колбэк, сценарий дальше не пойдёт
                pendingOnReady = null
                return
            }

            handler.postDelayed({ check() }, intervalMs)
        }

        check()
    }

    private fun killFromRecentsInternal(root: AccessibilityNodeInfo): Boolean {
        // Ищем подпись карточки с именем приложения
        val labelNode = findNodeByTextExact(root, targetAppLabel)
        if (labelNode == null) {
            Log.d(TAG, "In Recents not found text with label '$targetAppLabel'")
            debug(service.getString(R.string.recents_card_not_found, targetAppLabel))
            return false
        }

        var card: AccessibilityNodeInfo? = labelNode

        if (card == null) {
            debug(service.getString(R.string.recents_card_container_not_found, targetAppLabel))
            return false
        }

        val rect = android.graphics.Rect()
        card.getBoundsInScreen(rect)
        val cx = rect.centerX()
        val cy = rect.centerY()

        debug(service.getString(R.string.swiping_app_from_recents, targetAppLabel))

        performSwipe(cx, cy, cx, cy - RECENTS_SWIPE_DISTANCE_PX)
        return true
    }

    /**
     * Только закрыть приложение через Recents по указанному пакету.
     * Сценарий: Recents -> свайп карточки -> HOME -> onClosed.
     */
    fun requestCloseOnly(pkg: String, onClosed: (() -> Unit)? = null) {
        if (pendingKillFromRecents || isRestartingTarget || isClosingOnly) {
            Log.d(TAG, "requestCloseOnly: another operation is already in progress, ignoring")
            return
        }

        setTargetPackage(pkg)

        isClosingOnly = true
        isRestartingTarget = false
        pendingKillFromRecents = true
        pendingOnClosed = onClosed

        debug(service.getString(R.string.closing_app_via_recents, targetAppLabel))
        Log.d(TAG, "requestCloseOnly: GLOBAL_ACTION_RECENTS")

        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)

        // Фолбэк: Recents так и не открылись
        handler.postDelayed({
            if (pendingKillFromRecents && isClosingOnly) {
                Log.d(
                    TAG,
                    "requestCloseOnly: Recents TIMEOUT, considering close completed without swipe"
                )
                pendingKillFromRecents = false
                isClosingOnly = false

                // просто вернёмся на рабочий стол и дернем колбэк
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

                val cb = pendingOnClosed
                pendingOnClosed = null
                cb?.invoke()
            }
        }, RECENTS_FALLBACK_TIMEOUT_MS)
    }

    /**
     * Убить приложение через root (am force-stop), без Recents.
     */
    fun killAppRoot(pkg: String, onDone: (() -> Unit)? = null) {
        try {
            debug(service.getString(R.string.root_kill_force_stop, pkg))

            val cmd = arrayOf("su", "-c", "am force-stop $pkg")

            //Runtime.getRuntime().exec(arrayOf("su", "-c", "pm disable-user --user 0 $pkg"))

            Runtime.getRuntime().exec(cmd)

            //Runtime.getRuntime().exec(arrayOf("su", "-c", "pm enable $pkg"))

            //Runtime.getRuntime().exec(cmd)

            //val process = Runtime.getRuntime().exec(cmd)
            //val code = process.waitFor()

        } catch (e: Exception) {
            debug(service.getString(R.string.root_kill_error, pkg))
        }

        handler.postDelayed({
            onDone?.invoke()
        }, 400L) // лёгкая задержка, чтобы система успела закрыть процесс
    }


    /**
     * Просто открыть текущее targetPackage (без закрытия через Recents)
     * и при необходимости подождать, пока оно станет активным.
     */
    fun launchOnlyAndWait(onTargetReady: (() -> Unit)? = null) {
        if (pendingKillFromRecents || isRestartingTarget || isClosingOnly) {
            Log.d(TAG, "launchOnlyAndWait: is busy, ignoring")
            return
        }

        // На всякий случай сбросим все «режимы»
        isRestartingTarget = false
        isClosingOnly = false
        pendingOnClosed = null
        pendingOnReady = null
        pendingKillFromRecents = false

        debug(service.getString(R.string.returning_to_home))
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

        handler.postDelayed({
            debug(service.getString(R.string.launching_app, targetAppLabel))
            openTargetApp()

            // Если колбэк не нужен — просто выходим
            if (onTargetReady == null) return@postDelayed

            waitForPackage(targetPackage) {
                debug(service.getString(R.string.app_active, targetAppLabel))
                //Log.d(TAG, "launchOnlyAndWait: $targetPackage активен")
                onTargetReady()
            }
        }, AFTER_HOME_OPEN_DELAY_MS)
    }


    private fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int) {
        try {
            val dm = service.resources.displayMetrics
            val maxX = dm.widthPixels - 1
            val maxY = dm.heightPixels - 1

            var sx = x1.coerceIn(0, maxX)
            var sy = y1.coerceIn(0, maxY)
            var ex = x2.coerceIn(0, maxX)
            var ey = y2.coerceIn(0, maxY)

            if (sx == ex && sy == ey) {
                ey = (sy - dm.heightPixels / 4).coerceAtLeast(0)
            }

            val path = Path().apply {
                moveTo(sx.toFloat(), sy.toFloat())
                lineTo(ex.toFloat(), ey.toFloat())
            }

            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,
                        300L
                    )
                )
                .build()

            service.dispatchGesture(gesture, null, null)
            Log.d(TAG, "performSwipe: ($sx,$sy) -> ($ex,$ey)")

        } catch (e: IllegalArgumentException) {
            Log.e(
                TAG,
                "performSwipe: IllegalArgumentException for x1=$x1 y1=$y1 x2=$x2 y2=$y2",
                e
            )
        } catch (e: Exception) {
            Log.e(TAG, "performSwipe: another error", e)
        }
    }
}
