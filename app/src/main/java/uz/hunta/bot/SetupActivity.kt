package uz.hunta.bot

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.CheckBox
import android.widget.LinearLayout

/**
 * Мастер первого запуска из трёх шагов:
 *  1. включить спецвозможности,
 *  2. выдать разрешения MIUI,
 *  3. проверить аккаунты.
 * Что можно проверить автоматически — проверяется само, остальное отмечается галочкой.
 */
class SetupActivity : Page() {

    private class Item(
        val id: String,
        val title: String,
        val help: String,
        val open: (() -> Unit)?,
        val auto: (() -> Boolean)?
    )

    private var step: Int
        get() = Store.i("setupStep", 0).coerceIn(0, 2)
        set(v) { Store.put("setupStep", v.toString()) }

    private fun go(vararg intents: Intent) {
        for (x in intents) {
            try { startActivity(x); return } catch (_: Throwable) {}
        }
        toast("Не получилось открыть автоматически — найди этот пункт в настройках вручную")
    }

    private fun miui(cls: String, pkgName: String = "com.miui.securitycenter") = Intent().setClassName(pkgName, cls)

    private val details get() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    private fun accessibilityItems() = listOf(
        Item("acc", "Включить спецвозможности",
            "Спецвозможности → Установленные службы (или «Скачанные») → Leongram → включить. Без этого бот не работает.",
            { go(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            { BotService.instance != null }),
        Item("restricted", "Переключатель серый?",
            "Откроется «О приложении». Нажми ⋮ вверху справа → «Разрешить ограниченные настройки», потом вернись и включи спецвозможности.",
            { go(details) }, null)
    )

    private fun miuiItems(): List<Item> {
        val pkg = packageName
        return listOf(
            Item("notif", "Уведомления",
                "Пока бот работает, в шторке висит уведомление с кнопкой СТОП. Сюда же придёт сообщение об автопаузе при блокировке.",
                { go(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg), details) },
                { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled() }),
            Item("autostart", "Автозапуск — ВКЛ",
                "Откроется список «Автозапуск». Найди Leongram и включи переключатель.",
                { go(miui("com.miui.permcenter.autostart.AutoStartManagementActivity"), details) }, null),
            Item("saver", "Экономия энергии — «Нет ограничений»",
                "Если окно не открылось: О приложении → Экономия энергии → Нет ограничений.",
                {
                    go(
                        miui("com.miui.powerkeeper.ui.HiddenAppsConfigActivity", "com.miui.powerkeeper")
                            .putExtra("package_name", pkg).putExtra("package_label", getString(R.string.app_name)),
                        details
                    )
                }, null),
            Item("battery", "Не оптимизировать батарею",
                "Нажми «Разрешить», когда система спросит про работу в фоне без ограничений батареи.",
                {
                    go(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                        details
                    )
                },
                { (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(pkg) }),
            Item("popup", "«Показывать всплывающие окна, когда приложение работает в фоне» — ВКЛ",
                "В списке «Другие разрешения» найди этот пункт. Без него MIUI не даёт боту открывать Instagram из фона.",
                {
                    go(
                        Intent("miui.intent.action.APP_PERM_EDITOR")
                            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                            .putExtra("extra_pkgname", pkg),
                        Intent("miui.intent.action.APP_PERM_EDITOR")
                            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                            .putExtra("extra_pkgname", pkg),
                        details
                    )
                }, null),
            Item("powersched", "Автовыключение и автовключение (по желанию)",
                "Без root приложение не выключает телефон, это делает Xiaomi по расписанию. Настройки → Аккумулятор → «Расписание включения и выключения». Те же часы укажи в Leongram → Расписание, и бот сам продолжит после включения.",
                { go(miui("com.miui.powercenter.autotask.AutoTaskManageActivity"), Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), Intent(Settings.ACTION_SETTINGS)) }, null),
            Item("recents", "Закрепить в «Недавних»",
                "Кнопка «Обзор» → у карточки приложения значок замка (или удерживай карточку → замок). Тогда «Очистить всё» его не убьёт.",
                null, null)
        )
    }

    private fun accountItems() = listOf(
        Item("lang", "Язык Instagram: английский или русский",
            "Бот понимает английский и русский Instagram. Если какая-то кнопка не находится, добавь её в Настройки → Язык Instagram. Английский надёжнее: Instagram → Настройки → Язык → English.",
            null, null),
        Item("lock", "Блокировка экрана: «Нет» (для расписания)",
            "Бот включает экран сам, но пароль ввести не может. Поставь «Нет» или «Провести пальцем».",
            { go(Intent(Settings.ACTION_SECURITY_SETTINGS), Intent(Settings.ACTION_SETTINGS)) }, null),
        Item("awake", "Не выключать экран при зарядке (для расписания)",
            "Для разработчиков → «Не выключать экран». Если пункта нет: О телефоне → 7 раз нажать «Версия MIUI».",
            { go(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }, null)
    )

    private fun isDone(i: Item): Boolean = i.auto?.invoke() ?: (Store.s("setup_${i.id}") == "1")

    override fun render() {
        title = "Мастер настройки"
        col.removeAllViews()
        val s = step
        col.addView(Ui.text(this, "Шаг ${s + 1} из 3", 13f, Ui.MUTED))
        col.addView(progress(s), Ui.lp(this, top = 6, bottom = 10))
        when (s) {
            0 -> {
                col.addView(Ui.text(this, "Включить спецвозможности", 22f, bold = true))
                col.addView(Ui.text(this, "Бот управляет Instagram через службу спецвозможностей Android.", 14f, Ui.MUTED), Ui.lp(this, top = 4, bottom = 8))
                accessibilityItems().forEach { col.addView(itemCard(it), Ui.lp(this, top = 8)) }
            }
            1 -> {
                col.addView(Ui.text(this, "Разрешения MIUI", 22f, bold = true))
                col.addView(Ui.text(this, "Без них MIUI может убить бота в фоне. Пройди пункты сверху вниз.", 14f, Ui.MUTED), Ui.lp(this, top = 4, bottom = 8))
                miuiItems().forEach { col.addView(itemCard(it), Ui.lp(this, top = 8)) }
            }
            else -> {
                col.addView(Ui.text(this, "Проверить аккаунты", 22f, bold = true))
                col.addView(Ui.text(this, "Ники должны совпадать с теми, что показаны в списке аккаунтов Instagram.", 14f, Ui.MUTED), Ui.lp(this, top = 4, bottom = 8))
                col.addView(accountsCard(), Ui.lp(this, top = 8))
                accountItems().forEach { col.addView(itemCard(it), Ui.lp(this, top = 8)) }
            }
        }
        val nav = Ui.row(this)
        if (s > 0) nav.addView(Ui.button(this, "← Назад") { step = s - 1; render() }, Ui.lp(this, weight = 1f))
        if (s < 2) nav.addView(Ui.button(this, "Далее →", 1) { step = s + 1; render() }, Ui.lp(this, weight = 1f))
        else nav.addView(Ui.button(this, "Готово", 3) {
            Store.put("setupDone", "1"); Store.put("setupStep", "0"); finish()
        }, Ui.lp(this, weight = 1f))
        col.addView(nav, Ui.lp(this, top = 18))
        col.addView(Ui.button(this, "Закрыть, настрою позже") { finish() }, Ui.lp(this, top = 6))
    }

    private fun progress(s: Int): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0..2) {
            r.addView(android.view.View(this).apply { background = Ui.bg(this@SetupActivity, if (i <= s) Ui.BLUE else Ui.GRAY, 4) },
                LinearLayout.LayoutParams(0, Ui.dp(this, 6), 1f).apply { rightMargin = Ui.dp(this@SetupActivity, 4) })
        }
        return r
    }

