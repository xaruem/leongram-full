package uz.hunta.bot

import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch

/** Расписание: рабочее окно, «ночной режим» без действий и длительность автопаузы после блокировки. */
class ScheduleActivity : Page() {
    private lateinit var bar: LinearLayout
    private lateinit var info: android.widget.TextView

    private fun hours(max: Int) = (0..max).map { "%02d:00".format(it) }

    override fun render() {
        col.removeAllViews()
        col.addView(Ui.text(this, "Расписание и темп", 24f, bold = true))

        // ── рабочее окно ──
        val w = Ui.card(this)
        w.addView(Ui.text(this, "Рабочее время", 16f, bold = true))
        w.addView(Ui.text(this, "Бот запускается и действует только в это окно. Если «с» и «до» одинаковые — без ограничений.", 13f, Ui.MUTED), Ui.lp(this, top = 2, bottom = 6))
        val r1 = Ui.row(this)
        r1.addView(Ui.text(this, "с", 15f), Ui.lp(this, weight = 0.3f))
        r1.addView(Ui.spinner(this, hours(23), Schedule.startH()) { Store.put("startHour", it.toString()); refreshBar() }, Ui.lp(this, weight = 1f))
        r1.addView(Ui.text(this, "до", 15f), Ui.lp(this, weight = 0.3f))
        r1.addView(Ui.spinner(this, hours(24), Schedule.endH()) { Store.put("endHour", it.toString()); refreshBar() }, Ui.lp(this, weight = 1f))
        w.addView(r1)
        col.addView(w, Ui.lp(this, top = 10))

        // ── ночной режим ──
        val n = Ui.card(this)
        val nh = Ui.row(this)
        nh.addView(Ui.text(this, "Ночной режим", 16f, bold = true), Ui.lp(this, weight = 1f))
        nh.addView(Switch(this).apply {
            isChecked = Schedule.nightOn()
            setOnCheckedChangeListener { _, on -> Store.put("nightOn", if (on) "1" else "0"); refreshBar() }
        })
        n.addView(nh)
        n.addView(Ui.text(this, "В эти часы бот ничего не делает, даже если они попадают в рабочее время.", 13f, Ui.MUTED), Ui.lp(this, top = 2, bottom = 6))
        val r2 = Ui.row(this)
        r2.addView(Ui.text(this, "с", 15f), Ui.lp(this, weight = 0.3f))
        r2.addView(Ui.spinner(this, hours(23), Schedule.nightFrom()) { Store.put("nightFrom", it.toString()); refreshBar() }, Ui.lp(this, weight = 1f))
        r2.addView(Ui.text(this, "до", 15f), Ui.lp(this, weight = 0.3f))
        r2.addView(Ui.spinner(this, hours(23), Schedule.nightTo()) { Store.put("nightTo", it.toString()); refreshBar() }, Ui.lp(this, weight = 1f))
        n.addView(r2)
        col.addView(n, Ui.lp(this, top = 10))

        // ── шкала суток ──
        val t = Ui.card(this)
        t.addView(Ui.text(this, "Сутки", 16f, bold = true))
        bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        t.addView(bar, Ui.lp(this, top = 8))
        val ticks = Ui.row(this)
        for (x in listOf("0", "6", "12", "18", "24"))
            ticks.addView(Ui.text(this, x, 11f, Ui.MUTED), Ui.lp(this, weight = 1f))
        t.addView(ticks, Ui.lp(this, top = 2))
        t.addView(Ui.text(this, "■ зелёный — работает   ■ синий — ночной режим   ■ тёмный — вне окна", 11f, Ui.MUTED), Ui.lp(this, top = 6))
        info = Ui.text(this, "", 13f)
        t.addView(info, Ui.lp(this, top = 6))
        col.addView(t, Ui.lp(this, top = 10))

        // ── автопауза ──
        val p = Ui.card(this)
        p.addView(Ui.text(this, "Автопауза при блокировке Instagram", 16f, bold = true))
        p.addView(
            Ui.text(this, "Если Instagram ограничил действия, аккаунт отдыхает указанное время, в шторке появляется уведомление, остальные аккаунты продолжают. " +
                "При повторной блокировке в течение суток пауза удваивается (максимум 72 часа). В режиме «РАСПИСАНИЕ» бот сам вернётся к аккаунту после паузы.", 13f, Ui.MUTED),
            Ui.lp(this, top = 2, bottom = 6)
        )
        val opts = listOf(1, 2, 3, 4, 6, 8, 12, 24, 48, 72)
        val cur = Store.i("pauseHours", 6)
        val idx = opts.indexOf(cur).let { if (it >= 0) it else opts.indexOfFirst { o -> o >= cur }.let { j -> if (j >= 0) j else opts.size - 1 } }
        p.addView(Ui.spinner(this, opts.map { "$it ч" }, idx) { Store.put("pauseHours", opts[it].toString()) })
        col.addView(p, Ui.lp(this, top = 10))

        // ── темп и случайные паузы ──
        val sp = Ui.card(this)
        sp.addView(Ui.text(this, "Темп и «человеческие» паузы", 16f, bold = true))
        sp.addView(Ui.text(this, "Темп растягивает или сокращает все задержки между действиями. Случайные паузы — шанс, что после действия бот «отвлечётся» на 1–4 минуты.", 13f, Ui.MUTED), Ui.lp(this, top = 2, bottom = 6))
        sp.addView(Ui.text(this, "Темп", 13f, Ui.MUTED))
        val speeds = listOf("Быстро (×0.7)", "Обычно (×1)", "Медленно (×1.5)", "Очень медленно (×2.2)")
        sp.addView(Ui.spinner(this, speeds, Store.i("speed", 1).coerceIn(0, 3)) { Store.put("speed", it.toString()) })
        sp.addView(Ui.text(this, "Шанс случайной паузы после действия", 13f, Ui.MUTED), Ui.lp(this, top = 8))
        val pcts = listOf(0, 5, 8, 15, 25, 40)
        val pc = Store.i("longPausePct", 8)
        sp.addView(Ui.spinner(this, pcts.map { if (it == 0) "Выключено" else "$it %" }, pcts.indexOf(pc).let { if (it >= 0) it else 2 }) {
            Store.put("longPausePct", pcts[it].toString())
        })
        col.addView(sp, Ui.lp(this, top = 10))

        // ── уведомления ──
        val nt = Ui.card(this)
        val nr = Ui.row(this)
        nr.addView(Ui.text(this, "Итоги дня в уведомлении", 16f, bold = true), Ui.lp(this, weight = 1f))
        nr.addView(Switch(this).apply {
            isChecked = Store.s("dailySummary", "1") == "1"
            setOnCheckedChangeListener { _, on -> Store.put("dailySummary", if (on) "1" else "0") }
        })
        nt.addView(nr)
        nt.addView(Ui.text(this, "Когда бот закончил день или круг по аккаунтам, приходит уведомление: подписки, лайки, отписки и комментарии по каждому аккаунту.", 13f, Ui.MUTED), Ui.lp(this, top = 2))
        col.addView(nt, Ui.lp(this, top = 10))

        // ── питание телефона и режим ожидания (2.7.4) ──
        val pw = Ui.card(this)
        pw.addView(Ui.text(this, "Питание телефона и ожидание", 16f, bold = true))
        pw.addView(Ui.text(this, "Приложение без root не может выключить телефон. Зато Xiaomi умеет делать это по расписанию (автовыключение и автовключение). " +
            "Укажи здесь те же часы, что в настройках Xiaomi: бот не начнёт аккаунт, если до выключения меньше 25 минут, после включения подождёт сеть и VPN и продолжит сам.", 13f, Ui.MUTED), Ui.lp(this, top = 2, bottom = 6))
        val phours = listOf("не задано") + hours(23)
        pw.addView(Ui.text(this, "Телефон выключается в", 13f, Ui.MUTED))
        pw.addView(Ui.spinner(this, phours, Schedule.powerOffH() + 1) { Store.put("powerOffHour", (it - 1).toString()) })
        pw.addView(Ui.text(this, "Телефон включается в", 13f, Ui.MUTED), Ui.lp(this, top = 8))
        pw.addView(Ui.spinner(this, phours, Schedule.powerOnH() + 1) { Store.put("powerOnHour", (it - 1).toString()) })
        pw.addView(Ui.button(this, "Открыть настройки автовключения Xiaomi") {
            val tries = listOf(
                Intent().setClassName("com.miui.securitycenter", "com.miui.powercenter.autotask.AutoTaskManageActivity"),
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
            var ok = false
            for (x in tries) { try { startActivity(x); ok = true; break } catch (_: Throwable) {} }
            if (!ok) toast("Открой вручную: Настройки → Аккумулятор → Расписание питания")
        }, Ui.lp(this, top = 10))
        pw.addView(Ui.text(this, "Если пункта нет: Настройки → Аккумулятор и производительность (или «Питание») → «Расписание включения и выключения».", 12f, Ui.MUTED), Ui.lp(this, top = 4))
        val dr = Ui.row(this)
        dr.addView(Ui.text(this, "Глубокое ожидание", 15f, bold = true), Ui.lp(this, weight = 1f))
        dr.addView(Switch(this).apply {
            isChecked = Store.s("deepIdle", "1") == "1"
            setOnCheckedChangeListener { _, on -> Store.put("deepIdle", if (on) "1" else "0") }
        })
        pw.addView(dr, Ui.lp(this, top = 12))
        pw.addView(Ui.text(this, "Пока бот ждёт следующего запуска (больше 30 минут), он не держит процессор: телефон спит, к сроку его будит будильник Android. Если бот просыпается с опозданием, выключи.", 12f, Ui.MUTED))
        col.addView(pw, Ui.lp(this, top = 10))

        refreshBar()
    }

    private fun refreshBar() {
        bar.removeAllViews()
        for (hh in 0 until 24) {
            val color = when {
                Schedule.hourInNight(hh) -> Ui.DARK
                Schedule.hourInWork(hh) -> Ui.GREEN
                else -> Ui.GRAY
            }
            bar.addView(View(this).apply { setBackgroundColor(color) },
                LinearLayout.LayoutParams(0, Ui.dp(this, 22), 1f).apply { rightMargin = 1 })
        }
        info.text = when {
            Schedule.inNight() -> "Сейчас: ночной режим — бот не действует."
            Schedule.inWindow() -> "Сейчас: рабочее время — бот может действовать."
            else -> "Сейчас: вне рабочего окна — бот ждёт."
        }
    }
}
