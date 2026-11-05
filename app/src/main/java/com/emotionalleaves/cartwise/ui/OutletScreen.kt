@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.emotionalleaves.cartwise.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.emotionalleaves.cartwise.Graph
import com.emotionalleaves.cartwise.Watch
import com.emotionalleaves.cartwise.engine.Fmt
import com.emotionalleaves.cartwise.engine.Listing
import com.emotionalleaves.cartwise.engine.Load
import com.emotionalleaves.cartwise.engine.Reason
import com.emotionalleaves.cartwise.engine.ShopRepo
import com.emotionalleaves.cartwise.rank.Preset
import com.emotionalleaves.cartwise.rank.Ranker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale

/** Price-tag icon for the Outlet tab (Material "local offer" shape). */
val OutletIcon: ImageVector by lazy {
    ImageVector.Builder("Outlet", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = addPathNodes(
                "M21.41 11.58l-9-9C12.05 2.22 11.55 2 11 2H4c-1.1 0-2 .9-2 2v7c0 .55.22 1.05.59 1.42l9 9c.36.36.86.58 1.41.58.55 0 1.05-.22 1.41-.59l7-7c.37-.36.59-.86.59-1.41 0-.55-.23-1.06-.59-1.42zM5.5 7C4.67 7 4 6.33 4 5.5S4.67 4 5.5 4 7 4.67 7 5.5 6.33 7 5.5 7z",
            ),
            fill = SolidColor(Color.Black),
        )
        .build()
}

enum class OutletSection(val label: String, val urlName: String?, val blurb: String) {
    FOR_YOU("For you", null, "Outlet and like-new deals that match what you buy and keep."),
    OUTLET("Outlet", "outlet", "Overstock and clearance, new condition."),
    RESALE("Like new", "resale", "Open-box and used items inspected and resold by Amazon."),
    DEALS("Deals", "deals", "Today's limited-time deals."),
}

data class OutletRow(val l: Listing, val reason: String?, val score: Double)

/** One Outlet section: endless pages, ranked per page, next page pre-loaded in the background. */
class OutletVm : ViewModel() {
    var section by mutableStateOf(OutletSection.FOR_YOU)
        private set
    var query by mutableStateOf("")
        private set
    var preset by mutableStateOf(Preset.DISCOUNT)
        private set
    val rows = mutableStateListOf<OutletRow>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var errorReason by mutableStateOf<Reason?>(null)
        private set
    var fallbackUrl by mutableStateOf<String?>(null)
        private set
    var ended by mutableStateOf(false)
        private set
    var noInterests by mutableStateOf(false)
        private set

    private var page = 1
    private var step = 0
    private var empties = 0
    private val pages = ArrayList<List<Listing>>()
    private var job: Job? = null
    private var hidden = emptySet<String>()
    private var blockedBrands = emptySet<String>()
    private var hideSponsored = true

    init {
        viewModelScope.launch {
            hideSponsored = Graph.settings.current().hideSponsored
            val b = Graph.db.dao().blocks()
            hidden = b.filter { it.kind == "hidden" || it.kind == "item" }.map { it.value }.toSet()
            blockedBrands = b.filter { it.kind == "brand" || it.kind == "seller" }.map { it.value.lowercase(Locale.US) }.toSet()
            loadMore()
        }
    }

    fun choose(s: OutletSection) { if (s != section) { section = s; restart() } }
    fun search(q: String) { query = q.trim(); restart() }
    fun sortBy(p: Preset) { preset = p; rerank() }

    fun restart() {
        job?.cancel()
        rows.clear(); pages.clear(); page = 1; step = 0; empties = 0
        loading = false; error = null; errorReason = null; fallbackUrl = null; ended = false; noInterests = false
        loadMore()
    }

    private fun url(name: String, q: String, page: Int): String {
        val u = Graph.shop.url(name, "q" to ShopRepo.enc(q), "page" to page.toString())
        return if (q.isBlank()) u.replace("k=&", "").replace("&k=", "").replace("?&", "?") else u
    }

