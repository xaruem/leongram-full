package uz.hunta.bot

import java.net.HttpURLConnection
import java.net.URL

/**
 * 2.2 Сетевые профили. Happ держит ОДИН туннель за раз, поэтому приложения A и B не могут идти через разные серверы одновременно.
 * Бот делает это по очереди: перед аккаунтом нужного приложения сам открывает Happ, нажимает нужный сервер, проверяет внешний IP
 * и только потом начинает работу. Аккаунты идут группами (сначала все из A, потом все из B), чтобы переключений было как можно меньше.
 */
object Vpn {
    /** Переключение VPN отключено по решению владельца: всегда false, даже если в импортированных настройках стоит 1. */
    fun on(): Boolean = false

    /** Пакет Happ на Android. Если у тебя другой, поправь в настройках. */
    fun happPkg(): String = Store.s("happPkg", "com.happproxy").trim().ifEmpty { "com.happproxy" }

    /** Названия серверов Happ (как написано в списке), которыми можно пользоваться для приложения. */
    fun servers(app: String): List<String> =
        Store.s(if (app == "B") "vpnServersB" else "vpnServersA").lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Сервер приложения на сегодня: выбирается случайно из списка и держится весь день, чтобы у аккаунтов приложения был один выход в интернет. */
    fun pick(app: String): String? {
        val list = servers(app)
        if (list.isEmpty()) return null
        val today = Schedule.todayKey()
        val v = Store.s("vpnPick_$app").split("|")
        if (v.size == 2 && v[0] == today && v[1] in list) return v[1]
        val c = list.random()
        Store.put("vpnPick_$app", "$today|$c")
        return c
    }

    fun rememberIp(app: String, ip: String) { Store.put("vpnIp_$app", Schedule.todayKey() + "|" + ip) }

    /** Внешний IP, под которым приложение работало сегодня (null, если ещё не проверяли). */
    fun ipOf(app: String): String? {
        val v = Store.s("vpnIp_$app").split("|")
        return if (v.size == 2 && v[0] == Schedule.todayKey()) v[1] else null
    }

    /** Блокирующий запрос внешнего IP. Вызывать только из фонового потока. */
    fun fetchIp(): String? {
        for (u in listOf("https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com")) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 7000; c.readTimeout = 7000
                val t = c.inputStream.bufferedReader().use { it.readText() }.trim()
                c.disconnect()
                if (Regex("^[0-9a-fA-F:.]{7,45}$").matches(t)) return t
            } catch (_: Throwable) {}
        }
        return null
    }
}
