package uz.hunta.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.random.Random

class BotService : AccessibilityService() {

    companion object {
        @Volatile var instance: BotService? = null
        @Volatile var running = false
        private const val IG = "com.instagram.android"
        private const val DAY = 86_400_000L
        /** Сколько Instagram может быть не на экране во время сессии, прежде чем сработает сторож. */
        private const val OFF_SCREEN_MS = 150_000L
        private const val CHANNEL = "hbot_run"
        private const val NOTIF_ID = 1
        private const val CHANNEL_ALERT = "hbot_alert"
        private const val CHANNEL_INFO = "hbot_info"
        private const val NOTIF_ALERT = 100
        const val ACTION_STOP = "uz.hunta.bot.STOP"
        const val ACTION_WAKE = "uz.hunta.bot.WAKE"
        /** Что бот делает в режиме ожидания (для главного экрана). Пусто — не ждёт. */
        @Volatile var idleText = ""
        const val DUMP_FILE = "dump.txt"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() +
        CoroutineExceptionHandler { _, e -> try { CrashHandler.record(this@BotService, "необработанный сбой в службе", e) } catch (_: Throwable) {} })
    private var job: Job? = null
    private var wl: PowerManager.WakeLock? = null
    private var wlWait: PowerManager.WakeLock? = null
    private var receiverOn = false
    private var wakeOn = false
    private var wlWake: PowerManager.WakeLock? = null
    /** Пакет Instagram, с которым бот работает сейчас (приложение A или B). */
    @Volatile private var igPkg = IG
    @Volatile private var notInList = false

    // ───────────── 2.7.1 сторож (watchdog) ─────────────
    /** Время последнего «живого» действия бота (нажатие, жест, пауза с отметкой). */
    @Volatile private var lastProgress = System.currentTimeMillis()
    /** С какого момента Instagram не на экране (0 — на экране). */
    @Volatile private var offSince = 0L
    /** Причина последнего срабатывания сторожа. */
    @Volatile private var dogReason = ""
    /** Следующий запуск Instagram делаем «с чистого листа» (после перезапуска сторожем). */
    @Volatile private var freshLaunch = false

