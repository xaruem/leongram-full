package uz.hunta.bot

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Switch

/** Список аккаунтов карточками: добавить, удалить, включить/выключить, статус и счётчики. */
class AccountsActivity : Page() {
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { render(); h.postDelayed(this, 3000) }
    }

    override fun onResume() { super.onResume(); h.removeCallbacks(tick); h.postDelayed(tick, 3000) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    override fun render() {
        col.removeAllViews()
        val all = Store.accounts()
        val active = Store.activeAccounts()
        col.addView(Ui.text(this, "Аккаунты", 24f, bold = true))
        col.addView(
            Ui.text(this, "Всего ${all.size}, включено ${active.size}. Режимы «ВСЕ АККАУНТЫ» и «РАСПИСАНИЕ» работают только с включёнными.", 13f, Ui.MUTED),
            Ui.lp(this, top = 4, bottom = 10)
        )
        col.addView(appsCard(), Ui.lp(this, bottom = 10))
        col.addView(Ui.button(this, "+ Добавить аккаунт", 1) { addDialog() })
        if (Store.twoApps() && all.isNotEmpty()) {
            col.addView(Ui.button(this, "Разбить аккаунты по приложениям A / B") { bulkDialog() }, Ui.lp(this, top = 8))
        }
        if (all.isEmpty()) {
            col.addView(
                Ui.text(this, "Пока пусто. Нажми «Добавить» и впиши ник так, как он показан в Instagram (без @). Можно вставить сразу несколько.", 14f, Ui.MUTED),
                Ui.lp(this, top = 16)
            )
        }
        for (a in all) col.addView(cardFor(a), Ui.lp(this, top = 10))
    }

    private fun statusOf(a: String): Pair<String, Int> {
        val now = System.currentTimeMillis()
        val paused = Store.pausedUntil(a)
        return when {
            !Store.enabled(a) -> "Выключен" to Ui.GRAY
            paused > now -> "⛔ Пауза до ${Schedule.dm(paused)}" to Ui.RED
            BotService.running && (Store.cur == a || (Store.cur == null && Store.acc() == a)) -> "▶ Работает" to Ui.GREEN
            else -> "Готов" to Ui.BLUE
        }
    }

    private fun cardFor(a: String): android.view.View {
        val c = Ui.card(this)
        val head = Ui.row(this)
        head.addView(Ui.text(this, "@$a", 18f, bold = true), Ui.lp(this, weight = 1f))
        head.addView(Switch(this).apply {
            isChecked = Store.enabled(a)
            setOnCheckedChangeListener { _, on -> Store.setEnabled(a, on); render() }
        })
        c.addView(head)

        val (label, color) = statusOf(a)
        val chipRow = Ui.row(this)
        chipRow.addView(Ui.chip(this, label, color))
        c.addView(chipRow, Ui.lp(this, top = 6))

        val t = Stats.forDay(a, 0)
        c.addView(
            Ui.text(this, "Сегодня: подписок ${t[Stats.FOLLOW]} · лайков ${t[Stats.LIKE]} · отписок ${t[Stats.UNFOLLOW]} · комментариев ${t[Stats.COMMENT]}", 14f),
            Ui.lp(this, top = 10)
        )
        c.addView(
            Ui.text(this, "В списке бота (подписан): ${Store.followedCount(a)} · блокировок за всё время: ${Store.blocks(a).size}", 13f, Ui.MUTED),
            Ui.lp(this, top = 2)
        )
        c.addView(Ui.text(this, Limits.describe(a), 12f, Ui.MUTED), Ui.lp(this, top = 6))
        if (Store.twoApps()) {
            val chk = when (Store.s("chk_$a")) {
                "1" -> "найден в приложении ${Store.s("chkapp_$a").toCharArray().joinToString("+")}"
                "0" -> "не найден ни в одном приложении"
                else -> "ещё не проверялся"
            }
            c.addView(Ui.text(this, "Приложение ${Store.appOf(a)} · проверка: $chk", 13f, Ui.MUTED), Ui.lp(this, top = 6))
        }

        val btns = Ui.row(this)
        if (Store.twoApps()) {
            btns.addView(Ui.button(this, "Прил. ${Store.appOf(a)}") {
                Store.setAppOf(a, if (Store.appOf(a) == "A") "B" else "A"); render()
            }, Ui.lp(this, weight = 1f))
        }
        btns.addView(Ui.button(this, "Уровень") { tierDialog(a) }, Ui.lp(this, weight = 1f))
        if (Store.pausedUntil(a) > System.currentTimeMillis()) {
            btns.addView(Ui.button(this, "Снять паузу") { Store.clearPause(a); render() }, Ui.lp(this, weight = 1f))
        }
        btns.addView(Ui.button(this, "Удалить", 2) { deleteDialog(a) }, Ui.lp(this, weight = 1f))
        c.addView(btns, Ui.lp(this, top = 10))
        return c
    }


    // ───────────── 2.1.1: два приложения Instagram ─────────────
    private fun installed(pkg: String): Boolean =
        pkg.isNotEmpty() && packageManager.getLaunchIntentForPackage(pkg) != null

    private fun appsCard(): View {
        val c = Ui.card(this)
        c.addView(Ui.text(this, "Приложения Instagram", 16f, bold = true))
        c.addView(
            Ui.text(this, "A — основной Instagram, B — копия с другим именем пакета. Каждому аккаунту назначается A или B, бот сам открывает нужное приложение. Поле B пустое — работает только A.", 12f, Ui.MUTED),
            Ui.lp(this, top = 4, bottom = 8)
        )
        fun field(initial: String, hintText: String) = Ui.styleEdit(this, EditText(this).apply {
            setText(initial); hint = hintText
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
        })
        val eA = field(Store.pkgA(), Store.DEFAULT_PKG)
        val eB = field(Store.pkgB(), "пакет копии (пусто — нет второго приложения)")
        c.addView(Ui.text(this, "Приложение A — ${if (installed(Store.pkgA())) "✅ установлено" else "❌ не найдено"}", 13f))
        c.addView(eA, Ui.lp(this, top = 4, bottom = 8))
        c.addView(Ui.text(this, "Приложение B — " + when {
            Store.pkgB().isEmpty() -> "не задано"
            installed(Store.pkgB()) -> "✅ установлено"
            else -> "❌ не найдено"
        }, 13f))
        c.addView(eB, Ui.lp(this, top = 4, bottom = 8))
        val row = Ui.row(this)
        row.addView(Ui.button(this, "Сохранить", 1) {
            val a = eA.text.toString().trim(); val b = eB.text.toString().trim()
            val okFmt = Regex("^[A-Za-z0-9_.]+$")
            when {
                a.isNotEmpty() && !okFmt.matches(a) -> toast("Пакет A записан неверно")
                b.isNotEmpty() && !okFmt.matches(b) -> toast("Пакет B записан неверно")
                else -> {
                    Store.put("pkgA", a.ifEmpty { Store.DEFAULT_PKG }); Store.put("pkgB", b)
                    toast("Сохранено"); render()
                }
            }
        }, Ui.lp(this, weight = 1f))
        row.addView(Ui.button(this, "Найти копии") { findCopiesDialog(eB) }, Ui.lp(this, weight = 1f))
        c.addView(row)
        return c
    }

    /** Список установленных приложений со словом instagram в названии или имени пакета. */
    private fun findCopiesDialog(target: EditText) {
        val list = try {
            packageManager.getInstalledApplications(0)
                .filter { it.packageName != packageName && packageManager.getLaunchIntentForPackage(it.packageName) != null }
                .filter { it.packageName.contains("insta", true) || packageManager.getApplicationLabel(it).toString().contains("insta", true) }
                .map { packageManager.getApplicationLabel(it).toString() to it.packageName }
        } catch (e: Throwable) { emptyList() }
        if (list.isEmpty()) { toast("Копий Instagram не найдено. Впиши пакет вручную"); return }
        AlertDialog.Builder(this).setTitle("Какое приложение — B?")
            .setItems(list.map { "${it.first}\n${it.second}" }.toTypedArray()) { _, i ->
                target.setText(list[i].second)
                toast("Нажми «Сохранить»")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Массовое назначение: ники из поля идут в приложение B, остальные — в A. */
    private fun bulkDialog() {
        val e = EditText(this).apply {
            hint = "ники приложения B — через пробел или с новой строки (остальные уйдут в A)"
            setText(Store.accounts().filter { Store.appOf(it) == "B" }.joinToString("\n"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 4
        }
        AlertDialog.Builder(this).setTitle("Аккаунты приложения B")
            .setView(e)
            .setPositiveButton("Применить") { _, _ ->
                val b = e.text.toString().split(Regex("[\\s,;]+")).map { it.trim().removePrefix("@").lowercase() }.filter { it.isNotEmpty() }.toSet()
                val all = Store.accounts()
                all.forEach { Store.setAppOf(it, if (it in b) "B" else "A") }
                val unknown = b.filter { it !in all }
                toast("В B: ${all.count { it in b }}, в A: ${all.count { it !in b }}" + if (unknown.isNotEmpty()) ". Нет в списке: ${unknown.joinToString()}" else "")
                render()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun addDialog() {
        val e = EditText(this).apply {
            hint = "ник, без @ (можно несколько — через пробел или с новой строки)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
        }
        AlertDialog.Builder(this).setTitle("Добавить аккаунт")
            .setView(e)
            .setPositiveButton("Добавить") { _, _ ->
                val nicks = e.text.toString().split(Regex("[\\s,;]+")).filter { it.isNotBlank() }
                val ok = nicks.count { Store.addAccount(it) }
                toast(
                    when {
                        nicks.isEmpty() -> "Ничего не введено"
                        ok == 0 -> "Не добавлено: ник неверный или уже есть в списке"
                        else -> "Добавлено: $ok"
                    }
                )
                render()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun tierDialog(a: String) {
        val names = arrayOf("Новый — лимиты растут 4 недели", "Обычный — 100% базы", "Прогретый — 150% базы")
        val keys = arrayOf(Limits.NEW, Limits.NORMAL, Limits.WARM)
        AlertDialog.Builder(this).setTitle("Уровень @$a")
            .setSingleChoiceItems(names, keys.indexOf(Limits.tier(a)).coerceAtLeast(0)) { d, which ->
                val t = keys[which]
                if (t != Limits.tier(a)) {
                    Limits.setTier(a, t)
                    if (t == Limits.NEW) Limits.resetSince(a)
                }
                d.dismiss(); render()
            }
            .setNeutralButton("Сбросить рост") { _, _ -> Limits.resetSince(a); toast("Отсчёт недель начат заново"); render() }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun deleteDialog(a: String) {
        AlertDialog.Builder(this).setTitle("Удалить @$a?")
            .setMessage("Из приложения пропадут настройки, список «на кого подписан бот» и статистика этого аккаунта. Сам аккаунт в Instagram не меняется.")
            .setPositiveButton("Удалить") { _, _ -> Store.removeAccount(a); render() }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
