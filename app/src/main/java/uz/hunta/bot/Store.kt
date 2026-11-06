package uz.hunta.bot

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Настройки и состояние (по каждому аккаунту отдельно). */
object Store {
    private lateinit var p: SharedPreferences

    /** Безопасно вызывать сколько угодно раз и из Activity, и из сервиса. */
    fun init(c: Context) {
        if (!::p.isInitialized) p = c.applicationContext.getSharedPreferences("hbot", Context.MODE_PRIVATE)
    }

    fun s(k: String, d: String = ""): String = p.getString(k, d) ?: d
    fun i(k: String, d: Int): Int = s(k, d.toString()).trim().toIntOrNull() ?: d
    fun put(k: String, v: String) {
        // сухой запуск: рабочее состояние (счётчики, списки подписанных, «сделано сегодня») не меняется, настройки пишутся как обычно
        if (dry() && RUNTIME_KEYS.any { k.startsWith(it) }) return
        p.edit().putString(k, v).apply()
    }

    /** Сухой запуск: бот показывает в журнале, что сделал бы, но ничего не меняет в аккаунтах и не копит состояние. */
    fun dry(): Boolean = s("dryRun", "0") == "1"
    private val RUNTIME_KEYS = listOf(
        "fl_", "fd_", "h_", "ev_", "eg_", "lk_", "ph_", "gv_", "gd_", "cu_", "done_", "lu_", "catUse", "cmRecent",
        "skipd_", "st_", "blk_", "since_", "nf_"
    )

    /** Чёрный список: на эти ники бот не подписывается, их профили не лайкает и не комментирует. */
    fun blacklist(): Set<String> = parseNicks(s("blacklist")).toSet()

    /** Аккаунт, с которым бот работает прямо сейчас (в режиме «ВСЕ АККАУНТЫ»). */
    @Volatile var cur: String? = null

    fun acc(): String = cur ?: s("acc", "acc1").trim().ifEmpty { "acc1" }

    fun accounts(): List<String> =
        s("accounts").lines().map { it.trim().removePrefix("@").lowercase() }.filter { it.isNotEmpty() }.distinct()

    fun saveAccounts(l: List<String>) =
        put("accounts", l.map { it.trim().removePrefix("@").lowercase() }.filter { it.isNotEmpty() }.distinct().joinToString("\n"))

    fun addAccount(n: String): Boolean {
        val u = n.trim().removePrefix("@").lowercase()
        if (!Regex("^[a-z0-9._]{1,30}$").matches(u)) return false
        val l = accounts()
        if (u in l) return false
        saveAccounts(l + u)
        return true
    }

    fun removeAccount(a: String) {
        saveAccounts(accounts().filter { it != a })
        listOf("fl_", "fd_", "pause_", "blk_", "dd_", "tier_", "since_", "done_", "skipd_", "lu_", "off_",
            "h_follow_", "h_unfollow_", "chk_", "chkapp_", "app_", "ph_", "eg_", "lk_", "ev_", "cu_", "gv_", "gd_", "nf_").forEach { remove(it + a) }
        all().keys.filter { it.startsWith("st_${a}_") }.forEach { remove(it) }
    }

    // ───────────── два приложения Instagram (A и B) ─────────────
    const val DEFAULT_PKG = "com.instagram.android"

    /** Пакет приложения A (по умолчанию обычный Instagram). */
    fun pkgA(): String = s("pkgA", DEFAULT_PKG).trim().ifEmpty { DEFAULT_PKG }
    /** Пакет приложения B (копия Instagram). Пусто — второго приложения нет. */
    fun pkgB(): String = s("pkgB").trim()
    fun twoApps(): Boolean = pkgB().isNotEmpty() && pkgB() != pkgA()
    fun pkgOf(app: String): String = if (app == "B" && twoApps()) pkgB() else pkgA()

    /** В каком приложении лежит аккаунт: "A" или "B". */
    fun appOf(a: String): String = if (s("app_$a") == "B") "B" else "A"
    fun setAppOf(a: String, app: String) { if (app == "B") put("app_$a", "B") else remove("app_$a") }
    /** Пакет, через который надо работать с аккаунтом. */
    fun pkgFor(a: String): String = pkgOf(appOf(a))

    /** Аккаунт включён (по умолчанию да). */
    fun enabled(a: String): Boolean = s("off_$a") != "1"
    fun setEnabled(a: String, on: Boolean) { if (on) remove("off_$a") else put("off_$a", "1") }

