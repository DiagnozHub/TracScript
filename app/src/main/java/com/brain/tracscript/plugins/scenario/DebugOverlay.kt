package com.brain.tracscript.plugins.scenario

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.isVisible
import com.brain.tracscript.R

class DebugOverlay(
    private val service: AccessibilityService,
    private val onToggleExploreRequest: () -> Unit   // ← просим сервис переключить режим
) {

    private val windowManager =
        service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager

    private val rootView: View = LayoutInflater.from(service)
        .inflate(R.layout.overlay_debug, null, false)

    private val textView: TextView = rootView.findViewById(R.id.debugText)
    private val toggleView: TextView = rootView.findViewById(R.id.debugExit)

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT, // по умолчанию только шапка
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP
    }

    private var added = false
    private var exploreMode = false
    private var isBottom = false   // ← следим, где сейчас overlay: TOP/BOTTOM
    private var screenLocked = false  // ← заблокирован ли экран

    // запоминаем исходный фон overlay (как в XML)
    private val originalBackground = rootView.background

    // --- таймер DELAY ---
    private val handler = Handler(Looper.getMainLooper())
    private var delayTotalMs: Long = 0L
    private var delayStartMs: Long = 0L
    private var delayRunnable: Runnable? = null
    private var delayActive = false

    init {
        // Кнопка-меню всегда есть
        toggleView.isVisible = true
        toggleView.text = "☰"

        toggleView.setOnClickListener {
            if (exploreMode) {
                // В режиме исследования — просто выходим (как раньше "✕")
                onToggleExploreRequest()
            } else {
                // В обычном режиме — показываем выпадающее меню
                showMainMenu()
            }
        }
    }

    fun show() {
        if (!added) {
            windowManager.addView(rootView, params)
            added = true
        }
    }

    fun hide() {
        if (added) {
            stopDelayCountdown()
            windowManager.removeView(rootView)
            added = false
        }
    }

    fun update(text: String) {
        if (!added) return
        textView.text = text
        textView.isVisible = true
    }

    /**
     * Запуск показа обратного отсчёта для DELAY.
     * Формат: DELAY <текущее>/<total> мс
     */
    fun startDelayCountdown(totalMs: Long) {
        if (!added) return

        stopDelayCountdown() // сброс старого

        delayTotalMs = totalMs
        delayStartMs = System.currentTimeMillis()
        delayActive = true

        delayRunnable = object : Runnable {
            override fun run() {
                if (!delayActive || !added) return

                val elapsed = System.currentTimeMillis() - delayStartMs
                val clamped = elapsed.coerceAtMost(delayTotalMs)
                val remaining = (delayTotalMs - clamped).coerceAtLeast(0L)

                //textView.text = "DELAY ${clamped}/${delayTotalMs} мс (осталось ${remaining} мс)"
                textView.text = service.getString(R.string.delay_progress, clamped, delayTotalMs, remaining)
                textView.isVisible = true

                if (clamped >= delayTotalMs) {
                    delayActive = false
                    return
                }

                handler.postDelayed(this, 200L)
            }
        }

        handler.post(delayRunnable!!)
    }

    fun stopDelayCountdown() {
        delayActive = false
        delayRunnable?.let { handler.removeCallbacks(it) }
        delayRunnable = null
    }

    /**
     * Сервис говорит overlay'ю: режим исследования ВКЛ / ВЫКЛ.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun setExploreMode(enabled: Boolean) {
        if (!added) {
            exploreMode = enabled
            return
        }
        if (exploreMode == enabled) return
        exploreMode = enabled

        // В режиме исследования блокировка неактуальна — сбрасываем
        if (enabled) {
            screenLocked = false
            // возвращаем родной фон, без затемнения
            rootView.background = originalBackground
        }

        if (enabled) {
            // Полноэкранный, тачабл, ловим все тапы
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.flags =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

            toggleView.text = "✕"

            rootView.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val x = event.rawX.toInt()
                    val y = event.rawY.toInt()

                    // если попали в кнопку – просим сервис переключить режим
                    if (isInToggleViewBounds(x, y)) {
                        onToggleExploreRequest()
                        return@setOnTouchListener true
                    }

                    val dm = service.resources.displayMetrics
                    val w = dm.widthPixels.toFloat()
                    val h = dm.heightPixels.toFloat()

                    val rx = x / w
                    val ry = y / h

                    val coordMsg =
                        "Tap x=$x y=$y  (rx=${"%.3f".format(rx)} ry=${"%.3f".format(ry)})"

                    val svc = service as? MyAccessibilityService
                    val root = service.rootInActiveWindow
                    val node = svc?.findNodeAtPosition(root, x, y)
                    val treeDump = svc?.dumpFullTreeFrom(node) ?: "node=null"

                    val shortClass = node?.className ?: "null"
                    val shortText = (node?.text ?: node?.contentDescription ?: "").toString()

                    update(
                        coordMsg +
                                "\nNode: $shortClass" +
                                "\nText: $shortText"
                    )

                    android.util.Log.d("TracScript", coordMsg)
                    android.util.Log.d("TracScript_NODE", treeDump)

                    val fullDump = buildString {
                        appendLine(coordMsg)
                        appendLine(treeDump)
                    }
                    svc?.appendInspectorDumpToFile(fullDump)
                }
                true // в режиме исследования все тапы остаются в overlay
            }

        } else {
            // Обычный режим: только шапка, координаты не ловим
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.flags =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

            toggleView.text = "☰"

            rootView.setOnTouchListener(null)
            // при выходе из explore без блокировки возвращаем исходный фон
            if (!screenLocked) {
                rootView.background = originalBackground
            }
        }

        windowManager.updateViewLayout(rootView, params)
    }

    private fun isInToggleViewBounds(x: Int, y: Int): Boolean {
        val loc = IntArray(2)
        toggleView.getLocationOnScreen(loc)
        val left = loc[0]
        val top = loc[1]
        val right = left + toggleView.width
        val bottom = top + toggleView.height

        return x in left..right && y in top..bottom
    }

    // --- меню: исследование, позиция, блокировка экрана ---

    private fun showMainMenu() {
        val popup = PopupMenu(service, toggleView)

        // 1. исследование экрана (Play)
        popup.menu.add(0, 1, 0, "Исследование экрана")

        // 2. Переместить вверх/вниз
        val moveTitle = if (isBottom) "Переместить наверх" else "Переместить вниз"
        popup.menu.add(0, 2, 1, moveTitle)

        // 3. Заблокировать / Разблокировать экран
        val lockTitle = if (screenLocked) "Разблокировать экран" else "Заблокировать экран"
        popup.menu.add(0, 3, 2, lockTitle)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    // исследование экрана → просим сервис переключить режим (включит exploreMode)
                    onToggleExploreRequest()
                    true
                }

                2 -> {
                    togglePosition()
                    true
                }

                3 -> {
                    toggleScreenLock()
                    true
                }

                else -> false
            }
        }

        popup.show()
    }

    private fun togglePosition() {
        isBottom = !isBottom
        params.gravity = if (isBottom) Gravity.BOTTOM else Gravity.TOP
        windowManager.updateViewLayout(rootView, params)

        val msg = if (isBottom) "Overlay перемещён вниз" else "Overlay перемещён вверх"
        textView.text = msg
        textView.isVisible = true
    }

    private fun toggleScreenLock() {
        if (exploreMode) {
            update("Нельзя блокировать экран в режиме исследования")
            return
        }

        if (screenLocked) {
            disableScreenLock()
        } else {
            enableScreenLock()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableScreenLock() {
        if (!added) return
        screenLocked = true

        // Делаем overlay полноэкранным и ставим полупрозрачный серый фон
        params.height = WindowManager.LayoutParams.MATCH_PARENT
        params.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        // полупрозрачный серый (можно подстроить, если хочешь потемнее/посветлее)
        rootView.setBackgroundColor(Color.parseColor("#80000000"))

        rootView.setOnTouchListener { _, event ->
            // Глотаем все тапы, кроме области кнопки меню
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.rawX.toInt()
                val y = event.rawY.toInt()

                if (isInToggleViewBounds(x, y)) {
                    toggleView.performClick()
                }
            }
            true
        }

        windowManager.updateViewLayout(rootView, params)

        textView.text = "Экран заблокирован"
        textView.isVisible = true
    }

    private fun disableScreenLock() {
        if (!added) return
        screenLocked = false

        // Возвращаемся к обычной "шапке"
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        rootView.setOnTouchListener(null)
        // возвращаем исходный фон (как в overlay_debug.xml)
        rootView.background = originalBackground

        windowManager.updateViewLayout(rootView, params)

        textView.text = "Экран разблокирован"
        textView.isVisible = true
    }
}
