package com.csc.translate

import android.app.Application
import androidx.room.Room
import com.csc.translate.data.SettingsStore
import com.csc.translate.data.db.AppDatabase
import com.csc.translate.net.AppJson
import com.csc.translate.net.SimulInferApi
import com.csc.translate.session.LiveSessionController
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class CscApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    val json = AppJson
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    val settings = SettingsStore(app)
    val db: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "csc-translate.db")
        .fallbackToDestructiveMigration()
        .build()
    val api = SimulInferApi(http, json)
    val sessions = LiveSessionController(
        app = app,
        settings = settings,
        dao = db.sessionDao(),
        api = api,
        http = http,
        json = json,
    )
}
