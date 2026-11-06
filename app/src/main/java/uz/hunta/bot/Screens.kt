package uz.hunta.bot

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 2.7.2 Библиотека экранов.
 * Правило = «если на экране есть такая-то фраза, нажми такую-то кнопку».
 * Встроенные правила закрывают типовые всплывающие окна Instagram и системные окна Android.
 * Свои правила пишутся в приложении (Библиотека экранов) строкой вида:
 *     фраза на экране => кнопка | другая кнопка
 * Незнакомые экраны, на которых бот застрял, попадают в журнал «Неизвестные экраны»:
 * их присылают, и под каждый добавляется правило.
 */
object Screens {

    enum class Act {
        /** Нажать кнопку из списка. */
        CLICK,
        /** Нажать кнопку и пометить, что Instagram упал: сторож перезапустит его. */
        CRASH,
        /** Экран, который бот сам не решит (подтверждение личности): аккаунт на паузу, остальные работают. */
        PROBLEM
    }

    class Rule(
        val id: String,
        val title: String,
        /** Любая из фраз (строчными) на экране включает правило. */
        val triggers: List<String>,
        /** Подписи кнопок (строчными), по порядку предпочтения. «[назад]» — системная кнопка «Назад». */
        val buttons: List<String>,
        val act: Act = Act.CLICK,
        /** true — искать только в окнах НЕ Instagram (системные диалоги). */
        val system: Boolean = false,
        val custom: Boolean = false
    )

    const val BACK = "[назад]"

    /** Приводит текст к виду для сравнения: строчные буквы, одинаковые апострофы. */
    fun norm(s: String): String =
        s.lowercase(Locale.ROOT).replace('’', '\'').replace('‘', '\'').replace('`', '\'').replace('ʻ', '\'').trim()

    private val NOT_NOW = listOf("not now", "не сейчас", "hozir emas")

    val builtin: List<Rule> = listOf(
        Rule(
            "notif_off", "Уведомления выключены (Turn On / Not now)",
            listOf("your notifications are off", "уведомления выключены", "уведомления отключены", "bildirishnomalar o'chirilgan"),
            NOT_NOW
        ),
        Rule(
            "notif_ask", "Включить уведомления?",
            listOf("turn on notifications", "включить уведомления", "bildirishnomalarni yoqish"),
            NOT_NOW
        ),
        Rule(
            "save_login", "Сохранить данные для входа?",
            listOf("save your login info", "save login info", "сохранить данные для входа", "login ma'lumotlarini saqlash"),
            NOT_NOW
        ),
        Rule(
            "contacts_sync", "Синхронизация контактов",
            listOf("sync contacts", "connect your contacts", "connect contacts", "синхронизировать контакты", "kontaktlarni sinxronlash"),
            NOT_NOW + listOf("skip", "пропустить")
        ),
        Rule(
            "phone_add", "Добавить номер телефона",
            listOf("add your phone number", "add phone number", "добавьте номер телефона", "telefon raqamingizni qo'shing"),
            NOT_NOW + listOf("skip", "пропустить")
        ),
        Rule(
            "perm_deny", "Системный запрос разрешения (контакты, фото, камера…) — отказать",
            listOf("allow instagram", "разрешить instagram", "разрешить приложению instagram", "разрешить приложению «instagram"),
            listOf("don't allow", "deny", "не разрешать", "запретить", "rad etish"),
            system = true
        ),
        Rule(
            "vpn_consent", "Системный запрос на VPN-подключение (Happ) — разрешить",
            listOf("wants to set up a vpn connection", "хочет настроить vpn-подключение", "запрос на подключение", "connection request"),
            listOf("ok", "ок", "разрешить", "allow"),
            system = true
        ),
        Rule(
            "anr", "Instagram не отвечает — подождать",
            listOf("instagram isn't responding", "instagram не отвечает"),
            listOf("wait", "подождать", "kutish"),
            system = true
        ),
        Rule(
            "crash", "Instagram остановлен (вылет) — закрыть и перезапустить",
            listOf("instagram keeps stopping", "instagram has stopped", "instagram постоянно останавливается", "instagram остановлен"),
            listOf("close app", "закрыть приложение", "ok", "ок", "закрыть"),
            act = Act.CRASH, system = true
        ),
        Rule(
            "challenge", "Подтверждение личности — аккаунт на паузу",
            listOf(
                "confirm it's you", "help us confirm you own this account", "we detected unusual activity",
                "suspicious login attempt", "подтвердите, что это вы", "подтвердите, что это ваш аккаунт"
            ),
            emptyList(),
            act = Act.PROBLEM
        )
    )