    fun loadMore() {
        if (loading || ended) return
        loading = true
        error = null
        job = viewModelScope.launch {
            try {
                if (section == OutletSection.FOR_YOU) loadForYou() else loadPage()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
                Graph.debug("outlet error " + e)
            }
            loading = false
            Graph.debug("outlet ${section.name} q='$query' rows=${rows.size} pages=${pages.size} ended=$ended err=$error fb=$fallbackUrl")
        }
    }

    private suspend fun loadPage() {
        val name = section.urlName ?: return
        val u = url(name, query, page)
        when (val r = Graph.shop.searchUrl(u)) {
            is Load.Ok -> {
                if (page == 1 && !r.v.health.ok) { fallbackUrl = u; return }
                val known = pages.flatten().map { it.asin }.toHashSet()
                val fresh = r.v.items.filter { it.asin !in known }
                if (fresh.isEmpty()) { ended = true; return }
                pages.add(fresh)
                page++
                rerank()
                prefetch(url(name, query, page), if (page == 2) fresh.take(3).map { it.asin } else emptyList())
            }
            is Load.Fail -> {
                if (page == 1 && r.reason != Reason.OFFLINE && r.reason != Reason.TIMEOUT) fallbackUrl = r.url ?: u
                else { error = r.message; errorReason = r.reason }
            }
        }
    }

    private suspend fun loadForYou() {
        val interests = Graph.feed.interests()
        if (interests.isEmpty()) { noInterests = true; ended = true; return }
        var added = 0
        var tries = 0
        while (added == 0 && tries < 3 && !ended) {
            tries++
            val c = interests[step % interests.size]
            val round = step / interests.size
            step++
            val resale = round % 2 == 1
            val q = if (query.isBlank()) c.e.query else query + " " + c.e.query
            val u = url(if (resale) "resale" else "outlet", q, round / 2 + 1)
            val items = when (val r = Graph.shop.searchUrl(u)) {
                is Load.Ok -> r.v.items
                is Load.Fail -> { error = r.message; errorReason = r.reason; return }
            }
            val shown = rows.map { it.l.asin }.toHashSet()
            val brands = HashMap<String, Int>()
            rows.forEach { val b = Ranker.brandKey(it.l); brands[b] = (brands[b] ?: 0) + 1 }
            val picks = Graph.feed.pick(items.filter { it.asin !in shown && it.asin !in hidden && Ranker.brandKey(it) !in blockedBrands }, (if (resale) "Like-new deal in " else "Outlet deal in ") + c.name)
            for (p in picks) {
                val b = Ranker.brandKey(p.listing)
                if ((brands[b] ?: 0) >= 3) continue
                brands[b] = (brands[b] ?: 0) + 1
                rows.add(OutletRow(p.listing, p.reason, p.score))
                added++
            }
            if (added == 0) { if (++empties >= 8) ended = true else delay(1_500) } else empties = 0
            if (items.isNotEmpty()) prefetch(null, picks.take(2).map { it.listing.asin })
        }
    }

    private fun rerank() {
        if (section == OutletSection.FOR_YOU) return
        val ctx = Ranker.Ctx(query, preset, null, hideSponsored, hidden, blockedBrands)
        val out = ArrayList<OutletRow>()
        val shown = HashSet<String>()
        for (pg in pages) for (r in Ranker.rank(pg.filter { it.asin !in shown }, ctx)) {
            out += OutletRow(r.item, null, r.score); shown += r.item.asin; r.similar.forEach { shown += it.asin }
        }
        rows.clear(); rows.addAll(out)
    }

    /** Quietly reads the next page and a few top products in the background browser so they open instantly. */
    private fun prefetch(nextUrl: String?, asins: List<String>) {
        viewModelScope.launch {
            if (nextUrl != null) Graph.bgShop.searchUrl(nextUrl)
            for (a in asins) { delay(1_200); Graph.bgShop.product(a) }
        }
    }

    fun watch(l: Listing) { viewModelScope.launch { Watch.add(l) } }
}