    /** Только включённые аккаунты — их берут режимы «ВСЕ» и «РАСПИСАНИЕ». */
    fun activeAccounts(): List<String> = accounts().filter { enabled(it) }

    fun followedCount(a: String): Int = s("fl_$a").lines().count { it.contains("|") }

    // белый список: этих ников бот никогда не отписывает
    fun whitelist(): Set<String> =
        s("whitelist").split("\n", ",", " ", ";").map { it.trim().removePrefix("@").lowercase() }
            .filter { it.isNotEmpty() }.toSet()

    fun remove(k: String) { p.edit().remove(k).apply() }
    fun all(): Map<String, *> = p.all

    // список «на кого подписался бот»: ник|время
    fun followed(): MutableList<Pair<String, Long>> =
        s("fl_${acc()}").lines().mapNotNull {
            val a = it.split("|")
            if (a.size == 2) a[0] to (a[1].toLongOrNull() ?: 0L) else null
        }.toMutableList()

    private fun saveFollowed(l: List<Pair<String, Long>>) =
        put("fl_${acc()}", l.joinToString("\n") { "${it.first}|${it.second}" })

    fun addFollowed(u: String) {
        val l = followed(); l.add(u to System.currentTimeMillis()); saveFollowed(l)
        put("fd_${acc()}", "${today()}|${followedToday() + 1}")
    }

    fun removeFollowed(u: String) = saveFollowed(followed().filter { it.first != u })

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun followedToday(): Int {
        val v = s("fd_${acc()}").split("|")
        return if (v.size == 2 && v[0] == today()) v[1].toIntOrNull() ?: 0 else 0
    }

    // часовые счётчики действий (по аккаунту): отметки времени за последний час
    private fun hourList(kind: String): MutableList<Long> {
        val now = System.currentTimeMillis()
        return s("h_${kind}_${acc()}").split(",").mapNotNull { it.toLongOrNull() }
            .filter { now - it < 3_600_000L }.toMutableList()
    }

    fun markHour(kind: String) {
        val l = hourList(kind); l.add(System.currentTimeMillis())
        put("h_${kind}_${acc()}", l.joinToString(","))
    }

    /** Сколько мс ждать, пока освободится место в часовом лимите (0 — можно действовать). */
    fun hourWait(kind: String, limit: Int): Long {
        val l = hourList(kind)
        if (l.size < limit) return 0L
        val oldest = l.minOrNull() ?: return 0L
        return maxOf(0L, oldest + 3_600_000L - System.currentTimeMillis()) + 1_000L
    }

    // ───────────── 2.3 общий каталог: все аккаунты равны ─────────────
    /** Ники из текста: через перевод строки, запятую, пробел или «;». Принимает @ник и ссылки instagram.com/ник. */
    fun parseNicks(text: String): List<String> =
        text.split("\n", ",", " ", ";", "\t").map { t ->
            var x = t.trim().removePrefix("@")
            if (x.contains("instagram.com/")) x = x.substringAfter("instagram.com/").substringBefore("/").substringBefore("?")
            x.lowercase()
        }.filter { Regex("^[a-z0-9._]{1,30}$").matches(it) }.distinct()

    /** Каталог: ники, на чьих подписчиков заходят аккаунты. Один на всех. */
    fun catalog(): List<String> = parseNicks(s("catalog"))

    /** Добавляет ники из текста (файл или вставка) в каталог. Возвращает, сколько новых. */
    fun addToCatalog(text: String): Int {
        val old = catalog()
        val add = parseNicks(text).filter { it !in old }
        put("catalog", (old + add).joinToString("\n"))
        return add.size
    }

    /** Один раз переносит старые поля «доноры» в каталог. */
    fun migrateCatalog(): Boolean {
        if (s("catalogMigrated") == "1") return false
        put("catalogMigrated", "1")
        if (catalog().isNotEmpty()) return false
        val old = parseNicks(s("donors") + "\n" + s("donorsSpecial"))
        if (old.isEmpty()) return false
        put("catalog", old.joinToString("\n"))
        return true
    }

    /** Когда каждый ник каталога использовался любым аккаунтом: так заходы расходятся по каталогу и аккаунты не повторяют друг друга. */
    fun catalogUse(): Map<String, Long> =
        s("catUse").lines().mapNotNull { val a = it.split("|"); if (a.size == 2) a[0] to (a[1].toLongOrNull() ?: 0L) else null }.toMap()

    fun markCatalogUse(nick: String) {
        val m = LinkedHashMap(catalogUse()); m.remove(nick); m[nick] = System.currentTimeMillis()
        put("catUse", m.entries.toList().takeLast(300).joinToString("\n") { "${it.key}|${it.value}" })
    }