    /** Свои правила из приложения. Строки без «=>» пропускаются. Префикс «sys:» — искать в системных окнах. */
    fun custom(): List<Rule> = parseCustom(Store.s("screenRules")).first

    /** Возвращает (правила, число пропущенных непустых строк). */
    fun parseCustom(text: String): Pair<List<Rule>, Int> {
        val out = ArrayList<Rule>()
        var bad = 0
        for ((idx, raw) in text.lines().withIndex()) {
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            var sys = false
            if (line.lowercase(Locale.ROOT).startsWith("sys:")) { sys = true; line = line.substring(4).trim() }
            val parts = line.split("=>")
            if (parts.size != 2) { bad++; continue }
            val trig = parts[0].split("|").map { norm(it) }.filter { it.isNotEmpty() }
            val btn = parts[1].split("|").map { norm(it) }.filter { it.isNotEmpty() }
            if (trig.isEmpty() || btn.isEmpty()) { bad++; continue }
            out.add(Rule("custom${idx + 1}", "Своё правило: «${trig.first().take(30)}» → «${btn.first()}»", trig, btn, Act.CLICK, sys, true))
        }
        return out to bad
    }

    /** Свои правила проверяются первыми, чтобы можно было переопределить встроенное. */
    fun rules(): List<Rule> = custom() + builtin

    fun enabled(id: String): Boolean = Store.s("scrOff_$id") != "1"
    fun setEnabled(id: String, on: Boolean) { if (on) Store.remove("scrOff_$id") else Store.put("scrOff_$id", "1") }

    /** Главный выключатель библиотеки. */
    fun libraryOn(): Boolean = Store.s("screenLib", "1") == "1"

    fun hits(id: String): Int = Store.i("scr_$id", 0)
    fun hit(id: String) { Store.put("scr_$id", (hits(id) + 1).toString()); Store.put("scrLast", fmt()) }
    fun lastHit(): String = Store.s("scrLast")

    private fun fmt(): String = SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date())

    // ───────────── журнал неизвестных экранов ─────────────
    private const val UNKNOWN_FILE = "unknown_screens.txt"
    private const val UNKNOWN_KEEP = 30

    @Synchronized
    fun noteUnknown(c: Context, reason: String, pkg: String, texts: List<String>, buttons: List<String>) {
        try {
            val f = File(c.applicationContext.filesDir, UNKNOWN_FILE)
            val old = if (f.exists()) f.readText().split("\n\n").filter { it.isNotBlank() } else emptyList()
            val e = StringBuilder()
            e.append("[${fmt()}] $reason\n")
            e.append("Пакет: $pkg\n")
            e.append("Тексты на экране: ").append(if (texts.isEmpty()) "(нет)" else texts.joinToString(" | ")).append("\n")
            e.append("Кнопки: ").append(if (buttons.isEmpty()) "(нет)" else buttons.joinToString(" | "))
            f.writeText((old + e.toString()).takeLast(UNKNOWN_KEEP).joinToString("\n\n") + "\n\n")
        } catch (_: Throwable) {}
    }

    fun unknown(c: Context): String = try {
        val f = File(c.applicationContext.filesDir, UNKNOWN_FILE)
        if (f.exists()) f.readText().split("\n\n").filter { it.isNotBlank() }.reversed().joinToString("\n\n") else ""
    } catch (_: Throwable) { "" }

    fun clearUnknown(c: Context) { try { File(c.applicationContext.filesDir, UNKNOWN_FILE).delete() } catch (_: Throwable) {} }
}

/** Экран, который бот не может пройти сам (например, подтверждение личности). */
class ScreenProblem(msg: String) : IllegalStateException(msg)
