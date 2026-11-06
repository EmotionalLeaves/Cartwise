package com.emotionalleaves.cartwise

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emotionalleaves.cartwise.data.FeedCardEntity
import com.emotionalleaves.cartwise.data.WatchEntity
import com.emotionalleaves.cartwise.engine.Extract
import com.emotionalleaves.cartwise.rank.Preset
import com.emotionalleaves.cartwise.rank.Ranker
import com.emotionalleaves.cartwise.taste.Quiz
import com.emotionalleaves.cartwise.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Draws every screen on a simulated phone so crashes at composition time fail CI, not your phone. */
@RunWith(AndroidJUnit4::class)
class ScreensRenderTest {
    @get:Rule val rule = createComposeRule()
    private val dao get() = Graph.db.dao()

    @Before fun clean() = runBlocking {
        Graph.taste.wipe()
        dao.watchByStaleness().forEach { dao.deleteWatch(it.asin) }
    }

    private fun show(content: @Composable (NavHostController) -> Unit) {
        rule.setContent { CartwiseTheme("light") { content(rememberNavController()) } }
    }

    private fun waitText(t: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun fixture(name: String) = JSONObject(javaClass.getResource("/fixtures/$name.json")!!.readText())

    @Test fun forYouEmptyAsksForQuiz() {
        show { ForYouScreen(it) }
        waitText("what you actually keep")
    }

    @Test fun forYouShowsShelves() {
        runBlocking {
            val now = System.currentTimeMillis()
            dao.replaceFeed(
                listOf(
                    FeedCardEntity("B0RESTOCK1", "RESTOCK", "Time to restock", 0, "Bounty paper towels 12 pack", "", 24.99, 4.8, 90000, "Bounty", "You rebuy this about every 30 days", 0.9, 0.7, false, now),
                    FeedCardEntity("B0WILD0001", "WILDCARD", "Wildcard", 5000, "Pour over coffee kettle", "", 39.0, 4.6, 1200, "Fellow", "Wildcard — next to Coffee", 0.6, 0.3, true, now),
                ),
            )
        }
        show { ForYouScreen(it) }
        waitText("Time to restock")
        waitText("Bounty paper towels")
        waitText("Wildcard · something new")
    }

    @Test fun searchScreen() {
        show { SearchScreen(it) }
        waitText("What are you looking for?")
        waitText("air fryer")
    }

    @Test fun resultCardAndWhyRank() {
        val page = Extract.searchPage(fixture("search_search"), "https://www.amazon.com/s?k=usb")
        val ranked = Ranker.rank(page.items, Ranker.Ctx("usb c cable", Preset.BEST, hideSponsored = false))
        show {
            Column {
                ProductTile(ranked[0].item.tile(), onOpen = {}, onLong = {})
                WhyRank(ranked[0])
            }
        }
        waitText("Anker USB C")
        waitText("Score ")
    }

    @Test fun productBodyTabs() {
        val p = Extract.product(fixture("product_product"), "https://www.amazon.com/dp/B0AAAAAAA1")
        show { nav ->
            var tab by remember { mutableIntStateOf(0) }
            ProductBody(p, nav, "https://www.amazon.com/dp/B0AAAAAAA1", adding = false, watched = false, blockedSeller = false, onAdd = {}, onWatch = {}, tab = tab, onTab = { tab = it })
        }
        waitText("Add to cart")
        waitText("10ft")
        rule.onNodeWithText("Reviews").performClick()
        waitText("Great cable")
        rule.onNodeWithText("Details").performClick()
        waitText("Fast charging")
    }

    @Test fun watchlist() {
        runBlocking { dao.upsertWatch(WatchEntity("B0DDDDDDD1", "Desk lamp with USB port", "", 24.5, 29.0, 20.0, System.currentTimeMillis())) }
        show { ListsScreen(it) }
        waitText("Desk lamp with USB port")
        waitText("Target $20.00")
        waitText("was $29.00")
    }

    @Test fun interests() {
        runBlocking { Graph.taste.applyQuiz(Quiz.CHIPS.filter { it.slug in setOf("keyboards", "coffee", "camping", "books", "pcgaming") }) }
        show { InterestsScreen(it) }
        waitText("Retake the start quiz")
        waitText(" things")
    }

    @Test fun quizNeedsFive() {
        show { QuizScreen(it) }
        waitText("Pick 5 more")
        listOf("Kitchen gear", "Coffee & tea", "Books", "Board games", "Cycling").forEach { rule.onNodeWithText(it).performClick() }
        waitText("Build my feed (5)")
    }

    @Test fun settings() {
        show { SettingsScreen(it) }
        waitText("Hide sponsored results")
        waitText("Page-reading rules")
    }

    @Test fun debugTabs() {
        show { DebugScreen(it) }
        waitText("signals logged")
        rule.onNodeWithText("Orders").performClick()
        waitText("order lines")
        rule.onNodeWithText("Page reader").performClick()
        waitText("Rules version")
        rule.onNodeWithText("Engine").performClick()
        waitText("No pages loaded yet")
    }

    @Test fun account() {
        show { AccountScreen(it) }
        waitText("Run self-check")
        waitText("What Cartwise will never do")
    }

    @Test fun selfCheck() {
        show { SelfCheckScreen(it) }
        waitText("Search reads 20+ results")
    }

    @Test fun cartScreen() {
        show { CartScreen(it) }
        waitText("Cart")
    }

    @Test fun outletTab() {
        show { OutletScreen(it) }
        waitText("Like new")
        waitText("Search for you deals")
    }

    @Test fun rawPageBanner() {
        show { WebRoute(it, "https://www.amazon.com/dp/B0AAAAAAA1", "Simplified view unavailable") }
        waitText("Simplified view unavailable")
    }
}
