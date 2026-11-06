@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.emotionalleaves.cartwise.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.ui.draw.clip
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
    val clusters by Graph.db.dao().clustersFlow().collectAsStateWithLifecycle(initialValue = emptyList())
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
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
                        Text("Try", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val fromYou = clusters.filter { !it.muted }.flatMap { c -> listOf(c.query) + c.topCategories.split("|") }
                                .map { it.trim().lowercase() }.filter { it.length in 3..30 }.distinct().take(8)
                            fromYou.ifEmpty { listOf("usb c cable", "sound bar", "air fryer", "running shoes", "desk lamp", "coffee grinder") }.forEach { q ->
                                SuggestionChip(onClick = { go(q) }, label = { Text(q) })
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                        Text(
                            "Results arrive with sponsored slots removed, near-identical listings folded together, unit prices shown and your own ranking on top.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                prefetch(page + 1, ranked.take(4).map { it.item.asin })
            }
            is Load.Fail -> state = when (r.reason) {
                Reason.CAPTCHA, Reason.EMPTY, Reason.ERROR, Reason.SIGNED_OUT -> St.Fallback(r.url ?: pageUrl)
                else -> St.Failed(r.reason, r.message)
            }
        }
        // Amazon's transient "something went wrong" page: wait a moment and try once more by itself.
        val st = state
        if (st is St.Failed && st.reason == Reason.TIMEOUT) {
            delay(1_500)
            val again = Graph.shop.search(query, 1)
            if (again is Load.Ok && again.v.health.ok) {
                pages.add(again.v.items); rerank(); state = St.Ready
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
        if (nextPage <= 40) viewModelScope.launch { Graph.bgShop.search(query, nextPage) }
        asins.take(2).forEach { a -> viewModelScope.launch { Graph.bgShop.product(a) } }
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
    var options by remember { mutableStateOf<Ranked?>(null) }
    var adjust by remember { mutableStateOf(false) }

    Screen(query, nav, snack) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = vm.state) {
                ResultsVm.St.Loading -> GridSkeleton()
                is ResultsVm.St.Failed -> ErrorState(s.reason, s.message, onRetry = { vm.retry() }, onOpenPage = { nav.openWeb(vm.pageUrl) })
                is ResultsVm.St.Fallback -> Column(Modifier.fillMaxSize()) {
                    Banner("Simplified view unavailable — showing Amazon's page", "Retry") { vm.retry() }
                    AmazonPage(s.url, PageMode.RAW, Modifier.weight(1f))
                }
                ResultsVm.St.Ready -> Column(Modifier.fillMaxSize()) {
                    SortBar(
                        preset = vm.preset, custom = vm.custom != null, saleOnly = vm.saleOnly,
                        onPick = { vm.choose(it) }, onAdjust = { adjust = true }, onSaleOnly = { vm.toggleSaleOnly(it) },
                        count = vm.ranked.size,
                    )
                    val grid = rememberLazyGridState()
                    LaunchedEffect(grid) {
                        snapshotFlow {
                            val info = grid.layoutInfo
                            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                            (info.totalItemsCount > 0 && last >= info.totalItemsCount - 8) to info.totalItemsCount
                        }.distinctUntilChanged().collect { (near, _) -> if (near) vm.more() }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = grid,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (vm.ranked.isEmpty()) {
                            item(span = { GridItemSpan(2) }) { EmptyState("Nothing to show", "Everything on this page is hidden by your filters.") }
                        }
                        items(vm.ranked, key = { it.item.asin }) { r ->
                            val l = r.item
                            val similar = if (r.similar.isNotEmpty()) "+${r.similar.size} similar" else null
                            ProductTile(
                                l.tile(tag = if (l.sponsored) "Sponsored" else similar),
                                onOpen = { nav.openProduct(l) },
                                onLong = { options = r },
                            )
                        }
                        if (vm.loadingMore) items(4) { TileSkeleton() }
                        vm.moreError?.let { err ->
                            item(span = { GridItemSpan(2) }) {
                                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(err, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { vm.more() }) { Text("Try again") }
                                }
                            }
                        }
                        if (vm.endReached) {
                            item(span = { GridItemSpan(2) }) {
                                Text(
                                    "That's everything for \"$query\".",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    options?.let { r ->
        ModalBottomSheet(onDismissRequest = { options = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProductImage(r.item.image, Modifier.size(56.dp), r.item.title, px = 200)
                    Spacer(Modifier.width(12.dp))
                    Text(r.item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                ListItem(headlineContent = { Text("Add to watchlist") }, modifier = Modifier.clickable {
                    vm.watch(r.item); options = null; scope.launch { snack.showSnackbar("Added to your watchlist") }
                })
                ListItem(headlineContent = { Text("Hide this item") }, modifier = Modifier.clickable { vm.hide(r.item); options = null })
                ListItem(headlineContent = { Text("Hide everything from " + r.item.brand.ifBlank { "this brand" }) }, modifier = Modifier.clickable {
                    vm.blockBrand(r.item); options = null; scope.launch { snack.showSnackbar("Hidden everywhere in Cartwise") }
                })
                if (r.similar.isNotEmpty()) {
                    SectionHeader("${r.similar.size} near-identical listings")
                    r.similar.forEach { s ->
                        ListItem(
                            leadingContent = { ProductImage(s.image, Modifier.size(44.dp), s.title, px = 150) },
                            headlineContent = { Text(s.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
                            supportingContent = { Text(Fmt.money(s.price).ifBlank { s.priceText }) },
                            modifier = Modifier.clickable { options = null; nav.openProduct(s) },
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                WhyRank(r)
            }
        }
    }
    if (adjust) {
        ModalBottomSheet(onDismissRequest = { adjust = false }) {
            AdjustSheet(vm.custom ?: vm.preset.w) { vm.setWeights(it); adjust = false }
        }
    }
}

/** One sort control and one filter, instead of a row of chips. */
@Composable
internal fun SortBar(
    preset: Preset,
    custom: Boolean,
    saleOnly: Boolean?,
    onPick: (Preset) -> Unit,
    onAdjust: (() -> Unit)?,
    onSaleOnly: (Boolean) -> Unit = {},
    count: Int? = null,
) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (custom) "Custom ranking" else preset.label, style = MaterialTheme.typography.titleSmall)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change sort")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Preset.entries.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.label) },
                        onClick = { open = false; onPick(p) },
                        trailingIcon = if (!custom && p == preset) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
                    )
                }
                if (onAdjust != null) {
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Adjust weights…") }, onClick = { open = false; onAdjust() })
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (count != null) {
            Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
        }
        if (saleOnly != null) {
            FilterChip(
                selected = saleOnly,
                onClick = { onSaleOnly(!saleOnly) },
                label = { Text("On sale") },
                leadingIcon = if (saleOnly) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
            )
        }
    }
}

@Composable
internal fun TileSkeleton() {
    Column(Modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        SkeletonBox(Modifier.fillMaxWidth().aspectRatio(1f))
        Column(Modifier.padding(12.dp)) {
            SkeletonBox(Modifier.fillMaxWidth().height(14.dp)); Spacer(Modifier.height(6.dp))
            SkeletonBox(Modifier.fillMaxWidth(0.7f).height(14.dp)); Spacer(Modifier.height(10.dp))
            SkeletonBox(Modifier.width(70.dp).height(18.dp))
        }
    }
}

@Composable
internal fun GridSkeleton() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2), userScrollEnabled = false,
        contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { items(6) { TileSkeleton() } }
}

@Composable
internal fun WhyRank(r: Ranked) {
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
