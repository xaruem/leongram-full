package uz.hunta.bot

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Рабочее окно (часы «с» и «до») и вспомогательные функции времени.
 * Если «с» и «до» одинаковые — ограничений по времени нет.
 */
object Schedule {
    fun startH(): Int = Store.i("startHour", 10).coerceIn(0, 23)
    fun endH(): Int = Store.i("endHour", 22).coerceIn(0, 24)

    fun always(): Boolean = startH() == endH()

    // «ночной режим»: в эти часы бот не делает никаких действий
    fun nightOn(): Boolean = Store.s("nightOn", "1") == "1"
    fun nightFrom(): Int = Store.i("nightFrom", 23).coerceIn(0, 23)
    fun nightTo(): Int = Store.i("nightTo", 7).coerceIn(0, 23)

    private fun curHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    fun hourInNight(h: Int): Boolean {
        if (!nightOn()) return false
        val f = nightFrom(); val t = nightTo()
        if (f == t) return false
        return if (f < t) h in f until t else (h >= f || h < t)
    }

    fun hourInWork(h: Int): Boolean {
        if (always()) return true
        val s = startH(); val e = endH()
        return if (s < e) h in s until e else (h >= s || h < e)
    }

    fun inNight(): Boolean = hourInNight(curHour())

    fun inWindow(): Boolean = !inNight() && hourInWork(curHour())

    private fun at(dayOffset: Int, hour: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, dayOffset)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun windowStart(dayOffset: Int): Long = at(dayOffset, startH())
    fun windowEnd(dayOffset: Int): Long = at(dayOffset, endH())

    // ───────────── 2.7.4 питание телефона (автовыключение / автовключение по расписанию Xiaomi) ─────────────
    /** Час, когда телефон выключается по расписанию Xiaomi. -1 — не задано. */
    fun powerOffH(): Int = Store.i("powerOffHour", -1).let { if (it in 0..23) it else -1 }
    /** Час, когда телефон включается. -1 — не задано. */
    fun powerOnH(): Int = Store.i("powerOnHour", -1).let { if (it in 0..23) it else -1 }

    private fun nextAt(from: Long, hour: Int): Long = Calendar.getInstance().apply {
        timeInMillis = from
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= from) add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    /** Ближайшее выключение телефона после момента [from] (null, если не задано). */
    fun nextPowerOff(from: Long): Long? = if (powerOffH() < 0) null else nextAt(from, powerOffH())

    /** Когда телефон снова включится после выключения в момент [off] (null, если не задано). */
    fun powerBackAt(off: Long): Long? = if (powerOnH() < 0) null else nextAt(off, powerOnH())

    /** До выключения телефона осталось меньше [minutes] минут: новый аккаунт не начинаем. */
    fun nearPowerOff(minutes: Int = 25): Boolean {
        val now = System.currentTimeMillis()
        val po = nextPowerOff(now) ?: return false
        return po - now < minutes * 60_000L
    }

    fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    fun dayKey(dayOffset: Int): String {
        val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, dayOffset)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
    }
    fun dm(ms: Long): String = SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(ms))
    fun hm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
}
