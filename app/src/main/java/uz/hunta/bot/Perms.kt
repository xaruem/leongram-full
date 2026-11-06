package uz.hunta.bot

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Проверка разрешений телефона. После переустановки приложения MIUI сбрасывает разрешения, и самое частое последствие:
 * бот не может открыть Instagram из фона (на экране остаётся рабочий стол).
 */
object Perms {
    /** MIUI: «Показывать всплывающие окна, когда приложение работает в фоне». null — проверить не удалось (не MIUI или скрыто). */
    fun miuiPopup(c: Context): Boolean? = try {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val m = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
        (m.invoke(ops, 10021, android.os.Process.myUid(), c.packageName) as Int) == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { null }

    fun battery(c: Context): Boolean = try {
        (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)
    } catch (_: Throwable) { false }

    fun overlay(c: Context): Boolean = try { Settings.canDrawOverlays(c) } catch (_: Throwable) { false }

    private fun mark(v: Boolean?) = when (v) { true -> "✅"; false -> "❌"; null -> "❓" }

    /** Короткая строка для журнала. */
    fun short(c: Context): String =
        "всплывающие окна в фоне ${mark(miuiPopup(c))}, батарея без ограничений ${mark(battery(c))}, поверх других окон ${mark(overlay(c))}, спецвозможности ${mark(BotService.instance != null)}"

    /** Подробный отчёт для экрана «Проверка разрешений». */
    fun report(c: Context): String {
        val popup = miuiPopup(c)
        val sb = StringBuilder()
        sb.append("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n\n")
        sb.append("${mark(BotService.instance != null)} Спецвозможности (служба бота)\n")
        sb.append("   Выключены: Настройки → Спецвозможности → Leongram → включить.\n\n")
        sb.append("${mark(popup)} «Показывать всплывающие окна, когда приложение работает в фоне»\n")
        sb.append("   ГЛАВНОЕ. Без этого MIUI не даёт боту открыть Instagram: на экране остаётся рабочий стол, проверка перед запуском не проходится. ")
        sb.append("Настройки → Приложения → Leongram → Другие разрешения.\n")
        if (popup == null) sb.append("   (Автоматически проверить не удалось: открой этот пункт и убедись вручную.)\n")
        sb.append("\n${mark(battery(c))} Батарея: «Нет ограничений» / не оптимизировать\n")
        sb.append("   Иначе система усыпляет бота ночью.\n\n")
        sb.append("${mark(overlay(c))} Поверх других окон\n")
        sb.append("   Не обязательно, но помогает открывать Instagram на некоторых прошивках.\n\n")
        sb.append("Если ты ставил приложение заново (удалял и устанавливал), MIUI сбросила все эти разрешения и автозапуск. ")
        sb.append("Пройди «Настройка телефона (мастер Redmi)» ещё раз.")
        return sb.toString()
    }
}
