package uz.hunta.bot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Столбики по дням для одного показателя. Старые дни слева, сегодня справа. */
class BarChart(c: Context, private val values: List<Int>, private val labels: List<String>, private val color: Int) : View(c) {
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@BarChart.color }
    private val base = Paint().apply { this.color = Ui.GRAY; strokeWidth = 2f }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Ui.MUTED; textSize = Ui.dp(c, 10).toFloat(); textAlign = Paint.Align.CENTER
    }
    private val val_ = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Ui.TEXT; textSize = Ui.dp(c, 10).toFloat(); textAlign = Paint.Align.CENTER
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(context, 150))
    }

    override fun onDraw(cv: Canvas) {
        val n = values.size
        if (n == 0) return
        val w = width.toFloat(); val h = height.toFloat()
        val top = Ui.dp(context, 16).toFloat(); val bottom = Ui.dp(context, 18).toFloat()
        val max = maxOf(1, values.maxOrNull() ?: 1)
        val slot = w / n
        val bw = slot * 0.62f
        val step = if (n <= 7) 1 else if (n <= 14) 2 else 5
        val r = Ui.dp(context, 3).toFloat()
        for (i in 0 until n) {
            val v = values[i]
            val x = i * slot + (slot - bw) / 2
            val bh = (h - top - bottom) * v / max
            if (v > 0) {
                cv.drawRoundRect(RectF(x, h - bottom - bh, x + bw, h - bottom), r, r, bar)
                if (n <= 14) cv.drawText(v.toString(), x + bw / 2, h - bottom - bh - Ui.dp(context, 3), val_)
            }
            if ((n - 1 - i) % step == 0) cv.drawText(labels[i], x + bw / 2, h - Ui.dp(context, 4), txt)
        }
        cv.drawLine(0f, h - bottom, w, h - bottom, base)
    }
}

/** Статистика по дням в виде столбиков: подписки, лайки, отписки, комментарии по каждому аккаунту. */
class StatsActivity : Page() {
    private var days = 7
    private var metric = Stats.FOLLOW
    private val colors = listOf(0xFF2F6FED.toInt(), 0xFFE0475B.toInt(), 0xFFF29D38.toInt(), 0xFF1E9E5A.toInt())

    override fun render() {
        col.removeAllViews()
        col.addView(Ui.text(this, "Статистика", 24f, bold = true))

        val sw = Ui.row(this)
        for (d in listOf(7, 14, 30)) {
            sw.addView(Ui.button(this, "$d дн.", if (d == days) 1 else 0) { days = d; render() }, Ui.lp(this, weight = 1f))
        }
        col.addView(sw, Ui.lp(this, top = 10))

        val mr = Ui.row(this)
        Stats.TITLES.forEachIndexed { i, t ->
            mr.addView(Ui.button(this, t, if (i == metric) 1 else 0) { metric = i; render() }, Ui.lp(this, weight = 1f))
        }
        col.addView(mr, Ui.lp(this, top = 6))

        val accs = Store.accounts()
        if (accs.isEmpty()) {
            col.addView(Ui.text(this, "Нет аккаунтов. Добавь их на экране «Аккаунты».", 14f, Ui.MUTED), Ui.lp(this, top = 16))
            return
        }

        val sum = IntArray(4)
        for (a in accs) { val t = Stats.total(a, days); for (i in 0..3) sum[i] += t[i] }
        val total = Ui.card(this)
        total.addView(Ui.text(this, "Все аккаунты за $days дн.", 16f, bold = true))
        total.addView(
            Ui.text(this, "подписок ${sum[0]} · лайков ${sum[1]} · отписок ${sum[2]} · комментариев ${sum[3]}", 14f),
            Ui.lp(this, top = 4)
        )
        val zero = accs.filter { Stats.forDay(it, 0)[Stats.FOLLOW] == 0 && Store.enabled(it) }
        if (zero.isNotEmpty())
            total.addView(Ui.text(this, "⚠ Сегодня без подписок: " + zero.joinToString(", ") { "@$it" } + ". Бот доделает их в конце круга (до 2 раз в день).", 13f, Ui.RED), Ui.lp(this, top = 6))
        col.addView(total, Ui.lp(this, top = 10))

        val offsets = (days - 1 downTo 0).map { -it }   // от старых к сегодняшнему
        val labels = offsets.map { Stats.dateLabel(it) }
        for (a in accs) {
            val c = Ui.card(this)
            val t = Stats.total(a, days)
            c.addView(Ui.text(this, "@$a — ${Stats.TITLES[metric].lowercase()}: ${t[metric]}", 16f, bold = true))
            if (metric == Stats.FOLLOW && Stats.forDay(a, 0)[Stats.FOLLOW] == 0)
                c.addView(Ui.text(this, "⚠ сегодня 0 подписок", 13f, Ui.RED))
            val vals = offsets.map { Stats.forDay(a, it)[metric] }
            c.addView(BarChart(this, vals, labels, colors[metric]), Ui.lp(this, top = 8))
            col.addView(c, Ui.lp(this, top = 10))
        }

        if (Store.hasPriority()) {
            val g = Ui.card(this)
            g.addView(Ui.text(this, "⭐ Золотые профили сегодня", 16f, bold = true))
            val sumG = IntArray(6)
            for (a in accs) { val t = Gold.today(a); for (i in 0..5) sumG[i] += t[i] }
            g.addView(Ui.text(this, Gold.TITLES.indices.joinToString(" · ") { "${Gold.TITLES[it]} ${sumG[it]}" }, 13f), Ui.lp(this, top = 4))
            for (a in accs) {
                val l = Gold.line(a)
                g.addView(Ui.text(this, "@$a: " + (if (l.isEmpty()) "ещё не заходил к золотым" else l.removePrefix("золотые: ")), 12f, Ui.MUTED), Ui.lp(this, top = 4))
            }
            col.addView(g, Ui.lp(this, top = 14))
        }
        col.addView(Ui.button(this, "Скопировать статистику (таблица)") { copy(accs) }, Ui.lp(this, top = 14))
    }

    private fun copy(accs: List<String>) {
        val sb = StringBuilder("аккаунт;день;подписок;лайков;отписок;комментариев\n")
        for (a in accs) for (o in 0 until days) {
            val v = Stats.forDay(a, -o)
            sb.append("$a;${Stats.dateLabel(-o)};${v[0]};${v[1]};${v[2]};${v[3]}\n")
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Leongram stats", sb.toString()))
        toast("Статистика скопирована")
    }
}
