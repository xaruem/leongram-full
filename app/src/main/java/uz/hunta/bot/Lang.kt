package uz.hunta.bot

/**
 * Язык Instagram. Бот внутри ищет английские подписи («Home», «Profile», «Follow»…).
 * Все тексты экрана проходят через canon(): русские и узбекские подписи превращаются в английские, остальной код ничего не замечает.
 * Если какая-то подпись у тебя называется иначе, добавь её в «Настройки → Язык Instagram» строкой «как на экране = English».
 * Тексты RU/UZ написаны по памяти, на твоём телефоне проверяются в журнале («Снимок экрана»).
 */
object Lang {
    /** Весь текст элемента целиком (строчными) → английская подпись. */
    private val exact: Map<String, String> = HashMap<String, String>().apply {
        fun add(en: String, vararg local: String) { for (l in local) put(l, en) }
        add("Home", "главная", "bosh sahifa", "asosiy")
        add("Profile", "профиль", "profil")
        add("Follow", "подписаться", "obuna bo'lish", "obuna bo‘lish", "kuzatish")
        add("Following", "подписки", "вы подписаны", "obunalar", "obuna bo'lingan", "kuzatilmoqda")
        add("Follow back", "подписаться в ответ", "javoban obuna bo'lish")
        add("Message", "сообщение", "написать", "написать сообщение", "xabar", "xabar yuborish")
        add("Unfollow", "отменить подписку", "отписаться", "obunani bekor qilish")
        add("Like", "нравится", "yoqdi", "yoqtirish")
        add("Unlike", "отменить «нравится»", "отменить отметку «нравится»", "отменить отметку \"нравится\"", "yoqtirishni bekor qilish")
        add("Comment", "комментировать", "комментарий", "izoh", "izoh qoldirish")
        add("Post", "опубликовать", "joylash")
        add("Share", "поделиться", "ulashish")
        add("Send post", "отправить публикацию", "постни юбориш")
        add("Send", "отправить", "yuborish")
        add("Notifications", "уведомления", "bildirishnomalar")
        add("Repost", "репост", "сделать репост", "qayta joylash")
        add("Copy link", "копировать ссылку", "скопировать ссылку", "havolani nusxalash", "havolani nusxalab olish")
    }

    /** Части фраз: заменяются внутри текста. Порядок важен: сначала частные случаи. */
    private val parts: List<Pair<Regex, String>> = listOf(
        Regex("(?iu)ряд\\s*1\\W+столб\\p{L}*\\s*1") to "Row 1, Column 1",
        Regex("(?iu)столб\\p{L}*\\s*1(?!\\d)") to "Column 1",
        Regex("(?iu)подписал\\S*\\s+на\\s+(ваши\\s+обновления|вас)") to "started following you",
        Regex("(?iu)подписан\\S*\\s+на\\s+вас") to "follows you",
        Regex("(?iu)закрыт\\p{L}*\\s+аккаунт") to "private account",
        Regex("(?iu)подписчик\\p{L}*") to "followers",
        Regex("(?iu)фото профиля") to "profile picture",
        Regex("(?iu)ваша истори\\p{L}*") to "your story",
        Regex("(?iu)добавить в истори\\p{L}*") to "add to story",
        Regex("(?iu)истори\\p{L}*") to "story",
        Regex("(?iu)переключ\\p{L}*|сменить аккаунт") to "switch"
    )

    private var cacheRaw: String? = null
    private var cacheMap: Map<String, String> = emptyMap()

    /** Свои переводы из настроек: «как на экране = English», по одному в строке. */
    private fun custom(): Map<String, String> {
        val raw = Store.s("langMap")
        if (raw != cacheRaw) {
            cacheRaw = raw
            cacheMap = raw.lines().mapNotNull { l ->
                val i = l.indexOf('=')
                if (i <= 0) null else {
                    val k = Screens.norm(l.substring(0, i)); val v = l.substring(i + 1).trim()
                    if (k.isEmpty() || v.isEmpty()) null else k to v
                }
            }.toMap()
        }
        return cacheMap
    }

    /** Английский вариант подписи. Английские тексты не меняются. */
    fun canon(raw: String): String {
        if (raw.isEmpty()) return raw
        val key = Screens.norm(raw)
        custom()[key]?.let { return it }
        exact[key]?.let { return it }
        if (!raw.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' }) return raw   // без кириллицы дальше смотреть незачем
        var s = raw
        for ((re, en) in parts) if (re.containsMatchIn(s)) s = re.replace(s, en)
        return s
    }
}
