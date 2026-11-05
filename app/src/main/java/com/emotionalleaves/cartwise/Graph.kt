package com.emotionalleaves.cartwise

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.room.Room
import com.emotionalleaves.cartwise.data.CartwiseDb
import com.emotionalleaves.cartwise.data.Settings
import com.emotionalleaves.cartwise.data.WatchEntity
import com.emotionalleaves.cartwise.engine.Listing
import com.emotionalleaves.cartwise.engine.Product
import com.emotionalleaves.cartwise.engine.SelectorStore
import com.emotionalleaves.cartwise.engine.ShopRepo
import com.emotionalleaves.cartwise.engine.WebEngine
import com.emotionalleaves.cartwise.feed.FeedBuilder
import com.emotionalleaves.cartwise.taste.Embedder
import com.emotionalleaves.cartwise.taste.OrderImporter
import com.emotionalleaves.cartwise.taste.SignalLog
import com.emotionalleaves.cartwise.taste.SignalType
import com.emotionalleaves.cartwise.taste.TasteModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Date

/** App-wide singletons. */
object Graph {
    lateinit var app: Application
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Set when a price alert notification is tapped; the nav host opens that product. */
    val pendingAsin = MutableStateFlow<String?>(null)

    fun init(a: Application) {
        app = a
    }

    val db: CartwiseDb by lazy {
        Room.databaseBuilder(app, CartwiseDb::class.java, "cartwise.db")
            .fallbackToDestructiveMigration()
            .build()
    }
    val settings: Settings by lazy { Settings(app) }
    val selectors: SelectorStore by lazy { SelectorStore(app) }
    /** Hidden browser for things you tap. */
    val engine: WebEngine by lazy { WebEngine(app, selectors, "you") }
    val shop: ShopRepo by lazy { ShopRepo(engine, selectors) }

    /** Second hidden browser for feed refreshes, "More for you", price checks and order import, so they never make you wait. */
    val bgEngine: WebEngine by lazy { WebEngine(app, selectors, "background") }
    val bgShop: ShopRepo by lazy { ShopRepo(bgEngine, selectors) }

    /** What you tapped (photo, title, price), shown instantly while the full product page loads. */
    val previews = java.util.concurrent.ConcurrentHashMap<String, Listing>()
    val embedder: Embedder by lazy { Embedder(app) }
    val signals: SignalLog by lazy { SignalLog(db.dao()) }
    val taste: TasteModel by lazy { TasteModel(db, embedder, settings, signals) }
    val feed: FeedBuilder by lazy { FeedBuilder(app, db, bgShop, taste, signals, settings) }
    val orders: OrderImporter by lazy { OrderImporter(bgShop, db, signals, settings) }
}

/** Watchlist actions shared by several screens. */
object Watch {
    suspend fun add(l: Listing) {
        val dao = Graph.db.dao()
        val cur = dao.watch(l.asin)
        dao.upsertWatch(
            WatchEntity(
                asin = l.asin, title = l.title, image = l.image, price = l.price ?: cur?.price,
                prevPrice = cur?.prevPrice, target = cur?.target, addedAt = cur?.addedAt ?: System.currentTimeMillis(),
                checkedAt = cur?.checkedAt ?: System.currentTimeMillis(),
            ),
        )
        if (cur == null) Graph.signals.logListing(l, SignalType.LISTED)
    }

    suspend fun add(p: Product) = add(p.toListing())

    suspend fun remove(asin: String) = Graph.db.dao().deleteWatch(asin)

    suspend fun setTarget(asin: String, target: Double?) {
        val dao = Graph.db.dao()
        val cur = dao.watch(asin) ?: return
        dao.upsertWatch(cur.copy(target = target, notifiedAt = 0))
    }
}

object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "crash.txt")

    fun install(c: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                file(c).appendText("=== ${Date()} on ${t.name} (build ${BuildConfig.VERSION_NAME})\n${Log.getStackTraceString(e)}\n")
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun read(c: Context): String = file(c).takeIf { it.exists() }?.readText()?.takeLast(30_000) ?: ""

    fun clear(c: Context) {
        file(c).delete()
    }
}
