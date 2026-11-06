package uz.hunta.bot

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Статистика по дням для каждого аккаунта: подписки, лайки, отписки, комментарии. */
object Stats {
    const val FOLLOW = 0
    const val LIKE = 1
    const val UNFOLLOW = 2
    const val COMMENT = 3
    val TITLES = listOf("Подписок", "Лайков", "Отписок", "Комментариев")

    private fun day(offset: Int): String {
        val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, offset)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
    }

    private fun key(acc: String, d: String) = "st_${acc}_$d"

    private fun read(acc: String, d: String): IntArray {
        val v = Store.s(key(acc, d)).split(",").map { it.toIntOrNull() ?: 0 }
        return IntArray(4) { v.getOrElse(it) { 0 } }
    }

    @Synchronized fun inc(acc: String, kind: Int) {
        val d = day(0)
        val a = read(acc, d); a[kind]++
        Store.put(key(acc, d), a.joinToString(","))
    }

    fun forDay(acc: String, offset: Int): IntArray = read(acc, day(offset))

    fun label(offset: Int): String = when (offset) {
        0 -> "сегодня"
        -1 -> "вчера"
        else -> day(offset).substring(5).split("-").reversed().joinToString(".")
    }

    /** Дата вида «30.10». */
    fun dateLabel(offset: Int): String = day(offset).substring(5).split("-").reversed().joinToString(".")

    /** Итог за последние [days] дней, включая сегодня. */
    fun total(acc: String, days: Int): IntArray {
        val t = IntArray(4)
        for (o in 0 until days) { val a = forDay(acc, -o); for (i in 0..3) t[i] += a[i] }
        return t
    }

    /** Удаляет записи старше 90 дней. */
    fun prune() {
        val limit = "st_" // ключи: st_<ник>_<дата>
        val cut = day(-90)
        Store.all().keys.filter { it.startsWith(limit) }.forEach { k ->
            val d = k.takeLast(10)
            if (d < cut) Store.remove(k)
        }
    }
}
