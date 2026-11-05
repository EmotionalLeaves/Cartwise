@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.emotionalleaves.cartwise.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.emotionalleaves.cartwise.Graph
import com.emotionalleaves.cartwise.data.FeedCardEntity
import com.emotionalleaves.cartwise.engine.Fmt
import com.emotionalleaves.cartwise.feed.Shelf
import com.emotionalleaves.cartwise.feed.Workers
import com.emotionalleaves.cartwise.engine.Parse
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch


/** The endless "More for you" stream under the shelves. One Amazon page per batch, at least 2 s apart, started well before you reach the bottom. */
class ForYouVm : ViewModel() {
    val more = mutableStateListOf<FeedCardEntity>()
    var loading by mutableStateOf(false)
        private set
    var exhausted by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var noInterests by mutableStateOf(false)
        private set

    private var step = 0
    private var empties = 0
    private var lastLoad = 0L
    private var job: Job? = null

    fun reset() {
        job?.cancel()
        more.clear(); step = 0; empties = 0; exhausted = false; error = null; noInterests = false; loading = false
        Graph.feed.forgetProfile()
    }

    fun loadMore(shelf: List<FeedCardEntity>) {
        if (loading || exhausted) return
        loading = true
        error = null
        job = viewModelScope.launch {
            var added = 0
            var tries = 0
            while (added == 0 && tries < 3 && !exhausted) {
                tries++
                val wait = 2_000 - (System.currentTimeMillis() - lastLoad)
                if (wait > 0) delay(wait)
                val exclude = shelf.map { it.asin }.toHashSet().apply { more.forEach { add(it.asin) } }
                val res = Graph.feed.more(step++, exclude)
                lastLoad = System.currentTimeMillis()
                if (res.noInterests) { noInterests = true; exhausted = true; break }
                if (res.error != null) { error = res.error; break }
                // Variety: max 3 cards per brand across the whole feed, 1 in 8 wildcards, no near-duplicate photos.
                val brands = HashMap<String, Int>()
                (shelf + more).forEach { val b = brandOf(it); brands[b] = (brands[b] ?: 0) + 1 }
                val images = (shelf + more).map { Parse.imageId(it.image) }.filter { it.isNotEmpty() }.toHashSet()
                val wildShown = more.count { it.wildcard }
                var wildAllowed = maxOf(1, (more.size + res.cards.size) / 8) - wildShown
                val fresh = ArrayList<FeedCardEntity>()
                for (c in res.cards) {
                    val b = brandOf(c)
                    if ((brands[b] ?: 0) >= 3) continue
                    val img = Parse.imageId(c.image)
                    if (img.isNotEmpty() && img in images) continue
                    if (c.wildcard) { if (wildAllowed <= 0) continue; wildAllowed-- }
                    brands[b] = (brands[b] ?: 0) + 1
                    if (img.isNotEmpty()) images += img
                    fresh += c
                }
                more.addAll(fresh)
                added = fresh.size
                if (added == 0) { if (++empties >= 8) exhausted = true } else empties = 0
            }
            loading = false
        }
    }

    fun remove(asin: String) {
        more.removeAll { it.asin == asin }
    }

    private fun brandOf(c: FeedCardEntity) = c.brand.ifBlank { c.title.substringBefore(' ') }.lowercase()
}