    // ───────────── 2.7.2 библиотека экранов ─────────────
    private var popupJob: Job? = null
    /** Экран, который бот не пройдёт сам (подтверждение личности): сессия аккаунта прерывается. */
    @Volatile private var screenProblem: String? = null
    /** Появилось системное окно «Instagram остановлен»: сторож должен сразу перезапустить приложение. */
    @Volatile private var crashSeen = false
    /** Номер попытки сессии аккаунта (1–3): на последней попытке ошибки уже не ведут к перезапуску. */
    @Volatile private var attemptNo = 1
    private val itemErrs = ArrayList<Long>()
    private val fireLog = HashMap<String, MutableList<Long>>()
    private val noButtonLogAt = HashMap<String, Long>()
    private var lastUnknownAt = 0L

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            BotLog.add("Стоп нажат в уведомлении")
            stop()
        }
    }

    @Volatile private var destroyed = false

    /** Будильник Android будит телефон к концу долгого ожидания (режим глубокого ожидания, 2.7.4). */
    private val wakeReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context?, i: Intent?) {
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                try { if (wlWake?.isHeld == true) wlWake?.release() } catch (_: Throwable) {}
                wlWake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hbot:wake")
                wlWake?.acquire(3 * 60_000L)
                BotLog.add("⏰ Будильник: конец ожидания, бот просыпается")
            } catch (_: Throwable) {}
        }
    }

    override fun onServiceConnected() {
        instance = this
        Store.init(this); BotLog.init(this)
        registerStop(); registerWake()
        if (Store.migrateCatalog()) BotLog.add("Старые «доноры» перенесены в «Каталог»")
        if (Store.seedComments()) BotLog.add("Комментарии: загружены готовые наборы (100+)")
        resumeInterrupted()
        BotLog.add(
            "Сервис подключён | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | Instagram A: ${igVersion(Store.pkgA())}" +
                (if (Store.twoApps()) " | Instagram B (${Store.pkgB()}): ${igVersion(Store.pkgB())}" else "")
        )
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { BotLog.add("Система прервала сервис (onInterrupt)") }

    override fun onDestroy() {
        destroyed = true   // 2.7.3: режим остаётся записанным, после включения службы бот продолжит
        BotLog.add("Сервис остановлен системой (onDestroy)")
        instance = null
        try { if (receiverOn) unregisterReceiver(stopReceiver) } catch (_: Throwable) {}
        receiverOn = false
        try { if (wakeOn) unregisterReceiver(wakeReceiver) } catch (_: Throwable) {}
        wakeOn = false
        cancelWake()
        scope.cancel(); release()
        super.onDestroy()
    }

    private fun igVersion(pkg: String = igPkg): String = try {
        packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
    } catch (_: Throwable) { "не установлен" }

    private fun registerStop() {
        if (receiverOn) return
        try {
            val f = IntentFilter(ACTION_STOP)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(stopReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(stopReceiver, f)
            receiverOn = true
        } catch (e: Throwable) { BotLog.add("Кнопка «Стоп» в уведомлении недоступна: ${e.message}") }
    }

    private fun registerWake() {
        if (wakeOn) return
        try {
            val f = IntentFilter(ACTION_WAKE)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(wakeReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(wakeReceiver, f)
            wakeOn = true
        } catch (e: Throwable) { BotLog.add("Будильник ожидания недоступен: ${e.message}") }
    }

    private fun wakeIntent() = PendingIntent.getBroadcast(
        this, 7, Intent(ACTION_WAKE).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun scheduleWake(at: Long) {
        try {
            val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, wakeIntent())
        } catch (e: Throwable) { BotLog.add("Не смог поставить будильник: ${e.message}") }
    }

    private fun cancelWake() {
        try { (getSystemService(ALARM_SERVICE) as android.app.AlarmManager).cancel(wakeIntent()) } catch (_: Throwable) {}
    }

    // ───────────── уведомление (бот не убивается системой + кнопка СТОП) ─────────────
    @Suppress("DEPRECATION")
    private fun foreground(text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Работа бота", NotificationManager.IMPORTANCE_LOW))
            val stopIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_STOP).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val openIntent = PendingIntent.getActivity(
                this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_leongram)
                .setContentTitle("Leongram работает")
                .setContentText(text)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_delete, "СТОП", stopIntent)
                .build()
            startForeground(NOTIF_ID, n)
        } catch (e: Throwable) { BotLog.add("Не смог показать уведомление: ${e.message}") }
    }

    @Suppress("DEPRECATION")
    private fun background() {
        try { stopForeground(true) } catch (_: Throwable) {}
    }

    private fun modeTitle(mode: String) = when (mode) {
        "follow" -> "Подписки"
        "engage" -> "Активность"
        "unfollow" -> "Отписка"
        "all" -> "Все аккаунты по очереди"
        "sched" -> "Расписание"
        "browse" -> "Разнообразие (проверка)"
        "check" -> "Проверка аккаунтов"
        "vpncheck" -> "Проверка VPN"
        "auto" -> "Авто-режим"
        else -> mode
    }

    // ───────────── запуск / остановка ─────────────
    fun start(mode: String) {
        if (job?.isActive == true) { BotLog.add("Уже работает"); return }
        job = scope.launch {
            running = true; acquire(); foreground(modeTitle(mode))
            startPopupWatcher()
            if (mode == "all" || mode == "sched") { Store.put("runMode", mode); Store.put("runModeAt", System.currentTimeMillis().toString()) }
            BotLog.add("▶ Старт: ${modeTitle(mode)}")
            BotLog.add("  Разрешения: ${Perms.short(this@BotService)}")
            if (Perms.miuiPopup(this@BotService) == false) {
                BotLog.add("  ⚠ Выключено «Показывать всплывающие окна, когда приложение работает в фоне»: бот не сможет открыть Instagram. Настройки → Приложения → Leongram → Другие разрешения")
                alert(4109, "⚠ Instagram не откроется", "В MIUI выключено «Показывать всплывающие окна, когда приложение работает в фоне». Включи: Настройки → Приложения → Leongram → Другие разрешения. Подробнее: Настройки → Проверка разрешений.", true)
            }
            if (Store.dry()) BotLog.add("🧪 СУХОЙ ЗАПУСК: бот ничего не нажимает в аккаунтах (подписки, лайки, комментарии, отписки, репосты, истории) и не копит статистику. Выключается полем «Сухой запуск» = 0")
            try {
                igPkg = Store.pkgFor(Store.acc())
                if (mode in setOf("follow", "engage", "unfollow", "auto")) {
                    val until = Store.pausedUntil(Store.acc())
                    if (until > System.currentTimeMillis()) {
                        BotLog.add("@${Store.acc()} на паузе после блокировки до ${Schedule.dm(until)} — запуск отменён")
                        return@launch
                    }
                    if (!preflight()) return@launch
                }
                when (mode) {
                    "follow" -> follow()
                    "engage" -> engage()
                    "unfollow" -> unfollow()
                    "all" -> supervised("Все аккаунты") { runAll() }
                    "sched" -> supervised("Расписание") { runSchedule() }
                    "browse" -> browseBreak(force = true)
                    "check" -> checkAccounts()
                    "vpncheck" -> vpnCheck()
                    "auto" -> {
                        browseBreak(); follow(); browseBreak(); engage()
                        if (System.currentTimeMillis() - Store.lastUnfollow() > 7 * DAY) unfollow()
                        sendSummary()
                    }
                }
                BotLog.add("Готово")
            } catch (e: CancellationException) {
                BotLog.add("Остановлено")
            } catch (e: ActionBlocked) {
                blocked(Store.acc())
            } catch (e: Throwable) {
                BotLog.add("Ошибка: ${e.message ?: e.javaClass.simpleName}")
                CrashHandler.record(this@BotService, "ошибка в работе бота", e)
            } finally { running = false; Store.cur = null; popupJob?.cancel(); release(); background()
                if (!destroyed) Store.remove("runMode") }
        }
    }

    @Volatile private var resumeJob: Job? = null

    /** Остановка. Отменяет и работу, и отложенное автопродолжение: после неё бот сам не стартует. */
    fun stop() { resumeJob?.cancel(); Store.remove("runMode"); job?.cancel() }

    /** 2.7.3: если «ВСЕ АККАУНТЫ» / «РАСПИСАНИЕ» оборвались (сервис убила система, процесс упал), продолжаем сами. */
    private fun resumeInterrupted() {
        val rm = Store.s("runMode")
        if (rm.isEmpty()) return
        val at = Store.s("runModeAt", "0").toLongOrNull() ?: 0L
        when {
            Store.s("autoResume", "1") != "1" -> Store.remove("runMode")
            System.currentTimeMillis() - at > 12 * 3_600_000L -> {
                BotLog.add("Прошлый запуск (${modeTitle(rm)}) оборвался больше 12 часов назад — сам не продолжаю")
                Store.remove("runMode")
            }
            else -> {
                BotLog.add("🔁 Прошлый запуск «${modeTitle(rm)}» оборвался (служба или процесс были остановлены). Продолжу через 20 сек, сделанное сегодня повторять не буду")
                resumeJob = scope.launch {
                    delay(20_000)
                    // после включения телефона даём время сети / VPN и ждём, пока экран разблокирован
                    if (android.os.SystemClock.elapsedRealtime() < 5 * 60_000L) {
                        BotLog.add("🔁 Телефон только что включился — жду 90 сек (сеть, VPN)")
                        delay(90_000)
                    }
                    val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                    var waited = 0
                    while (km.isKeyguardLocked && waited < 40) {
                        if (waited == 0) BotLog.add("🔁 Экран заблокирован — жду разблокировки (в настройках лучше «Нет» или «Провести пальцем»)")
                        delay(15_000); waited++
                    }
                    if (!Schedule.inWindow() && rm == "all") { BotLog.add("🔁 Сейчас вне рабочего окна — не продолжаю"); Store.remove("runMode"); return@launch }
                    start(rm)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun acquire() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "hbot:run")
            wl?.acquire(12 * 3600_000L)
        } catch (e: Throwable) { BotLog.add("Не смог удержать экран включённым: ${e.message}") }
    }
    private fun release() {
        try { if (wl?.isHeld == true) wl?.release() } catch (_: Throwable) {}
        try { if (wlWait?.isHeld == true) wlWait?.release() } catch (_: Throwable) {}
    }

    /** 2.7.4: цикл закончен. Уведомление + строка в журнале; если задано питание телефона, говорим, когда он выключится и включится. */
    private fun cycleDone(next: String) {
        val po = Schedule.nextPowerOff(System.currentTimeMillis())
        val back = po?.let { Schedule.powerBackAt(it) }
        val power = if (po != null)
            " Телефон по расписанию выключится в ${Schedule.hm(po)}" + (if (back != null) " и включится в ${Schedule.hm(back)}" else "") + ". Бот продолжит сам после включения."
        else ""
        BotLog.add("✅ Цикл завершён. $next$power")
        alert(4104, "✅ Все задачи цикла выполнены", "$next$power", false)
    }

    // ───────────── ДИАГНОСТИКА: снимок экрана Instagram ─────────────
    fun dumpScreen() {
        scope.launch {
            try {
                BotLog.add("Диагностика: открой нужный экран Instagram, снимок через 10 сек")
                delay(10_000)
                val sb = buildDump("Снимок экрана")
                File(filesDir, DUMP_FILE).writeText(sb)
                BotLog.add("Снимок экрана сохранён (${sb.length} символов). Открой Leongram → «Показать снимок»")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                BotLog.add("Снимок не получился: ${e.message ?: e.javaClass.simpleName}")
                CrashHandler.record(this@BotService, "снимок экрана", e)
            }
        }
    }

    /** Текстовый снимок дерева элементов текущего экрана (то, что бот «видит»). */
    private fun buildDump(title: String): String {
        val sb = StringBuilder()
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        sb.append("$title $ts\n")
        sb.append("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Instagram: ${igVersion()}\n")
        try {
            val ws = windows
            sb.append("Окон: ${ws.size}\n")
            for (w in ws) sb.append("  окно type=${w.type} layer=${w.layer} active=${w.isActive} focused=${w.isFocused} title=${w.title}\n")
        } catch (e: Throwable) { sb.append("Окна недоступны: ${e.message}\n") }
        val root = rootInActiveWindow
        if (root == null) {
            sb.append("\nrootInActiveWindow = null (Instagram не на экране или не отдаёт элементы)\n")
        } else {
            sb.append("\nПакет: ${root.packageName}\n")
            val counter = IntArray(1)
            dumpNode(root, 0, sb, counter)
            sb.append("\nВсего элементов: ${counter[0]}\n")
        }
        return sb.toString()
    }

    private fun dumpNode(n: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder, counter: IntArray) {
        if (n == null || depth > 40 || counter[0] >= 1500) return
        counter[0]++
        val r = Rect(); n.getBoundsInScreen(r)
        val flags = (if (n.isClickable) "C" else "") + (if (n.isLongClickable) "L" else "") +
            (if (n.isScrollable) "S" else "") + (if (n.isEditable) "E" else "") + (if (n.isSelected) "*" else "")
        sb.append("  ".repeat(minOf(depth, 20)))
            .append(n.className?.toString()?.substringAfterLast('.') ?: "?")
        n.viewIdResourceName?.let { sb.append(" id=").append(it.substringAfter(":id/")) }
        if (rawTxt(n).isNotEmpty()) sb.append(" t='").append(rawTxt(n).replace("\n", " ").take(80)).append("'")
        if (rawDesc(n).isNotEmpty()) sb.append(" d='").append(rawDesc(n).replace("\n", " ").take(80)).append("'")
        if (flags.isNotEmpty()) sb.append(" [").append(flags).append("]")
        sb.append(" ").append(r.toShortString()).append("\n")
        for (i in 0 until n.childCount) dumpNode(n.getChild(i), depth + 1, sb, counter)
    }

    /** Короткое описание того, что бот сейчас видит на экране (для журнала). */
    private fun visibleSummary(): String {
        if (rootInActiveWindow == null) return "экран Instagram недоступен"
        val items = all { rawTxt(it).isNotEmpty() || rawDesc(it).isNotEmpty() }
            .take(25).map { (rawTxt(it).ifEmpty { rawDesc(it) }).replace("\n", " ").take(30) }
        return if (items.isEmpty()) "ни одного подписанного элемента" else items.joinToString(" | ")
    }

    // ───────────── ОДИН АККАУНТ: переключиться и выполнить авто-режим ─────────────
    /**
     * Автопауза при блокировке: длительность берётся из настройки «Пауза после блокировки, часов».
     * Если предыдущая блокировка была меньше суток назад, пауза удваивается (максимум 72 часа).
     */
    private fun blocked(a: String) {
        val base = Store.i("pauseHours", 6).coerceIn(1, 72)
        val repeat = System.currentTimeMillis() - Store.lastBlock(a) < DAY
        val hours = if (repeat) minOf(72, base * 2) else base
        Store.pause(a, hours * 3_600_000L)
        val until = Schedule.dm(Store.pausedUntil(a))
        BotLog.add("⛔ @$a: Instagram ограничил действия. Пауза $hours ч (до $until)" +
            (if (repeat) ", повторная блокировка — пауза удвоена" else "") + ", потом 3 дня лимиты ×0.5")
        notifyBlock(a, hours, until)
    }

    private fun notifyBlock(a: String, hours: Int, until: String) =
        alert(a.hashCode() and 0xFFF, "⛔ @$a: Instagram ограничил действия",
            "Автопауза $hours ч, до $until. Аккаунт @$a пропускается, остальные работают. После паузы 3 дня лимиты ×0.5.", true)

    /** Обычное уведомление (не «живое» уведомление работы). high — со звуком и всплытием. */
    private fun alert(id: Int, title: String, text: String, high: Boolean = true) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = if (high) CHANNEL_ALERT else CHANNEL_INFO
            nm.createNotificationChannel(
                if (high) NotificationChannel(ch, "Блокировки и ошибки", NotificationManager.IMPORTANCE_HIGH)
                else NotificationChannel(ch, "Итоги дня", NotificationManager.IMPORTANCE_LOW)
            )
            val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, ch)
                .setSmallIcon(R.drawable.ic_stat_leongram)
                .setContentTitle(title)
                .setContentText(text.lines().first())
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ALERT + id, n)
        } catch (e: Throwable) { BotLog.add("Не смог показать уведомление: ${e.message}") }
    }

    /** Итоги дня одним уведомлением: по каждому аккаунту подписки, лайки, отписки, комментарии. */
    private fun sendSummary() {
        if (Store.s("dailySummary", "1") != "1") return
        val lines = (Store.accounts() + Store.acc()).distinct().mapNotNull { a ->
            val t = Stats.forDay(a, 0)
            if (t.sum() == 0) null else "@$a: подписок ${t[0]}, лайков ${t[1]}, отписок ${t[2]}, комм. ${t[3]}"
        }
        alert(4101, "📊 Итоги дня", if (lines.isEmpty()) "Сегодня действий не было" else lines.joinToString("\n"), false)
    }

    /** Проверка перед запуском: Instagram установлен, открывается и видны кнопки нижнего меню. */
    private suspend fun preflight(quiet: Boolean = false): Boolean {
        BotLog.add("Проверка перед запуском…")
        var problem: String? = null
        if (igVersion() == "не установлен") problem = "Instagram не установлен (пакет $igPkg)"
        else if (!openInstagram()) problem = "Instagram не открылся. Проверь: экран разблокирован, в MIUI включены «всплывающие окна в фоне»"
        else {
            delay(rnd(1500, 2500))
            var tries = 0
            while ((tabByDesc("Home") == null || profileTab() == null) && tries < 4) {
                tries++
                if (dismissPopups()) { delay(rnd(1000, 1600)); continue }   // 2.7.2: сначала закрываем всплывающее окно
                if (!safeBack(1000)) break
                if (!igOnScreen()) {   // «Назад» вывела из Instagram на рабочий стол: открываем его заново, а не сдаёмся
                    BotLog.add("  Instagram закрылся от «Назад» — открываю заново")
                    if (!openInstagram()) break
                    delay(rnd(1500, 2500))
                }
            }
            if (tabByDesc("Home") == null || profileTab() == null)
                problem = "не нашёл кнопки меню Instagram (Home / Profile). Возможные причины: язык не английский, открыт другой экран или Instagram обновился. Сделай «Снимок экрана Instagram»"
        }
        if (problem != null) {
            BotLog.add("❌ Проверка не пройдена: $problem")
            BotLog.add("  На экране: ${visibleSummary()}")
            noteUnknown("проверка перед запуском: $problem")
            if (!quiet) alert(4100, "Бот не запустился", problem)
            return false
        }
        BotLog.add("  проверка пройдена")
        return true
    }

    /** true — аккаунт на сегодня сделан (его можно отмечать «сделано»). false — пауза, сбой или пропуск по ошибке. */
    private suspend fun runAccountSession(a: String, allowSkip: Boolean = false, goldenOnly: Boolean = false): Boolean {
        val until = Store.pausedUntil(a)
        if (until > System.currentTimeMillis()) {
            BotLog.add("  @$a на паузе после блокировки до ${Schedule.dm(until)} — пропуск")
            return false
        }
        if (Schedule.nearPowerOff()) {
            BotLog.add("  @$a: скоро выключение телефона по расписанию (${Schedule.hm(Schedule.nextPowerOff(System.currentTimeMillis()) ?: 0L)}) — новый аккаунт не начинаю, продолжу после включения")
            return false
        }
        if (allowSkip && skipToday(a)) {
            BotLog.add("  @$a: сегодня выходной (случайный пропуск дня) — как у живого человека")
            return true
        }
        // 2.2: правильный сервер Happ под приложение аккаунта; без него (или при одинаковом IP у A и B) аккаунт не стартует
        if (!ensureVpnFor(Store.appOf(a))) { BotLog.add("  @$a: VPN не готов — аккаунт пропущен, вернусь к нему позже"); return false }
        // 2.7.1: если бот завис, сторож перезапускает Instagram, и сессия аккаунта идёт заново.
        // Всё, что уже сделано, хранится в Store (подписки за день, доноры, список подписанных), поэтому повтор продолжает, а не начинает с нуля.
        val maxTries = 3
        // 2.7.5: любая ошибка ведёт к действию: повтор с перезапуском Instagram, а после трёх попыток пропуск аккаунта.
        for (attempt in 1..maxTries) {
            attemptNo = attempt
            var ok = false
            val finished = try {
                guarded { ok = sessionBody(a, goldenOnly) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ActionBlocked) {
                blocked(a); return false
            } catch (e: Throwable) {
                dogReason = if (e is NeedRestart) (e.message ?: "нужен перезапуск") else "сбой: ${e.javaClass.simpleName}: ${e.message}"
                if (e !is NeedRestart) CrashHandler.record(this, "сбой в сессии @$a", e)
                false
            }
            if (finished) return ok
            val why = dogReason
            restartInstagram(why)
            if (attempt < maxTries) {
                BotLog.add("  🔁 повтор для @$a: попытка ${attempt + 1} из $maxTries")
                delay(rnd(4_000, 7_000))
            } else {
                BotLog.add("  ⏭ @$a: $maxTries попытки с перезапуском Instagram не помогли — аккаунт пропущен, иду дальше (причина: $why)")
                alert(4103, "@$a пропущен после $maxTries попыток", "Бот $maxTries раза перезапускал Instagram и начинал аккаунт заново. Последняя причина: $why. Остальные аккаунты продолжают работу. Подробности: «Показать снимок» и «Библиотека экранов → Неизвестные экраны».")
            }
        }
        return false
    }

    /** Одна сессия аккаунта: лимиты, запуск Instagram, переключение, подписки / активность / отписка. */
    private suspend fun sessionBody(a: String, goldenOnly: Boolean = false): Boolean {
        screenProblem = null
        return try {
            sessionCore(a, goldenOnly)
        } catch (e: ScreenProblem) {
            problemAccount(a, e.message ?: "неизвестный экран")
            false
        }
    }

    private suspend fun sessionCore(a: String, goldenOnly: Boolean = false): Boolean {
        // 2.7.3: всё, что аккаунт уже сделал сегодня, помнится и после перезапуска
        if (goldenOnly && !Store.goldenDue(a)) {
            BotLog.add("  @$a: золотые профили на сегодня уже пройдены")
            return true
        }
        if (!goldenOnly && Store.phaseDone(a, "f") && Store.phaseDone(a, "e") && Store.phaseDone(a, "n") && !unfollowDue() && !Store.goldenDue(a)) {
            BotLog.add("  @$a: сегодня уже всё сделано (подписки и активность) — пропуск")
            return true
        }
        if (!goldenOnly && (Store.phaseDone(a, "f") || Store.phaseDone(a, "e") || Store.phaseDone(a, "n")))
            BotLog.add("  @$a: продолжаю с места остановки (сегодня готово: ${Store.phasesToday(a).ifEmpty { "ничего" }})")
        BotLog.add("  лимиты: ${Limits.describe(a)}")
        igPkg = Store.pkgFor(a)
        if (Store.twoApps()) BotLog.add("  приложение ${Store.appOf(a)} ($igPkg)")
        if (!preflight(quiet = attemptNo < 3)) {
            if (igVersion() != "не установлен" && attemptNo < 3) throw NeedRestart("проверка перед запуском не пройдена")
            return false
        }
        if (!switchAccount(a)) {
            checkScreenProblem()
            if (!notInList && attemptNo < 3) throw NeedRestart("не удалось переключиться на @$a: $switchError")
            BotLog.add("Не смог переключиться на @$a — пропуск. Причина: $switchError")
            alert(4102, "Не смог переключиться на @$a", "Аккаунт пропущен. Причина: $switchError. Проверь список аккаунтов (кнопка «Проверить аккаунты»), снимок экрана сохранён.")
            return false
        }
        try {
            // 2.8: золотые профили всегда первыми, и только потом общие задачи
            if (Store.goldenDue(a)) { optional("золотые профили") { goldenRound(a) }; Store.addGoldVisit(a) }
            if (goldenOnly) return true
            if (!Store.phaseDone(a, "n")) { optional("новые подписчики") { followersRound(a) }; Store.markPhase(a, "n") }   // новые подписчики этого аккаунта
            if (!Store.phaseDone(a, "f")) { browseBreak(); follow(); Store.markPhase(a, "f") }
            if (!Store.phaseDone(a, "e")) { browseBreak(); engage(); Store.markPhase(a, "e") }
            if (unfollowDue()) unfollow()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: ActionBlocked) {
            blocked(a)
        } catch (e: ScreenProblem) {
            throw e
        } catch (e: NeedRestart) {
            throw e
        } catch (e: Exception) {
            BotLog.add("@$a: ${e.message} — перехожу к следующему")
        }
        return false
    }

    private fun unfollowDue(): Boolean = System.currentTimeMillis() - Store.lastUnfollow() > 7 * DAY

    // ───────────── 2.7.1 СТОРОЖ: зависание → закрыть Instagram и запустить заново ─────────────
    private fun touch() { lastProgress = System.currentTimeMillis() }

    /** Лимит «нет действий» из настроек, мс. 0 — сторож выключен. Меньше 4 минут не бывает: есть законные долгие паузы. */
    private fun watchdogMs(): Long {
        val m = Store.i("watchdogMin", 8)
        return if (m <= 0) 0L else m.coerceAtLeast(4) * 60_000L
    }

    /** Пауза кусками по 20 сек: после каждого куска сторож видит, что бот жив. */
    private suspend fun patientDelay(ms: Long) {
        var left = if (Store.dry()) ms / 10 else ms   // в сухом запуске паузы в 10 раз короче
        while (left > 0) {
            val d = minOf(left, 20_000L)
            delay(d)
            left -= d
            touch()
        }
    }

    /**
     * Выполняет блок под присмотром. true — блок завершился сам (или сторож выключен).
     * false — сторож сработал: действий не было дольше лимита либо Instagram слишком долго не на экране;
     * блок отменён, причина лежит в dogReason.
     */
    private suspend fun guarded(block: suspend () -> Unit): Boolean {
        val limit = watchdogMs()
        if (limit == 0L) { block(); return true }
        touch(); offSince = 0L; dogReason = ""; crashSeen = false
        coroutineScope<Unit> {
            val work = async { block() }
            val dog = launch {
                while (true) {
                    delay(15_000)
                    val now = System.currentTimeMillis()
                    if (igOnScreen()) offSince = 0L else if (offSince == 0L) offSince = now
                    val idle = now - lastProgress
                    val off = if (offSince == 0L) 0L else now - offSince
                    val why = when {
                        crashSeen -> "Instagram упал (системное окно «остановлен»)"
                        idle > limit -> "нет действий ${idle / 60_000} мин"
                        off > OFF_SCREEN_MS -> "Instagram не на экране ${off / 1000} сек"
                        else -> ""
                    }
                    if (why.isNotEmpty()) {
                        dogReason = why
                        BotLog.add("  🐕 сторож сработал: $why")
                        work.cancel(CancellationException("watchdog"))
                        break
                    }
                }
            }
            try {
                work.await()
            } catch (e: CancellationException) {
                if (dogReason.isEmpty()) throw e   // остановили не мы (кнопка СТОП) — пропускаем дальше
            } finally {
                dog.cancel()
            }
            Unit
        }
        return dogReason.isEmpty()
    }

    /** Ищет на экране (любого приложения) кнопку с одним из текстов. */
    private fun anyButton(words: List<String>): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || found != null || depth > 40) return
            val t = txt(n).trim().lowercase(); val d = desc(n).trim().lowercase()
            if (n.isEnabled && (t in words || d in words)) { found = n; return }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(rootInActiveWindow, 0)
        return found
    }

    /**
     * Без root закрыть приложение можно только через «О приложении» → «Остановить».
     * Это best-effort: кнопка зовётся по-разному на разных прошивках, поэтому ищем несколько вариантов.
     * Возвращает true, если кнопку нашли и нажали.
     */
    private suspend fun forceStop(pkg: String): Boolean {
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            delay(2_500)
            val stopWords = listOf("force stop", "force close", "принудительно остановить", "остановить", "завершить", "закрыть")
            val btn = anyButton(stopWords) ?: return false
            click(btn)
            delay(1_500)
            val okWords = listOf("ok", "ок", "force stop", "принудительно остановить", "остановить", "завершить")
            anyButton(okWords)?.let { click(it); delay(1_500) }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            BotLog.add("  не смог открыть «О приложении»: ${e.message}")
            return false
        }
    }

    /** Закрывает Instagram и готовит чистый запуск: сохраняет снимок экрана, жмёт «Остановить», уходит на главный экран. */
    private suspend fun restartInstagram(reason: String) {
        BotLog.add("  🔄 перезапуск Instagram ($igPkg): $reason")
        try {
            File(filesDir, DUMP_FILE).writeText(buildDump("Сторож: $reason\n"))
            BotLog.add("  снимок экрана на момент зависания сохранён: Leongram → «Показать снимок»")
        } catch (e: Throwable) { BotLog.add("  снимок не получился: ${e.message}") }
        val stopped = forceStop(igPkg)
        BotLog.add(if (stopped) "  приложение остановлено через «О приложении»" else "  кнопку «Остановить» не нашёл — запущу Instagram заново с чистого экрана")
        performGlobalAction(GLOBAL_ACTION_HOME)
        delay(rnd(1_500, 2_500))
        freshLaunch = true
        offSince = 0L
        touch()
    }

    // ───────────── 2.7.2 БИБЛИОТЕКА ЭКРАНОВ: всплывающие окна закрываются сами ─────────────
    /** Каждые 3 секунды, пока бот работает, смотрит на экран и закрывает окна из библиотеки (Screens). */
    private fun startPopupWatcher() {
        popupJob?.cancel()
        popupJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(4_000)
                try {
                    // системные окна смотрим реже (каждый третий раз): обход дерева экрана нагружает Instagram и замедляет бота
                    tick++
                    if (Screens.libraryOn()) handleScreens(withSystem = tick % 3 == 0)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    BotLog.add("  🧩 библиотека экранов: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    /** Разовая проверка (бот зовёт её, когда не видит нужных кнопок). true — окно найдено и обработано. */
    private suspend fun dismissPopups(): Boolean = try {
        Screens.libraryOn() && handleScreens(withSystem = true)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) { false }

    private fun isIgPkg(p: String): Boolean =
        p == igPkg || p == Store.pkgA() || (Store.twoApps() && p == Store.pkgB())

    /** Корни всех окон на экране, кроме самого Leongram: окно Instagram и системные диалоги. */
    private fun screenRoots(withSystem: Boolean = true): List<Pair<String, AccessibilityNodeInfo>> {
        val out = ArrayList<Pair<String, AccessibilityNodeInfo>>()
        val seen = HashSet<String>()
        fun add(r: AccessibilityNodeInfo?) {
            val pkg = r?.packageName?.toString() ?: return
            if (pkg == packageName) return
            if (seen.add(pkg + ":" + r.windowId)) out.add(pkg to r)
        }
        add(rootInActiveWindow)
        if (withSystem) try { for (w in windows) add(w.root) } catch (_: Throwable) {}
        return out
    }

    private fun collectScreen(n: AccessibilityNodeInfo?, depth: Int, texts: MutableList<String>, nodes: MutableList<AccessibilityNodeInfo>) {
        if (n == null || depth > 40 || nodes.size >= 300) return
        nodes.add(n)
        val t = rawTxt(n).replace("\n", " ").trim(); if (t.isNotEmpty()) texts.add(t)
        val d = rawDesc(n).replace("\n", " ").trim(); if (d.isNotEmpty()) texts.add(d)
        for (i in 0 until n.childCount) collectScreen(n.getChild(i), depth + 1, texts, nodes)
    }

    private suspend fun handleScreens(withSystem: Boolean = true): Boolean {
        for ((pkg, root) in screenRoots(withSystem)) {
            val isIg = isIgPkg(pkg)
            val texts = ArrayList<String>(); val nodes = ArrayList<AccessibilityNodeInfo>()
            collectScreen(root, 0, texts, nodes)
            if (texts.isEmpty()) continue
            val joined = texts.joinToString("\n") { Screens.norm(it) }
            for (r in Screens.rules()) {
                if (!Screens.enabled(r.id)) continue
                if (r.system == isIg) continue
                if (r.triggers.none { joined.contains(it) }) continue
                return applyScreenRule(r, pkg, nodes)
            }
        }
        return false
    }

    /** Один и тот же экран не должен «долбиться» бесконечно: 5 срабатываний за минуту — пробуем «Назад». */
    private fun tooOften(id: String): Boolean {
        val now = System.currentTimeMillis()
        val l = fireLog.getOrPut(id) { ArrayList() }
        l.removeAll { now - it > 60_000L }
        l.add(now)
        return l.size >= 5
    }

    private suspend fun applyScreenRule(r: Screens.Rule, pkg: String, nodes: List<AccessibilityNodeInfo>): Boolean {
        if (r.act == Screens.Act.PROBLEM) {
            if (screenProblem == null) {
                screenProblem = r.title
                Screens.hit(r.id)
                BotLog.add("  🧩 экран «${r.title}» — этот аккаунт пропускаю, остальные работают")
                noteUnknown("экран «${r.title}»", "Экран «${r.title}»\n")
            }
            return true
        }
        if (tooOften(r.id)) {
            fireLog[r.id]?.clear()
            BotLog.add("  🧩 экран «${r.title}» не закрывается кнопкой — жму «Назад»")
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(1_000)
            return true
        }
        for (b in r.buttons) {
            if (b == Screens.BACK) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                afterScreenRule(r, "Назад")
                delay(800)
                return true
            }
            val node = nodes.firstOrNull { n ->
                n.isEnabled && (Screens.norm(rawTxt(n)).trimEnd('.', '!') == b || Screens.norm(rawDesc(n)).trimEnd('.', '!') == b)
            } ?: continue
            if (click(node) || tapNode(node)) {
                afterScreenRule(r, rawTxt(node).ifEmpty { rawDesc(node) })
                delay(800)
                return true
            }
        }
        // окно из библиотеки видно, а кнопки нет (другой язык, другая версия) — запишем, чтобы добавить правило
        val now = System.currentTimeMillis()
        if (now - (noButtonLogAt[r.id] ?: 0L) > 600_000L) {
            noButtonLogAt[r.id] = now
            val btns = nodes.filter { it.isClickable && (rawTxt(it).isNotEmpty() || rawDesc(it).isNotEmpty()) }
                .map { (rawTxt(it).ifEmpty { rawDesc(it) }).replace("\n", " ").take(30) }.distinct().take(10)
            BotLog.add("  🧩 вижу экран «${r.title}», но кнопки из правила нет. Кнопки на экране: ${btns.joinToString(" | ").ifEmpty { "(нет)" }}")
            val texts = ArrayList<String>()
            nodes.forEach { n -> rawTxt(n).trim().takeIf { it.isNotEmpty() }?.let { texts.add(it) } }
            Screens.noteUnknown(this, "экран «${r.title}» без нужной кнопки", pkg, texts.distinct().take(25).map { it.take(30) }, btns)
        }
        return false
    }

    private fun afterScreenRule(r: Screens.Rule, button: String) {
        Screens.hit(r.id)
        BotLog.add("  🧩 экран «${r.title}»: нажал «$button»")
        if (r.act == Screens.Act.CRASH) {
            crashSeen = true
            freshLaunch = true
            BotLog.add("  Instagram упал — сторож перезапустит его")
        }
    }

    /** Запасной путь, если у кнопки нет кликабельного родителя: нажать в центр её рамки. */
    private suspend fun tapNode(n: AccessibilityNodeInfo): Boolean {
        val r = Rect(); n.getBoundsInScreen(r)
        if (r.isEmpty) return false
        gesture(Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }, 60)
        return true
    }

    /** Экран, который бот не узнал: тексты и кнопки идут в журнал «Неизвестные экраны», полный снимок — в «Показать снимок». */
    private fun noteUnknown(reason: String, dumpTitle: String = reason + "\n") {
        val now = System.currentTimeMillis()
        if (now - lastUnknownAt < 30_000L) return
        lastUnknownAt = now
        try {
            val root = rootInActiveWindow
            val pkg = root?.packageName?.toString() ?: "?"
            val texts = ArrayList<String>(); val nodes = ArrayList<AccessibilityNodeInfo>()
            collectScreen(root, 0, texts, nodes)
            val btns = nodes.filter { it.isClickable && (rawTxt(it).isNotEmpty() || rawDesc(it).isNotEmpty()) }
                .map { (rawTxt(it).ifEmpty { rawDesc(it) }).replace("\n", " ").take(30) }.distinct().take(10)
            Screens.noteUnknown(this, reason, pkg, texts.distinct().take(25).map { it.take(30) }, btns)
            File(filesDir, DUMP_FILE).writeText(buildDump(dumpTitle))
            BotLog.add("  🧩 экран записан: Библиотека экранов → «Неизвестные экраны», полный снимок — «Показать снимок»")
        } catch (e: Throwable) { BotLog.add("  снимок не получился: ${e.message}") }
    }

    /** Аккаунт упёрся в экран, который бот не решает сам: пауза на 12 часов (без записи в блокировки) и уведомление. */
    private fun problemAccount(a: String, why: String) {
        screenProblem = null
        Store.pauseOnly(a, 12 * 3_600_000L)
        val until = Schedule.dm(Store.pausedUntil(a))
        BotLog.add("⚠ @$a: экран «$why». Пауза 12 ч (до $until), остальные аккаунты работают")
        alert(a.hashCode() and 0xFFF, "⚠ @$a: нужно открыть вручную",
            "Instagram показал экран «$why». Бот его не проходит. Аккаунт на паузе до $until. Открой @$a сам и подтверди вход, потом сними паузу («Паузы после блокировок»).", true)
    }

    // ───────────── 2.2 VPN: сервер Happ под приложение A / B ─────────────
    private suspend fun currentIp(): String? = withContext(Dispatchers.IO) { Vpn.fetchIp() }

    private fun happConnected(): Boolean =
        first { val t = Screens.norm(txt(it)); t == "подключен" || t == "connected" } != null

    private fun happStatusNode(): AccessibilityNodeInfo? =
        first { val t = Screens.norm(txt(it)); t == "подключен" || t == "connected" || t == "отключен" || t == "disconnected" }

    /** Нажимает кнопку питания в Happ (текст статуса внутри круглой кнопки). */
    private suspend fun happToggle(): Boolean {
        val st = happStatusNode() ?: return false
        return click(st) || tapNode(st)
    }

    /** Открывает Happ, выбирает сервер [target], при необходимости включает VPN. true — Happ показывает «подключен». */
    private suspend fun switchHapp(target: String, oldIp: String?): Boolean {
        val pkg = Vpn.happPkg()
        val li = packageManager.getLaunchIntentForPackage(pkg)
        if (li == null) { BotLog.add("🌐 Happ не найден (пакет $pkg). Укажи пакет в настройках «Пакет Happ»"); return false }
        val saved = igPkg
        igPkg = pkg   // поиск узлов работает только по «выбранному» приложению: на время переключения это Happ
        try {
            touch()
            startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            delay(rnd(3000, 4500))
            var found: AccessibilityNodeInfo? = null
            for (i in 0..5) {
                dismissPopups()
                found = first { txt(it).equals(target, true) || desc(it).equals(target, true) }
                    ?: first { txt(it).contains(target, true) && !txt(it).contains("VLESS", true) }
                if (found != null) break
                swipeUp(); delay(rnd(1000, 1800))
            }
            if (found == null) {
                BotLog.add("🌐 В Happ не вижу сервер «$target». На экране: ${visibleSummary()}")
                return false
            }
            click(found) || tapNode(found)
            delay(rnd(4000, 6000))
            dismissPopups()
            if (!happConnected()) {
                BotLog.add("🌐 Happ не подключён — нажимаю кнопку питания")
                happToggle()
                delay(rnd(4000, 6000)); dismissPopups()
            } else if (oldIp != null && currentIp() == oldIp) {
                // сервер выбран, но IP тот же: переподключаем
                BotLog.add("🌐 IP не сменился — переподключаю Happ")
                happToggle(); delay(rnd(3000, 4500))
                happToggle(); delay(rnd(4000, 6000)); dismissPopups()
            }
            for (i in 0 until 8) { if (happConnected()) return true; delay(2500) }
            BotLog.add("🌐 Happ не показывает «подключен». На экране: ${visibleSummary()}")
            return false
        } finally { igPkg = saved }
    }

    /** Ждёт, пока интернет заработает через VPN, и возвращает внешний IP. */
    private suspend fun waitIp(): String? {
        repeat(8) { currentIp()?.let { return it }; delay(3000) }
        return null
    }

    /**
     * Перед аккаунтом нужного приложения ставит правильный сервер Happ. Если приложение A и приложение B вышли с одного IP, не стартует.
     * true — можно работать. Если для приложения серверы не заданы, VPN считается ручным и бот его не трогает.
     */
    private suspend fun ensureVpnFor(app: String): Boolean {
        if (!Vpn.on()) return true
        if (Store.dry()) { BotLog.add("  🧪 сухой запуск: VPN не переключаю"); return true }
        val target = Vpn.pick(app) ?: return true
        val ip0 = currentIp()
        if (Store.s("vpnCur") == target && ip0 != null && Store.s("vpnCurIp") == ip0) {
            Vpn.rememberIp(app, ip0)
            return crossCheckIp(app, ip0)
        }
        BotLog.add("🌐 VPN: для приложения $app нужен сервер «$target»")
        for (attempt in 1..3) {
            if (switchHapp(target, ip0)) {
                val ip = waitIp()
                if (ip != null) {
                    Store.put("vpnCur", target); Store.put("vpnCurIp", ip)
                    Vpn.rememberIp(app, ip)
                    BotLog.add("🌐 VPN: сервер «$target», внешний IP $ip" + if (ip0 != null && ip == ip0) " (тот же, что был)" else "")
                    return crossCheckIp(app, ip)
                }
                BotLog.add("🌐 VPN подключён, но интернета нет (попытка $attempt из 3)")
            } else BotLog.add("🌐 Не удалось переключить Happ (попытка $attempt из 3)")
            delay(rnd(3000, 6000))
        }
        alert(4106, "🌐 VPN не переключился", "Бот не смог выбрать сервер «$target» в Happ для приложения $app. Аккаунты без правильного VPN бот не запускает. Подробности в журнале.", true)
        return false
    }

    private fun crossCheckIp(app: String, ip: String): Boolean {
        if (!Store.twoApps()) return true
        val other = if (app == "A") "B" else "A"
        if (Vpn.ipOf(other) == ip) {
            BotLog.add("🌐 ⛔ Приложения A и B вышли с одного IP ($ip): не запускаю. Выбери разные серверы в настройках VPN")
            alert(4107, "🌐 Один IP у приложений A и B", "Оба приложения вышли в сеть с IP $ip. Задай для A и B разные серверы Happ (не прокси-цепочки одного провайдера).", true)
            return false
        }
        return true
    }

    /** Кнопка «Проверить VPN»: проходит по приложениям A и B, пишет IP каждого и сравнивает. */
    private suspend fun vpnCheck() {
        val apps = if (Store.twoApps()) listOf("A", "B") else listOf("A")
        val ips = LinkedHashMap<String, String?>()
        if (!Vpn.on()) BotLog.add("🌐 Переключение VPN выключено в настройках: проверяю только текущий IP")
        for (app in apps) {
            val ok = ensureVpnFor(app)
            val ip = currentIp()
            ips[app] = ip
            BotLog.add("🌐 Приложение $app: IP ${ip ?: "не получен"}" + (if (ok) "" else " (переключение не удалось)"))
        }
        val text = ips.entries.joinToString(", ") { "${it.key}: ${it.value ?: "нет"}" }
        if (apps.size == 2) {
            val a = ips["A"]; val b = ips["B"]
            val verdict = when {
                a == null || b == null -> "не удалось получить IP"
                a == b -> "IP ОДИНАКОВЫЙ"
                else -> "IP разные, всё в порядке"
            }
            BotLog.add("🌐 Итог: $verdict ($text)")
            alert(4108, "🌐 Проверка VPN", "$verdict. $text", a == b)
        } else {
            BotLog.add("🌐 Итог: $text")
        }
    }

    // ───────────── 2.5 / 2.8 ЗОЛОТЫЕ ПРОФИЛИ ─────────────
    private fun goldenCommentList(): List<String> {
        val own = Store.s("goldenComments").lines().map { it.trim() }.filter { it.isNotEmpty() }
        return if (own.isNotEmpty()) own else Store.s("comments").lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun onProfilePage(): Boolean =
        byText("Message") != null || byText("Following") != null || byText("Follow") != null

    /** Вкладка Reels внутри профиля, а не нижнее меню: нижняя панель занимает нижние 20% экрана. */
    private fun reelsTabOnProfile(): AccessibilityNodeInfo? {
        val h = resources.displayMetrics.heightPixels
        return all { desc(it).equals("Reels", true) || txt(it).equals("Reels", true) }.firstOrNull {
            val r = Rect(); it.getBoundsInScreen(r); r.centerY() < h * 0.8
        }
    }

    /** Кнопка профиля (Follow / Following / Message), а не подпись счётчика: у кнопки есть клик или класс Button. */
    private fun profileButton(label: String): AccessibilityNodeInfo? =
        first { txt(it).equals(label, true) && (it.isClickable || it.className == "android.widget.Button") }

    /** Первая ячейка сетки профиля (подряд: «Row 1, Column 1», «Reel by …», «Photo by …»). */
    private fun gridItem(): AccessibilityNodeInfo? =
        first { desc(it).contains("Row 1, Column 1", true) }
            ?: first { Regex("(?i)\\b(reel|video|clip)\\b.*\\bby\\b").containsMatchIn(desc(it)) }
            ?: first { Regex("(?i)\\b(photo|carousel|post)\\b.*\\bby\\b").containsMatchIn(desc(it)) }
            ?: first { desc(it).contains("Column 1", true) }

    /**
     * Открывает первое видео профиля (вкладка Reels). videoOnly: если вкладки Reels нет, видео нет, и профиль пропускается.
     * Открылось ли, проверяется по кнопке «Like» / «Unlike» в просмотрщике.
     */
    private suspend fun openFirstVideo(videoOnly: Boolean, shown: String, hide: Boolean): Boolean {
        val h = resources.displayMetrics.heightPixels
        val w = resources.displayMetrics.widthPixels
        val tab = reelsTabOnProfile()
        if (tab == null && videoOnly) { BotLog.add("  у профиля ($shown) нет вкладки Reels — видео нет, пропуск"); return false }
        var tabBottom = 0
        if (tab != null) {
            click(tab) || tapNode(tab); delay(rnd(1500, 2500))
            val r = Rect(); tab.getBoundsInScreen(r); tabBottom = r.bottom
        }
        val node = gridItem()
        if (node != null) click(node) || tapNode(node)
        else {
            BotLog.add("  сетку публикаций не нашёл — нажимаю в первую ячейку по координатам")
            val y = if (tabBottom > 0) (tabBottom + w / 3 * 0.8f) / h else 0.62f
            tapXY(0.17f, y.coerceIn(0.3f, 0.9f))
        }
        delay(rnd(2500, 4000))
        val opened = first { val d = desc(it); d.equals("Like", true) || d.equals("Unlike", true) } != null
        if (!opened) {
            BotLog.add("  видео не открылось у профиля ($shown). На экране: ${visibleSummary()}")
            if (!hide) noteUnknown("видео не открылось у профиля ($shown)")
        }
        return opened
    }

    /** Подписка на золотой / премиум профиль, если аккаунт ещё не подписан. Список «подписанных» бота не трогает: на них отписка не распространяется. */
    private suspend fun followGolden(a: String, shown: String) {
        if (!onProfilePage()) return
        if (profileButton("Following") != null || profileButton("Requested") != null) return
        val f = profileButton("Follow") ?: profileButton("Follow back") ?: return
        if (act("подписался бы на профиль ($shown)", f)) {
            Stats.inc(a, Stats.FOLLOW); BotLog.add("  подписка на профиль ($shown)")
            delay(rnd(1500, 3000))
        }
    }

    /**
     * Заход к золотым профилям: подписка (если ещё нет), истории, последние видео, а если лайка нет: лайк, комментарий, репост, «Поделиться», «Скопировать ссылку».
     * Если лайк уже стоит, видео пропускается. Видео идут по одному. Время захода ограничено (по умолчанию 10 минут): премиум идут первыми и успевают всегда.
     */
    private suspend fun goldenRound(a: String) {
        // сначала премиум-профили, потом золотые из списка (порядок внутри каждой группы случайный)
        val me = a.lowercase()
        val gold = Premium.all().filter { it != me }.shuffled() + Store.golden().filter { it != me && !Premium.has(it) }.shuffled()
        if (gold.isEmpty()) return
        val nVideos = Store.i("goldenVideos", 3).coerceIn(1, 6)
        val stories = Store.i("goldenStories", 3).coerceIn(0, 10)
        val templates = goldenCommentList()
        val deadline = System.currentTimeMillis() + Store.i("goldenMaxMin", 10).coerceIn(3, 60) * 60_000L
        BotLog.add("⭐ Золотые профили: ${gold.size}, видео на профиль: до $nVideos, не дольше ${Store.i("goldenMaxMin", 10).coerceIn(3, 60)} мин")
        for (u in gold) {
            if (System.currentTimeMillis() > deadline) { BotLog.add("⭐ Время золотого захода вышло, иду дальше"); break }
            val shown = if (Premium.has(u)) "приоритетный профиль" else "@$u"   // ники премиум-профилей в журнале не пишутся
            try {
                checkWindow()
                BotLog.add("⭐ $shown")
                openProfile(u)
                checkBlock()
                followGolden(a, shown)
                if (stories > 0) {
                    watchStories(stories)
                    var t = 0
                    while (!onProfilePage() && t < 3) { if (!safeBack(1000)) break; t++ }
                }
                if (!openFirstVideo(false, shown, Premium.has(u))) {
                    var back = 0
                    while (!onProfilePage() && back < 3) { if (!safeBack(1000)) break; back++ }
                    continue
                }
                for (i in 1..nVideos) {
                    checkBlock(); checkWindow()
                    Gold.inc(a, Gold.VIDEOS)
                    delay(rnd(4000, 9000))   // «смотрим» видео
                    if (first { desc(it).equals("Unlike", true) } != null) {
                        Gold.inc(a, Gold.SKIPPED)
                        BotLog.add("  видео $i: лайк уже стоит, пропуск")
                    } else {
                        val like = first { desc(it).equals("Like", true) }
                        if (like == null) BotLog.add("  видео $i: кнопки «Like» не вижу, пропуск")
                        else if (act("поставил бы лайк на видео", like)) {
                            Stats.inc(a, Stats.LIKE); Gold.inc(a, Gold.LIKES)
                            BotLog.add("  видео $i: лайк")
                            delay(rnd(1500, 3000))
                            if (templates.isNotEmpty() && comment(Gold.pick(templates))) Gold.inc(a, Gold.COMMENTS)
                            goldenShare(u, a)
                        }
                    }
                    if (i < nVideos) { swipeUp(); delay(rnd(2500, 5000)) }
                }
                var back = 0
                while (!onProfilePage() && back < 4) { if (!safeBack(1000)) break; back++ }
                patientDelay(rnd(8_000, 20_000))
                bigPause()
            } catch (e: Exception) {
                if (isFatal(e)) throw e
                val ex = if (Premium.has(u) && e.message != null) IllegalStateException(e.message!!.replace(u, "[приоритетный]", true)) else e
                if (!itemFailed("золотой профиль ($shown)", ex)) throw e
            }
        }
    }

    /**
     * Необязательный блок (золотые профили, новые подписчики). Его сбой НЕ ломает сессию аккаунта: ошибка пишется в журнал,
     * бот возвращается на главный экран Instagram и продолжает обычные задачи (подписки, активность).
     * Исключения, которые управляют ходом сессии (блокировка, пауза, остановка), пропускаются дальше как есть.
     */
    private suspend fun optional(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ActionBlocked) {
            throw e
        } catch (e: ScreenProblem) {
            throw e
        } catch (e: SessionStop) {
            throw e
        } catch (e: Exception) {
            BotLog.add("  ⚠ «$name» не получилось (${e.message ?: e.javaClass.simpleName}), продолжаю без неё")
            if (e !is IllegalStateException) CrashHandler.record(this, "сбой в блоке «$name»", e)
            recover(name)   // если Instagram потерян совсем, recover сам запросит перезапуск
        }
    }

    // ───────────── новые подписчики аккаунта: видео, хороший комментарий, по желанию благодарность в личку ─────────────
    private val startedFollowing = Regex("(?i)^(.{1,200}?)\\s+started following you")

    private fun commentListOf(key: String): List<String> =
        Store.s(key).lines().map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Ответная активность: открывает уведомления своего аккаунта, берёт новых подписчиков («started following you»),
     * заходит на каждого по одному и только если есть видео: смотрит, лайкает, пишет хороший комментарий.
     */
    private suspend fun followersRound(a: String) {
        if (Store.s("newFollowersOn", "1") != "1") return
        val max = Store.i("newFollowersMax", 5).coerceIn(1, 20)
        BotLog.add("👥 Новые подписчики @$a (не больше $max за день)")
        val home = tabByDesc("Home")
        if (home != null) { click(home); delay(rnd(1500, 2500)) }
        val bell = first { desc(it).equals("Notifications", true) || desc(it).equals("Activity", true) }
        if (bell == null) { BotLog.add("  кнопки «Notifications» не вижу, новых подписчиков пропускаю"); noteUnknown("нет кнопки уведомлений"); return }
        click(bell) || tapNode(bell); delay(rnd(2500, 4000))
        val names = LinkedHashSet<String>()
        repeat(3) {
            for (n in all { true }) for (t in listOf(txt(n), desc(n))) {
                val m = startedFollowing.find(t) ?: continue
                m.groupValues[1].split(",", " and ").map { it.trim().lowercase() }
                    .filter { Regex("^[a-z0-9._]{1,30}$").matches(it) }.forEach { names.add(it) }
            }
            swipeUp(); delay(rnd(1200, 2200))
        }
        var back = 0
        while (tabByDesc("Home") == null && back < 3) { if (!safeBack(1000)) break; back++ }
        val done = Store.nfDone(a); val bl = Store.blacklist()
        val todo = names.filter { it !in done && it != a.lowercase() && it !in bl }.take(max)
        if (todo.isEmpty()) { BotLog.add("  новых подписчиков для обработки нет (найдено в уведомлениях: ${names.size})"); return }
        BotLog.add("  новых подписчиков: ${todo.size}")
        val templates = commentListOf("newFollowerComments").ifEmpty { goldenCommentList() }
        val deadline = System.currentTimeMillis() + 10 * 60_000L
        for (u in todo) {
            if (System.currentTimeMillis() > deadline) { BotLog.add("  время на подписчиков вышло"); break }
            try {
                checkWindow()
                BotLog.add("👥 @$u")
                openProfile(u); checkBlock()
                Store.nfAdd(a, u)   // отмечаем сразу: «ядовитый» профиль повторно не трогаем
                if (first { txt(it).contains("private account", true) } != null) { BotLog.add("  закрытый аккаунт, пропуск"); continue }
                if (!openFirstVideo(true, "@$u", false)) {
                    var b2 = 0
                    while (!onProfilePage() && b2 < 3) { if (!safeBack(1000)) break; b2++ }
                    continue
                }
                delay(rnd(4000, 9000))   // смотрим видео
                if (first { desc(it).equals("Unlike", true) } != null) BotLog.add("  лайк уже стоит")
                else {
                    val like = first { desc(it).equals("Like", true) }
                    if (like != null && act("поставил бы лайк на видео подписчика", like)) {
                        Stats.inc(a, Stats.LIKE); BotLog.add("  лайк")
                        delay(rnd(1500, 3000))
                        if (templates.isNotEmpty()) comment(Gold.pick(templates))
                    } else if (like == null) BotLog.add("  кнопки «Like» не вижу")
                }
                var b3 = 0
                while (!onProfilePage() && b3 < 4) { if (!safeBack(1000)) break; b3++ }
                patientDelay(rnd(10_000, 25_000))
                bigPause()
            } catch (e: Exception) {
                if (isFatal(e) || !itemFailed("новый подписчик @$u", e)) throw e
            }
        }
    }

    /**
     * Репост, «Поделиться», «Скопировать ссылку».
     * 2.5.4: жёсткая защита. Функция вызывается только из золотого захода и сама ещё раз проверяет, что профиль золотой.
     * На остальных профилях репоста и «Поделиться» в боте нет вообще: там только просмотр, лайк и комментарий.
     */
    private suspend fun goldenShare(u: String, a: String) {
        if (!Store.isGolden(u) && !Premium.has(u)) { BotLog.add("  ⛔ репост запрещён: @$u не в списке золотых"); return }
        val rp = first { desc(it).equals("Repost", true) }
        if (rp != null && act("сделал бы репост", rp)) {
            Gold.inc(a, Gold.REPOSTS); BotLog.add("  репост")
            delay(rnd(2000, 3500))
        } else BotLog.add("  кнопки «Repost» не вижу (нет в этой версии Instagram или уже сделан)")
        val sh = first { desc(it).equals("Share", true) || desc(it).equals("Send post", true) || desc(it).equals("Send", true) }
        if (sh == null) { BotLog.add("  кнопки «Share» не вижу. На экране: ${visibleSummary()}"); return }
        click(sh); delay(rnd(2000, 3500))
        val cp = first { txt(it).equals("Copy link", true) || desc(it).equals("Copy link", true) }
        if (cp != null && act("скопировал бы ссылку", cp)) {
            Gold.inc(a, Gold.LINKS); BotLog.add("  ссылка скопирована")
            delay(rnd(1000, 2000))
        } else BotLog.add("  «Copy link» не вижу. На экране: ${visibleSummary()}")
        if (first { txt(it).equals("Copy link", true) || desc(it).equals("Copy link", true) } != null) safeBack(1000)   // закрыть шторку
    }

    // ───────────── 2.4.2 аккаунт с нулём подписок за день доделывается ─────────────
    private fun needsCatchUp(a: String, today: String): Boolean {
        if (Store.catalog().isEmpty()) return false
        if (Store.s("done_$a") != today) return false                     // сегодня его ещё не гоняли — это не «доделать»
        if (Store.pausedUntil(a) > System.currentTimeMillis()) return false
        if (Store.s("skipd_$a") == "$today:1") return false               // выходной по расписанию
        if (Stats.forDay(a, 0)[Stats.FOLLOW] > 0) return false
        return Store.catchUps(a) < 2
    }

    /** После круга: у кого сегодня 0 подписок, тех бот прогоняет ещё раз (не больше двух раз за день на аккаунт). */
    private suspend fun catchUpPass(list: List<String>) {
        val today = Schedule.todayKey()
        for (a in list) {
            if (!needsCatchUp(a, today)) continue
            if (!Schedule.inWindow()) return
            Store.addCatchUp(a)
            BotLog.add("🩹 @$a: сегодня 0 подписок — доделываю (попытка ${Store.catchUps(a)} из 2)")
            Store.unmarkPhase(a, "f"); Store.remove("done_$a")
            Store.cur = a
            val ok = runAccountSession(a)
            if (ok) Store.put("done_$a", today)
            Store.cur = null
            delay(rnd(120_000, 300_000))
        }
    }

    // ───────────── 2.7.5 ЛЮБАЯ ОШИБКА ВЕДЁТ К ДЕЙСТВИЮ ─────────────
    /** Эти исключения нельзя глотать на уровне одного элемента: они управляют ходом сессии. */
    private fun isFatal(e: Throwable): Boolean =
        e is CancellationException || e is ActionBlocked || e is ScreenProblem || e is SessionStop || e is NeedRestart

    /**
     * Сбой на одном доноре / профиле / отписке. Бот записывает его, возвращается на главный экран Instagram и идёт к следующему.
     * 5 сбоев за 10 минут подряд значит, что дело не в одном элементе: просим перезапустить Instagram (NeedRestart).
     */
    private suspend fun itemFailed(what: String, e: Exception): Boolean {
        val now = System.currentTimeMillis()
        itemErrs.removeAll { now - it > 600_000L }
        itemErrs.add(now)
        val msg = e.message ?: e.javaClass.simpleName
        BotLog.add("  ⚠ сбой на «$what»: $msg — иду дальше")
        if (e !is IllegalStateException) CrashHandler.record(this, "сбой на «$what»", e)
        noteUnknown("сбой на «$what»: $msg")
        if (itemErrs.size >= 5) {
            itemErrs.clear()
            throw NeedRestart("5 сбоев за 10 минут подряд (последний: $msg)")
        }
        recover(what)
        return true
    }

    /** Возврат в известное состояние: закрыть окна, открыть Instagram, выйти на главный экран. */
    private suspend fun recover(what: String) {
        BotLog.add("  🔧 восстановление после сбоя «$what»")
        delay(rnd(1500, 2500))
        if (!igOnScreen() && !openInstagram()) throw NeedRestart("Instagram не открывается после сбоя")
        var tries = 0
        while (tabByDesc("Home") == null && tries < 4) {
            tries++
            if (dismissPopups()) { delay(rnd(1000, 1600)); continue }
            if (!safeBack(1000)) break
        }
        if (tabByDesc("Home") == null) throw NeedRestart("не смог вернуться на главный экран Instagram после сбоя")
        touch()
    }

    /**
     * Присмотр за всем режимом («ВСЕ АККАУНТЫ», «РАСПИСАНИЕ»): если он упал по неожиданной причине, цикл запускается заново.
     * Сделанное сегодня помнится (2.7.3), поэтому повтор продолжает, а не начинает с нуля.
     * 5 падений за час подряд: бот останавливается и сообщает, чтобы не зациклиться.
     */
    private suspend fun supervised(name: String, body: suspend () -> Unit) {
        val fails = ArrayList<Long>()
        while (true) {
            try {
                body(); return
            } catch (e: CancellationException) {
                throw e
            } catch (e: ActionBlocked) {
                throw e
            } catch (e: Throwable) {
                val now = System.currentTimeMillis()
                fails.removeAll { now - it > 3_600_000L }; fails.add(now)
                CrashHandler.record(this, "сбой режима «$name»", e)
                val msg = e.message ?: e.javaClass.simpleName
                if (fails.size >= 5) {
                    BotLog.add("⛔ «$name»: 5 сбоев за час — останавливаюсь, чтобы не зациклиться")
                    alert(4105, "Бот остановлен после сбоев", "Режим «$name» упал 5 раз за час (последняя ошибка: $msg). Подробности: «Последняя ошибка» на главном экране.")
                    return
                }
                BotLog.add("🔧 Сбой режима «$name»: $msg. Перезапускаю цикл через 60 сек (сбой ${fails.size} из 5)")
                patientDelay(60_000)
            }
        }
    }

    // ───────────── РАЗНООБРАЗИЕ: случайные «прогулки» по Instagram и выходные дни ─────────────
    /** Решение «пропустить день» принимается один раз в сутки на аккаунт; два выходных подряд не бывает. */
    private fun skipToday(a: String): Boolean {
        val pct = Store.i("skipDayPct", 10)
        if (pct <= 0) return false
        val today = Schedule.todayKey()
        val saved = Store.s("skipd_$a")
        if (saved.startsWith("$today:")) return saved.endsWith(":1")
        val yesterdaySkipped = Store.s("skipd_$a").startsWith("${Schedule.dayKey(-1)}:1")
        val skip = !yesterdaySkipped && Random.nextInt(100) < pct
        Store.put("skipd_$a", "$today:${if (skip) 1 else 0}")
        return skip
    }

    private fun tabByDesc(name: String): AccessibilityNodeInfo? =
        first { desc(it).equals(name, true) }
            ?: first { desc(it).startsWith(name, true) && !desc(it).contains("picture", true) && it.isClickable }

    /** Короткая «прогулка»: лента, рилсы, чужие истории. Шанс задаётся полем «разнообразие, %». */
    private suspend fun browseBreak(force: Boolean = false) {
        val pct = Store.i("diversityPct", 60)
        if (!force && Random.nextInt(100) >= pct) return
        if (!igOnScreen()) { BotLog.add("  разнообразие пропущено: Instagram не на экране"); return }
        val acts = listOf("feed", "reels", "stories").shuffled().take(if (force) 3 else Random.nextInt(1, 3))
        BotLog.add("  ↺ разнообразие: " + acts.joinToString(" + ") {
            when (it) { "feed" -> "лента"; "reels" -> "рилсы"; else -> "истории других" }
        })
        for (x in acts) {
            checkBlock(); checkWindow()
            when (x) {
                "feed" -> browseFeed()
                "reels" -> browseReels()
                else -> browseStories()
            }
            delay(rnd(2000, 5000))
        }
    }

    private suspend fun goHome(): Boolean {
        val h = tabByDesc("Home")
        if (h == null) { BotLog.add("  вкладка Home не найдена"); return false }
        click(h); delay(rnd(1800, 3000))
        return true
    }

    private suspend fun browseFeed() {
        if (!goHome()) return
        val n = Random.nextInt(5, 13)
        repeat(n) {
            checkBlock()
            delay(rnd(2500, 6000))
            if (Random.nextInt(100) < 12) delay(rnd(6000, 14000))   // иногда «зачитаться»
            swipeUp()
        }
        BotLog.add("  лента: прокручено $n")
    }

    private suspend fun browseReels() {
        val r = tabByDesc("Reels")
        if (r == null) { BotLog.add("  вкладка Reels не найдена"); return }
        click(r); delay(rnd(3000, 4500))
        val n = Random.nextInt(3, 8)
        repeat(n) {
            checkBlock()
            delay(rnd(4000, 12000))
            swipeUp(); delay(rnd(1200, 2500))
        }
        BotLog.add("  рилсы: просмотрено $n")
        goHome()
    }

    private suspend fun browseStories() {
        if (Store.dry()) { BotLog.add("  🧪 сухой запуск: посмотрел бы чужие истории"); return }
        if (!goHome()) return
        val ring = first {
            val d = desc(it).lowercase()
            d.contains("story") && !d.contains("your story") && !d.contains("add to") && !d.contains("create")
        }
        if (ring == null) { BotLog.add("  чужих историй не видно"); return }
        click(ring); delay(rnd(2000, 3500))
        val n = Random.nextInt(3, 7)
        repeat(n) {
            delay(rnd(3000, 7000))
            tapXY(0.92f, 0.5f)
        }
        BotLog.add("  чужие истории: просмотрено $n")
        var tries = 0
        while (tabByDesc("Home") == null && tries < 3) { if (!safeBack(1000)) break; tries++ }
    }

    // ───────────── ВСЕ АККАУНТЫ ПО ОЧЕРЕДИ ─────────────
    private suspend fun runAll() {
        // с включённым VPN аккаунты идут группами по приложениям: сначала все из A, потом все из B (меньше переключений Happ)
        val list = Store.activeAccounts().let { l -> if (Vpn.on()) l.sortedBy { Store.appOf(it) } else l }
        if (list.isEmpty()) { BotLog.add("Нет включённых аккаунтов — добавь или включи их на экране «Аккаунты»"); return }
        val today = Schedule.todayKey()
        if (list.all { Store.s("done_$it") == today })
            BotLog.add("Все аккаунты сегодня уже сделаны. Чтобы повторить, нажми «Сбросить «сделано сегодня»» на главном экране")
        for ((idx, a) in list.withIndex()) {
            if (!Schedule.inWindow()) { BotLog.add("Рабочее окно закончилось — остановка"); return }
            if (Store.s("done_$a") == today) { BotLog.add("══ @$a (${idx + 1}/${list.size}): сегодня уже сделан — пропуск"); continue }
            Store.cur = a
            BotLog.add("══ @$a (${idx + 1}/${list.size})")
            val ok = runAccountSession(a)
            if (ok) Store.put("done_$a", today)   // 2.7.3: после перезапуска бот не начнёт круг заново
            Store.cur = null
            if (idx < list.size - 1) delay(rnd(120_000, 300_000))   // пауза между аккаунтами 2–5 мин
        }
        catchUpPass(list)
        sendSummary()
        cycleDone("Режим ожидания: для нового круга нажми «ВСЕ АККАУНТЫ» или включи «РАСПИСАНИЕ».")
    }

    // ───────────── РАСПИСАНИЕ: аккаунты стартуют сами в разное время дня ─────────────
    private suspend fun runSchedule() {
        if (Schedule.startH() >= Schedule.endH()) {
            BotLog.add("Для расписания задай «Работать с» меньше «до» (например 10 и 22)"); return
        }
        while (true) {
            val list = Store.activeAccounts()
            if (list.isEmpty()) { BotLog.add("Нет включённых аккаунтов — добавь или включи их на экране «Аккаунты»"); return }
            val today = Schedule.todayKey()
            val now = System.currentTimeMillis()
            // аккаунты на паузе после блокировки в план не попадают — к ним вернёмся, когда пауза кончится
            val todo = list.filter { Store.s("done_$it") != today && Store.pausedUntil(it) <= now }.shuffled()
                .let { l -> if (Vpn.on()) l.sortedBy { Store.appOf(it) } else l }   // sortedBy стабилен: внутри приложения порядок случайный
            val we = effectiveEnd(now)
            // 2.5.2: заходов к золотым профилям в день (утро / обед / вечер). Первый заход идёт внутри обычной сессии аккаунта,
            // остальные планируются отдельными короткими заходами «только золотые» в других частях рабочего окна.
            val gMax = Store.goldVisitsMax()
            val gExtra = Store.hasPriority() && gMax > 1
            if ((todo.isNotEmpty() || gExtra) && now < we - 10 * 60_000L) {
                val from = maxOf(now + 60_000L, Schedule.windowStart(0))
                val span = we - from
                val slot = if (todo.isEmpty()) 0L else span / todo.size
                val plan = ArrayList<Triple<String, Long, Boolean>>()   // аккаунт, время, «только золотые»
                todo.forEachIndexed { i, a -> plan.add(Triple(a, from + i * slot + Random.nextLong(0, maxOf(1L, (slot * 0.4).toLong())), false)) }
                if (gExtra) {
                    for (a in list) {
                        if (Store.pausedUntil(a) > now) continue
                        val need = gMax - Store.goldVisits(a) - (if (a in todo) 1 else 0)
                        if (need <= 0) continue
                        val fullT = plan.firstOrNull { it.first == a && !it.third }?.second
                        val used = fullT?.let { (((it - from).toDouble() / span) * gMax).toInt().coerceIn(0, gMax - 1) }
                        for (k in (0 until gMax).filter { it != used }.shuffled().take(need))
                            plan.add(Triple(a, from + span * k / gMax + Random.nextLong(0, maxOf(1L, span / gMax)), true))
                    }
                }
                plan.sortBy { it.second }
                BotLog.add("План на сегодня: " + plan.joinToString(", ") { "@${it.first} ${Schedule.hm(it.second)}" + if (it.third) " (золотые)" else "" })
                for ((a, t, gOnly) in plan) {
                    waitLong(t - System.currentTimeMillis(), "@$a до ${Schedule.hm(t)}")
                    if (System.currentTimeMillis() > we) { BotLog.add("Рабочее окно закончилось"); break }
                    if (gOnly && !Store.goldenDue(a)) continue
                    Store.cur = a
                    BotLog.add("══ @$a (по расписанию" + (if (gOnly) ", только золотые профили)" else ")"))
                    foreground("Работает @$a")
                    acquire()
                    val ok = runAccountSession(a, allowSkip = true, goldenOnly = gOnly)
                    release()
                    // заблокированный или упавший аккаунт не отмечаем выполненным: он получит новую попытку
                    if (ok && !gOnly) Store.put("done_$a", today)
                    Store.cur = null
                }
            }
            catchUpPass(list)
            val now2 = System.currentTimeMillis()
            val retry = list.filter { Store.s("done_$it") != today && Store.pausedUntil(it) > now2 }
                .map { Store.pausedUntil(it) }.minOrNull()
            if (retry != null && retry < Schedule.windowEnd(0) - 10 * 60_000L) {
                BotLog.add("Есть аккаунты на паузе — вернусь к ним после ${Schedule.hm(retry)}")
                waitLong(retry - now2 + rnd(60_000, 600_000), "конец паузы ${Schedule.hm(retry)}")
                continue
            }
            sendSummary()
            var next = Schedule.windowStart(1) + Random.nextLong(0, 40 * 60_000L)
            // 2.7.4: если телефон выключается и включается по расписанию, не раньше чем через 15 минут после включения
            Schedule.nextPowerOff(System.currentTimeMillis())?.let { po ->
                Schedule.powerBackAt(po)?.let { back -> if (back + 15 * 60_000L > next) next = back + 15 * 60_000L + Random.nextLong(0, 10 * 60_000L) }
            }
            BotLog.add("На сегодня всё. Следующий запуск завтра около ${Schedule.hm(next)}")
            cycleDone("Следующий запуск завтра около ${Schedule.hm(next)}.")
            waitLong(next - System.currentTimeMillis(), "завтра около ${Schedule.hm(next)}")
        }
    }

    /** Конец рабочего окна на сегодня; если телефон скоро выключится по расписанию, то на 25 минут раньше его. */
    private fun effectiveEnd(now: Long): Long {
        val e = Schedule.windowEnd(0)
        val po = Schedule.nextPowerOff(now) ?: return e
        val guard = po - 25 * 60_000L
        return if (guard < e) guard else e
    }

    /**
     * Долгое ожидание (2.7.4). Экран можно выключить.
     * Короткое ожидание: процессор удерживается, таймер точный.
     * Долгое (больше 30 мин, «глубокое ожидание» включено): процессор не удерживается, телефон спит,
     * к сроку его будит будильник Android. Так телефон на зарядке почти не тратит энергию.
     */
    private suspend fun waitLong(ms: Long, label: String) {
        if (ms <= 0) return
        release()
        foreground("Ждёт: $label")
        idleText = "💤 Ожидание: $label"
        val wait = if (Store.dry()) minOf(ms, 4_000L) else ms   // сухой запуск не ждёт расписания
        val target = System.currentTimeMillis() + wait
        val deep = wait > 30 * 60_000L && Store.s("deepIdle", "1") == "1"
        try {
            if (deep) {
                BotLog.add("💤 Глубокое ожидание до ${Schedule.hm(target)}: процессор не удерживаю, разбудит будильник")
                scheduleWake(target)
                while (true) {
                    val left = target - System.currentTimeMillis()
                    if (left <= 0) break
                    delay(minOf(left, 60_000L))
                }
            } else {
                try {
                    val pm = getSystemService(POWER_SERVICE) as PowerManager
                    wlWait = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hbot:wait")
                    wlWait?.acquire(wait + 60_000L)
                } catch (e: Throwable) { BotLog.add("Не смог удержать процессор в ожидании: ${e.message}") }
                delay(wait)
            }
        } finally {
            idleText = ""
            if (deep) cancelWake()
            try { if (wlWait?.isHeld == true) wlWait?.release() } catch (_: Throwable) {}
        }
    }

    /** Рабочее окно по часам. Вне окна действия не выполняются. */
    private fun checkWindow() {
        if (Schedule.nearPowerOff(5))
            throw SessionStop("через несколько минут телефон выключится по расписанию — останавливаюсь, продолжу после включения")
        if (Schedule.inNight())
            throw SessionStop("сейчас ночной режим (${Schedule.nightFrom()}:00–${Schedule.nightTo()}:00) — действий нет")
        if (!Schedule.inWindow())
            throw SessionStop("сейчас вне рабочего окна ${Schedule.startH()}:00–${Schedule.endH()}:00 (чтобы убрать ограничение, поставь одинаковые «с» и «до»)")
    }

    /** true — можно действовать. false — часовой лимит исчерпан, а ждать нельзя (режим «по очереди»/расписание). */
    private suspend fun hourGate(kind: String, limit: Int): Boolean {
        while (true) {
            val w = Store.hourWait(kind, limit)
            if (w <= 0) return true
            if (Store.cur != null) {
                BotLog.add("  часовой лимит ($limit/час) исчерпан — этот аккаунт на сегодня пока всё")
                return false
            }
            BotLog.add("  часовой лимит ($limit/час): жду ${w / 60_000 + 1} мин")
            patientDelay(w + rnd(5_000, 60_000))
            checkWindow()
        }
    }

    private fun profileTab() = first {
        val d = desc(it)
        d.equals("Profile", true) || (d.startsWith("Profile", true) && !d.contains("picture", true) && it.isClickable)
    }

    private fun launchIg(i: Intent): Boolean = try {
        touch()
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Throwable) {
        BotLog.add("  не смог запустить Instagram: ${e.message}"); false
    }

    // ───────────── поиск ника: по text И contentDescription, «содержит» с границами слова ─────────────
    private fun nickRegex(u: String): Regex =
        Regex("(^|[^a-z0-9._])" + Regex.escape(u.lowercase().removePrefix("@")) + "($|[^a-z0-9._])")

    private fun hasNick(n: AccessibilityNodeInfo, re: Regex): Boolean =
        re.containsMatchIn(txt(n).lowercase()) || re.containsMatchIn(desc(n).lowercase())

    private fun findNick(u: String): AccessibilityNodeInfo? {
        val re = nickRegex(u)
        return first { hasNick(it, re) }
    }

    /** Ищет ник, при необходимости пролистывая список аккаунтов. */
    private suspend fun findNickScrolling(u: String, rounds: Int = 4): AccessibilityNodeInfo? {
        repeat(rounds) { i ->
            findNick(u)?.let { return it }
            if (i < rounds - 1) { swipeUp(); delay(rnd(900, 1500)) }
        }
        return null
    }

    private suspend fun openInstagram(): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(igPkg)
        if (launch == null) { BotLog.add("  Instagram не установлен (пакет $igPkg)"); return false }
        if (freshLaunch) {
            freshLaunch = false
            launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)   // после перезапуска сторожем — с начала, без старого экрана
        }
        if (!launchIg(launch)) return false
        delay(rnd(3500, 5500))
        if (!igOnScreen()) {
            delay(3000)
            if (!igOnScreen()) {
                BotLog.add("  Instagram не открылся. MIUI: Настройки → Приложения → (это приложение) → Другие разрешения → «Показывать всплывающие окна, когда приложение работает в фоне» ВКЛ")
                BotLog.add("  Разрешения: ${Perms.short(this)}")
                return false
            }
        }
        return true
    }

    private suspend fun goToProfileTab(): AccessibilityNodeInfo? {
        var tab = profileTab(); var n = 0
        while (tab == null && n < 3) {
            if (dismissPopups()) { delay(rnd(1000, 1600)); tab = profileTab(); n++; continue }
            if (!safeBack()) break
            tab = profileTab(); n++
        }
        if (tab == null) {
            BotLog.add("  не вижу вкладку Profile. На экране: ${visibleSummary()}")
            noteUnknown("не вижу вкладку Profile")
        }
        return tab
    }

    /** Долгое нажатие на вкладку Profile -> список аккаунтов. */
    private suspend fun longPressProfile(tab: AccessibilityNodeInfo) {
        val r = Rect(); (profileTab() ?: tab).getBoundsInScreen(r)
        gesture(Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }, 900)
        delay(rnd(1800, 2800))
    }

    /** Причина последнего неудачного переключения (для лога и уведомления). */
    @Volatile private var switchError = ""

    /** Элемент лежит в шапке экрана (верхняя четверть) — там Instagram показывает ник текущего аккаунта. */
    private fun inHeader(n: AccessibilityNodeInfo): Boolean {
        val r = Rect(); n.getBoundsInScreen(r)
        return r.top < resources.displayMetrics.heightPixels * 0.25
    }

    /** 2.6.1/2.6.2: текущий аккаунт определяется по нику в шапке профиля, а не по любому месту экрана. */
    private fun isCurrent(u: String): Boolean {
        val re = nickRegex(u)
        return first { inHeader(it) && hasNick(it, re) } != null
    }

    /** Что стоит в шапке сейчас (для лога при ошибке). */
    private fun headerSummary(): String {
        val items = all { inHeader(it) && (txt(it).isNotEmpty() || desc(it).isNotEmpty()) }
            .take(6).map { (txt(it).ifEmpty { desc(it) }).replace("\n", " ").take(30) }
        return if (items.isEmpty()) "шапка пустая" else items.joinToString(" | ")
    }

    /** Ждёт, пока в шапке появится нужный ник (экран обновляется не мгновенно). */
    private suspend fun waitCurrent(u: String, tries: Int = 4): Boolean {
        repeat(tries) {
            if (isCurrent(u)) return true
            delay(rnd(1200, 1800))
        }
        return false
    }

    /**
     * 2.1.2: открывает то приложение (A или B), где лежит аккаунт, и переключается внутри него.
     * Если в назначенном приложении ника нет, а второе приложение настроено, пробует второе
     * и при успехе само исправляет назначение.
     */
    private suspend fun switchAccount(u: String): Boolean {
        val assigned = Store.appOf(u)
        igPkg = Store.pkgFor(u)
        if (Store.twoApps()) BotLog.add("  @$u: приложение $assigned ($igPkg)")
        if (switchInApp(u)) return true
        if (!Store.twoApps() || !notInList) return false
        val other = if (assigned == "A") "B" else "A"
        BotLog.add("  @$u не найден в приложении $assigned — пробую приложение $other (${Store.pkgOf(other)})")
        igPkg = Store.pkgOf(other)
        if (switchInApp(u)) {
            Store.setAppOf(u, other)
            BotLog.add("  ✅ @$u найден в приложении $other — назначение исправлено автоматически")
            return true
        }
        igPkg = Store.pkgFor(u)
        return false
    }

    private suspend fun switchInApp(u: String): Boolean {
        switchError = ""; notInList = false
        if (!openInstagram()) { switchError = "Instagram не открылся"; return false }
        val tab = goToProfileTab() ?: run { switchError = "не вижу вкладку Profile"; return false }

        click(tab); delay(rnd(2000, 3000))
        // 2.6.1: уже нужный аккаунт — это успех, а не ошибка
        if (isCurrent(u)) { BotLog.add("  уже в @$u"); return true }
        BotLog.add("  сейчас в шапке: ${headerSummary()}")

        val attempts = 2
        for (attempt in 1..attempts) {
            longPressProfile(profileTab() ?: tab)
            // 2.6.3: ищем ник в списке с прокруткой
            var row = findNickScrolling(u)
            if (row == null) {
                // запасной путь: кнопка переключения аккаунтов в шапке профиля
                val sw = first { desc(it).contains("switch", true) && it.isClickable }
                if (sw != null && click(sw)) { delay(rnd(1800, 2800)); row = findNickScrolling(u) }
            }
            if (row != null) {
                click(row); delay(rnd(4000, 6000))
                profileTab()?.let { click(it) }; delay(rnd(2000, 3000))
                // 2.6.2: результат проверяем по нику на экране, а не по нажатию кнопки
                if (waitCurrent(u)) { BotLog.add("  переключился на @$u"); return true }
                switchError = "нажал на @$u в списке, но в шапке другой ник (${headerSummary()})"
                notInList = false
            } else {
                switchError = "@$u нет в списке аккаунтов Instagram (список: ${visibleSummary()})"
                notInList = true
                safeBack()
            }
            BotLog.add("  попытка $attempt из $attempts не удалась: $switchError")
            if (attempt < attempts) {
                delay(rnd(2500, 4000))
                val t = goToProfileTab() ?: break
                click(t); delay(rnd(2000, 3000))
                if (isCurrent(u)) { BotLog.add("  уже в @$u"); return true }
            }
        }

        // 2.6.4: сверка с «Проверкой аккаунтов» + снимок экрана для разбора
        val chk = Store.s("chk_$u")
        BotLog.add(
            when (chk) {
                "1" -> "  ⚠ «Проверка аккаунтов» ранее нашла @$u, а переключиться не вышло — причина в экране, снимок сохранён"
                "0" -> "  «Проверка аккаунтов» ранее не находила @$u — вероятно, его нет в Instagram на этом телефоне"
                else -> "  «Проверка аккаунтов» для @$u ещё не запускалась — запусти её для сверки"
            }
        )
        noteUnknown("сбой переключения на @$u: $switchError", "Сбой переключения на @$u: $switchError\n")
        return false
    }

    // ───────────── ПРОВЕРКА АККАУНТОВ: открывает список и отмечает, какие ники нашёл ─────────────
    private suspend fun checkAccounts() {
        val list = Store.accounts()
        if (list.isEmpty()) { BotLog.add("Впиши ники в поле «ВСЕ аккаунты»"); return }
        val apps = if (Store.twoApps()) listOf("A", "B") else listOf("A")
        BotLog.add("Проверка: ищу ${list.size} ник(ов) в ${if (apps.size == 2) "двух приложениях Instagram (A и B)" else "приложении Instagram"}")
        val foundIn = HashMap<String, MutableSet<String>>()
        for (app in apps) {
            igPkg = Store.pkgOf(app)
            BotLog.add("Приложение $app (${igPkg})")
            if (igVersion() == "не установлен") { BotLog.add("  ❌ не установлено — пропуск"); continue }
            if (!openInstagram()) continue
            val tab = goToProfileTab() ?: continue
            click(tab); delay(rnd(2000, 3000))
            longPressProfile(tab)

            val found = LinkedHashSet<String>()
            for (round in 0 until 5) {
                for (u in list) if (u !in found && findNick(u) != null) found.add(u)
                if (found.size == list.size) break
                swipeUp(); delay(rnd(900, 1500))
            }
            for (u in found) foundIn.getOrPut(u) { mutableSetOf() }.add(app)
            BotLog.add("  в приложении $app видно ${found.size} из ${list.size} ников")
            if (found.isEmpty()) BotLog.add("  список пуст или не открылся. Сделай «Снимок экрана Instagram» на открытом списке. Сейчас на экране: ${visibleSummary()}")
            safeBack(); delay(rnd(1500, 2500))
        }

        var okCount = 0
        for (u in list) {
            val where = foundIn[u] ?: emptySet()
            val assigned = if (Store.twoApps()) Store.appOf(u) else "A"
            when {
                assigned in where -> { okCount++; BotLog.add("  ✅ @$u — приложение $assigned") }
                where.size == 1 && Store.twoApps() -> {
                    val w = where.first()
                    Store.setAppOf(u, w); okCount++
                    BotLog.add("  ⚠ @$u найден в приложении $w, а был назначен $assigned — назначение исправлено")
                }
                else -> BotLog.add("  ❌ @$u не найден" + (if (Store.twoApps()) " ни в A, ни в B" else ""))
            }
            Store.put("chk_$u", if (where.isNotEmpty()) "1" else "0")
            Store.put("chkapp_$u", where.sorted().joinToString(""))
        }
        BotLog.add("Найдено $okCount из ${list.size}")
        igPkg = Store.pkgFor(Store.acc())
    }

    // ───────────── ПОДПИСКИ ─────────────
    /**
     * 2.3: подписки из общего каталога. Все аккаунты равны (особых нет).
     * Аккаунт заходит на ник из каталога (порядок случайный, реже использованные первыми), листает его подписчиков случайно,
     * подписывается на случайных людей (3–8 за заход, настраивается), делает паузу и идёт на следующий ник.
     * На тех, на кого аккаунт уже подписывался когда-либо, повторно не подписываемся.
     */
    private suspend fun follow() {
        val acc = Store.acc()
        val limit = Limits.followDay(acc)
        val hourLimit = Limits.followHour(acc)
        val bl = Store.blacklist()
        val catalog = Store.catalog().filter { it !in bl }
        if (catalog.isEmpty()) {
            BotLog.add("Каталог пуст (или все ники в чёрном списке): вставь ники в поле «Каталог» на главном экране или загрузи файл")
            return
        }
        var done = Store.followedToday()
        BotLog.add("Подписки: сегодня $done из $limit. Каталог: ${catalog.size} ников")
        if (done >= limit) return
        val ever = Store.everFollowed(acc)
        val minV = Store.i("visitMin", 3).coerceIn(1, 30)
        val maxV = Store.i("visitMax", 8).coerceIn(minV, 40)
        val use = Store.catalogUse()
        val order = catalog.shuffled().sortedBy { use[it] ?: 0L }   // ничьи решает случай, реже использованные первыми
        var visits = 0

        for (d in order) {
            if (done >= limit || visits >= 12) break
            try {
                BotLog.add("Заход в каталог: @$d")
                Store.markCatalogUse(d)
                openProfile(d)
                val f = first { txt(it).contains("followers", true) || desc(it).contains("followers", true) }
                if (f == null) { BotLog.add("  не нашёл «followers»"); continue }
                click(f); delay(rnd(2500, 4000))
                visits++

                val target = Random.nextInt(minV, maxV + 1).coerceAtMost(limit - done)
                repeat(Random.nextInt(0, 4)) { swipeUp(); delay(rnd(1200, 2200)) }   // не всегда с верха списка
                var got = 0; var idle = 0
                while (got < target && done < limit && idle < 6) {
                    checkBlock(); checkWindow()
                    var acted = false
                    for (b in all { txt(it) == "Follow" }.shuffled()) {   // случайные люди, а не по порядку
                        if (got >= target || done >= limit) break
                        if (!hourGate("follow", hourLimit)) return
                        val u = usernameNear(b) ?: continue
                        if (u in ever || u in bl) continue
                        if (act("подписался бы на @$u", b)) {
                            done++; got++; acted = true; ever.add(u)
                            Store.addFollowed(u); Store.addEver(acc, u); Store.markHour("follow"); Stats.inc(acc, Stats.FOLLOW)
                            BotLog.add("  подписка @$u ($done/$limit, заход $got/$target)")
                            patientDelay(rnd(25_000, 70_000))
                            bigPause()
                        }
                    }
                    idle = if (acted) 0 else idle + 1
                    swipeUp(); delay(rnd(1500, 3000))
                }
                BotLog.add("  с @$d: подписок $got")
                patientDelay(rnd(20_000, 90_000))   // пауза между заходами
            } catch (e: Exception) {
                if (isFatal(e) || !itemFailed("заход @$d", e)) throw e
            }
        }
    }

    // ───────────── АКТИВНОСТЬ (только на тех, на кого подписан бот) ─────────────
    private suspend fun engage() {
        val acc = Store.acc()
        val engaged = Store.engagedToday(acc)
        val allF = Store.followed().map { it.first }
        val bl = Store.blacklist()
        val list = allF.filter { it !in engaged && it !in bl }.shuffled()
        if (allF.isEmpty()) { BotLog.add("Список подписок пуст — сначала сделай подписки"); return }
        if (list.isEmpty()) { BotLog.add("Активность: все ${allF.size} профилей уже обработаны сегодня"); return }
        if (engaged.isNotEmpty()) BotLog.add("Активность: продолжаю, сегодня уже обработано ${engaged.size}, осталось ${list.size}")
        val likeLimit = Limits.likes(Store.acc())
        val perProfile = Store.i("likesPerProfile", 2).coerceAtLeast(1)
        val stories = Store.i("storiesPerProfile", 5)
        val cPct = Store.i("commentPct", 20)
        val comments = Store.s("comments").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val deadline = System.currentTimeMillis() + Store.i("maxMin", 30) * 60_000L
        var liked = Store.sessionLikes(acc)   // 2.7.3: лайки уже сделанной части сессии

        for (u in list) {
            if (liked >= likeLimit || System.currentTimeMillis() > deadline) break
            try {
            checkWindow()
            BotLog.add("@$u")
            openProfile(u)
            checkBlock()
            if (byText("Following") == null) { BotLog.add("  не в подписках — пропуск"); Store.markEngaged(acc, u); continue }
            watchStories(stories)
            ensureProfile()
            liked += likePosts(minOf(perProfile, likeLimit - liked), cPct, comments)
            Store.markEngaged(acc, u); Store.setSessionLikes(acc, liked)
            ensureProfile()
            patientDelay(rnd(8_000, 20_000))
            bigPause()
            } catch (e: Exception) {
                if (isFatal(e)) throw e
                Store.markEngaged(acc, u)   // «ядовитый» профиль сегодня больше не трогаем
                if (!itemFailed("профиль @$u", e)) throw e
            }
        }
        BotLog.add("Лайков за сессию: $liked")
        Store.setSessionLikes(acc, 0)
    }

    private suspend fun watchStories(n: Int) {
        if (n <= 0) return
        if (Store.dry()) { BotLog.add("  🧪 сухой запуск: посмотрел бы историй: $n"); return }
        val av = first { desc(it).contains("story", true) }
        if (av == null) { BotLog.add("  историй нет"); return }
        click(av); delay(rnd(2000, 3500))
        var seen = 0
        repeat(n) {
            delay(rnd(2500, 6500)); seen++
            if (byText("Following") != null) return@repeat   // уже вышли в профиль
            tapXY(0.92f, 0.5f)
        }
        BotLog.add("  истории: $seen")
    }

    private suspend fun likePosts(n: Int, cPct: Int, comments: List<String>): Int {
        if (n <= 0) return 0
        val post = first { desc(it).contains("Row 1, Column 1", true) }
            ?: first { desc(it).contains("Column 1", true) }
        if (post == null) { BotLog.add("  постов нет"); return 0 }
        click(post); delay(rnd(2500, 4000))
        var done = 0; var tries = 0
        while (done < n && tries < n + 3) {
            tries++
            checkBlock()
            val like = first { desc(it).equals("Like", true) }
            if (like != null && act("поставил бы лайк", like)) {
                done++; Stats.inc(Store.acc(), Stats.LIKE); BotLog.add("  лайк")
                delay(rnd(1500, 3500))
                if (comments.isNotEmpty() && Random.nextInt(100) < cPct) comment(Gold.pick(comments))
            }
            swipeUp(); delay(rnd(2000, 4500))
        }
        safeBack(1500)
        return done
    }

    /** Действие, которое что-то меняет в аккаунте (подписка, лайк, отписка, репост). В сухом запуске только пишется в журнал. */
    private fun act(what: String, n: AccessibilityNodeInfo?): Boolean {
        if (Store.dry()) { touch(); BotLog.add("  🧪 сухой запуск: $what"); return true }
        return click(n)
    }

    /** true — комментарий отправлен. Каждая неудача пишется в журнал, чтобы было видно, на чём застряло. */
    private suspend fun comment(base: String): Boolean {
        val emoji = listOf("🔥", "✨", "👏", "😍", "💯", "")
        val text = (base + " " + emoji.random()).trim()
        val c = first { desc(it).equals("Comment", true) } ?: first { desc(it).startsWith("Comment", true) }
        if (c == null) { BotLog.add("  комментарий: кнопки «Comment» нет на экране"); return false }
        click(c) || tapNode(c); delay(rnd(2000, 3500))
        val field = first { it.className == "android.widget.EditText" }
        if (field == null) { BotLog.add("  комментарий: поле ввода не открылось"); safeBack(); return false }
        if (Store.dry()) {
            BotLog.add("  🧪 сухой запуск: написал бы комментарий «$text»")
            safeBack(1000)
            if (first { it.className == "android.widget.EditText" } != null) safeBack(1000)
            return true
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(rnd(1500, 3000))
        val labels = listOf("Post", "Send", "Post comment", "Send comment")
        val send = first { n -> labels.any { txt(n).equals(it, true) || desc(n).equals(it, true) } }
        var posted = send != null && (click(send) || tapNode(send))
        if (!posted && Build.VERSION.SDK_INT >= 30) {
            BotLog.add("  комментарий: кнопки отправки не нашёл, пробую Enter")
            posted = field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        if (posted) { Stats.inc(Store.acc(), Stats.COMMENT); BotLog.add("  комментарий: $text") }
        else BotLog.add("  комментарий не отправился. На экране: ${visibleSummary()}")
        delay(rnd(1500, 2500))
        safeBack(1000)
        if (first { it.className == "android.widget.EditText" } != null) safeBack(1000)
        return posted
    }

    // ───────────── ОТПИСКА (самых старых, не младше 2 дней) ─────────────
    /** 2.3.5: сколько аккаунт держит подписку перед отпиской: у каждого человека своя выдержка (по умолчанию 3–7 дней), стабильная для пары аккаунт+ник. */
    private fun holdMs(acc: String, u: String): Long {
        val min = Store.i("holdMinDays", 3).coerceIn(1, 30)
        val max = Store.i("holdMaxDays", 7).coerceIn(min, 60)
        val h = (acc + ":" + u).hashCode() and 0x7fffffff
        return (min + h % (max - min + 1)) * DAY + ((h / 7) % 24) * 3_600_000L
    }

    private suspend fun unfollow() {
        val acc = Store.acc()
        val n = Limits.unfollowWeek(acc)
        val hourLimit = Limits.unfollowHour(acc)
        val now = System.currentTimeMillis()
        val wl = Store.whitelist()
        val keepMutual = Store.s("unfollowKeepMutual", "1") == "1"
        val targets = Store.followed().sortedBy { it.second }
            .filter { now - it.second > holdMs(acc, it.first) && it.first.lowercase() !in wl }.take(n)
        if (targets.isEmpty()) { BotLog.add("Некого отписывать: выдержка ещё не прошла или все в белом списке (${wl.size})"); return }
        BotLog.add("Отписка: ${targets.size} шт." + if (keepMutual) " (кто подписался в ответ, остаётся)" else "")
        var done = 0; var kept = 0; var diag = 0
        for ((u, _) in targets) {
            try {
                checkWindow()
                if (!hourGate("unfollow", hourLimit)) break
                openProfile(u)
                checkBlock()
                val b = byText("Following")
                if (b == null) {
                    if (byText("Follow") != null || byText("Follow back") != null) Store.removeFollowed(u)
                    continue
                }
                if (keepMutual) {
                    val mutual = first { txt(it).contains("follows you", true) || desc(it).contains("follows you", true) }
                    if (diag < 3) { diag++; BotLog.add("  проверка взаимности @$u: " + (if (mutual != null) "подписан на тебя" else "«Follows you» не вижу") + ". На экране: ${visibleSummary()}") }
                    if (mutual != null) {
                        kept++; Store.removeFollowed(u)   // в списке бота больше не числится: отписывать не будем
                        BotLog.add("  @$u подписан в ответ — оставляю")
                        patientDelay(rnd(8_000, 20_000))
                        continue
                    }
                }
                click(b); delay(rnd(1500, 2500))
                val un = byText("Unfollow")
                if (un != null && act("отписался бы от @$u", un)) {
                    done++; Store.removeFollowed(u); Store.markHour("unfollow"); Stats.inc(acc, Stats.UNFOLLOW)
                    BotLog.add("  отписка @$u ($done/${targets.size})")
                    delay(rnd(1500, 2500))
                } else safeBack(0)
                patientDelay(rnd(20_000, 50_000))
                bigPause()
            } catch (e: Exception) {
                if (isFatal(e) || !itemFailed("отписка @$u", e)) throw e
            }
        }
        BotLog.add("Отписка: отписался $done, оставил взаимных $kept")
        Store.markUnfollow()
    }

    // ───────────── навигация / поиск узлов ─────────────
    private fun igOnScreen(): Boolean = rootInActiveWindow?.packageName?.toString() == igPkg

    /** «Назад» нажимается только если Instagram на экране — иначе бот не вылетает из чужих приложений. */
    private suspend fun safeBack(pause: Long = 1200): Boolean {
        if (!igOnScreen()) { BotLog.add("  Instagram не на экране — «Назад» не нажимаю"); return false }
        touch()
        performGlobalAction(GLOBAL_ACTION_BACK)
        if (pause > 0) delay(pause)
        return true
    }

    private suspend fun openProfile(u: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/_u/$u"))
            .setPackage(igPkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try { startActivity(i) } catch (e: Throwable) {
            throw IllegalStateException("не смог открыть профиль @$u: ${e.message}")
        }
        delay(rnd(3500, 5500))
        if (!igOnScreen()) {
            delay(3000)
            if (!igOnScreen())
                throw IllegalStateException("Instagram не открылся (или ты вышел из него). MIUI: разреши всплывающие окна в фоне")
        }
    }

    private suspend fun ensureProfile() {
        repeat(3) {
            if (byText("Following") != null) return
            if (!safeBack()) return
        }
    }

    private val blockPhrases = listOf(
        "try again later", "action blocked", "we restrict certain activity",
        "we limit how often", "temporarily blocked", "temporarily restricted"
    )

    private fun checkScreenProblem() {
        screenProblem?.let { throw ScreenProblem(it) }
    }

    private fun checkBlock() {
        checkScreenProblem()
        val hit = first { n ->
            val t = txt(n).lowercase(); val d = desc(n).lowercase()
            blockPhrases.any { t.contains(it) || d.contains(it) }
        }
        if (hit != null) throw ActionBlocked("Instagram ограничил действия — остановка")
    }

    private fun usernameNear(btn: AccessibilityNodeInfo): String? {
        val re = Regex("^[a-zA-Z0-9._]{1,30}$")
        val skip = setOf("follow", "following", "remove", "message")
        var p = btn.parent; var depth = 0
        while (p != null && depth < 4) {
            val ts = ArrayList<String>()
            fun walk(n: AccessibilityNodeInfo?) {
                if (n == null) return
                val t = txt(n); if (t.isNotEmpty()) ts.add(t)
                for (i in 0 until n.childCount) walk(n.getChild(i))
            }
            walk(p)
            ts.firstOrNull { re.matches(it) && it.lowercase() !in skip }?.let { return it.lowercase() }
            p = p.parent; depth++
        }
        return null
    }

    private fun all(pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun dfs(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60) return
            if (pred(n)) out.add(n)
            for (i in 0 until n.childCount) dfs(n.getChild(i), depth + 1)
        }
        val root = rootInActiveWindow
        // работаем только с экраном выбранного Instagram (служба видит все приложения)
        if (root?.packageName?.toString() == igPkg) dfs(root, 0)
        return out
    }
    private fun first(pred: (AccessibilityNodeInfo) -> Boolean) = all(pred).firstOrNull()
    /** Тексты как на экране (для журнала, снимков и библиотеки экранов). */
    private fun rawTxt(n: AccessibilityNodeInfo) = n.text?.toString() ?: ""
    private fun rawDesc(n: AccessibilityNodeInfo) = n.contentDescription?.toString() ?: ""
    /** Тексты для поиска кнопок: русские и узбекские подписи Instagram приведены к английским (Lang). */
    private fun txt(n: AccessibilityNodeInfo) = Lang.canon(rawTxt(n))
    private fun desc(n: AccessibilityNodeInfo) = Lang.canon(rawDesc(n))
    private fun byText(t: String) = first { txt(it).equals(t, true) }

    private fun click(n: AccessibilityNodeInfo?): Boolean {
        touch()
        var c = n
        while (c != null && !c.isClickable) c = c.parent
        return c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    // ───────────── жесты ─────────────
    /** Множитель темпа из настроек: 0 быстро, 1 обычно, 2 медленно, 3 очень медленно. */
    private fun speed(): Double = when (Store.s("speed", "1")) { "0" -> 0.7; "2" -> 1.5; "3" -> 2.2; else -> 1.0 }

    /** Случайная задержка. Темп масштабирует только «человеческие» паузы (от 5 сек), а не ожидание экранов. */
    private fun rnd(a: Long, b: Long): Long {
        val v = Random.nextLong(a, b)
        return if (b >= 5000L) (v * speed()).toLong() else v
    }

    /** Иногда делаем длинную паузу (1–4 мин), как будто человек отвлёкся. Шанс — в настройках. */
    private suspend fun bigPause() {
        val pct = Store.i("longPausePct", 8)
        if (Store.dry() || pct <= 0 || Random.nextInt(100) >= pct) return
        val sec = Random.nextLong(60, 240)
        BotLog.add("  ☕ случайная пауза $sec сек")
        patientDelay(sec * 1000)
    }

    private suspend fun gesture(p: Path, dur: Long) = suspendCancellableCoroutine<Unit> { c ->
        touch()
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build()
        val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(Unit) }
            override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(Unit) }
        }, null)
        if (!ok && c.isActive) c.resume(Unit)
    }

    private suspend fun tapXY(fx: Float, fy: Float) {
        val m = resources.displayMetrics
        gesture(Path().apply { moveTo(m.widthPixels * fx, m.heightPixels * fy) }, 60)
    }

    private suspend fun swipeUp() {
        val m = resources.displayMetrics
        val x = m.widthPixels * Random.nextDouble(0.4, 0.6).toFloat()
        val p = Path().apply {
            moveTo(x, m.heightPixels * 0.75f)
            lineTo(x + Random.nextInt(-30, 30), m.heightPixels * 0.30f)
        }
        gesture(p, rnd(250, 450))
    }
}

/** Instagram показал «Try Again Later» / «Action Blocked» — аккаунт уходит на паузу. */
class ActionBlocked(msg: String) : IllegalStateException(msg)

/** Сессию аккаунта пора закончить (вне окна, ночь, скоро выключение телефона): не ошибка, повторять не надо. */
class SessionStop(msg: String) : IllegalStateException(msg)

/** Бот потерял ориентацию: Instagram надо закрыть и начать аккаунт заново. */
class NeedRestart(msg: String) : IllegalStateException(msg)
