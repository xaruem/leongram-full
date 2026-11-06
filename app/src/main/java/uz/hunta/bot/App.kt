package uz.hunta.bot

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        BotLog.init(this)
        CrashHandler.install(this)
        try { Stats.prune() } catch (_: Throwable) {}
        BotLog.add("Процесс приложения запущен")
    }
}
