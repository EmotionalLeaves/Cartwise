@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.emotionalleaves.cartwise.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.emotionalleaves.cartwise.Graph
import com.emotionalleaves.cartwise.Watch
import com.emotionalleaves.cartwise.engine.AddResult
import com.emotionalleaves.cartwise.engine.Fmt
import com.emotionalleaves.cartwise.engine.Load
import com.emotionalleaves.cartwise.engine.Product
import com.emotionalleaves.cartwise.engine.Reason
import com.emotionalleaves.cartwise.taste.SignalType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class ProductVm(val asin: String) : ViewModel() {
    sealed interface St {
        data object Loading : St
        data class Ready(val p: Product) : St
        data class Failed(val reason: Reason, val message: String) : St
        data class Fallback(val url: String) : St
    }

    sealed interface Event {
        data class Message(val text: String) : Event
        data class OpenPage(val url: String, val banner: String) : Event
    }

    var state by mutableStateOf<St>(St.Loading)
        private set
    var adding by mutableStateOf(false)
        private set
    var watched by mutableStateOf(false)
        private set
    var blockedSeller by mutableStateOf(false)
        private set
    val events = MutableSharedFlow<Event>(extraBufferCapacity = 4)

    private val openedAt = SystemClock.elapsedRealtime()
    val pageUrl: String get() = Graph.shop.url("product", "asin" to asin)

    init {
        load()
        viewModelScope.launch { watched = Graph.db.dao().watch(asin) != null }
    }

    fun load() {
        viewModelScope.launch {
            state = St.Loading
            when (val r = Graph.shop.product(asin)) {
                is Load.Ok -> {
                    val p = r.v
                    state = St.Ready(p)
                    Graph.signals.logProduct(p, SignalType.OPEN)
                    val blocks = Graph.db.dao().blocks().filter { it.kind == "seller" }.map { it.value }
                    blockedSeller = blocks.any { b -> p.soldBy.lowercase().contains(b) }
                }
                is Load.Fail -> state = when (r.reason) {
                    Reason.OFFLINE, Reason.TIMEOUT -> St.Failed(r.reason, r.message)
                    else -> St.Fallback(r.url ?: pageUrl)
                }
            }
        }
    }

    fun addToCart() {
        if (adding) return
        adding = true
        viewModelScope.launch {
            when (val r = Graph.shop.addToCart(asin)) {
                is AddResult.Added -> events.tryEmit(Event.Message(if (r.count != null) "Added to cart · ${r.count} in cart" else "Added to cart"))
                is AddResult.Failed -> events.tryEmit(Event.OpenPage(pageUrl, "Couldn't confirm the add (${r.message}). Here's Amazon's page."))
            }
            adding = false
        }
    }

    fun toggleWatch() {
        val p = (state as? St.Ready)?.p ?: return
        viewModelScope.launch {
            if (watched) Watch.remove(asin) else Watch.add(p)
            watched = !watched
            events.tryEmit(Event.Message(if (watched) "On your watchlist" else "Removed from watchlist"))
        }
    }

    override fun onCleared() {
        val secs = (SystemClock.elapsedRealtime() - openedAt) / 1000
        if (secs >= 20 && state is St.Ready) {
            Graph.scope.launch { Graph.signals.log(asin, SignalType.DWELL, "Looked for ${secs}s") }
        }
    }
}

