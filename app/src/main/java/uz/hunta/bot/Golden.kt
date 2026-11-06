package uz.hunta.bot

/**
 * 2.5 / 2.8 «Золотые» профили: свои страницы, которые бот проходит первыми при каждом заходе.
 * Здесь счётчики золотых действий по аккаунтам (отдельно от обычной статистики) и шаблоны комментариев с вариациями.
 */
object Gold {
    const val VIDEOS = 0     // видео просмотрено
    const val LIKES = 1      // лайк поставлен
    const val COMMENTS = 2   // комментарий написан
    const val REPOSTS = 3    // репост сделан
    const val LINKS = 4      // ссылка скопирована
    const val SKIPPED = 5    // лайк уже стоял, видео пропущено
    val TITLES = listOf("видео", "лайков", "комментариев", "репостов", "ссылок", "пропущено (лайк уже был)")

    private fun key(acc: String) = "gd_$acc"
    private fun day(): String = Schedule.todayKey()

    @Synchronized fun today(acc: String): IntArray {
        val v = Store.s(key(acc))
        val out = IntArray(6)
        if (v.startsWith(day() + "|")) {
            v.substringAfter("|").split(",").forEachIndexed { i, x -> if (i < 6) out[i] = x.toIntOrNull() ?: 0 }
        }
        return out
    }

    @Synchronized fun inc(acc: String, kind: Int) {
        val a = today(acc); a[kind]++
        Store.put(key(acc), day() + "|" + a.joinToString(","))
    }

    /** Строка для экранов: «золотые: 3 видео · 2 лайков…» или пусто, если за сегодня ничего. */
    fun line(acc: String): String {
        val a = today(acc)
        if (a.all { it == 0 }) return ""
        return "золотые: видео ${a[VIDEOS]}, лайков ${a[LIKES]}, комментариев ${a[COMMENTS]}, репостов ${a[REPOSTS]}, ссылок ${a[LINKS]}, пропущено ${a[SKIPPED]}"
    }

    // ───────────── шаблоны комментариев (2.5.5) ─────────────
    private val group = Regex("\\{([^{}]*)\\}")

    /** «{Класс|Круто|Огонь} {!|.}» превращается в случайный вариант, например «Круто !». Группы можно вкладывать по очереди. */
    fun spin(t: String): String {
        var s = t
        var n = 0
        while (n++ < 30) {
            val m = group.find(s) ?: break
            val opts = m.groupValues[1].split("|")
            s = s.replaceRange(m.range, opts.random())
        }
        return s.replace(Regex("\\s+"), " ").replace(" !", "!").replace(" .", ".").trim()
    }

    /**
     * Берёт шаблон, раскрывает вариации и не повторяет текст, который уже писали любые аккаунты недавно (последние 40).
     * Если шаблонов мало и все варианты уже были, берётся последний полученный.
     */
    fun pick(templates: List<String>): String {
        if (templates.isEmpty()) return ""
        val recent = Store.s("cmRecent").split("\n").filter { it.isNotEmpty() }
        var text = ""
        for (i in 0 until 12) {
            text = spin(templates.random())
            if (text.lowercase() !in recent) break
        }
        Store.put("cmRecent", (recent + text.lowercase()).takeLast(40).joinToString("\n"))
        return text
    }
}
