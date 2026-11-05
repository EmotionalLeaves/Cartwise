package com.emotionalleaves.cartwise.taste

import com.emotionalleaves.cartwise.data.CartwiseDb
import com.emotionalleaves.cartwise.data.ItemEntity
import com.emotionalleaves.cartwise.data.OrderEntity
import com.emotionalleaves.cartwise.data.Settings
import com.emotionalleaves.cartwise.data.SignalEntity
import com.emotionalleaves.cartwise.engine.Load
import com.emotionalleaves.cartwise.engine.OrderCard
import com.emotionalleaves.cartwise.engine.PageBudget
import com.emotionalleaves.cartwise.engine.Reason
import com.emotionalleaves.cartwise.engine.ShopRepo
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opt-in, one-time read of your Orders pages (kept vs returned), at human pace.
 * Stored only on the phone; wipe it from Settings.
 */
class OrderImporter(
    private val shop: ShopRepo,
    private val db: CartwiseDb,
    private val signals: SignalLog,
    private val settings: Settings,
) {
    val progress = MutableStateFlow<String?>(null)
    private val busy = AtomicBoolean(false)

    suspend fun run(): Load<Int> {
        if (!busy.compareAndSet(false, true)) return Load.Fail(Reason.ERROR, "An import is already running")
        try {
            return runInner()
        } finally {
            busy.set(false)
            progress.value = null
        }
    }

    private suspend fun runInner(): Load<Int> {
        val budget = PageBudget(12, paced = true)
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val cards = ArrayList<OrderCard>()
        val seenIds = HashSet<String>()
        var lastFail: Load.Fail? = null
        outer@ for (y in year downTo year - 2) {
            var start = 0
            while (true) {
                if (!budget.take()) break@outer
                progress.value = "Reading your $y orders (page ${start / 10 + 1}, ${budget.used} of ${budget.max} pages)"
                val r = shop.orders(shop.url("orders", "year" to y.toString(), "start" to start.toString()))
                val page = when (r) {
                    is Load.Ok -> r.v
                    is Load.Fail -> {
                        if (r.reason == Reason.SIGNED_OUT || r.reason == Reason.CAPTCHA) return r
                        lastFail = r
                        break
                    }
                }
                val got = page.orders.filter { it.items.isNotEmpty() }
                val fresh = got.filter { c -> val key = c.id.ifBlank { c.items.first().asin + c.date }; seenIds.add(key) }
                if (fresh.isEmpty()) break
                cards += fresh
                if (got.size < 10 && !page.hasNext) break
                start += 10
            }
        }
        if (cards.isEmpty()) return lastFail ?: Load.Fail(Reason.EMPTY, "No orders were found on your Orders pages")

        val dao = db.dao()
        val lines = cards.flatMap { c ->
            c.items.map {
                OrderEntity(
                    orderId = c.id.ifBlank { "d" + c.date }, asin = it.asin, title = it.title, image = it.image,
                    price = it.price, date = c.date, returned = c.returned,
                )
            }
        }.distinctBy { it.orderId + it.asin }
        dao.upsertOrders(lines)
        for (l in lines) signals.remember(ItemEntity(ref = l.asin, title = l.title, image = l.image, price = l.price))

        dao.deleteSignalTypes(listOf(SignalType.KEPT, SignalType.RETURNED))
        val now = System.currentTimeMillis()
        val all = dao.orders()
        dao.insertSignals(
            all.map {
                val type = if (it.returned) SignalType.RETURNED else SignalType.KEPT
                SignalEntity(ref = it.asin, type = type, weight = SignalType.weight(type), ts = if (it.date > 0) it.date else now, note = it.title.take(100))
            },
        )
        settings.setOrdersImported(now)
        return Load.Ok(all.size)
    }
}
