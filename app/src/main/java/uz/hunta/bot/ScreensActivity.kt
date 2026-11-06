package uz.hunta.bot

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView

/** 2.7.2 Библиотека экранов: правила, счётчики срабатываний, свои правила, неизвестные экраны. */
class ScreensActivity : Page() {
    private var editor: EditText? = null

    override fun render() {
        title = "Библиотека экранов"
        col.removeAllViews()
        col.addView(Ui.text(this, "Библиотека экранов", 22f, bold = true))
        col.addView(Ui.text(this, "Пока бот работает, он сам закрывает всплывающие окна Instagram и системные окна Android по этим правилам. Незнакомые экраны попадают в «Неизвестные экраны»: пришли их, и добавим правило.", 13f, Ui.MUTED), Ui.lp(this, top = 4, bottom = 8))

        val main = Ui.card(this)
        main.addView(CheckBox(this).apply {
            text = "Закрывать окна из библиотеки автоматически"; isChecked = Screens.libraryOn()
            setOnCheckedChangeListener { _, on -> Store.put("screenLib", if (on) "1" else "0") }
        })
        val last = Screens.lastHit()
        main.addView(Ui.text(this, if (last.isEmpty()) "Срабатываний пока не было" else "Последнее срабатывание: $last", 12f, Ui.MUTED))
        col.addView(main, Ui.lp(this, top = 6))

        col.addView(Ui.text(this, "ВСТРОЕННЫЕ ПРАВИЛА", 13f, Ui.MUTED, bold = true), Ui.lp(this, top = 18, bottom = 4))
        for (r in Screens.builtin) {
            val c = Ui.card(this)
            c.addView(Ui.text(this, r.title, 14f, bold = true))
            c.addView(Ui.text(this, "Если на экране: «" + r.triggers.first() + "»" + (if (r.triggers.size > 1) " (+${r.triggers.size - 1} вариантов, RU/UZ)" else ""), 12f, Ui.MUTED), Ui.lp(this, top = 4))
            val does = when (r.act) {
                Screens.Act.PROBLEM -> "Аккаунт на паузу 12 ч, уведомление, остальные работают"
                Screens.Act.CRASH -> "Нажимает «" + r.buttons.first() + "» и перезапускает Instagram"
                else -> "Нажимает: " + r.buttons.take(3).joinToString(" / ") { "«$it»" }
            }
            c.addView(Ui.text(this, does, 12f, Ui.MUTED))
            c.addView(Ui.text(this, "Сработало раз: ${Screens.hits(r.id)}", 12f, Ui.MUTED))
            c.addView(CheckBox(this).apply {
                text = "Включено"; isChecked = Screens.enabled(r.id)
                setOnCheckedChangeListener { _, on -> Screens.setEnabled(r.id, on) }
            })
            col.addView(c, Ui.lp(this, top = 8))
        }

        col.addView(Ui.text(this, "СВОИ ПРАВИЛА", 13f, Ui.MUTED, bold = true), Ui.lp(this, top = 18, bottom = 4))
        col.addView(Ui.text(this, "Одна строка = одно правило:\nфраза на экране => кнопка | другая кнопка\nНесколько фраз через |. Регистр не важен. «[назад]» вместо кнопки жмёт системное «Назад». Префикс sys: ищет в системных окнах, а не в Instagram. Строка с # игнорируется.\nПример:\nyour notifications are off => not now | не сейчас", 12f, Ui.MUTED))
        val e = Ui.styleEdit(this, EditText(this)).apply {
            setText(Store.s("screenRules"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 4
        }
        editor = e
        col.addView(e, Ui.lp(this, top = 8))
        col.addView(Ui.button(this, "Сохранить правила", 1) {
            val (ok, bad) = Screens.parseCustom(e.text.toString())
            Store.put("screenRules", e.text.toString())
            toast("Правил: ${ok.size}" + (if (bad > 0) ", строк без «=>» пропущено: $bad" else ""))
        }, Ui.lp(this, top = 8))

        col.addView(Ui.text(this, "НЕИЗВЕСТНЫЕ ЭКРАНЫ", 13f, Ui.MUTED, bold = true), Ui.lp(this, top = 18, bottom = 4))
        col.addView(Ui.button(this, "Показать / отправить") {
            val t = Screens.unknown(this)
            showText("Неизвестные экраны", if (t.isEmpty()) "Пока пусто: бот не застревал на незнакомых экранах." else t)
        }, Ui.lp(this, top = 4))
        col.addView(Ui.button(this, "Очистить список") { Screens.clearUnknown(this); toast("Список очищен") }, Ui.lp(this, top = 6))
    }

    override fun onPause() {
        super.onPause()
        editor?.let { Store.put("screenRules", it.text.toString()) }
    }

    private fun showText(title: String, body: String) {
        val tv = TextView(this).apply {
            text = body; textSize = 11f; typeface = Typeface.MONOSPACE
            setPadding(28, 16, 28, 16); setTextIsSelectable(true)
        }
        AlertDialog.Builder(this).setTitle(title)
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Копировать") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Leongram", body))
                toast("Скопировано")
            }
            .setNegativeButton("Закрыть", null).show()
    }
}