    private fun itemCard(i: Item): LinearLayout {
        val ok = isDone(i)
        val c = Ui.card(this)
        c.addView(Ui.text(this, (if (ok) "✅ " else if (i.auto != null) "⚠️ " else "☐ ") + i.title, 15f, bold = true))
        c.addView(Ui.text(this, i.help, 13f, Ui.MUTED), Ui.lp(this, top = 4))
        i.open?.let { open -> c.addView(Ui.button(this, "Открыть настройки") { open() }, Ui.lp(this, top = 8)) }
        if (i.auto == null) {
            c.addView(CheckBox(this).apply {
                text = "Сделано"; isChecked = ok
                setOnCheckedChangeListener { _, checked ->
                    Store.put("setup_${i.id}", if (checked) "1" else "0")
                    post { render() }
                }
            })
        }
        return c
    }

    private fun accountsCard(): LinearLayout {
        val c = Ui.card(this)
        val list = Store.accounts()
        c.addView(Ui.text(this, if (list.isEmpty()) "⚠️ Аккаунтов пока нет" else "✅ Аккаунтов: ${list.size}", 15f, bold = true))
        if (list.isNotEmpty())
            c.addView(Ui.text(this, list.joinToString("\n") { (if (Store.enabled(it)) "• @" else "• (выкл) @") + it }, 13f), Ui.lp(this, top = 4))
        c.addView(Ui.button(this, "Открыть «Аккаунты»") { startActivity(Intent(this, AccountsActivity::class.java)) }, Ui.lp(this, top = 8))
        c.addView(Ui.button(this, "Найти ники в Instagram", 1) {
            val svc = BotService.instance
            when {
                svc == null -> toast("Сначала включи спецвозможности (шаг 1)")
                list.isEmpty() -> toast("Сначала добавь аккаунты")
                else -> { svc.start("check"); moveTaskToBack(true) }
            }
        }, Ui.lp(this, top = 6))
        c.addView(Ui.text(this, "Результат проверки (✅/❌ по каждому нику) смотри в журнале на главном экране.", 12f, Ui.MUTED), Ui.lp(this, top = 6))
        return c
    }
}
