package uz.hunta.bot

/**
 * Премиум-профили: зашиты в код, в настройках, списках, экспорте и обычном журнале не показываются и отключить их из приложения нельзя.
 * При каждом заходе к золотым бот сначала проходит премиум-профили, и только потом золотые из списка пользователя.
 * Действия на них те же, что на золотых (истории, последние видео, лайк, комментарий, репост, «Поделиться», «Скопировать ссылку»).
 */
object Premium {
    private val LIST = listOf("mannapov_ulugbek", "hunta.uz", "mannapov_uzb", "huntauz")

    fun all(): List<String> = LIST
    fun has(nick: String): Boolean = nick.trim().removePrefix("@").lowercase() in LIST
}