@Composable
fun ForYouScreen(nav: NavController) {
    val ctx = LocalContext.current
    val dao = Graph.db.dao()
    val cards by dao.feedFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val clusters by dao.clustersFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val prefs by Graph.settings.prefs.collectAsStateWithLifecycle(initialValue = null)
    val progress by Graph.feed.progress.collectAsStateWithLifecycle()
    val signedIn = remember { Graph.shop.isSignedIn() }
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<FeedCardEntity?>(null) }
    val vm: ForYouVm = viewModel { ForYouVm() }
    val list = rememberLazyListState()
    val hasInterests = clusters.isNotEmpty()
    val cardsNow by rememberUpdatedState(cards)
    val refreshing by rememberUpdatedState(progress.running)
    LaunchedEffect(list, hasInterests) {
        if (!hasInterests) return@LaunchedEffect
        snapshotFlow {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            (info.totalItemsCount > 0 && last >= info.totalItemsCount - 7) to info.totalItemsCount
        }.distinctUntilChanged().collect { (near, _) -> if (near && !refreshing) vm.loadMore(cardsNow) }
    }

    Screen(
        title = "For You",
        actions = {
            IconButton(onClick = { nav.navigate("interests") }) { Icon(Icons.Filled.Star, contentDescription = "Your interests") }
            IconButton(onClick = { vm.reset(); Graph.feed.refreshNow() }, enabled = !progress.running) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh the feed")
            }
            AccountButton(nav)
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = progress.running,
            onRefresh = { vm.reset(); Graph.feed.refreshNow() },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                if (progress.running) {
                    item("progress") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(progress.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { if (progress.max > 0) progress.used.toFloat() / progress.max else 0f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "Reading pages slowly on purpose (${progress.used} of up to ${progress.max}) so your account looks normal.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
                if (!signedIn) {
                    item("signin") {
                        Banner("Sign in to Amazon for restock, price-drop and Lists shelves.", "Sign in") { nav.navigate("login") }
                    }
                }
                if (prefs?.quizDone != true && clusters.isEmpty()) {
                    item("quiz") {
                        Card(Modifier.fillMaxWidth().padding(16.dp)) {
                            Column(Modifier.padding(20.dp)) {
                                Text("Make this feed yours", style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Pick 5–10 things you shop for. It takes 30 seconds, and the feed only shows items that match what you buy and keep.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { nav.navigate("quiz") }) { Text("Start the quiz") }
                            }
                        }
                    }
                }
                val shelves = cards.groupBy { it.shelfLabel }
                    .entries.sortedBy { e -> e.value.minOf { it.position } }
                for ((label, group) in shelves) {
                    val shelf = runCatching { Shelf.valueOf(group.first().shelf) }.getOrNull()
                    item("h-$label") {
                        SectionHeader(if (shelf == Shelf.WILDCARD) "Wildcard · something new" else label)
                    }
                    item("r-$label") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(group, key = { it.asin }) { card ->
                                FeedCard(
                                    card,
                                    onOpen = {
                                        scope.launch { Graph.feed.opened(card.asin) }
                                        nav.openProduct(card.toListing())
                                    },
                                    onUp = { scope.launch { Graph.feed.thumb(card, true) } },
                                    onDown = { scope.launch { Graph.feed.thumb(card, false) } },
                                    onMore = { menuFor = card },
                                )
                            }
                        }
                    }
                }
                if (cards.isEmpty() && !progress.running && (prefs?.quizDone == true || clusters.isNotEmpty())) {
                    item("empty") {
                        EmptyState(
                            "Nothing cleared the bar yet",
                            "Cartwise only shows things that match what you buy and keep. An empty shelf beats a shelf of junk. " +
                                "Pull down to refresh, import your orders from Account, or search a few things you like.",
                            "Refresh now",
                        ) { vm.reset(); Graph.feed.refreshNow() }
                    }
                }
                val p = prefs
                if (p != null && p.lastFeed > 0 && cards.isNotEmpty()) {
                    item("updated") {
                        Text(
                            "Updated " + Fmt.ago(p.lastFeed) + ". Refreshes once a day on Wi-Fi.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                progress.error?.let { err ->
                    item("err") { Banner("Last refresh failed: $err", "Try again") { vm.reset(); Graph.feed.refreshNow() } }
                }
                if (!progress.running && progress.error == null) {
                    progress.note?.let { n -> item("note") { Banner(n) } }
                }
                if (hasInterests && (vm.more.isNotEmpty() || vm.loading || vm.error != null)) {
                    item("more-h") { SectionHeader("More for you") }
                }
                items(vm.more, key = { "m-" + it.asin }) { card ->
                    MoreCard(
                        card,
                        modifier = Modifier.animateItem(),
                        onOpen = {
                            scope.launch { Graph.feed.opened(card.asin) }
                            nav.openProduct(card.toListing())
                        },
                        onUp = { scope.launch { Graph.feed.thumb(card, true) } },
                        onDown = { vm.remove(card.asin); scope.launch { Graph.feed.thumb(card, false) } },
                        onMore = { menuFor = card },
                    )
                }
                if (vm.loading) item("more-loading") { Column { ResultSkeleton(); ResultSkeleton() } }
                vm.error?.let { err ->
                    item("more-err") {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Couldn't load more: $err", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.loadMore(cards) }) { Text("Try again") }
                        }
                    }
                }
                if (vm.exhausted && hasInterests) {
                    item("more-end") {
                        Text(
                            "That's everything that clears the bar right now. Thumbs-up a few things or search for what you like, and there'll be more.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                }
            }
        }
    }

    menuFor?.let { card ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text("Never show", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                ListItem(
                    headlineContent = { Text("This item") },
                    supportingContent = { Text(Fmt.short(card.title, 60)) },
                    modifier = Modifier.combinedClickable(onClick = { vm.remove(card.asin); scope.launch { Graph.feed.neverShow(card, "item") }; menuFor = null }),
                )
                val brand = card.brand.ifBlank { card.title.substringBefore(' ') }
                ListItem(
                    headlineContent = { Text("Anything from $brand") },
                    supportingContent = { Text("Hidden everywhere in Cartwise, including search") },
                    modifier = Modifier.combinedClickable(onClick = {
                        val b = card.brand.ifBlank { card.title.substringBefore(' ') }
                        vm.more.removeAll { it.brand.ifBlank { it.title.substringBefore(' ') }.equals(b, true) }
                        scope.launch { Graph.feed.neverShow(card, "brand") }; menuFor = null
                    }),
                )
                ListItem(
                    headlineContent = { Text("Things like this") },
                    supportingContent = { Text("Removes this topic from your feed for good") },
                    modifier = Modifier.combinedClickable(onClick = { vm.remove(card.asin); scope.launch { Graph.feed.neverShow(card, "topic") }; menuFor = null }),
                )
            }
        }
    }
}

@Composable
private fun FeedCard(card: FeedCardEntity, onOpen: () -> Unit, onUp: () -> Unit, onDown: () -> Unit, onMore: () -> Unit) {
    LaunchedEffect(card.asin) { Graph.feed.impression(card.asin) }
    ElevatedCard(
        modifier = Modifier.width(236.dp).combinedClickable(onClick = onOpen, onLongClick = onMore),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column {
            ProductImage(card.image, Modifier.fillMaxWidth().height(196.dp), card.title, px = 500)
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(card.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(40.dp))
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (card.price != null) {
                        Text(Fmt.money(card.price), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                    }
                    RatingLine(card.rating, card.reviews)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    card.reason,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(34.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onUp) { Icon(Icons.Filled.ThumbUp, contentDescription = "More like this") }
                    IconButton(onClick = onDown) {
                        Icon(Icons.Filled.ThumbUp, contentDescription = "Less like this", modifier = Modifier.rotate(180f))
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onMore) { Icon(Icons.Filled.MoreVert, contentDescription = "Never show options") }
                }
            }
        }
    }
}

@Composable
private fun MoreCard(card: FeedCardEntity, modifier: Modifier, onOpen: () -> Unit, onUp: () -> Unit, onDown: () -> Unit, onMore: () -> Unit) {
    LaunchedEffect(card.asin) { Graph.feed.impression(card.asin) }
    Row(
        modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onMore).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProductImage(card.image, Modifier.size(112.dp), card.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(card.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (card.price != null) {
                    Text(Fmt.money(card.price), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(8.dp))
                }
                RatingLine(card.rating, card.reviews)
            }
            Text(
                if (card.wildcard) "Wildcard · " + card.reason.removePrefix("Wildcard — ") else card.reason,
                style = MaterialTheme.typography.labelMedium,
                color = if (card.wildcard) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onUp) { Icon(Icons.Filled.ThumbUp, contentDescription = "More like this") }
                IconButton(onClick = onDown) { Icon(Icons.Filled.ThumbUp, contentDescription = "Less like this", modifier = Modifier.rotate(180f)) }
                IconButton(onClick = onMore) { Icon(Icons.Filled.MoreVert, contentDescription = "Never show options") }
            }
        }
    }
}
