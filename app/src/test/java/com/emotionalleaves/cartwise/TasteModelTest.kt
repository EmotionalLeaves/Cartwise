package com.emotionalleaves.cartwise

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emotionalleaves.cartwise.data.ItemEntity
import com.emotionalleaves.cartwise.data.SignalEntity
import com.emotionalleaves.cartwise.data.displayName
import com.emotionalleaves.cartwise.taste.Quiz
import com.emotionalleaves.cartwise.taste.SignalType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real taste model on a simulated device: Room, DataStore, embeddings, clustering, fit. */
@RunWith(AndroidJUnit4::class)
class TasteModelTest {
    private val dao get() = Graph.db.dao()
    private val picks = listOf("keyboards", "coffee", "camping", "books", "pcgaming")

    @Before fun clean() = runBlocking { Graph.taste.wipe() }

    @Test fun quizBuildsInterests() = runBlocking {
        Graph.taste.applyQuiz(Quiz.CHIPS.filter { it.slug in picks })
        val clusters = dao.clusters()
        assertTrue("expected interests after the quiz", clusters.isNotEmpty())
        assertTrue(clusters.size <= 8)
        clusters.forEach { assertTrue(it.displayName().isNotBlank()) }
        assertTrue(Graph.settings.current().quizDone)
        assertTrue(Graph.taste.quizPicks() == picks.toSet())
    }

    @Test fun fitPrefersMatchingItems() = runBlocking {
        Graph.taste.applyQuiz(Quiz.CHIPS.filter { it.slug in picks })
        val profile = Graph.taste.profile()
        val keyboard = ItemEntity(ref = "T1", title = "Keychron K2 wireless mechanical keyboard brown switches", category = "Electronics › Mechanical keyboards")
        val hose = ItemEntity(ref = "T2", title = "Garden hose nozzle with 8 spray patterns", category = "Patio, Lawn & Garden › Watering")
        val fk = Graph.taste.fit(profile, Graph.taste.vectorFor(keyboard), "Keychron", keyboard.category).score
        val fh = Graph.taste.fit(profile, Graph.taste.vectorFor(hose), "Melnor", hose.category).score
        assertTrue("keyboard fit $fk should beat hose fit $fh", fk > fh)
    }

    @Test fun keptItemsBecomeInterests() = runBlocking {
        val now = System.currentTimeMillis()
        val coffee = listOf("Whole bean coffee dark roast 2lb", "Burr coffee grinder conical", "Pour over coffee dripper ceramic", "Coffee filters cone #2 100 count", "Gooseneck kettle for pour over coffee", "Cold brew coffee maker pitcher")
        val camp = listOf("Ultralight backpacking tent 2 person", "Camping sleeping bag 20 degree", "Backpacking stove canister", "Headlamp rechargeable for camping", "Trekking poles collapsible", "Camping sleeping pad inflatable")
        (coffee + camp).forEachIndexed { i, t ->
            val ref = "K$i"
            Graph.signals.remember(ItemEntity(ref = ref, title = t, category = if (i < 6) "Home & Kitchen › Coffee" else "Sports & Outdoors › Camping"))
            dao.insertSignal(SignalEntity(ref = ref, type = SignalType.KEPT, weight = SignalType.weight(SignalType.KEPT), ts = now - i * 86_400_000L))
        }
        Graph.taste.rebuild()
        val clusters = dao.clusters()
        assertTrue("expected at least 2 interests, got ${clusters.map { it.displayName() }}", clusters.size >= 2)
        val names = clusters.map { it.displayName().lowercase() }
        assertTrue(names.toString(), names.any { "coffee" in it } && names.any { "camping" in it })
    }

    @Test fun wipeClearsEverything() = runBlocking {
        Graph.taste.applyQuiz(Quiz.CHIPS.filter { it.slug in picks })
        Graph.taste.wipe()
        assertTrue(dao.clusters().isEmpty())
        assertTrue(dao.allSignals().isEmpty())
    }
}
