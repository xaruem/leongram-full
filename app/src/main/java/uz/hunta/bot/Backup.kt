package uz.hunta.bot

import org.json.JSONObject

/** Экспорт и импорт всех настроек и данных аккаунтов в JSON-файл. */
object Backup {
    private const val MAGIC = "huntabot-backup"

    fun export(): String {
        val o = JSONObject()
        o.put("format", MAGIC); o.put("version", 1)
        o.put("time", System.currentTimeMillis())
        val d = JSONObject()
        for ((k, v) in Store.all()) if (v is String) d.put(k, v)
        o.put("data", d)
        return o.toString(2)
    }

    /** Возвращает число восстановленных значений или бросает исключение с понятным текстом. */
    fun import(text: String): Int {
        val o = try { JSONObject(text) } catch (_: Exception) { throw IllegalArgumentException("Это не файл настроек Leongram") }
        if (o.optString("format") != MAGIC) throw IllegalArgumentException("Это не файл настроек Leongram")
        val d = o.getJSONObject("data")
        var n = 0
        val it = d.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (k == "setupShown") continue
            Store.put(k, d.getString(k)); n++
        }
        return n
    }

    fun fileName(): String =
        "huntabot-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".json"
}
