@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.emotionalleaves.cartwise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.emotionalleaves.cartwise.data.BlockEntity
import com.emotionalleaves.cartwise.engine.Extract
import com.emotionalleaves.cartwise.engine.Fmt
import com.emotionalleaves.cartwise.engine.Listing
import com.emotionalleaves.cartwise.engine.Load
import com.emotionalleaves.cartwise.engine.Reason
import com.emotionalleaves.cartwise.engine.ShopRepo
import com.emotionalleaves.cartwise.rank.Preset
import com.emotionalleaves.cartwise.rank.Ranked
import com.emotionalleaves.cartwise.rank.Ranker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale

// ---------------- Search ----------------

@Composable
fun SearchScreen(nav: NavController) {
    val prefs by Graph.settings.prefs.collectAsStateWithLifecycle(initialValue = null)
    var text by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun go(q: String) {
        val t = q.trim()
        if (t.isBlank()) return
        scope.launch {
            Graph.settings.addRecent(t)
            Graph.signals.logSearch(t)
        }
        nav.openResults(t)
    }

    Screen("Search", actions = { AccountButton(nav) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus),
                    placeholder = { Text("What are you looking for?") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { go(text) }),
                )
            }
            val saved = prefs?.saved.orEmpty()
            if (saved.isNotEmpty()) {
                item { SectionHeader("Saved searches") }
                items(saved, key = { "s-$it" }) { q ->
                    ListItem(
                        headlineContent = { Text(q) },
                        leadingContent = { Icon(Icons.Filled.Star, contentDescription = null, tint = Star) },
                        trailingContent = {
                            IconButton(onClick = { scope.launch { Graph.settings.toggleSaved(q) } }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove saved search")
                            }
                        },
                        modifier = Modifier.clickable { go(q) },
                    )
                }
            }
            val recent = prefs?.recent.orEmpty()
            if (recent.isNotEmpty()) {
                item {
                    SectionHeader("Recent") {
                        TextButton(onClick = { scope.launch { Graph.settings.clearRecent() } }) { Text("Clear") }
                    }
                }
                items(recent, key = { "r-$it" }) { q ->
                    val isSaved = saved.any { it.equals(q, true) }
                    ListItem(
                        headlineContent = { Text(q) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { scope.launch { Graph.settings.toggleSaved(q) } }) {
                                    Icon(if (isSaved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = if (isSaved) "Unsave" else "Save search")
                                }
                                IconButton(onClick = { scope.launch { Graph.settings.removeRecent(q) } }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove")
                                }
                            }
                        },
                        modifier = Modifier.clickable { go(q) },
                    )
                }
            }
            if (saved.isEmpty() && recent.isEmpty()) {
                item {
                    EmptyState(
                        "Search Amazon, without the noise",
                        "Results come back with sponsored slots removed, clone listings folded together, unit prices shown, and your own ranking on top.",
                    )
                }
            }
        }
    }
}

// ---------------- Results ----------------

class ResultsVm(val query: String) : ViewModel() {
    sealed interface St {
        data object Loading : St
        data object Ready : St
        data class Failed(val reason: Reason, val message: String) : St
        data class Fallback(val url: String) : St
    }

    var state by mutableStateOf<St>(St.Loading)
        private set
    var ranked by mutableStateOf<List<Ranked>>(emptyList())
        private set
    var preset by mutableStateOf(Preset.BEST)
        private set
    var custom by mutableStateOf<DoubleArray?>(null)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var saleOnly by mutableStateOf(false)
        private set

    /** Each Amazon page is ranked on its own and appended, so scrolling never reshuffles what you've seen. */
    private val pages = ArrayList<List<Listing>>()
    private var page = 1
    var endReached by mutableStateOf(false)
        private set
    var moreError by mutableStateOf<String?>(null)
        private set
    private var hideSponsored = true
    private var personalize = false
    private var hidden = emptySet<String>()
    private var blockedBrands = emptySet<String>()
    private var kept = emptySet<String>()
    private var returned = emptySet<String>()

    val pageUrl: String get() = Graph.shop.url("search", "q" to ShopRepo.enc(query), "page" to "1")

    init {
        viewModelScope.launch {
            val p = Graph.settings.current()
            preset = Preset.from(p.preset)
            hideSponsored = p.hideSponsored
            personalize = p.personalize
            val blocks = Graph.db.dao().blocks()
            hidden = blocks.filter { it.kind == "hidden" || it.kind == "item" }.map { it.value }.toSet()
            blockedBrands = blocks.filter { it.kind == "brand" || it.kind == "seller" }.map { it.value.lowercase(Locale.US) }.toSet()
            if (personalize) {
                val orders = Graph.db.dao().orders()
                val items = Graph.db.dao().items(orders.map { it.asin }.distinct().take(400)).associateBy { it.ref }
                fun brand(asin: String, title: String) =
                    (items[asin]?.brand?.takeIf { it.isNotBlank() } ?: Extract.guessBrand(title)).lowercase(Locale.US)
                kept = orders.filter { !it.returned }.map { brand(it.asin, it.title) }.toSet()
                returned = orders.filter { it.returned }.map { brand(it.asin, it.title) }.toSet()
            }
            load()
        }
    }

