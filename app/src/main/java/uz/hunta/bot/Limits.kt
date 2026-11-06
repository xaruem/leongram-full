package uz.hunta.bot

import kotlin.math.roundToInt

/**
 * Лимиты по каждому аккаунту отдельно.
 * База — числа из полей «Подписок в день», «Лайков за сессию», «Отписок раз в неделю».
 *  • Новый     — 33% базы в первую неделю, +17% каждую следующую, с 5-й недели 100%
 *  • Обычный   — 100% базы
 *  • Прогретый — 150% базы
 * Жёсткие потолки защищают от случайно больших значений в полях.
 */
object Limits {
    const val NEW = "new"
    const val NORMAL = "normal"
    const val WARM = "warm"

    private const val WEEK = 7 * 86_400_000L
    private val TIERS = setOf(NEW, NORMAL, WARM)

    fun tier(acc: String): String = Store.s("tier_$acc", NEW).let { if (it in TIERS) it else NEW }

    fun setTier(acc: String, t: String) { Store.put("tier_$acc", t) }

    /** Дата, с которой считаются недели роста лимитов. Ставится при первом обращении. */
    fun since(acc: String): Long {
        val v = Store.s("since_$acc", "").toLongOrNull()
        if (v != null) return v
        val now = System.currentTimeMillis()
        Store.put("since_$acc", now.toString())
        return now
    }

    fun resetSince(acc: String) { Store.put("since_$acc", System.currentTimeMillis().toString()) }

    /** Номер недели с нуля. */
    fun week(acc: String): Int = ((System.currentTimeMillis() - since(acc)) / WEEK).toInt().coerceAtLeast(0)

    /** Три дня после блокировки лимиты вдвое меньше. */
    fun cautious(acc: String): Boolean {
        val lb = Store.lastBlock(acc)
        return lb > 0 && System.currentTimeMillis() - lb < 3 * 86_400_000L
    }

    fun factor(acc: String): Double {
        val base = when (tier(acc)) {
            NEW -> minOf(1.0, 0.33 + 0.17 * week(acc))
            NORMAL -> 1.0
            else -> 1.5
        }
        return if (cautious(acc)) base * 0.5 else base
    }

    private fun scaled(base: Int, f: Double, cap: Int): Int = minOf(cap, maxOf(1, (base * f).roundToInt()))

    fun followDay(acc: String): Int = scaled(Store.i("followDay", 20), factor(acc), 100)
    fun likes(acc: String): Int = scaled(Store.i("likes", 10), factor(acc), 100)
    fun unfollowWeek(acc: String): Int = scaled(Store.i("unfollowWeek", 50), factor(acc), 150)

    fun followHour(acc: String): Int = scaled(Store.i("followHour", 12), factor(acc), 30)
    fun unfollowHour(acc: String): Int = scaled(Store.i("unfollowHour", 15), factor(acc), 40)

    fun tierName(t: String): String = when (t) {
        NEW -> "Новый"
        NORMAL -> "Обычный"
        else -> "Прогретый"
    }

    fun describe(acc: String): String =
        "${tierName(tier(acc))}, неделя ${week(acc) + 1}: подписок ${followDay(acc)}/день, " +
            "лайков ${likes(acc)}/сессия, отписок ${unfollowWeek(acc)}/неделя (не больше ${followHour(acc)} подписок и ${unfollowHour(acc)} отписок в час)" +
            (if (cautious(acc)) " [осторожный режим ×0.5 после блокировки]" else "")
}
