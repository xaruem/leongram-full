package uz.hunta.bot

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val fields = LinkedHashMap<String, EditText>()
    private lateinit var stats: TextView
    private lateinit var logView: TextView
    private val navCounters = ArrayList<Pair<Button, () -> String>>()
    private var noSave = false
    private val screenViews = LinkedHashMap<String, LinearLayout>()
    private val parentOf = HashMap<String, String>()
    private var curScreen = "home"
    private var scrollView: ScrollView? = null

    private fun showScreen(id: String) {
        curScreen = id
        for ((k, v) in screenViews) v.visibility = if (k == id) android.view.View.VISIBLE else android.view.View.GONE
        scrollView?.scrollTo(0, 0)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (curScreen != "home") showScreen(parentOf[curScreen] ?: "home") else super.onBackPressed()
    }
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            stats.text = (if (Store.dry()) "🧪 СУХОЙ ЗАПУСК включён: бот ничего не делает в аккаунтах\n" else "") + (if (BotService.instance == null) "⚠️ Спецвозможности ВЫКЛ" else if (BotService.running) "▶ РАБОТАЕТ" else "● Готов") +
                "\nАккаунтов: ${Store.accounts().size} (включено ${Store.activeAccounts().size}) · текущий: ${Store.acc()}" +
                "\nСегодня: подписок ${Store.followedToday()} · в списке бота: ${Store.followed().size} · каталог: ${Store.catalog().size}" +
                (if (Schedule.inNight()) "\n🌙 Ночной режим — бот не действует" else "") +
                (if (BotService.idleText.isNotEmpty()) "\n" + BotService.idleText else "")
            for (v in navCounters) v.first.text = v.second()
            logView.text = BotLog.text()
            h.postDelayed(this, 1000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.init(this); BotLog.init(this)
        Store.migrateCatalog()
        Store.seedComments()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(this@MainActivity, 16); setPadding(p, p, p, p * 2) }
        var target = LinearLayout(this)

        fun field(key: String, label: String, def: String, multi: Boolean = false, num: Boolean = false) {
            target.addView(Ui.text(this, label, 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(this@MainActivity, 14), 0, Ui.dp(this@MainActivity, 4)) })
            val e = Ui.styleEdit(this, EditText(this)).apply {
                setText(Store.s(key, def))
                if (num) inputType = InputType.TYPE_CLASS_NUMBER
                else if (multi) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 3 }
            }
            fields[key] = e; target.addView(e)
        }
        fun btn(label: String, kind: Int = 0, onClick: () -> Unit) {
            target.addView(Ui.button(this, label, kind) { onClick() }, Ui.lp(this, top = 8))
        }
        fun note(t: String) { target.addView(Ui.text(this, t, 13f, Ui.MUTED), Ui.lp(this, top = 4, bottom = 4)) }
        /** Крупная кнопка меню: открывает внутренний экран. */
        fun menu(label: String, id: String, kind: Int = 0) {
            target.addView(Ui.button(this, label, kind) { showScreen(id) }.apply { minHeight = Ui.dp(this@MainActivity, 58); textSize = 16f }, Ui.lp(this, top = 10))
        }
        /** Внутренний экран: стрелка назад, заголовок и содержимое. */
        fun screen(id: String, title: String, parent: String, build: () -> Unit) {
            val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
            screenViews[id] = l; parentOf[id] = parent; root.addView(l)
            target = l
            l.addView(Ui.button(this, "← Назад", 0) { showScreen(parent) })
            l.addView(Ui.gradientText(Ui.text(this, title, 24f, bold = true)), Ui.lp(this, top = 12, bottom = 4))
            build()
        }

        // ───────── ГЛАВНЫЙ ЭКРАН: шесть разделов и СТОП ─────────
        screen("home", "", "home") {
            val l = target
            l.removeAllViews()
            val head = Ui.row(this)
            head.addView(ImageView(this).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)))
            val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(this@MainActivity, 12), 0, 0, 0) }
            titles.addView(Ui.gradientText(Ui.text(this, "Leongram", 30f, bold = true)))
            titles.addView(Ui.text(this, "Автоматизация Instagram", 13f, Ui.MUTED))
            head.addView(titles)
            l.addView(head, Ui.lp(this, bottom = 10))
            val statsCard = Ui.card(this)
            stats = Ui.text(this, "", 14f)
            statsCard.addView(stats)
            l.addView(statsCard)
            menu("⚙️  Настройки", "settings")
            menu("📊  Статистика", "stats")
            menu("👤  Аккаунты", "accounts")
            menu("➕  Подписки", "follows")
            menu("💬  Комментарии", "comments")
            menu("🔥  Активность", "activity")
            l.addView(Ui.button(this, "🛑  СТОП", 2) {
                BotService.instance?.stop(); Toast.makeText(this, "Остановлено, автопродолжение отменено", Toast.LENGTH_SHORT).show()
            }.apply { minHeight = Ui.dp(this@MainActivity, 64); textSize = 18f }, Ui.lp(this, top = 22))
            l.addView(Ui.text(this, "Leongram · v" + try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }, 12f, Ui.MUTED), Ui.lp(this, top = 16))
        }

        // ───────── 1. НАСТРОЙКИ ─────────
        screen("settings", "⚙️ Настройки", "home") {
            btn("Настройка телефона (мастер Redmi, один раз)", 1) { startActivity(Intent(this, SetupActivity::class.java)) }
            btn("Проверка разрешений (если Instagram не открывается)") { showText("Проверка разрешений", Perms.report(this)) }
            btn("Расписание, ночной режим, автопауза, питание телефона") { startActivity(Intent(this, ScheduleActivity::class.java)) }
            menu("Надёжность и сухой запуск", "reliability")
            menu("Язык Instagram (русский / свои переводы)", "lang")
            menu("Экспорт и импорт настроек", "backup")
        }
        screen("reliability", "Надёжность и сухой запуск", "settings") {
            field("dryRun", "🧪 СУХОЙ ЗАПУСК (1 — да, 0 — нет): бот ходит по Instagram и пишет в журнал, что сделал бы, но ничего не подписывает, не лайкает, не комментирует, не отписывает и не копит статистику. Для проверки перед боевым запуском", "0", num = true)
            field("watchdogMin", "Сторож: если бот ничего не делает столько минут (или Instagram завис / вылетел), закрыть Instagram и начать аккаунт заново. 0 — выключено, минимум 4", "8", num = true)
            field("autoResume", "Автопродолжение: если «ВСЕ АККАУНТЫ» / «РАСПИСАНИЕ» оборвались (систему убила службу, телефон перезагрузился), продолжить само. 1 — да, 0 — нет", "1", num = true)
            field("skipDayPct", "Шанс выходного для аккаунта в режиме расписания, % (два дня подряд не пропускает)", "10", num = true)
            btn("Сбросить «сделано сегодня» (начать круг заново)") {
                AlertDialog.Builder(this).setTitle("Сбросить сделанное сегодня?")
                    .setMessage("Бот забудет, что аккаунты уже отработали сегодня, и следующий запуск пройдёт всех заново. Счётчик подписок за день и статистика не сбрасываются.")
                    .setPositiveButton("Сбросить") { _, _ -> Store.resetToday(); Toast.makeText(this, "Сброшено", Toast.LENGTH_SHORT).show() }
                    .setNegativeButton("Отмена", null).show()
            }
            btn("Библиотека экранов (всплывающие окна)") { startActivity(Intent(this, ScreensActivity::class.java)) }
        }
        screen("lang", "Язык Instagram", "settings") {
            note("Бот понимает Instagram на английском и на русском (узбекский частично). Сам язык менять не нужно. Если какая-то кнопка у тебя называется иначе и бот её не находит, впиши её сюда.")
            field("langMap", "Свои переводы, по одному в строке: «как написано на экране = английское слово». Например: Подписаться = Follow. Нужные слова: Home, Profile, Follow, Following, Message, Like, Unlike, Comment, Share, Repost, Copy link", "", multi = true)
            note("Как узнать, как кнопка называется: Журнал → Диагностика → «Снимок экрана Instagram».")
        }
        screen("backup", "Экспорт и импорт настроек", "settings") {
            note("Все настройки, списки и аккаунты одним файлом. Удобно при переходе на другой телефон.")
            btn("Экспорт настроек в файл") { exportSettings() }
            btn("Импорт настроек из файла") { importSettings() }
        }

        // ───────── 2. СТАТИСТИКА ─────────
        screen("stats", "📊 Статистика", "home") {
            btn("Статистика по дням", 1) { startActivity(Intent(this, StatsActivity::class.java)) }
            btn("Что сделано сегодня (по аккаунтам)") { showToday() }
            btn("Паузы после блокировок (посмотреть / снять)") { showPauses() }
            menu("Журнал", "log")
            menu("Диагностика", "diag")
        }
        screen("log", "Журнал", "stats") {
            btn("Скопировать журнал") { copyLog() }
            btn("Журнал целиком (копировать / отправить)") { showText("Журнал", BotLog.text()) }
            btn("Очистить журнал") { BotLog.clear() }
            logView = Ui.text(this, "", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(this@MainActivity, 16), 0, 0) }
            target.addView(logView)
        }
        screen("diag", "Диагностика", "stats") {
            btn("Последняя ошибка (вылет)") {
                val t = CrashHandler.last(this)
                showText("Последняя ошибка", if (t.isEmpty()) "Вылетов и сбоев пока не было." else t)
            }
            btn("Снимок экрана Instagram (через 10 сек)") {
                val s = BotService.instance
                if (s == null) Toast.makeText(this, "Сначала включи спецвозможности", Toast.LENGTH_LONG).show()
                else {
                    s.dumpScreen()
                    Toast.makeText(this, "Открой Instagram и нужный экран. Снимок через 10 сек", Toast.LENGTH_LONG).show()
                    moveTaskToBack(true)
                }
            }
            btn("Показать снимок экрана") {
                val f = File(filesDir, BotService.DUMP_FILE)
                showText("Снимок экрана", if (f.exists()) f.readText().take(120_000) else "Снимка ещё нет.")
            }
            btn("Библиотека экранов (неизвестные экраны)") { startActivity(Intent(this, ScreensActivity::class.java)) }
        }

        // ───────── 3. АККАУНТЫ ─────────
        screen("accounts", "👤 Аккаунты", "home") {
            btn("Подключение аккаунтов (карточки)", 1) { startActivity(Intent(this, AccountsActivity::class.java)) }
            btn("Проверить аккаунты (найдёт ники в Instagram)") { run("check") }
            menu("⭐ Золотые аккаунты", "golden")
            btn("Уровни и лимиты аккаунтов (новый / обычный / прогретый)") { showTiers() }
        }
        screen("golden", "⭐ Золотые аккаунты", "accounts") {
            note("Ваши страницы. Каждый аккаунт при каждом заходе проходит их первыми: истории, последние видео, а если лайка нет: лайк, комментарий, репост, «Поделиться», «Скопировать ссылку». Репост и «Поделиться» бот делает только здесь.")
            field("golden", "Золотые профили (по одному нику в строке)", "", multi = true)
            field("goldenVideos", "Сколько последних видео на профиль", "3", num = true)
            field("goldenStories", "Сколько историй смотреть на профиль", "3", num = true)
            field("goldenMaxMin", "Сколько минут максимум на золотой заход одного аккаунта (всё сверх этого пропускается; премиум-профили идут первыми)", "10", num = true)
            note("Золотой заход делается один раз в день на каждый аккаунт: чаще замедляет работу и повышает риск.")
            note("Слова для комментариев к золотым: раздел «Комментарии».")
        }
        // ───────── 4. ПОДПИСКИ ─────────
        screen("follows", "➕ Подписки", "home") {
            menu("Аккаунты для подписок (каталог)", "catalog")
            menu("Лимиты и выдержка", "followlimits")
            menu("Чёрный и белый списки", "lists")
        }
        screen("catalog", "Аккаунты для подписок", "follows") {
            note("Сюда вводятся ники, на чьих подписчиков заходят аккаунты. Один каталог на все аккаунты, все аккаунты равны.")
            field("catalog", "Каталог: ники по одному в строке (можно вставить список через запятую или ссылки)", "", multi = true)
            btn("Загрузить каталог из файла (.txt / .csv)", 1) { pickCatalogFile() }
        }
        screen("followlimits", "Лимиты и выдержка", "follows") {
            field("followDay", "Подписок в день (база для уровня «Обычный»)", "20", num = true)
            field("followHour", "Подписок в час, максимум (база)", "12", num = true)
            field("visitMin", "Подписок за один заход в каталог: от", "3", num = true)
            field("visitMax", "Подписок за один заход в каталог: до", "8", num = true)
            field("unfollowWeek", "Отписок раз в неделю (база)", "50", num = true)
            field("unfollowHour", "Отписок в час, максимум (база)", "15", num = true)
            field("holdMinDays", "Выдержка перед отпиской, дней: от", "3", num = true)
            field("holdMaxDays", "Выдержка перед отпиской, дней: до (у каждого человека своя в этих пределах)", "7", num = true)
            field("unfollowKeepMutual", "Не отписываться от тех, кто подписался в ответ. 1 — да, 0 — нет", "1", num = true)
        }
        screen("lists", "Чёрный и белый списки", "follows") {
            field("blacklist", "Чёрный список ников (по одному в строке): на них бот не подписывается, их профили не лайкает и не комментирует", "", multi = true)
            btn("Белый список (кого бот не отписывает)") { showWhitelist() }
        }

        // ───────── 5. КОММЕНТАРИИ ─────────
        screen("comments", "💬 Комментарии", "home") {
            note("Слова для комментариев, по одному в строке. Бот выбирает случайный и не повторяет один и тот же текст подряд. Можно с вариациями: {Класс|Круто|Огонь}{!|.}. Готовые наборы (100+) уже загружены, их можно править.")
            btn("Заполнить готовыми комментариями (100+)", 1) {
                AlertDialog.Builder(this).setTitle("Заменить комментарии готовыми?")
                    .setMessage("Все три поля ниже будут заменены готовыми наборами. Свои тексты в них пропадут.")
                    .setPositiveButton("Заменить") { _, _ ->
                        fields["comments"]?.setText(Presets.REGULAR.joinToString("\n"))
                        fields["goldenComments"]?.setText(Presets.GOLDEN.joinToString("\n"))
                        fields["newFollowerComments"]?.setText(Presets.NEW_FOLLOWER.joinToString("\n"))
                        Toast.makeText(this, "Готовые наборы загружены", Toast.LENGTH_SHORT).show()
                    }.setNegativeButton("Отмена", null).show()
            }
            field("comments", "Комментарии для обычных профилей (под постами и видео чужих людей)", Presets.REGULAR.joinToString("\n"), multi = true)
            field("goldenComments", "Комментарии для золотых и премиум профилей (чуть другие, пусто: берутся обычные)", Presets.GOLDEN.joinToString("\n"), multi = true)
            field("newFollowerComments", "Комментарии на видео новых подписчиков (хорошие, тёплые)", Presets.NEW_FOLLOWER.joinToString("\n"), multi = true)
            field("commentPct", "Шанс комментария после лайка, % (для обычных профилей)", "35", num = true)
        }

        // ───────── 6. АКТИВНОСТЬ ─────────
        screen("activity", "🔥 Активность", "home") {
            note("В основном всё работает само. Достаточно одной из двух кнопок.")
            btn("ВСЕ АККАУНТЫ: по очереди, авто-режим на каждом", 1) { run("all") }
            btn("РАСПИСАНИЕ: аккаунты сами стартуют в разное время дня", 1) { run("sched") }
            menu("Новые подписчики (видео, комментарий, спасибо)", "newfollowers")
            menu("Параметры активности", "actparams")
            menu("Ручной режим (один аккаунт)", "manual")
        }
        screen("newfollowers", "Новые подписчики", "activity") {
            note("Кто подписался на ваши аккаунты, на того бот заходит по одному: если у человека есть видео, смотрит, ставит лайк и пишет хороший комментарий. Раз в день на аккаунт.")
            field("newFollowersOn", "Включено (1 — да, 0 — нет)", "1", num = true)
            field("newFollowersMax", "Сколько новых подписчиков обрабатывать в день на один аккаунт", "5", num = true)
            note("Тексты сообщений и комментариев: раздел «Комментарии».")
        }
        screen("actparams", "Параметры активности", "activity") {
            field("likes", "Лайков за сессию (база)", "10", num = true)
            field("likesPerProfile", "Лайков на один профиль", "2", num = true)
            field("storiesPerProfile", "Историй смотреть на профиль", "5", num = true)
            field("maxMin", "Лимит времени сессии активности, мин", "30", num = true)
            field("diversityPct", "Шанс «прогулки» по ленте / рилсам / чужим историям между шагами, %", "60", num = true)
        }
        screen("manual", "Ручной режим", "activity") {
            note("Работа на одном аккаунте без переключения: Instagram должен быть открыт на нужном аккаунте.")
            field("acc", "Ник текущего аккаунта", "acc1")
            btn("АВТО: подписки → активность → отписка раз в неделю", 1) { run("auto") }
            btn("Подписки (до дневного лимита)") { run("follow") }
            btn("Активность: истории + лайки + комменты") { run("engage") }
            btn("Отписка (самых старых)") { run("unfollow") }
            btn("Разнообразие: проверить прямо сейчас") { run("browse") }
        }

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root); scrollView = this })
        showScreen("home")

        // при самом первом запуске сразу открываем мастер настройки
        if (Store.s("setupShown") != "1") {
            Store.put("setupShown", "1")
            startActivity(Intent(this, SetupActivity::class.java))
        }

        if (CrashHandler.last(this).isNotEmpty())
            Toast.makeText(this, "Был сбой — смотри «Последняя ошибка» внизу", Toast.LENGTH_LONG).show()
    }

    private val REQ_EXPORT = 501
    private val REQ_IMPORT = 502
    private val REQ_CATALOG = 503

    private fun pickCatalogFile() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_CATALOG)
        } catch (e: Throwable) { Toast.makeText(this, "Не открылся выбор файла: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun copyLog() {
        val t = BotLog.text()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Leongram log", t))
        Toast.makeText(this, if (t.isEmpty()) "Журнал пуст" else "Журнал скопирован (${t.lines().size} строк)", Toast.LENGTH_SHORT).show()
    }

    private fun exportSettings() {
        save()
        try {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, Backup.fileName()),
                REQ_EXPORT
            )
        } catch (e: Throwable) { Toast.makeText(this, "Не открылся выбор файла: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun importSettings() {
        AlertDialog.Builder(this).setTitle("Импорт настроек")
            .setMessage("Настройки и данные аккаунтов из файла заменят текущие совпадающие значения. Продолжить?")
            .setPositiveButton("Выбрать файл") { _, _ ->
                try {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                        REQ_IMPORT
                    )
                } catch (e: Throwable) { Toast.makeText(this, "Не открылся выбор файла: ${e.message}", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("Отмена", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri: Uri = data?.data ?: return
        if (res != RESULT_OK) return
        try {
            if (req == REQ_CATALOG) {
                val text = contentResolver.openInputStream(uri)!!.use { String(it.readBytes(), Charsets.UTF_8) }
                save()   // сначала сохраняем то, что уже в поле
                val added = Store.addToCatalog(text)
                fields["catalog"]?.setText(Store.s("catalog"))
                BotLog.add("Каталог: добавлено ников из файла: $added, всего ${Store.catalog().size}")
                Toast.makeText(this, "Добавлено новых ников: $added. Всего в каталоге: ${Store.catalog().size}", Toast.LENGTH_LONG).show()
            } else if (req == REQ_EXPORT) {
                contentResolver.openOutputStream(uri)!!.use { it.write(Backup.export().toByteArray(Charsets.UTF_8)) }
                Toast.makeText(this, "Настройки сохранены в файл", Toast.LENGTH_LONG).show()
            } else if (req == REQ_IMPORT) {
                val text = contentResolver.openInputStream(uri)!!.use { String(it.readBytes(), Charsets.UTF_8) }
                val n = Backup.import(text)
                BotLog.add("Импорт настроек: восстановлено значений: $n")
                Toast.makeText(this, "Импортировано значений: $n", Toast.LENGTH_LONG).show()
                noSave = true   // иначе onPause затрёт импортированное старыми значениями полей
                recreate()
            }
        } catch (e: Throwable) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showWhitelist() {
        val e = EditText(this).apply {
            setText(Store.s("whitelist"))
            hint = "ники по одному в строке"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 5
        }
        AlertDialog.Builder(this).setTitle("Белый список")
            .setMessage("Этих ников бот никогда не отписывает.")
            .setView(e)
            .setPositiveButton("Сохранить") { _, _ ->
                Store.put("whitelist", e.text.toString())
                Toast.makeText(this, "В белом списке: ${Store.whitelist().size}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null).show()
    }

    /** 2.7.3: что каждый аккаунт сделал сегодня — это бот помнит и после перезапуска. */
    private fun showToday() {
        val today = Schedule.todayKey()
        val now = System.currentTimeMillis()
        val accs = Store.accounts()
        val text = if (accs.isEmpty()) "Аккаунтов нет." else accs.joinToString("\n\n") { a ->
            val st = Stats.forDay(a, 0)
            val state = when {
                Store.pausedUntil(a) > now -> "⏸ пауза до ${Schedule.dm(Store.pausedUntil(a))}"
                Store.s("done_$a") == today -> "✅ сделан"
                Store.phasesToday(a).isNotEmpty() -> "◐ в процессе (готово: ${Store.phasesToday(a)})"
                else -> "☐ ещё не запускался"
            }
            "@$a — $state\n   подписок ${st[0]}, лайков ${st[1]}, отписок ${st[2]}, комм. ${st[3]}" +
                (Gold.line(a).let { if (it.isNotEmpty()) "\n   $it" else "" }) +
                (if (Store.engagedToday(a).isNotEmpty()) "\n   профилей с активностью: ${Store.engagedToday(a).size}" else "")
        }
        showText("Сегодня, ${Schedule.dm(now).substringBefore(' ')}", text)
    }

    /** Какие аккаунты сейчас на паузе после блокировки Instagram. */
    private fun showPauses() {
        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.US)
        val now = System.currentTimeMillis()
        val accs = Store.accounts().ifEmpty { listOf(Store.acc()) }
        val text = accs.joinToString("\n") { a ->
            val p = Store.pausedUntil(a)
            val st = if (p > now) "пауза до ${fmt.format(Date(p))}" else "работает"
            "@$a — $st (блокировок за всё время: ${Store.blocks(a).size})"
        }
        AlertDialog.Builder(this).setTitle("Паузы после блокировок")
            .setMessage(text)
            .setPositiveButton("Снять все паузы") { _, _ ->
                accs.forEach { Store.clearPause(it) }
                Toast.makeText(this, "Паузы сняты", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    /** Окно выбора уровня для каждого аккаунта: лимиты считаются отдельно и растут по неделям у «Нового». */
    private fun showTiers() {
        save()
        val accs = Store.accounts().ifEmpty { listOf(Store.acc()) }
        val names = arrayOf("Новый (лимиты растут 4 недели)", "Обычный (100% базы)", "Прогретый (150% базы)")
        val keys = arrayOf(Limits.NEW, Limits.NORMAL, Limits.WARM)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 16, 28, 16) }
        val spinners = LinkedHashMap<String, Spinner>()
        for (a in accs) {
            col.addView(TextView(this).apply { text = "@$a\n${Limits.describe(a)}"; setPadding(0, 24, 0, 0) })
            val sp = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
                setSelection(keys.indexOf(Limits.tier(a)).coerceAtLeast(0))
            }
            col.addView(sp); spinners[a] = sp
        }
        AlertDialog.Builder(this).setTitle("Уровни аккаунтов")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Сохранить") { _, _ ->
                for ((a, sp) in spinners) {
                    val t = keys[sp.selectedItemPosition]
                    if (t != Limits.tier(a)) {
                        Limits.setTier(a, t)
                        if (t == Limits.NEW) Limits.resetSince(a)
                    }
                }
                Toast.makeText(this, "Уровни сохранены", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Сбросить рост") { _, _ ->
                for (a in spinners.keys) Limits.resetSince(a)
                Toast.makeText(this, "Отсчёт недель начат заново", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showText(title: String, text: String) {
        val body = text.ifEmpty { "(пусто)" }
        val tv = TextView(this).apply {
            this.text = body; textSize = 11f; typeface = Typeface.MONOSPACE
            setPadding(28, 16, 28, 16); setTextIsSelectable(true)
        }
        AlertDialog.Builder(this).setTitle(title)
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Копировать") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Leongram", body))
                Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Отправить") { _, _ ->
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body.takeLast(100_000))
                startActivity(Intent.createChooser(i, "Отправить"))
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun save() = fields.forEach { (k, e) -> Store.put(k, e.text.toString()) }

    private fun run(mode: String) {
        save()
        val s = BotService.instance
        if (s == null) Toast.makeText(this, "Сначала включи спецвозможности Leongram", Toast.LENGTH_LONG).show()
        else { s.start(mode); moveTaskToBack(true) }
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); if (!noSave) save(); h.removeCallbacks(tick) }
}