    val hidesSponsored: Boolean get() = hideSponsored

    private suspend fun load() {
        state = St.Loading
        pages.clear()
        page = 1
        endReached = false
        moreError = null
        when (val r = Graph.shop.search(query, 1)) {
            is Load.Ok -> {
                if (!r.v.health.ok) {
                    state = St.Fallback(pageUrl)
                    return
                }
                pages.add(r.v.items)
                rerank()
                state = St.Ready
                prefetch(page + 1, ranked.take(3).map { it.item.asin })
            }
            is Load.Fail -> state = when (r.reason) {
                Reason.CAPTCHA, Reason.EMPTY, Reason.ERROR, Reason.SIGNED_OUT -> St.Fallback(r.url ?: pageUrl)
                else -> St.Failed(r.reason, r.message)
            }
            // Amazon's transient "something went wrong" page: wait a moment and try once more by itself.
            if (state is St.Failed && (state as St.Failed).reason == Reason.TIMEOUT) {
                kotlinx.coroutines.delay(1_500)
                when (val again = Graph.shop.search(query, 1)) {
                    is Load.Ok -> if (again.v.health.ok) { pages.add(again.v.items); rerank(); state = St.Ready }
                    is Load.Fail -> Unit
                }
            }
        }
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    /** Infinite scroll: keeps asking Amazon for the next page until it stops returning anything new. */
    fun more() {
        if (loadingMore || endReached || state != St.Ready) return
        if (page >= 40) { endReached = true; return }
        loadingMore = true
        moreError = null
        viewModelScope.launch {
            when (val r = Graph.shop.search(query, page + 1)) {
                is Load.Ok -> {
                    val known = pages.flatten().map { it.asin }.toHashSet()
                    val fresh = r.v.items.filter { it.asin !in known }
                    if (fresh.isEmpty()) endReached = true
                    else {
                        page++
                        pages.add(fresh)
                        rerank()
                        prefetch(page + 1, emptyList())
                    }
                }
                is Load.Fail -> moreError = when (r.reason) {
                    Reason.OFFLINE -> "You're offline."
                    Reason.CAPTCHA -> "Amazon wants a quick check."
                    else -> "Couldn't load more."
                }
            }
            loadingMore = false
        }
    }

    /**
     * Speed: quietly reads the next results page and the top few products in the background
     * browser, so scrolling and tapping usually hit the cache. Stops if you leave the screen.
     */
    private fun prefetch(nextPage: Int, asins: List<String>) {
        viewModelScope.launch {
            if (nextPage <= 40) Graph.bgShop.search(query, nextPage)
            for (a in asins) {
                delay(1_200)
                Graph.bgShop.product(a)
            }
        }
    }

    fun choose(p: Preset) {
        preset = p
        custom = null
        rerank()
    }

    fun toggleSaleOnly(v: Boolean) {
        saleOnly = v
        rerank()
    }

    fun setWeights(w: DoubleArray) {
        custom = w
        rerank()
    }

    fun hide(l: Listing) {
        hidden = hidden + l.asin
        rerank()
        viewModelScope.launch { Graph.db.dao().insertBlock(BlockEntity(kind = "hidden", value = l.asin, label = l.title)) }
    }

    fun blockBrand(l: Listing) {
        val b = Ranker.brandKey(l)
        blockedBrands = blockedBrands + b
        rerank()
        viewModelScope.launch { Graph.db.dao().insertBlock(BlockEntity(kind = "brand", value = b, label = l.brand.ifBlank { b })) }
    }

    fun watch(l: Listing) {
        viewModelScope.launch { Watch.add(l) }
    }

    private fun rerank() {
        val ctx = Ranker.Ctx(query, preset, custom, hideSponsored, hidden, blockedBrands, kept, returned, personalize, saleOnly)
        val out = ArrayList<Ranked>()
        val shown = HashSet<String>()
        for (pg in pages) {
            for (r in Ranker.rank(pg.filter { it.asin !in shown }, ctx)) {
                out += r
                shown += r.item.asin
                r.similar.forEach { shown += it.asin }
            }
        }
        ranked = out
    }
}

@Composable
fun ResultsScreen(nav: NavController, query: String) {
    val vm: ResultsVm = viewModel(key = "results:$query") { ResultsVm(query) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var why by remember { mutableStateOf<Ranked?>(null) }
    var adjust by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    Screen(query, nav, snack) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = vm.state) {
                ResultsVm.St.Loading -> Column { repeat(6) { ResultSkeleton() } }
                is ResultsVm.St.Failed -> ErrorState(s.reason, s.message, onRetry = { vm.retry() }, onOpenPage = { nav.openWeb(vm.pageUrl) })
                is ResultsVm.St.Fallback -> Column(Modifier.fillMaxSize()) {
                    Banner("Simplified view unavailable — showing Amazon's page", "Retry") { vm.retry() }
                    AmazonPage(s.url, PageMode.RAW, Modifier.weight(1f))
                }
                ResultsVm.St.Ready -> Column(Modifier.fillMaxSize()) {
                    PresetRow(vm.preset, vm.custom != null, onPick = { vm.choose(it) }, onAdjust = { adjust = true }, saleOnly = vm.saleOnly, onSaleOnly = { vm.toggleSaleOnly(it) })
                    val list = rememberLazyListState()
                    LaunchedEffect(list) {
                        snapshotFlow {
                            val info = list.layoutInfo
                            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                            (info.totalItemsCount > 0 && last >= info.totalItemsCount - 10) to info.totalItemsCount
                        }.distinctUntilChanged().collect { (near, _) -> if (near) vm.more() }
                    }
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (vm.ranked.isEmpty()) {
                            item { EmptyState("Nothing left to show", "Everything on this page was hidden by your filters or blocklist.") }
                        }
                        vm.ranked.forEach { r ->
                            item(key = r.item.asin) {
                                SwipeRow(
                                    modifier = Modifier.animateItem(),
                                    onHide = {
                                        vm.hide(r.item)
                                        scope.launch { snack.showSnackbar("Hidden from your results") }
                                    },
                                    onWatch = {
                                        vm.watch(r.item)
                                        scope.launch { snack.showSnackbar("Added to your watchlist") }
                                    },
                                ) {
                                    ResultCard(
                                        r,
                                        tagSponsored = !vm.hidesSponsored,
                                        expanded = expanded[r.item.asin] == true,
                                        onOpen = { nav.openProduct(r.item) },
                                        onLong = { why = r },
                                        onToggleSimilar = { expanded[r.item.asin] = expanded[r.item.asin] != true },
                                    )
                                }
                            }
                            if (expanded[r.item.asin] == true) {
                                items(r.similar, key = { r.item.asin + "~" + it.asin }) { s -> SimilarRow(s) { nav.openProduct(s) } }
                            }
                        }
                        if (vm.loadingMore) item("more") { Column { ResultSkeleton(); ResultSkeleton() } }
                        vm.moreError?.let { err ->
                            item("more-error") {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(err, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { vm.more() }) { Text("Try again") }
                                }
                            }
                        }
                        if (vm.endReached) {
                            item("end") {
                                Text(
                                    "That's everything Amazon has for \"$query\".",
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
    }

    why?.let { r ->
        ModalBottomSheet(onDismissRequest = { why = null }) {
            WhyRank(
                r,
                onHideBrand = {
                    vm.blockBrand(r.item); why = null
                    scope.launch { snack.showSnackbar("Hiding " + r.item.brand.ifBlank { "this brand" } + " everywhere") }
                },
                onHideItem = { vm.hide(r.item); why = null },
                onWatch = { vm.watch(r.item); why = null; scope.launch { snack.showSnackbar("Added to your watchlist") } },
            )
        }
    }
    if (adjust) {
        ModalBottomSheet(onDismissRequest = { adjust = false }) {
            AdjustSheet(vm.custom ?: vm.preset.w) { vm.setWeights(it); adjust = false }
        }
    }
}

@Composable
internal fun PresetRow(
    current: Preset,
    custom: Boolean,
    onPick: (Preset) -> Unit,
    onAdjust: (() -> Unit)?,
    saleOnly: Boolean? = null,
    onSaleOnly: (Boolean) -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (saleOnly != null) {
            FilterChip(
                selected = saleOnly,
                onClick = { onSaleOnly(!saleOnly) },
                label = { Text("On sale") },
                leadingIcon = if (saleOnly) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
            )
        }
        Preset.entries.forEach { p ->
            FilterChip(selected = !custom && p == current, onClick = { onPick(p) }, label = { Text(p.label) })
        }
        if (onAdjust != null) FilterChip(selected = custom, onClick = onAdjust, label = { Text(if (custom) "Custom" else "Adjust") })
    }
}

@Composable
private fun SwipeRow(onHide: () -> Unit, onWatch: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val hide by rememberUpdatedState(onHide)
    val watch by rememberUpdatedState(onWatch)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            when (v) {
                SwipeToDismissBoxValue.EndToStart -> { hide(); true }
                SwipeToDismissBoxValue.StartToEnd -> { watch(); false }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val toWatch = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier.fillMaxSize()
                    .background(if (toWatch) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (toWatch) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Text(
                    if (toWatch) "Watchlist" else "Hide",
                    color = if (toWatch) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    ) { content() }
}

@Composable
internal fun ResultCard(
    r: Ranked,
    tagSponsored: Boolean,
    expanded: Boolean,
    onOpen: () -> Unit,
    onLong: () -> Unit,
    onToggleSimilar: () -> Unit,
) {
    val l = r.item
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = onLong, onLongClickLabel = "Why this rank")
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            ProductImage(l.image, Modifier.size(108.dp), l.title)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (tagSponsored && l.sponsored) {
                    Pill("Sponsored")
                    Spacer(Modifier.height(4.dp))
                }
                Text(l.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                PriceLine(l.price, l.priceText, l.unitPrice, if (l.discountPct > 0) Fmt.money(l.listPrice) else "")
                if (l.discountPct > 0) {
                    Spacer(Modifier.height(2.dp))
                    DiscountPill(l.discountPct)
                }
                Spacer(Modifier.height(2.dp))
                RatingLine(if (l.rating != null) r.bayes else null, l.reviews)
                if (l.deliveryText.isNotBlank() || l.deliveryDays != null) {
                    Text(
                        Fmt.delivery(l.deliveryDays, l.deliveryText),
                        style = MaterialTheme.typography.bodySmall,
                        color = if ((l.deliveryDays ?: 9) <= 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SellerBadge(l.seller)
                    if (r.similar.isNotEmpty()) {
                        TextButton(onClick = onToggleSimilar, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text(if (expanded) "Hide similar" else "+${r.similar.size} similar", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onLong, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = "Why this rank",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SimilarRow(l: Listing, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 48.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProductImage(l.image, Modifier.size(56.dp), l.title)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(l.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (l.price != null) Fmt.money(l.price) else l.priceText, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                RatingLine(l.rating, l.reviews)
            }
        }
    }
}

@Composable
internal fun WhyRank(r: Ranked, onHideBrand: () -> Unit, onHideItem: () -> Unit, onWatch: () -> Unit) {
    val explain = listOf(
        "Bayesian rating ${Fmt.stars(r.bayes)} from ${Fmt.stars(r.item.rating)}★ × ${Fmt.count(r.item.reviews)} reviews, vs. others on this page",
        if (r.item.unitPrice != null) "Unit price ${r.item.unitPrice.label()} vs. this page" else "Price ${Fmt.money(r.item.price)} vs. this page",
        Fmt.delivery(r.item.deliveryDays, r.item.deliveryText).ifBlank { "No delivery date shown" },
        when {
            r.item.seller >= 1.0 -> "Sold and shipped by Amazon"
            r.item.seller >= 0.8 -> "Prime: sold or fulfilled by Amazon"
            else -> "Third-party seller"
        },
        "Share of your search words in the title",
        if (r.item.discountPct > 0) "${r.item.discountPct}% off ${Fmt.money(r.item.listPrice)} (50%+ off scores full)" else "No discount shown",
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        Text("Why this rank", style = MaterialTheme.typography.titleLarge)
        Text(
            "Score " + String.format(Locale.US, "%.2f", r.score),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        for (i in 0 until minOf(Preset.N, r.parts.size)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Preset.SIGNALS[i], style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(76.dp))
                LinearProgressIndicator(progress = { r.parts[i].toFloat() }, modifier = Modifier.weight(1f).height(6.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    String.format(Locale.US, "%.2f × %d%%", r.parts[i], (r.weights[i] * 100).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(78.dp),
                )
            }
            Text(explain[i], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 76.dp, bottom = 8.dp))
        }
        r.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        if (r.similar.isNotEmpty()) Text("• Grouped with ${r.similar.size} near-identical listings; this one scored best", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onHideBrand) { Text("Hide this brand") }
            OutlinedButton(onClick = onHideItem) { Text("Hide item") }
        }
        TextButton(onClick = onWatch) { Text("Add to watchlist") }
    }
}

@Composable
private fun AdjustSheet(start: DoubleArray, onApply: (DoubleArray) -> Unit) {
    val w = remember { mutableStateListOf(*DoubleArray(Preset.N) { if (it < start.size) start[it] else 0.0 }.toTypedArray()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        Text("Adjust ranking", style = MaterialTheme.typography.titleLarge)
        Text(
            "Drag to say what matters. Weights are balanced automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        for (i in 0 until Preset.N) {
            Text(Preset.SIGNALS[i], style = MaterialTheme.typography.labelLarge)
            Slider(value = w[i].toFloat(), onValueChange = { w[i] = it.toDouble() }, valueRange = 0f..1f)
        }
        Button(onClick = { onApply(w.toDoubleArray()) }, modifier = Modifier.fillMaxWidth()) { Text("Apply") }
    }
}