@Composable
fun OutletScreen(nav: NavController) {
    val vm: OutletVm = viewModel { OutletVm() }
    var text by rememberSaveable { mutableStateOf("") }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val watched by Graph.db.dao().watchFlow().collectAsStateWithLifecycle(initialValue = emptyList())

    LaunchedEffect(list) {
        snapshotFlow {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            (info.totalItemsCount > 0 && last >= info.totalItemsCount - 10) to info.totalItemsCount
        }.distinctUntilChanged().collect { (near, _) -> if (near) vm.loadMore() }
    }

    Screen(
        "Outlet",
        snackbar = snack,
        actions = {
            TextButton(onClick = { nav.openWeb(Graph.shop.url("outletWeb")) }) { Text("Full page") }
            AccountButton(nav)
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutletSection.entries.forEach { s ->
                    FilterChip(selected = vm.section == s, onClick = { vm.choose(s) }, label = { Text(s.label) })
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text(if (vm.section == OutletSection.DEALS) "Search today's deals" else "Search " + vm.section.label.lowercase(Locale.US) + " deals") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (text.isNotEmpty()) IconButton(onClick = { text = ""; vm.search("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(text) }),
            )
            if (vm.section != OutletSection.FOR_YOU) {
                PresetRow(vm.preset, false, onPick = { vm.sortBy(it) }, onAdjust = null)
            }
            Text(
                vm.section.blurb,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            val fb = vm.fallbackUrl
            if (fb != null) {
                Banner("Simplified view unavailable — showing Amazon's page", "Retry") { vm.restart() }
                AmazonPage(fb, PageMode.RAW, Modifier.weight(1f))
            } else LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("outlet-list"), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (vm.noInterests) {
                    item("nointerests") {
                        EmptyState(
                            "No interests yet",
                            "\"For you\" picks outlet deals that match what you buy and keep. Take the 30-second quiz, or browse the other sections.",
                            "Start the quiz",
                        ) { nav.navigate("quiz") }
                    }
                }
                items(vm.rows, key = { it.l.asin }) { r ->
                    OutletCard(
                        r,
                        watched = watched.any { it.asin == r.l.asin },
                        modifier = Modifier,
                        onOpen = { nav.openProduct(r.l) },
                        onWatch = {
                            vm.watch(r.l)
                            scope.launch { snack.showSnackbar("Added to your watchlist") }
                        },
                    )
                }
                if (vm.loading) item("loading") { Column { ResultSkeleton(); ResultSkeleton(); ResultSkeleton() } }
                vm.error?.let { err ->
                    item("err") {
                        if (vm.rows.isEmpty()) ErrorState(vm.errorReason ?: Reason.ERROR, err, onRetry = { vm.loadMore() })
                        else Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Couldn't load more: $err", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.loadMore() }) { Text("Try again") }
                        }
                    }
                }
                if (vm.ended && !vm.noInterests) {
                    item("end") {
                        Text(
                            if (vm.rows.isEmpty()) "Nothing here right now." else "That's everything for now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OutletCard(r: OutletRow, watched: Boolean, modifier: Modifier, onOpen: () -> Unit, onWatch: () -> Unit) {
    val l = r.l
    Row(
        modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onWatch, onLongClickLabel = "Add to watchlist")
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        ProductImage(l.image, Modifier.size(108.dp), l.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(l.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            PriceLine(l.price, l.priceText, l.unitPrice, if (l.discountPct > 0) Fmt.money(l.listPrice) else "")
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (l.discountPct > 0) {
                    DiscountPill(l.discountPct)
                    Spacer(Modifier.width(8.dp))
                }
                RatingLine(l.rating, l.reviews)
            }
            Text(
                r.reason ?: Fmt.delivery(l.deliveryDays, l.deliveryText),
                style = MaterialTheme.typography.labelMedium,
                color = if (r.reason != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onWatch) {
            Icon(if (watched) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = if (watched) "On your watchlist" else "Add to watchlist")
        }
    }
}