@Composable
fun ProductScreen(nav: NavController, asin: String) {
    val vm: ProductVm = viewModel(key = "product:$asin") { ProductVm(asin) }
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is ProductVm.Event.Message -> snack.showSnackbar(e.text)
                is ProductVm.Event.OpenPage -> nav.openWeb(e.url, e.banner)
            }
        }
    }

    Screen(
        title = "",
        nav = nav,
        snackbar = snack,
        actions = {
            IconButton(onClick = { vm.toggleWatch() }) {
                Icon(if (vm.watched) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = if (vm.watched) "Remove from watchlist" else "Add to watchlist")
            }
            IconButton(onClick = { shareText(ctx, vm.pageUrl) }) { Icon(Icons.Filled.Share, contentDescription = "Share link") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open full Amazon page") }, onClick = { menu = false; nav.openWeb(vm.pageUrl) })
                    DropdownMenuItem(text = { Text("Price history on Keepa") }, onClick = { menu = false; openExternal(ctx, Graph.shop.url("keepa", "asin" to asin)) })
                    DropdownMenuItem(text = { Text("Price history on CamelCamelCamel") }, onClick = { menu = false; openExternal(ctx, Graph.shop.url("camel", "asin" to asin)) })
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = vm.state) {
                ProductVm.St.Loading -> ProductSkeleton(Graph.previews[asin])
                is ProductVm.St.Failed -> ErrorState(s.reason, s.message, onRetry = { vm.load() }, onOpenPage = { nav.openWeb(vm.pageUrl) })
                is ProductVm.St.Fallback -> Column(Modifier.fillMaxSize()) {
                    Banner("Simplified view unavailable — showing Amazon's page", "Retry") { vm.load() }
                    AmazonPage(s.url, PageMode.RAW, Modifier.weight(1f))
                }
                is ProductVm.St.Ready -> ProductBody(
                    s.p, nav, vm.pageUrl, vm.adding, vm.watched, vm.blockedSeller,
                    onAdd = { vm.addToCart() }, onWatch = { vm.toggleWatch() }, tab = tab, onTab = { tab = it },
                )
            }
        }
    }
}