    /** Все, на кого аккаунт когда-либо подписывался (включая уже отписанных): повторно на них не подписываемся. */
    fun everFollowed(acc: String): MutableSet<String> {
        val set = s("ev_$acc").lines().filter { it.isNotEmpty() }.toMutableSet()
        s("fl_$acc").lines().forEach { l -> val a = l.split("|"); if (a.size == 2) set.add(a[0]) }
        return set
    }

    fun addEver(acc: String, u: String) {
        val l = s("ev_$acc").lines().filter { it.isNotEmpty() } + u
        put("ev_$acc", l.takeLast(4000).joinToString("\n"))
    }

    // ───────────── 2.5 золотые профили ─────────────
    /** Свои («золотые») профили: бот проходит их первыми при каждом заходе. */
    fun golden(): List<String> = parseNicks(s("golden"))
    fun isGolden(nick: String): Boolean = nick.trim().removePrefix("@").lowercase() in golden()

    /** Сколько заходов к золотым профилям аккаунт сделал сегодня. */
    fun goldVisits(acc: String): Int {
        val v = s("gv_$acc").split("|")
        return if (v.size == 2 && v[0] == dayKey()) v[1].toIntOrNull() ?: 0 else 0
    }
    fun addGoldVisit(acc: String) { put("gv_$acc", dayKey() + "|" + (goldVisits(acc) + 1)) }
    /** Золотой заход: один раз в день на аккаунт (больше замедляет работу и повышает риск). */
    fun goldVisitsMax(): Int = 1
    /** Есть ли что проходить в золотом заходе: премиум-профили есть всегда, золотые добавляет пользователь. */
    fun hasPriority(): Boolean = Premium.all().isNotEmpty() || golden().isNotEmpty()

    /** Золотой заход аккаунту ещё положен сегодня. */
    fun goldenDue(acc: String): Boolean = hasPriority() && goldVisits(acc) < goldVisitsMax()

    // ───────────── новые подписчики ─────────────
    /** Новые подписчики аккаунта, которых уже обработали (последние 500). */
    fun nfDone(acc: String): Set<String> = s("nf_$acc").lines().filter { it.isNotEmpty() }.toSet()
    fun nfAdd(acc: String, u: String) { put("nf_$acc", (s("nf_$acc").lines().filter { it.isNotEmpty() } + u).takeLast(500).joinToString("\n")) }

    /** Один раз заменяет старые комментарии по умолчанию готовыми наборами (100+), пустые наборы заполняет. */
    fun seedComments(): Boolean {
        if (s("commentsSeeded") == "1") return false
        put("commentsSeeded", "1")
        val cur = s("comments").lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (cur.isEmpty() || cur == Presets.OLD_DEFAULT) put("comments", Presets.REGULAR.joinToString("\n"))
        if (s("goldenComments").isBlank()) put("goldenComments", Presets.GOLDEN.joinToString("\n"))
        if (s("newFollowerComments").isBlank()) put("newFollowerComments", Presets.NEW_FOLLOWER.joinToString("\n"))
        if (s("commentPct") == "20") put("commentPct", "35")
        return true
    }

    /** Сколько раз аккаунт сегодня «доделывали» из-за нуля подписок. */
    fun catchUps(acc: String): Int {
        val v = s("cu_$acc").split("|")
        return if (v.size == 2 && v[0] == dayKey()) v[1].toIntOrNull() ?: 0 else 0
    }
    fun addCatchUp(acc: String) { put("cu_$acc", dayKey() + "|" + (catchUps(acc) + 1)) }

    // паузы после блокировок Instagram
    fun pausedUntil(acc: String): Long = s("pause_$acc", "0").toLongOrNull() ?: 0L

    fun blocks(acc: String): List<Long> = s("blk_$acc").split(",").mapNotNull { it.toLongOrNull() }

    fun lastBlock(acc: String): Long = blocks(acc).maxOrNull() ?: 0L

    fun pause(acc: String, ms: Long) {
        val now = System.currentTimeMillis()
        put("pause_$acc", (now + ms).toString())
        put("blk_$acc", (blocks(acc) + now).takeLast(50).joinToString(","))
    }

    /** Пауза без записи в историю блокировок (экран подтверждения личности: это не блокировка действий). */
    fun pauseOnly(acc: String, ms: Long) { put("pause_$acc", (System.currentTimeMillis() + ms).toString()) }

