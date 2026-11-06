package com.emotionalleaves.cartwise.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.emotionalleaves.cartwise.Graph
import com.emotionalleaves.cartwise.engine.Fmt
import com.emotionalleaves.cartwise.engine.Load
import com.emotionalleaves.cartwise.engine.Reason
import com.emotionalleaves.cartwise.engine.Review
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** All reviews for one product, read from Amazon's reviews pages, with sort and star filters. */
class ReviewsVm(val asin: String) : ViewModel() {
    val reviews = mutableStateListOf<Review>()
    var total by mutableIntStateOf(0); private set
    var average by mutableStateOf<Double?>(null); private set
    var histogram by mutableStateOf<List<Int>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var ended by mutableStateOf(false); private set
    var error by mutableStateOf<Pair<Reason, String>?>(null); private set
    var sort by mutableStateOf("helpful"); private set
    var star by mutableStateOf("all_stars"); private set
    private var page = 0

    init { more() }

    fun set(sort: String = this.sort, star: String = this.star) {
        if (sort == this.sort && star == this.star) return
        this.sort = sort; this.star = star
        reviews.clear(); page = 0; ended = false; error = null
        more()
    }

    fun more() {
        if (loading || ended) return
        loading = true; error = null
        viewModelScope.launch {
            when (val r = Graph.shop.reviews(asin, page + 1, sort, star)) {
                is Load.Ok -> {
                    page++
                    val seen = reviews.map { it.title + it.body.take(60) }.toHashSet()
                    val fresh = r.v.reviews.filter { (it.title + it.body.take(60)) !in seen }
                    reviews.addAll(fresh)
                    if (r.v.total > 0) total = r.v.total
                    if (r.v.average != null) average = r.v.average
                    if (r.v.histogram.size == 5) histogram = r.v.histogram
                    if (fresh.isEmpty() || !r.v.hasNext) ended = true
                }
                is Load.Fail -> error = r.reason to r.message
            }
            loading = false
        }
    }
}

@Composable
fun ReviewsScreen(nav: NavController, asin: String) {
    val vm: ReviewsVm = viewModel(key = "reviews:$asin") { ReviewsVm(asin) }
    val list = rememberLazyListState()
    LaunchedEffect(list) {
        snapshotFlow {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }.distinctUntilChanged().collect { if (it) vm.more() }
    }
    val pageUrl = Graph.shop.url("reviews", "asin" to asin, "page" to "1", "sort" to vm.sort, "star" to vm.star)
    Screen("Reviews", nav, actions = { TextButton(onClick = { nav.openWeb(pageUrl) }) { Text("On Amazon") } }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 32.dp)) {
            item("summary") {
                ReviewSummary(vm.average, vm.total, vm.histogram, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            item("filters") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = vm.sort == "helpful", onClick = { vm.set(sort = "helpful") }, label = { Text("Most helpful") })
                    FilterChip(selected = vm.sort == "recent", onClick = { vm.set(sort = "recent") }, label = { Text("Newest") })
                    VerticalDivider(Modifier.height(32.dp).padding(horizontal = 4.dp))
                    listOf("all_stars" to "All", "critical" to "Critical", "five_star" to "5★", "four_star" to "4★", "three_star" to "3★", "two_star" to "2★", "one_star" to "1★").forEach { (k, label) ->
                        FilterChip(selected = vm.star == k, onClick = { vm.set(star = k) }, label = { Text(label) })
                    }
                }
            }
            items(vm.reviews, key = { it.title + it.author + it.body.take(40) }) { r -> ReviewItem(r, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), maxLines = 40) }
            if (vm.loading) item("loading") { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp) } }
            vm.error?.let { (reason, msg) ->
                item("err") {
                    if (reason == Reason.SIGNED_OUT) EmptyState("Sign in to read all reviews", "Amazon only shows the full reviews list to signed-in shoppers.", "Sign in") { nav.navigate("login") }
                    else ErrorState(reason, msg, onRetry = { vm.more() }, onOpenPage = { nav.openWeb(pageUrl) })
                }
            }
            if (vm.ended && vm.reviews.isEmpty() && vm.error == null) item("none") { EmptyState("No reviews here", "Try another star filter.") }
        }
    }
}

/** Average, count and the five-to-one star bars. */
@Composable
fun ReviewSummary(average: Double?, total: Int, histogram: List<Int>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(96.dp)) {
            Text(average?.let { Fmt.stars(it) } ?: "–", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("★".repeat(Math.round(average ?: 0.0).toInt().coerceIn(0, 5)), color = Star, style = MaterialTheme.typography.titleMedium)
            if (total > 0) Text(Fmt.count(total) + " ratings", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (histogram.size == 5) {
            Column(Modifier.weight(1f)) {
                histogram.forEachIndexed { i, pct ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Text("${5 - i}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(16.dp))
                        LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.weight(1f).height(8.dp), color = Star, drawStopIndicator = {})
                        Text("$pct%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(40.dp).padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ReviewItem(r: Review, modifier: Modifier = Modifier, maxLines: Int = 8) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (r.rating != null) {
                Text("★".repeat(Math.round(r.rating).toInt().coerceIn(0, 5)), color = Star, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
            }
            Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val meta = listOfNotNull(r.author.ifBlank { null }, r.date.ifBlank { null }, if (r.verified) "Verified purchase" else null).joinToString(", ")
        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(
            r.body,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (!expanded && r.body.length > maxLines * 45) TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(0.dp)) { Text("Read more") }
        if (r.helpful.isNotBlank()) Text(r.helpful, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
