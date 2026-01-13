package com.brain.tracscript.plugins.scenario

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import com.brain.tracscript.FileHelper
import com.brain.tracscript.R
import com.brain.tracscript.ScreenOnController
import com.brain.tracscript.core.TracScriptApp
import com.brain.tracscript.core.DataBus
import com.brain.tracscript.core.DataBusEvent
import com.brain.tracscript.plugins.gps.TableJsonExtractor
import com.brain.tracscript.telemetry.AppLog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob


const val TARGET_PACKAGE = "android"

data class ExpandableGroup(
    val group: String,
    val children: MutableList<String>
)

private data class RowInfo(
    val text: String,
    val isGroup: Boolean
)

private data class TextCellDump(
    val id: String?,
    val text: String
)

private data class CheckboxDump(
    val id: String?,
    val checked: Boolean
)

private data class RowDump(
    val index: Int,
    val top: Int,
    val bottom: Int,
    val textCells: List<TextCellDump>,
    val checkboxes: List<CheckboxDump>
)

class MyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TracScript"


        private const val WAIT_TEXT_TIMEOUT_MS = 5000L
        private const val WAIT_TEXT_INTERVAL_MS = 300L
        private const val MAX_FIND_BEST_CHECKS = 5000
        private var exploreMode = false

        private val serviceJob = SupervisorJob()
        private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main)
    }

    // --- периодическая проверка команд ---
    private val commandPollHandler = Handler(Looper.getMainLooper())
    private var commandPollRunning = false

    // хэндлер на главном потоке для задержек и ожиданий
    private val waitHandler = Handler(Looper.getMainLooper())
    private var debugOverlay: DebugOverlay? = null

    private lateinit var fileHelper: FileHelper
    private lateinit var targetController: TargetAppController

    private lateinit var scenarioManager: ScenarioManager

    private lateinit var screenOnController: ScreenOnController

    private var engineRunning = false

    private var busSub: com.brain.tracscript.core.Subscription? = null

    private var logScenario: AppLog? = null


    private fun bus(): DataBus =
        (application as TracScriptApp).pluginRuntime.dataBus

    private val prefs by lazy { ScenarioPrefs.prefs(applicationContext) }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ScenarioPrefs.KEY_ENABLED) {
            syncEngineState()
        }
    }

    private fun registerPrefListener() {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    private fun unregisterPrefListener() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
    }


    private fun syncEngineState() {
        val shouldRun = ScenarioPrefs.isEnabled(applicationContext)
        if (shouldRun && !engineRunning) startEngine()
        if (!shouldRun && engineRunning) stopEngine()
    }

    private fun startEngine() {
        if (engineRunning) return
        engineRunning = true

        // --- управление экраном ---
        screenOnController = ScreenOnController(this).also {
            it.register()
            it.initFromSettings()
        }

        fileHelper = FileHelper(this) { msg ->
            setDebugOverlayOnly(msg)
        }

        logScenario = AppLog(
            fileHelper = fileHelper,
            scope = serviceScope,
            fileName = "scenario_service.log"
        )

        logScenario?.i(TAG, "Scenario engine START")


        // --- Target controller ---
        targetController = TargetAppController(
            service = this,
            initialTargetPackage = TARGET_PACKAGE,
            debug = { msg -> setDebug(msg) },
            findNodeByTextExact = { root, text -> findNodeByTextExact(root, text) }
        )

        // --- Overlay ---
        debugOverlay = DebugOverlay(
            service = this,
            onToggleExploreRequest = { toggleExploreMode() }
        ).also {
            it.show()
            it.update("Scenarios started")
            it.setExploreMode(false)
        }

        // --- ScenarioManager ---
        scenarioManager = ScenarioManager(
            logTag = TAG,
            debug = { msg -> setDebug(msg) },
            startScenarioCallback = { runId -> runScenario(runId) },
            appContext = applicationContext
        )

        // --- восстановление periodic ---
        if (ScenarioPrefs.isPeriodicEnabled(applicationContext)) {
            val sec = ScenarioPrefs.getPeriodicIntervalSec(applicationContext).coerceAtLeast(1)
            scenarioManager.startPeriodic(sec * 1000L)
        }

        busSub = bus().subscribe { event ->
            if (!engineRunning) return@subscribe
            if (!ScenarioPrefs.isEnabled(applicationContext)) return@subscribe
            if (event.type != "scenario_cmd") return@subscribe

            val cmd = event.payload["cmd"] as? String ?: return@subscribe
            when (cmd) {
                "open_app" -> scenarioManager.startSingleRun()
                "explore_on" -> enableExploreMode()
                "explore_off" -> disableExploreMode()
                else -> { /* игнор */ }
            }
        }

        handleLastCommand()
        startCommandPolling()
    }


    private fun stopEngine() {
        if (!engineRunning) return
        engineRunning = false

        logScenario?.i(TAG, "Scenario engine STOP")

        stopCommandPolling()

        try {
            scenarioManager.onDestroy()
        } catch (_: Exception) {}

        try {
            debugOverlay?.hide()
        } catch (_: Exception) {}

        debugOverlay = null

        try {
            screenOnController.unregister()
        } catch (_: Exception) {}

        exploreMode = false

        busSub?.unsubscribe()
        busSub = null

        logScenario?.i(TAG, "Scenario engine STOP")
        logScenario?.close()
        logScenario = null
    }



    private val commandPollRunnable = object : Runnable {
        override fun run() {
            // просто дергаем существующий метод
            handleLastCommand()

            if (commandPollRunning) {
                // раз в 300 мс (можешь увеличить до 500–1000, если хочешь реже)
                commandPollHandler.postDelayed(this, 300L)
            }
        }
    }

    private fun startCommandPolling() {
        if (commandPollRunning) return
        commandPollRunning = true
        commandPollHandler.post(commandPollRunnable)
    }

    private fun stopCommandPolling() {
        commandPollRunning = false
        commandPollHandler.removeCallbacksAndMessages(null)
    }

    private fun enableExploreMode() {
        exploreMode = true
        debugOverlay?.setExploreMode(true)
        setDebug(applicationContext.getString(R.string.expl_mode_on))
    }

    private fun disableExploreMode() {
        exploreMode = false
        debugOverlay?.setExploreMode(false)
        setDebug(applicationContext.getString(R.string.expl_mode_off))
    }

    private fun toggleExploreMode() {
        if (exploreMode) {
            disableExploreMode()
        } else {
            enableExploreMode()
        }
    }

    private fun runLater(delayMs: Long, block: () -> Unit) {
        waitHandler.postDelayed({ block() }, delayMs)
    }

    fun appendInspectorDumpToFile(dump: String) {
        if (this::fileHelper.isInitialized) {
            fileHelper.appendInspectorDumpToFile(dump)
        } else {
            logScenario?.w(TAG, "appendInspectorDumpToFile: fileHelper not yet initialized")
        }
    }

    private fun findNodeByIdLike(root: AccessibilityNodeInfo?, idQuery: String): AccessibilityNodeInfo? {
        if (root == null) return null

        val hasWildcard = idQuery.contains('*')
        val pattern = if (hasWildcard) {
            idQuery.replace("*", ".*").toRegex()
        } else null

        fun matches(resId: String?): Boolean {
            if (resId == null) return false
            return if (hasWildcard) {
                pattern!!.matches(resId)
            } else {
                resId == idQuery
            }
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val visited = HashSet<Int>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val id = System.identityHashCode(node)
            if (!visited.add(id)) continue

            val resId = node.viewIdResourceName
            if (matches(resId)) {
                logScenario?.i(TAG, "findNodeByIdLike: found id=$resId, class=${node.className}")
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return null
    }

    private fun buildExpandableListJson(listNode: AccessibilityNodeInfo, viewId: String): String {
        val rows = mutableListOf<RowDump>()

        // Берём ПРЯМЫХ детей ExpandableListView — это видимые строки (group + child)
        val count = listNode.childCount
        for (i in 0 until count) {
            val rowNode = listNode.getChild(i) ?: continue

            val rect = android.graphics.Rect()
            rowNode.getBoundsInScreen(rect)

            val textCells = mutableListOf<TextCellDump>()
            val checkboxes = mutableListOf<CheckboxDump>()

            collectRowInfo(rowNode, textCells, checkboxes)

            // если вообще нет текста и чекбоксов — пропускаем
            if (textCells.isEmpty() && checkboxes.isEmpty()) continue

            rows += RowDump(
                index = i,
                top = rect.top,
                bottom = rect.bottom,
                textCells = textCells,
                checkboxes = checkboxes
            )
        }

        // сортируем по top (на всякий случай, если порядок детей странный)
        val sorted = rows.sortedBy { it.top }

        // ручная сборка JSON
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"viewId\":\"").append(jsonEscape(viewId)).append("\",")
        sb.append("\"rows\":[")

        sorted.forEachIndexed { idx, row ->
            if (idx > 0) sb.append(",")

            sb.append("{")
            sb.append("\"index\":").append(row.index).append(",")
            sb.append("\"top\":").append(row.top).append(",")
            sb.append("\"bottom\":").append(row.bottom).append(",")

            // главный текст строки — первый TextView с непустым текстом
            val mainText = row.textCells.firstOrNull { it.text.isNotBlank() }?.text ?: ""
            sb.append("\"mainText\":\"").append(jsonEscape(mainText)).append("\",")

            // чекбоксы
            sb.append("\"checkboxes\":[")
            row.checkboxes.forEachIndexed { j, cb ->
                if (j > 0) sb.append(",")
                sb.append("{")
                sb.append("\"id\":").append(
                    if (cb.id != null) "\"${jsonEscape(cb.id)}\"" else "null"
                ).append(",")
                sb.append("\"checked\":").append(cb.checked)
                sb.append("}")
            }
            sb.append("],")

            // все текстовые ячейки
            sb.append("\"texts\":[")
            row.textCells.forEachIndexed { j, t ->
                if (j > 0) sb.append(",")
                sb.append("{")
                sb.append("\"id\":").append(
                    if (t.id != null) "\"${jsonEscape(t.id)}\"" else "null"
                ).append(",")
                sb.append("\"text\":\"").append(jsonEscape(t.text)).append("\"")
                sb.append("}")
            }
            sb.append("]")

            sb.append("}")
        }

        sb.append("]}")
        return sb.toString()
    }

    private fun collectRowInfo(
        node: AccessibilityNodeInfo,
        textCells: MutableList<TextCellDump>,
        checkboxes: MutableList<CheckboxDump>
    ) {
        val cls = node.className?.toString() ?: ""

        if (cls.contains("TextView", ignoreCase = true)) {
            val text = node.text?.toString() ?: ""
            val id = node.viewIdResourceName
            textCells += TextCellDump(id = id, text = text)
        }

        if (cls.contains("CheckBox", ignoreCase = true)) {
            val id = node.viewIdResourceName
            val checked = node.isChecked
            checkboxes += CheckboxDump(id = id, checked = checked)
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectRowInfo(child, textCells, checkboxes)
            }
        }
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    fun findNodeAtPosition(root: AccessibilityNodeInfo?, x: Int, y: Int): AccessibilityNodeInfo? {
        if (root == null) return null

        val rect = android.graphics.Rect()
        root.getBoundsInScreen(rect)

        if (!rect.contains(x, y)) return null

        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            val hit = findNodeAtPosition(child, x, y)
            if (hit != null) return hit
        }

        return root
    }

    fun dumpFullTreeFrom(node: AccessibilityNodeInfo?): String {
        if (node == null) return "node=null"

        val sb = StringBuilder()

        sb.appendLine("\n================= INSPECTOR =================")

        // 1. Родители
        sb.appendLine("\n--- PARENTS ---")
        var p = node.parent
        var level = 0
        while (p != null && level < 20) {
            sb.appendLine(describeNode("Parent[$level]", p))
            p = p.parent
            level++
        }

        // 2. Текущая нода
        sb.appendLine("\n--- CURRENT NODE ---")
        sb.appendLine(describeNode("Node", node))

        // 3. Дети
        sb.appendLine("\n--- CHILDREN ---")
        for (i in 0 until node.childCount) {
            sb.appendLine(describeNode("Child[$i]", node.getChild(i)))
        }

        // 4. Соседи (дети родителя)
        sb.appendLine("\n--- SIBLINGS ---")
        val parent = node.parent
        if (parent != null) {
            for (i in 0 until parent.childCount) {
                sb.appendLine(describeNode("Sibling[$i]", parent.getChild(i)))
            }
        }

        sb.appendLine("\n=============================================\n")

        return sb.toString()
    }

    private fun describeNode(tag: String, n: AccessibilityNodeInfo?): String {
        if (n == null) return "$tag: null"

        val rect = android.graphics.Rect()
        n.getBoundsInScreen(rect)

        val text = n.text ?: n.contentDescription ?: ""

        return "$tag: cls=${n.className}, txt='$text', id=${n.viewIdResourceName}, " +
                "click=${n.isClickable}, childCount=${n.childCount}, bounds=$rect"
    }

    private fun findNodeByTextExact(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null

        val target = text.trim().lowercase()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val visited = HashSet<Int>()

        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            val id = System.identityHashCode(node)
            if (!visited.add(id)) continue

            val nodeText = (node.text ?: node.contentDescription)
                ?.toString()
                ?.trim()
                ?.lowercase()

            if (nodeText == target) return node

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) queue.add(child)
            }
        }
        return null
    }

    private fun collectVisibleRows(list: AccessibilityNodeInfo): List<RowInfo> {
        val out = mutableListOf<RowInfo>()

        for (i in 0 until list.childCount) {
            val row = list.getChild(i) ?: continue

            val text = extractAllText(row)

            val isGroup = text.isNotEmpty() &&
                    (row.className?.contains("LinearLayout") == true)

            out += RowInfo(text, isGroup)
        }

        return out.filter { it.text.isNotBlank() }
    }

    private fun extractAllText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()

        node.text?.toString()?.let {
            if (it.isNotBlank()) sb.append(it).append(" ")
        }

        node.contentDescription?.toString()?.let {
            if (it.isNotBlank()) sb.append(it).append(" ")
        }

        for (i in 0 until node.childCount) {
            sb.append(extractAllText(node.getChild(i)))
        }

        return sb.toString().trim()
    }

    private fun mergeRowsIntoGroups(
        rows: List<RowInfo>,
        out: MutableList<ExpandableGroup>
    ) {
        var current: ExpandableGroup? = null

        for (r in rows) {
            if (r.isGroup) {
                current = ExpandableGroup(r.text, mutableListOf())
                out.add(current)
            } else {
                current?.children?.add(r.text)
            }
        }
    }

    private fun scrollListDown(node: AccessibilityNodeInfo): Boolean {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)

        val x = rect.centerX()
        val y1 = (rect.bottom * 0.8).toInt()
        val y2 = (rect.top * 0.2).toInt()

        return performSwipeGesture(x, y1, x, y2)
    }

    private fun performSwipeGesture(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        val path = android.graphics.Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    private fun toJson(data: List<ExpandableGroup>): String {
        val sb = StringBuilder()
        sb.append("[\n")

        data.forEachIndexed { i, g ->
            sb.append("  {\n")
            sb.append("    \"group\": \"${g.group}\",\n")
            sb.append("    \"children\": [\n")

            g.children.forEachIndexed { j, ch ->
                sb.append("      \"${ch.replace("\"", "\\\"")}\"")
                if (j < g.children.size - 1) sb.append(",")
                sb.append("\n")
            }

            sb.append("    ]\n")
            sb.append("  }")
            if (i < data.size - 1) sb.append(",")
            sb.append("\n")
        }

        sb.append("]")
        return sb.toString()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        registerPrefListener()

        syncEngineState()

        logScenario?.i(TAG, "AccessibilityService connected")
    }

    override fun onDestroy() {
        unregisterPrefListener()
        stopEngine()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun setDebug(text: String) {
        setDebugOverlayOnly(text)
        logScenario?.i(TAG, text)
    }


    private fun setDebugOverlayOnly(text: String) {
        waitHandler.post {
            debugOverlay?.update(text)
        }
    }



    private fun tapByRatio(
        xRatio: Float,
        yRatio: Float,
        durationMs: Long = 100L
    ) {
        try {
            val dm = resources.displayMetrics
            val width = dm.widthPixels
            val height = dm.heightPixels

            val x = (width * xRatio).toInt().coerceIn(0, width - 1)
            val y = (height * yRatio).toInt().coerceIn(0, height - 1)

            val path = android.graphics.Path().apply {
                moveTo(x.toFloat(), y.toFloat())
                lineTo(x.toFloat(), y.toFloat())   // тач в одной точке
            }

            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(
                    android.accessibilityservice.GestureDescription.StrokeDescription(
                        path,
                        0,
                        durationMs
                    )
                )
                .build()

            dispatchGesture(gesture, null, null)
            logScenario?.i(TAG, "tapByRatio: ($xRatio,$yRatio) -> ($x,$y), duration=$durationMs")

        } catch (e: Exception) {
            logScenario?.e(TAG, "tapByRatio error", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!engineRunning) return

        if (event == null) return

        val eventPkg = event.packageName?.toString()
        val cls = event.className?.toString()

        //logScenario?.i(TAG, "event from $eventPkg / $cls, type=${event.eventType}")

        // 1) логика управления Diagzone (Recents, убийство, запуск)
        targetController.onAccessibilityEvent(event)

        // 2) работа с командами
        //handleLastCommand()
    }

    override fun onInterrupt() {
        // ничего не нужно
    }

    // --- работа с командами через SharedPreferences (через CommandStorage) ---

    private fun clearCommand() {
        CommandStorage.clear(this)
    }

    private fun handleLastCommand() {
        val raw = CommandStorage.loadRaw(this) ?: return

        val activePkg = rootInActiveWindow?.packageName?.toString()

        logScenario?.i(TAG, "handleLastCommand: raw=$raw, activePkg=$activePkg")

        when {
            raw == "open_app" -> {
                clearCommand()

                val msg = applicationContext.getString(R.string.scenario_start_by_command)
                //logScenario?.i(TAG, msg)
                setDebug(msg)

                // Теперь запуск приложения — это шаг сценария LAUNCH_APP
                //runScenario()
                scenarioManager.startSingleRun()
            }

            raw == "explore_on" -> {
                clearCommand()
                logScenario?.i(TAG, applicationContext.getString(R.string.expl_mode_enabling))
                enableExploreMode()
            }

            raw == "explore_off" -> {
                clearCommand()
                logScenario?.i(TAG, applicationContext.getString(R.string.expl_mode_disabling))
                disableExploreMode()
            }

            raw.startsWith("start_periodic:") -> {
                clearCommand()
                val msStr = raw.removePrefix("start_periodic:").trim()
                val interval = msStr.toLongOrNull() ?: (10 * 60_000L)
                logScenario?.i(TAG, "handleLastCommand: start_periodic interval=$interval")
                scenarioManager.startPeriodic(interval)
            }

            raw == "stop_periodic" -> {
                clearCommand()
                logScenario?.i(TAG, "handleLastCommand: stop_periodic")
                scenarioManager.stopPeriodic()
            }

            else -> {
                clearCommand()
                logScenario?.i(TAG, applicationContext.getString(R.string.unknown_command, raw))
            }
        }
    }

    private fun runScenario(runId: Int) {
        // Скрипт из настроек, если нет — дефолт
        val script = ScenarioStorage.load(this) ?: ScenarioDefaults.DEFAULT_SCENARIO
        val steps = ScenarioParser.parse(script)

        if (steps.isEmpty()) {
            //logScenario?.w(TAG, "Сценарий пуст — ничего не делаем")
            setDebug(applicationContext.getString(R.string.scenario_empty))
            return
        }

        val msg = applicationContext.getString(R.string.scenario_start_steps, steps.size)
        //logScenario?.i(TAG, msg)
        setDebug(msg)

        runScenarioSteps(steps, 0, runId)
    }


    private fun runScenarioSteps(steps: List<ScenarioStep>, index: Int, runId: Int) {
        // сценарий уже отменён/перезапущен?
        if (!scenarioManager.isRunActive(runId)) {
            logScenario?.i(TAG, "runScenarioSteps: runId=$runId not actual")
            return
        }

        if (index >= steps.size) {

            val msg = applicationContext.getString(R.string.scenario_finished)
            //logScenario?.i(TAG, msg)
            setDebug(msg)
            return
        }

        val step = steps[index]

        when (step) {
            is ScenarioStep.Delay -> {

                val msg = applicationContext.getString(R.string.scenario_step_delay, index, step.ms)
                //logScenario?.i(TAG, msg)
                setDebug(msg)

                // запускаем обратный отсчёт в overlay
                debugOverlay?.startDelayCountdown(step.ms)

                runLater(step.ms) {
                    // по окончании ожидания останавливаем таймер
                    debugOverlay?.stopDelayCountdown()
                    runScenarioSteps(steps, index + 1, runId)
                }
            }

            is ScenarioStep.WaitText -> {
                val msg = applicationContext.getString(R.string.scenario_step_wait_text, index, step.text)
                //logScenario?.i(TAG, msg)
                setDebug(msg)

                waitForText(step.text, runId = runId, onFound = {
                    runScenarioSteps(steps, index + 1, runId)
                })
            }

            is ScenarioStep.ClickText -> {

                val msg = applicationContext.getString(R.string.scenario_step_click_text, index, step.text)
                //logScenario?.i(TAG, msg)
                setDebug(msg)

                clickButtonWithText(step.text)
                runLater(300L) {   // даём UI чуть времени
                    runScenarioSteps(steps, index + 1, runId)
                }
            }

            // НОВОЕ: TAP
            is ScenarioStep.Tap -> {

                val msg = applicationContext.getString(R.string.scenario_step_tap, index, step.xRatio, step.yRatio)

                //logScenario?.i(TAG, msg)

                setDebug(msg)

                tapByRatio(step.xRatio, step.yRatio, step.durationMs)

                // Переходим к следующему шагу после завершения жеста + небольшой запас
                val delayNext = step.durationMs + 200L
                runLater(delayNext) {
                    runScenarioSteps(steps, index + 1, runId)
                }
            }

            is ScenarioStep.LaunchApp -> {
                //val msg = "Step[$index]: LAUNCH_APP ${step.packageName}"
                val msg = applicationContext.getString(R.string.scenario_step_launch_app, index, step.packageName)
                //logScenario?.i(TAG, msg)
                setDebug(msg)

                // Настраиваем контроллер под нужный пакет
                targetController.setTargetPackage(step.packageName)

                // ТОЛЬКО открыть приложение (без предварительного закрытия)
                targetController.launchOnlyAndWait {
                    runScenarioSteps(steps, index + 1, runId)
                }
            }


            is ScenarioStep.CloseApp -> {
                val msg = applicationContext.getString(R.string.scenario_step_close_app, index, step.packageName)
                //logScenario?.i(TAG, msg)
                setDebug(msg)

                targetController.requestCloseOnly(step.packageName) {
                    runScenarioSteps(steps, index + 1, runId)
                }
            }

            is ScenarioStep.KillAppRoot -> {
                val msg = applicationContext.getString(R.string.scenario_step_kill_app_root, index, step.packageName)
                //logScenario?.i(TAG, msg)
                setDebug(msg)

                targetController.killAppRoot(step.packageName) {
                    runScenarioSteps(steps, index + 1, runId)
                }
            }


            is ScenarioStep.ExtractTableById -> {
                val msg = applicationContext.getString(R.string.scenario_step_extract_table_by_id, index, step.viewId, step.fileName)
                //logScenario?.i(TAG, msg)
                setDebug(msg)

                extractTableById(step.viewId, step.fileName)

                // дадим UI “подышать”
                runLater(500L) {
                    runScenarioSteps(steps, index + 1, runId)
                }
            }

            is ScenarioStep.ExtractExpandableList -> {
                val msg = applicationContext.getString(R.string.extract_expandable_list, step.resId)
                //logScenario?.i(TAG, msg)
                setDebug(msg)

                extractExpandableList(step.resId, step.file)

                runLater(800L) {
                    runScenarioSteps(steps, index + 1, runId)
                }
            }

            is ScenarioStep.SendWialonTable -> {
                val msg = applicationContext.getString(R.string.command_deprecated)
                //logScenario?.i(TAG, msg)
                setDebug(msg)
            }

            is ScenarioStep.SendTableToBus -> {
                val fileName = step.fileName

                var msg = applicationContext.getString(R.string.scenario_step_send_table_to_bus, index, fileName)
                //logScenario?.i(TAG, msg)
                setDebug(msg)

                val json = fileHelper.readTextFromDocuments(fileName)
                if (json == null) {
                    msg = applicationContext.getString(R.string.send_table_file_not_found_or_empty, fileName)
                    //logScenario?.w(TAG, msg)
                    setDebug(msg)
                    runLater(100L) {
                        runScenarioSteps(steps, index + 1, runId)
                    }
                    return
                }

                // Кладём в шину то же самое, что потом читает SEND_WIALON_TABLE
                val event = DataBusEvent(
                    type = "wialon_table_json",
                    payload = mapOf(
                        "fileName" to fileName,
                        "json" to json
                    )
                )
                bus().post(event)

                logScenario?.i(TAG, applicationContext.getString(R.string.send_table_event_sent))

                runLater(100L) {
                    runScenarioSteps(steps, index + 1, runId)
                }
            }

            is ScenarioStep.DeleteFile -> {
                val msg = applicationContext.getString(R.string.scenario_step_delete_file, index, step.fileName)
                //logScenario?.i(TAG, msg)
                setDebug(msg)

                fileHelper.deleteFileFromDocuments(step.fileName)

                // можно без паузы, но на всякий случай дадим 100 мс
                runLater(100L) {
                    runScenarioSteps(steps, index + 1, runId)
                }
            }

            ScenarioStep.Back -> {

                val msg = applicationContext.getString(R.string.scenario_step_back, index)
                //logScenario?.i(TAG, msg)
                setDebug(msg)

                val ok = performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                logScenario?.i(TAG, "GLOBAL_ACTION_BACK result = $ok")

                // даём UI чуть времени "отыграть" назад
                runLater(300L) {
                    runScenarioSteps(steps, index + 1, runId)
                }
            }

            is ScenarioStep.If -> {
                val condResult = try {
                    when (step.condition) {
                        ConditionCommand.IS_SCREEN_ON -> screenOnController.isScreenOn()
                    }
                } catch (e: Exception) {
                    logScenario?.e(TAG, applicationContext.getString(R.string.scenario_if_condition_error, step.condition), e)
                    false
                }

                val msg = applicationContext.getString(R.string.scenario_step_if_result, index, step.condition, condResult)
                //logScenario?.i(TAG, msg)
                setDebug(msg)

                val chosenStep =
                    if (condResult) step.thenStep
                    else step.elseStep ?: run {
                        // ELSE нет → просто перейти к следующему шагу
                        runScenarioSteps(steps, index + 1, runId)
                        return
                    }

                // Подменяем текущий шаг выбранным then/else и выполняем
                val newSteps = steps.toMutableList()
                newSteps[index] = chosenStep

                runScenarioSteps(newSteps, index, runId)
            }

            ScenarioStep.StopScenario -> {
                val msg = applicationContext.getString(R.string.scenario_step_stop, index)
                //logScenario?.i(TAG, msg)
                setDebug(msg)

                // Обнуляем актуальный runId, чтобы все отложенные шаги (DELAY/WAIT_TEXT и т.п.) не продолжались
                scenarioManager.cancelCurrentScenario()
                return
            }

            ScenarioStep.ScreenOn -> {
                val msg = applicationContext.getString(R.string.scenario_step_screen_on, index)
                //logScenario?.i(TAG, msg)
                setDebug(msg)

                screenOnController.turnScreenOn()

                // даём системе чуть времени отработать power key
                runLater(500L) {
                    runScenarioSteps(steps, index + 1, runId)
                }
            }

            // НОВОЕ: SCREEN_OFF
            ScenarioStep.ScreenOff -> {
                //val msg = "Step[$index]: SCREEN_OFF"
                val msg = applicationContext.getString(R.string.scenario_step_screen_off, index)
                //logScenario?.i(TAG, msg)
                setDebug(msg)

                screenOnController.turnScreenOff()

                // дальнейшие шаги всё равно выполнятся — если хочешь,
                // можно STOP_SCENARIO писать следом в скрипте.
                runLater(500L) {
                    runScenarioSteps(steps, index + 1, runId)
                }
            }

            is ScenarioStep.Light -> {
                val v = step.value

                //val msg = "Step[$index]: LIGHT $v"
                val msg = applicationContext.getString(R.string.scenario_step_light, index, v)
                //logScenario?.i(TAG, msg)
                setDebug(msg)

                val enabled = (v == 1)
                screenOnController.setLight(enabled)

                // даём системе чуть времени
                runLater(200L) {
                    runScenarioSteps(steps, index + 1, runId)
                }
            }

        }
    }


    private fun extractExpandableList(resId: String, fileName: String) {
        try {
            val root = rootInActiveWindow ?: return setDebug("root null")
            val listNode = findNodeByViewId(root, resId)
                ?: return setDebug(applicationContext.getString(R.string.expandable_list_not_found, resId))

            val result = mutableListOf<ExpandableGroup>()

            var reachedBottom = false
            var lastDumpHash = ""

            repeat(50) { _ -> // максимум 50 экранов списка
                val visibleItems = collectVisibleRows(listNode)

                val hash = visibleItems.joinToString { it.text }.hashCode().toString()
                if (hash == lastDumpHash) {
                    reachedBottom = true
                    return@repeat
                }
                lastDumpHash = hash

                mergeRowsIntoGroups(visibleItems, result)

                if (!scrollListDown(listNode)) {
                    reachedBottom = true
                    return@repeat
                }

                Thread.sleep(300)
            }

            val json = toJson(result)
            fileHelper.saveJsonFile(fileName, json)
            setDebug("Saved: $fileName")

        } catch (e: Exception) {
            val msg = "extractExpandableList error"
            logScenario?.e(TAG, msg, e)
            setDebug(msg)
        }
    }


    private fun extractTableById(viewId: String, fileName: String) {
        try {
            val root = rootInActiveWindow
            if (root == null) {
                val msg = "EXTRACT_TABLE: rootInActiveWindow = null"
                setDebug(msg)
                //logScenario?.i(TAG, msg)
                return
            }

            val msg = "Extracting table for id=$viewId"
            setDebug(msg)
            //logScenario?.i(TAG, msg)

            val tableNode = findNodeByIdLike(root, viewId)
            if (tableNode == null) {
                val json = TableJsonExtractor.buildNoErrorsTableJson(
                    viewId = viewId,
                    reason = "table_not_found"
                )
                fileHelper.saveJsonFile(fileName, json)
                setDebug(applicationContext.getString(R.string.table_not_found_no_errors_written))

                return
            }

            val json = buildExpandableListJson(tableNode, viewId)
            fileHelper.saveJsonFile(fileName, json)

            setDebug(applicationContext.getString(R.string.table_saved, fileName))
        } catch (e: Exception) {
            logScenario?.e(TAG, "extractTableById error", e)
            setDebug(applicationContext.getString(R.string.extract_table_by_id_error))
        }
    }


    private fun findNodeByViewId(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        val list = root.findAccessibilityNodeInfosByViewId(id)
        return list.firstOrNull()
    }

    private fun clickButtonWithText(text: String) {
        setDebug(applicationContext.getString(R.string.click_button_with_text, text))
        try {
            val root = rootInActiveWindow ?: run {
                //logScenario?.d(TAG, "rootInActiveWindow = null, не могу кликнуть по '$text'")
                setDebug(applicationContext.getString(R.string.root_window_null_cannot_click, text))
                return
            }

            val node = findBestNodeByText(root, text)
            if (node == null) {
                //logScenario?.i(TAG, "Не найдено подходящих элементов с текстом '$text'")
                setDebug(applicationContext.getString(R.string.element_with_text_not_found, text))
                return
            }

            val success = clickNodeOrParents(node)
            //logScenario?.i(TAG, "Итог клика по '$text' -> $success")
            setDebug(
                applicationContext.getString(
                    if (success)
                        R.string.click_text_success
                    else
                        R.string.click_text_failed,
                    text
                )
            )

        } catch (e: Exception) {
            //logScenario?.e(TAG, "Ошибка в clickButtonWithText('$text')", e)
            setDebug(applicationContext.getString(R.string.click_text_error))
        }
    }


    private fun findBestNodeByText(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null

        val target = text.trim().lowercase()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        val visited = HashSet<String>()
        val matches = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()

        queue.add(root to 0)

        var checks = 0

        fun nodeId(n: AccessibilityNodeInfo): String =
            "${n.windowId}:${System.identityHashCode(n)}"

        while (queue.isNotEmpty()) {

            if (++checks > MAX_FIND_BEST_CHECKS) {
                logScenario?.e(TAG, "findBestNodeByText: HARD STOP, visited=$checks")
                break
            }

            val (node, depth) = queue.removeFirst()

            val id = nodeId(node)
            if (!visited.add(id)) continue

            val nodeText = (node.text ?: node.contentDescription)
                ?.toString()
                ?.trim()
                ?.lowercase()

            if (nodeText == target) {
                matches.add(node to depth)
            }

            val count = node.childCount
            for (i in 0 until count) {
                node.getChild(i)?.let { child ->
                    queue.add(child to (depth + 1))
                }
            }
        }

        if (matches.isEmpty()) return null

        // приоритет кнопкам
        matches
            .filter { it.first.className?.toString()?.contains("button", true) == true }
            .minByOrNull { it.second }
            ?.let { return it.first }

        // затем кликабельные
        matches
            .filter { it.first.isClickable }
            .minByOrNull { it.second }
            ?.let { return it.first }

        return matches.minByOrNull { it.second }?.first
    }

    private fun clickNodeOrParents(
        startNode: AccessibilityNodeInfo,
        maxDepth: Int = 20
    ): Boolean {
        var current: AccessibilityNodeInfo? = startNode
        var depth = 0
        val visited = mutableSetOf<Int>()

        while (current != null && depth < maxDepth) {
            val idHash = System.identityHashCode(current)
            if (!visited.add(idHash)) {
                logScenario?.w(TAG, "Cycle detected in Accessibility tree (clickNodeOrParents), aborting")
                return false
            }

            val cls = current.className
            val txt = current.text
            val clickableFlag = current.isClickable

            logScenario?.i(TAG, "Try to click: depth=$depth cls=$cls txt=$txt clickable=$clickableFlag")

            val ok = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (ok) {
                logScenario?.i(TAG, "performAction(ACTION_CLICK) success on depth=$depth cls=$cls")
                return true
            }

            current = current.parent
            depth++
        }

        logScenario?.i(TAG, "Failed to click the node or any parent (maxDepth=$maxDepth)")
        return false
    }

    // --- ОЖИДАНИЕ ТЕКСТА ---

    private fun waitForText(
        expected: String,
        timeoutMs: Long = WAIT_TEXT_TIMEOUT_MS,
        intervalMs: Long = WAIT_TEXT_INTERVAL_MS,
        runId: Int,
        onFound: () -> Unit
    ) {
        val start = System.currentTimeMillis()
        setDebug(applicationContext.getString(R.string.searching_text, expected))

        fun check() {
            // сценарий уже отменён/перезапущен?
            if (!scenarioManager.isRunActive(runId)) {
                logScenario?.i(TAG, "waitForText: runId=$runId not actual, expected='$expected'")
                return
            }

            try {
                val root = rootInActiveWindow
                if (root != null) {
                    val node = findBestNodeByText(root, expected)
                    if (node != null) {
                        //logScenario?.i(TAG, "waitForText: найден текст '$expected'")
                        setDebug(applicationContext.getString(R.string.found_expected_text, expected))
                        onFound()
                        return
                    }
                }

                val elapsed = System.currentTimeMillis() - start
                if (elapsed >= timeoutMs) {
                    //logScenario?.i(TAG, "waitForText: TIMEOUT для '$expected'")
                    setDebug(applicationContext.getString(R.string.timeout_while_searching, expected))
                    return
                }

                runLater(intervalMs) { check() }

            } catch (e: Exception) {
                //logScenario?.e(TAG, "Ошибка в waitForText('$expected')", e)
                setDebug(applicationContext.getString(R.string.search_error, expected))
            }
        }

        check()
    }

}