    // ───────────── 2.7.3 что аккаунт уже сделал сегодня (переживает перезапуск) ─────────────
    private fun dayKey(): String = today()

    /** Этапы за сегодня: "f" — подписки, "e" — активность. Хранится как «дата|f,e». */
    private fun phases(acc: String): Set<String> {
        val v = s("ph_$acc")
        return if (v.startsWith(dayKey() + "|")) v.substringAfter("|").split(",").filter { it.isNotEmpty() }.toSet() else emptySet()
    }
    fun phaseDone(acc: String, ph: String): Boolean = ph in phases(acc)
    fun markPhase(acc: String, ph: String) { put("ph_$acc", dayKey() + "|" + (phases(acc) + ph).joinToString(",")) }
    fun unmarkPhase(acc: String, ph: String) { put("ph_$acc", dayKey() + "|" + (phases(acc) - ph).joinToString(",")) }
    fun phasesToday(acc: String): String =
        phases(acc).joinToString(", ") { if (it == "f") "подписки" else if (it == "e") "активность" else it }

    /** Профили, на которых бот уже отработал активность сегодня. */
    fun engagedToday(acc: String): Set<String> {
        val v = s("eg_$acc")
        return if (v.startsWith(dayKey() + "|")) v.substringAfter("|").split(",").filter { it.isNotEmpty() }.toSet() else emptySet()
    }
    fun markEngaged(acc: String, u: String) { put("eg_$acc", dayKey() + "|" + (engagedToday(acc) + u).joinToString(",")) }

    /** Лайки текущей (возможно оборванной) сессии активности. */
    fun sessionLikes(acc: String): Int {
        val v = s("lk_$acc").split("|")
        return if (v.size == 2 && v[0] == dayKey()) v[1].toIntOrNull() ?: 0 else 0
    }
    fun setSessionLikes(acc: String, n: Int) { if (n <= 0) remove("lk_$acc") else put("lk_$acc", dayKey() + "|" + n) }

    /** Кнопка «Сбросить сделано сегодня»: все аккаунты начнут сегодняшний круг заново. */
    fun resetToday() {
        for (a in accounts() + acc()) listOf("done_", "ph_", "eg_", "lk_", "cu_", "gv_").forEach { remove(it + a) }
    }

    fun clearPause(acc: String) { put("pause_$acc", "0") }

    fun lastUnfollow(): Long = s("lu_${acc()}", "0").toLongOrNull() ?: 0L
    fun markUnfollow() = put("lu_${acc()}", System.currentTimeMillis().toString())
}

/** Журнал: хранится в памяти и в файле, поэтому не пропадает после вылета. */
object BotLog {
    private var file: File? = null
    private val mem = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)

    @Synchronized fun init(c: Context) {
        if (file != null) return
        val f = File(c.applicationContext.filesDir, "log.txt")
        file = f
        try {
            if (f.exists() && f.length() > 200_000) {
                val tail = f.readLines().takeLast(500)
                f.writeText(tail.joinToString("\n") + "\n")
            }
            if (f.exists()) f.readLines().takeLast(150).forEach { mem.addLast(it) }
        } catch (_: Exception) {}
    }

    @Synchronized fun add(s: String) {
        val line = "${fmt.format(Date())}  $s"
        mem.addLast(line)
        while (mem.size > 150) mem.removeFirst()
        try { file?.appendText(line + "\n") } catch (_: Exception) {}
    }

    @Synchronized fun text(): String = mem.reversed().joinToString("\n")

    @Synchronized fun clear() {
        mem.clear()
        try { file?.writeText("") } catch (_: Exception) {}
    }
}

/** Запись причин вылета в файл, чтобы их можно было посмотреть в приложении. */
object CrashHandler {
    private const val FILE = "crash.txt"

    fun install(c: Context) {
        val app = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            record(app, "вылет, поток ${t.name}", e)
            prev?.uncaughtException(t, e)
        }
    }

    fun record(c: Context, where: String, e: Throwable) {
        try {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            File(c.applicationContext.filesDir, FILE).writeText("$ts  $where\n$sw")
            BotLog.add("СБОЙ ($where): ${e.javaClass.simpleName}: ${e.message}")
        } catch (_: Throwable) {}
    }

    fun last(c: Context): String = try {
        val f = File(c.applicationContext.filesDir, FILE)
        if (f.exists()) f.readText() else ""
    } catch (_: Throwable) { "" }

    fun clear(c: Context) { try { File(c.applicationContext.filesDir, FILE).delete() } catch (_: Throwable) {} }
}