/** While the full page loads, show what you tapped (photo, title, price, stars) right away. */
@Composable
internal fun ProductSkeleton(preview: com.emotionalleaves.cartwise.engine.Listing? = null) {
    Column(Modifier.padding(16.dp)) {
        if (preview != null && preview.image.isNotBlank()) {
            ProductImage(preview.image, Modifier.fillMaxWidth().height(300.dp), preview.title, px = 1000)
        } else {
            SkeletonBox(Modifier.fillMaxWidth().height(300.dp))
        }
        Spacer(Modifier.height(16.dp))
        if (preview != null) {
            if (preview.brand.isNotBlank()) Text(preview.brand, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(preview.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            PriceLine(preview.price, preview.priceText, preview.unitPrice, big = true)
            Spacer(Modifier.height(8.dp))
            RatingLine(preview.rating, preview.reviews)
            Spacer(Modifier.height(12.dp))
            Text("Loading delivery, seller and options…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            SkeletonBox(Modifier.fillMaxWidth().height(20.dp))
            Spacer(Modifier.height(8.dp))
            SkeletonBox(Modifier.fillMaxWidth(0.6f).height(20.dp))
            Spacer(Modifier.height(16.dp))
            SkeletonBox(Modifier.width(120.dp).height(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        SkeletonBox(Modifier.fillMaxWidth().height(120.dp))
    }
}

@Composable
internal fun ProductBody(
    p: Product,
    nav: NavController,
    pageUrl: String,
    adding: Boolean,
    watched: Boolean,
    blockedSeller: Boolean,
    onAdd: () -> Unit,
    onWatch: () -> Unit,
    tab: Int,
    onTab: (Int) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 112.dp)) {
            item("images") {
                val imgs = p.images.ifEmpty { listOf("") }
                val pager = rememberPagerState { imgs.size }
                Box {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { i ->
                        ProductImage(imgs[i], Modifier.fillMaxWidth().aspectRatio(1f), p.title, px = 1000, radius = 0.dp)
                    }
                    if (imgs.size > 1) {
                        Row(Modifier.align(Alignment.BottomCenter).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            repeat(imgs.size) { i ->
                                Box(
                                    Modifier.size(if (i == pager.currentPage) 8.dp else 6.dp)
                                        .background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f), CircleShape),
                                )
                            }
                        }
                    }
                    if (p.discountPct > 0) {
                        Surface(
                            color = Color(0xFF1B1F22), contentColor = Color(0xFFF2C77F),
                            shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                        ) { Text("Save ${p.discountPct}%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
                    }
                }
            }
            item("head") {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    if (p.brand.isNotBlank()) Text(p.brand, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(p.title, style = MaterialTheme.typography.titleLarge, lineHeight = 26.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(if (p.price != null) Fmt.money(p.price) else p.priceText.ifBlank { "See Amazon's page" }, style = MaterialTheme.typography.headlineSmall)
                        if (p.discountPct > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(Fmt.money(p.listPrice), style = MaterialTheme.typography.bodyMedium, textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        p.unitPrice?.let {
                            Spacer(Modifier.width(8.dp))
                            Text(it.label(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                    if (p.availability.isNotBlank() && !p.availability.contains("In Stock", true)) {
                        Text(p.availability, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RatingLine(p.rating, p.reviews)
                        if (p.rating != null && p.histogram.size == 5) {
                            Spacer(Modifier.width(10.dp))
                            Text("${p.histogram[0]}% five-star", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item("facts") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Fact("Delivery", Fmt.delivery(p.deliveryDays, p.deliveryText).ifBlank { "See Amazon's page" }, accent = (p.deliveryDays ?: 9) <= 1)
                        Fact("Sold by", p.soldBy.ifBlank { "Not shown" } + when { p.seller >= 1.0 -> ""; p.seller >= 0.8 -> " · Prime"; else -> " · third-party" })
                        if (p.shipsFrom.isNotBlank()) Fact("Ships from", p.shipsFrom)
                        Fact("Returns", p.returns.ifBlank { "See Amazon's page" })
                        if (blockedSeller) Text("You blocked this seller.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            item("tabs") {
                Spacer(Modifier.height(16.dp))
                TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, divider = {}) {
                    listOf("Options", "Reviews", "Details").forEachIndexed { i, t ->
                        Tab(selected = tab == i, onClick = { onTab(i) }, text = { Text(t) })
                    }
                }
            }
            when (tab) {
                0 -> {
                    val groups = p.variants.groupBy { it.dim.ifBlank { "Options" } }
                    if (groups.isEmpty()) {
                        item("nov") { Text("This item comes in one version.", modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    groups.forEach { (dim, vs) ->
                        item("v-$dim") {
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                                Text(dim, style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.height(6.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    vs.forEach { v ->
                                        FilterChip(
                                            selected = v.selected,
                                            enabled = v.available || v.selected,
                                            onClick = {
                                                if (!v.selected && v.asin.isNotBlank() && v.asin != p.asin) {
                                                    nav.navigate("product/${v.asin}") { popUpTo("product/{asin}") { inclusive = true } }
                                                }
                                            },
                                            label = { Text(v.label) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    if (p.reviewList.isEmpty()) {
                        item("nor") { Text("No reviews on this page.", modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    items(p.reviewList.size) { i ->
                        val r = p.reviewList[i]
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (r.rating != null) {
                                    Text(Fmt.stars(r.rating) + " ★", color = Star, style = MaterialTheme.typography.labelLarge)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (r.author.isNotBlank() || r.date.isNotBlank()) {
                                Text(listOf(r.author, r.date).filter { it.isNotBlank() }.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(r.body, style = MaterialTheme.typography.bodyMedium, maxLines = 12)
                        }
                    }
                    item("allr") {
                        TextButton(onClick = { nav.openWeb(Graph.shop.url("reviews", "asin" to p.asin)) }, modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text("All reviews on Amazon")
                        }
                    }
                }
                else -> {
                    if (p.bullets.isNotEmpty()) {
                        item("about") { SectionHeader("About this item") }
                        items(p.bullets.size) { i ->
                            Text("•  " + p.bullets[i], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                        }
                    }
                    if (p.details.isNotEmpty()) {
                        item("specs") { SectionHeader("Details") }
                        items(p.details.size) { i ->
                            val (k, v) = p.details[i]
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                                Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
                                Text(v, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.6f))
                            }
                        }
                    }
                    if (p.category.isNotBlank()) {
                        item("cat") {
                            Text(p.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
                        }
                    }
                }
            }
        }
        // Sticky actions. Add to cart presses Amazon's own button; checkout is always your tap.
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp, shadowElevation = 12.dp,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onWatch, modifier = Modifier.height(52.dp)) {
                    Icon(if (watched) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = if (watched) "Watching" else "Watch")
                }
                if (p.canAddToCart) {
                    Button(onClick = onAdd, enabled = !adding, modifier = Modifier.weight(1f).height(52.dp)) {
                        if (adding) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp)); Text("Adding…")
                        } else {
                            Icon(Icons.Filled.ShoppingCart, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Add to cart")
                        }
                    }
                } else {
                    Button(onClick = { nav.openWeb(pageUrl) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Buying options on Amazon") }
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, accent: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(92.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}
