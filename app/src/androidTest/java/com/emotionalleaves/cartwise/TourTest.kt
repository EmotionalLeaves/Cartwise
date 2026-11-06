package com.emotionalleaves.cartwise

import android.graphics.Bitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emotionalleaves.cartwise.engine.AmazonSession
import com.emotionalleaves.cartwise.engine.HealthLog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The emulator tour: drives the real app against live Amazon and saves a screenshot of each
 * screen plus engine logs, page-reader health and photo addresses. Not a pass/fail test;
 * every step records what happened and moves on.
 */
@RunWith(AndroidJUnit4::class)
class TourTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir by lazy { File(ctx.getExternalFilesDir(null), "tour").apply { deleteRecursively(); mkdirs() } }
    private val notes = StringBuilder()

    private fun note(s: String) {
        notes.appendLine(s)
        File(dir, "notes.txt").writeText(notes.toString())
    }

    private fun idle() {
        try { rule.waitForIdle() } catch (_: Throwable) { }
    }

    private fun shot(name: String, settleMs: Long = 1500) {
        try {
            idle()
            Thread.sleep(settleMs)
            val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
            FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            note("shot $name")
        } catch (e: Throwable) {
            note("shot $name failed: $e")
        }
    }

    private fun step(name: String, block: () -> Unit) {
        val t = System.currentTimeMillis()
        try {
            block()
            note("OK   $name (${System.currentTimeMillis() - t} ms)")
        } catch (e: Throwable) {
            note("FAIL $name (${System.currentTimeMillis() - t} ms): ${e.javaClass.simpleName}: ${e.message?.take(300)}")
            shot("fail-" + name.replace(Regex("[^a-z0-9]+"), "-"))
        }
    }

    private fun waitText(t: String, ms: Long = 45_000) {
        rule.waitUntil(ms) { rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun tab(route: String) {
        // Back out of any pushed screen (product, account) so the tab bar is showing.
        repeat(3) { if (rule.onAllNodes(androidx.compose.ui.test.hasTestTag("tab-$route")).fetchSemanticsNodes().isEmpty()) { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; Thread.sleep(600) } }
        rule.onNodeWithTag("tab-$route").performClick()
        idle()
        Thread.sleep(800)
    }

    private fun clickText(t: String, substring: Boolean = false) {
        rule.waitUntil(15_000) { rule.onAllNodes(hasText(t, substring = substring) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText(t, substring = substring) and hasClickAction()).onFirst().performClick()
        idle()
    }

    private fun scroller(): SemanticsNodeInteraction = rule.onAllNodes(hasScrollAction()).onFirst()

    private fun swipe(times: Int = 1) {
        repeat(times) { scroller().performTouchInput { swipeUp(durationMillis = 300) } }
        idle()
    }

    private fun dump(name: String, text: String) = File(dir, name).writeText(text)

    private fun dumpState() {
        val engines = (Graph.engine.log.value + Graph.bgEngine.log.value).sortedBy { it.time }
            .joinToString("\n") { "${it.kind}\t${it.ms} ms\t${it.result}\t${it.url}" }
        dump("engine-log.txt", engines)
        val health = JSONObject()
        HealthLog.state.value.forEach { (k, h) ->
            health.put(k, JSONObject().put("count", h.count).put("ok", h.ok).put("url", h.url).put("fields", JSONObject(h.fields)).put("notes", JSONArray(h.notes)))
        }
        dump("health.json", health.toString(2))
        val items = JSONArray()
        HealthLog.lastListings.value.forEach { l ->
            items.put(JSONObject().put("asin", l.asin).put("title", l.title).put("image", l.image).put("price", l.priceText).put("list", l.listPrice ?: JSONObject.NULL).put("sponsored", l.sponsored))
        }
        dump("last-search-items.json", items.toString(2))
        dump("debug-lines.txt", Graph.debugLines.value.joinToString("\n"))
        dump("session.txt", "cartCount=${AmazonSession.cartCount.value}\nrules=${Graph.selectors.info.value}\nembedder=${Graph.embedder.name}\nassets=${ctx.assets.list("")?.joinToString()}")
        dump("photo-failures.txt", com.emotionalleaves.cartwise.engine.PhotoLog.failures.value.joinToString("\n") { it.why + " :: " + it.url })
    }

    @Test
    fun tour() {
        note("Cartwise tour, build ${BuildConfig.VERSION_NAME}")
        step("launch") { shot("01-for-you-first-launch", 3000) }

        step("quiz") {
            clickText("Pick what you shop for")
            waitText("Pick 5 more", 10_000)
            listOf("Kitchen gear", "Coffee & tea", "Headphones & audio", "Camping & hiking", "Home office").forEach { clickText(it) }
            shot("02-quiz")
            clickText("Build my feed", substring = true)
        }
        step("for-you-refreshing") { shot("03-for-you-refreshing", 8000); dumpState() }

        step("search") {
            tab("search")
            shot("04-search-empty")
            rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("sound bar")
            rule.onAllNodes(hasSetTextAction()).onFirst().performImeAction()
            waitText("$", 60_000)
            shot("05-results", 4000)
            dumpState()
        }
        step("results-scroll") {
            swipe(3)
            shot("06-results-scrolled", 3000)
            swipe(6)
            shot("07-results-deep", 5000)
        }
        step("results-sort") {
            clickText("Best overall")
            clickText("Biggest discount")
            shot("08-results-discount", 2000)
            clickText("On sale")
            shot("09-results-on-sale", 2000)
        }
        step("product") {
            // Open the first non-sponsored result, the same way a price-alert notification does.
            val first = HealthLog.lastListings.value.firstOrNull { !it.sponsored } ?: error("no results to open")
            Graph.previews[first.asin] = first
            Graph.pendingAsin.value = first.asin
            waitText("Add to cart", 60_000)
            shot("10-product", 4000)
            swipe(2)
            shot("11-product-lower", 2000)
            clickText("Reviews")
            shot("12-product-reviews", 1500)
            clickText("Details")
            shot("13-product-details", 1500)
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        }

        step("outlet") {
            tab("outlet")
            shot("14-outlet-for-you", 25_000)
            try { dump("semantics-outlet.txt", rule.onRoot().printToString()) } catch (e: Throwable) { note("semantics: $e") }
            dumpState()
            clickText("Like new")
            shot("15-outlet-like-new", 15_000)
            clickText("Deals")
            shot("16-outlet-deals", 15_000)
        }

        step("lists") {
            tab("lists")
            shot("17-lists")
        }
        step("cart") {
            tab("cart")
            shot("18-cart", 8000)
        }
        step("for-you-after") {
            tab("foryou")
            shot("19-for-you-after", 5000)
            swipe(3)
            shot("20-for-you-more", 15_000)
        }
        step("account") {
            rule.onAllNodes(hasClickAction() and androidx.compose.ui.test.hasContentDescription("Account and settings")).onFirst().performClick()
            shot("21-account")
        }
        step("self-check") {
            clickText("Run self-check")
            waitText("Search reads 20+ results", 10_000)
            rule.onAllNodes(hasText("Run self-check") and hasClickAction()).onFirst().performClick()
            waitText("On-device taste model", 10_000)
            rule.waitUntil(120_000) { rule.onAllNodesWithText("Checking…").fetchSemanticsNodes().isEmpty() }
            shot("22-self-check", 1000)
        }
        step("demo") {
            rule.runOnUiThread { rule.activity.findViewById<android.view.View>(android.R.id.content) }
            Graph.pendingRoute.value = "demo"
            waitText("Demo", 10_000)
            shot("23-demo-grid", 6000)
            clickText("Product")
            shot("24-demo-product", 6000)
            swipe(2)
            shot("25-demo-product-lower", 1500)
        }
        step("save-html") {
            var ua = ""
            rule.runOnUiThread { ua = Graph.engine.view().settings.userAgentString ?: "" }
            val pages = mapOf(
                "bestsellers-electronics" to "https://www.amazon.com/gp/bestsellers/electronics",
                "deals" to "https://www.amazon.com/s?rh=p_n_deal_type%3A23566064011&page=1",
                "outlet" to "https://www.amazon.com/s?k=headphones&rh=n%3A517808&page=1",
                "search-sound-bar" to "https://www.amazon.com/s?k=sound+bar&page=1",
            )
            kotlinx.coroutines.runBlocking {
                for ((name, u) in pages) {
                    val r = com.emotionalleaves.cartwise.engine.FastFetch.get(u, ua)
                    dump("html-$name.html", "<!-- ${r.status} ${r.finalUrl} -->\n" + r.html)
                    kotlinx.coroutines.delay(2000)
                }
            }
        }
        dumpState()
        note("done")
    }
}
