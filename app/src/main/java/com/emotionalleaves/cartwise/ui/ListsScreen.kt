@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.emotionalleaves.cartwise.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.emotionalleaves.cartwise.Graph
import com.emotionalleaves.cartwise.Watch
import com.emotionalleaves.cartwise.data.WatchEntity
import com.emotionalleaves.cartwise.engine.Fmt
import com.emotionalleaves.cartwise.engine.Listing
import com.emotionalleaves.cartwise.engine.ListsPage
import com.emotionalleaves.cartwise.engine.Load
import com.emotionalleaves.cartwise.engine.Reason
import com.emotionalleaves.cartwise.feed.Notifier
import com.emotionalleaves.cartwise.taste.SignalType
import com.emotionalleaves.cartwise.taste.toItem
import kotlinx.coroutines.launch

@Composable
fun ListsScreen(nav: NavController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Screen(
        "Lists",
        actions = { IconButton(onClick = { nav.navigate("interests") }) { Icon(Icons.Filled.Star, contentDescription = "Your interests") } },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Watchlist") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Amazon Lists") })
            }
            if (tab == 0) WatchlistTab(nav) else AmazonListsTab(nav)
        }
    }
}

@Composable
private fun WatchlistTab(nav: NavController) {
    val ctx = LocalContext.current
    val items by Graph.db.dao().watchFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<WatchEntity?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    if (items.isEmpty()) {
        EmptyState(
            "Nothing on your watchlist",
            "Swipe a search result right, or tap the heart on a product. Cartwise checks prices once a day on Wi-Fi and tells you when one hits your target.",
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(items, key = { it.asin }) { w ->
            Row(
                Modifier.fillMaxWidth().clickable { nav.openProduct(w.toListing()) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProductImage(w.image, Modifier.size(72.dp), w.title)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(w.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Fmt.money(w.price).ifBlank { "—" }, style = MaterialTheme.typography.titleSmall)
                        val prev = w.prevPrice
                        if (prev != null && w.price != null && prev != w.price) {
                            Spacer(Modifier.width(6.dp))
                            val down = w.price < prev
                            Text(
                                (if (down) "▼ was " else "▲ was ") + Fmt.money(prev),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (down) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(
                            onClick = { editing = w },
                            label = { Text(if (w.target != null) "Target " + Fmt.money(w.target) else "Set target price") },
                            leadingIcon = { Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                        Spacer(Modifier.width(6.dp))
                        AssistChip(
                            onClick = { openExternal(ctx, Graph.shop.url("keepa", "asin" to w.asin)) },
                            label = { Text("Price chart") },
                            leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
                IconButton(onClick = { scope.launch { Watch.remove(w.asin) } }) { Icon(Icons.Filled.Close, contentDescription = "Remove from watchlist") }
            }
        }
        item {
            Text(
                "Prices are checked once a day on Wi-Fi, a few pages at a time.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    editing?.let { w ->
        var text by remember(w.asin) { mutableStateOf(w.target?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Target price") },
            text = {
                Column {
                    Text("Get a notification when " + Fmt.short(w.title, 50) + " drops to this price or lower.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
                        prefix = { Text("$") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = text.toDoubleOrNull()
                    scope.launch { Watch.setTarget(w.asin, v) }
                    if (v != null && Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(ctx)) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { scope.launch { Watch.setTarget(w.asin, null) }; editing = null }) { Text("Clear") }
            },
        )
    }
}

@Composable
private fun AmazonListsTab(nav: NavController) {
    val scope = rememberCoroutineScope()
    var listUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf<Load<ListsPage>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var knownLists by remember { mutableStateOf(emptyList<com.emotionalleaves.cartwise.engine.NamedList>()) }

    LaunchedEffect(listUrl, reload) {
        state = null
        val r = Graph.shop.lists(listUrl)
        state = r
        if (r is Load.Ok) {
            if (r.v.lists.isNotEmpty()) knownLists = r.v.lists
            val listed = Graph.db.dao().refsWithType(SignalType.LISTED).toSet()
            for (l in r.v.items) {
                Graph.signals.remember(l.toItem())
                if (l.asin !in listed) Graph.signals.log(l.asin, SignalType.LISTED, l.title)
            }
        }
    }

    when (val s = state) {
        null -> Column { repeat(5) { ResultSkeleton() } }
        is Load.Fail -> {
            if (s.reason == Reason.SIGNED_OUT) {
                EmptyState("Sign in to see your Lists", "Your Amazon Lists show up here once you're signed in.", "Sign in") { nav.navigate("login") }
            } else {
                ErrorState(s.reason, s.message, onRetry = { reload++ }, onOpenPage = { nav.openWeb(listUrl ?: Graph.shop.url("lists")) })
            }
        }
        is Load.Ok -> Column(Modifier.fillMaxSize()) {
            if (knownLists.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    knownLists.forEach { l ->
                        FilterChip(
                            selected = listUrl == l.url || (listUrl == null && s.v.name == l.name),
                            onClick = { listUrl = l.url },
                            label = { Text(l.name) },
                        )
                    }
                }
            }
            if (s.v.items.isEmpty()) {
                EmptyState("This list is empty", "Or Cartwise couldn't read it.", "Open Amazon's page") { nav.openWeb(listUrl ?: Graph.shop.url("lists")) }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(s.v.items, key = { it.asin }) { l -> ListRow(l, onOpen = { nav.openProduct(l) }, onWatch = { scope.launch { Watch.add(l) } }) }
                }
            }
        }
    }
}

@Composable
private fun ListRow(l: Listing, onOpen: () -> Unit, onWatch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProductImage(l.image, Modifier.size(72.dp), l.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(l.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (l.price != null) Fmt.money(l.price) else l.priceText.ifBlank { "No price shown" }, style = MaterialTheme.typography.titleSmall)
        }
        TextButton(onClick = onWatch) { Text("Watch") }
    }
}
