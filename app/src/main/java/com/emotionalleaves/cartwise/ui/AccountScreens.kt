@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.emotionalleaves.cartwise.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.emotionalleaves.cartwise.BuildConfig
import com.emotionalleaves.cartwise.CrashLog
import com.emotionalleaves.cartwise.Graph
import com.emotionalleaves.cartwise.engine.Fmt
import com.emotionalleaves.cartwise.engine.HealthLog
import com.emotionalleaves.cartwise.engine.Load
import com.emotionalleaves.cartwise.engine.Sensitive
import com.emotionalleaves.cartwise.rank.Preset
import com.emotionalleaves.cartwise.taste.SignalType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------- Account ----------------

@Composable
fun AccountScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by Graph.settings.prefs.collectAsStateWithLifecycle(initialValue = null)
    val importMsg by Graph.orders.progress.collectAsStateWithLifecycle()
    var signedIn by remember { mutableStateOf(Graph.shop.isSignedIn()) }
    var confirmImport by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(Unit) {
        signedIn = Graph.shop.isSignedIn()
        onPauseOrDispose { }
    }

    Screen("Account", nav) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(if (signedIn) "Signed in to Amazon" else "Not signed in", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (signedIn) "Cartwise uses the session your phone's WebView already holds. It never sees your password."
                            else "Sign in on Amazon's own page. Cartwise never reads or stores your password, codes or card details.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (signedIn) {
                            OutlinedButton(onClick = { Graph.engine.signOut(); com.emotionalleaves.cartwise.engine.ShopRepo.clearCache(); signedIn = false }) { Text("Sign out") }
                        } else {
                            Button(onClick = { nav.navigate("login") }) { Text("Sign in to Amazon") }
                        }
                    }
                }
            }
            item {
                SettingRow("Run self-check", "Tests sign-in, search, product, cart and Lists in one tap", onClick = { nav.navigate("check") })
                HorizontalDivider()
                SettingRow("Your orders", "Amazon's own Orders page", onClick = { nav.openWeb(Graph.shop.url("ordersWeb")) })
                HorizontalDivider()
                val imported = prefs?.ordersImported ?: 0L
                SettingRow(
                    "Import order history",
                    when {
                        importMsg != null -> importMsg!!
                        imported > 0 -> "Last imported " + Fmt.ago(imported) + ". Tap to refresh."
                        else -> "Optional. Reads your Orders pages once so the feed knows what you kept and returned."
                    },
                    onClick = if (importMsg == null && signedIn) ({ confirmImport = true }) else null,
                    trailing = { if (importMsg != null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                )
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp)) }
                HorizontalDivider()
                SettingRow("Your interests", "See, rename, mute or delete what the feed thinks you like", onClick = { nav.navigate("interests") })
                HorizontalDivider()
                SettingRow("Settings", "Sponsored results, ranking, theme, data", onClick = { nav.navigate("settings") })
                HorizontalDivider()
                SettingRow("Debug", "Logged signals, page-reader health, engine log", onClick = { nav.navigate("debug") })
                HorizontalDivider()
                SettingRow("Share crash log", "Send the last crash report to yourself or an AI to fix", onClick = {
                    val log = CrashLog.read(ctx)
                    shareText(ctx, if (log.isBlank()) "No crashes recorded. Cartwise ${BuildConfig.VERSION_NAME}" else log, "Cartwise crash log")
                })
            }
            item {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("What Cartwise will never do", style = MaterialTheme.typography.titleSmall)
                        }
                        Spacer(Modifier.height(6.dp))
                        listOf(
                            "Read, store or fill in your password, 2FA codes or card details.",
                            "Tap Place Order, Buy Now or confirm a payment. Those are always your taps on Amazon's page.",
                            "Run scripts or styles on sign-in, payment or checkout pages (dark mode aside).",
                            "Add affiliate tags or rewrite links.",
                            "Load pages in the background faster than a person would: 40 pages max per refresh, 3–8 seconds apart.",
                            "Send your taste data anywhere. It stays on this phone.",
                        ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
                        Spacer(Modifier.height(6.dp))
                        Text("Cartwise ${BuildConfig.VERSION_NAME} · personal use only · not affiliated with any retailer", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Import your order history?") },
            text = {
                Text(
                    "Cartwise will read up to 12 of your Orders pages from the last 3 years, slowly (3–8 seconds apart), " +
                        "and note which items you kept or returned. It's stored only on this phone and you can wipe it in Settings.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    result = null
                    scope.launch {
                        result = when (val r = Graph.orders.run()) {
                            is Load.Ok -> "Imported ${r.v} order lines. Your interests will update shortly."
                            is Load.Fail -> "Import stopped: " + r.message
                        }
                        Graph.taste.rebuild()
                    }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Not now") } },
        )
    }
}

// ---------------- Sign in ----------------

@Composable
fun LoginScreen(nav: NavController) {
    var done by remember { mutableStateOf(false) }
    Screen("Sign in to Amazon", nav) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Banner("This is Amazon's real sign-in page. Cartwise doesn't read anything you type here.")
            AmazonPage(
                Graph.shop.url("signin"),
                PageMode.LOGIN,
                Modifier.weight(1f),
                onSignedIn = {
                    if (!done) {
                        done = true
                        nav.popBackStack()
                    }
                },
            )
        }
    }
}

// ---------------- Cart ----------------

@Composable
fun CartScreen(nav: NavController) {
    var subtotal by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var reloadKey by remember { mutableIntStateOf(0) }
    val web = remember { mutableStateOf<WebView?>(null) }
    val onCart = Sensitive.isCart(url)

    LaunchedEffect(onCart, reloadKey) {
        while (onCart) {
            delay(2500)
            web.value?.let { w -> CartPage.restyle(w) { subtotal = it } }
        }
    }

    Screen(
        "Cart",
        actions = {
            IconButton(onClick = {
                val w = web.value
                subtotal = ""
                if (w != null && Sensitive.isCart(url)) w.reload() else reloadKey++
            }) { Icon(Icons.Filled.Refresh, contentDescription = "Reload cart") }
            AccountButton(nav)
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            key(reloadKey) {
                AmazonPage(
                    Graph.shop.url("cart"),
                    PageMode.CART,
                    Modifier.fillMaxSize(),
                    onUrl = { url = it },
                    onSubtotal = { subtotal = it },
                    webRef = web,
                )
            }
            if (onCart) {
                Surface(
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Subtotal", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(subtotal.ifBlank { "—" }, style = MaterialTheme.typography.titleLarge)
                        }
                        Button(onClick = { web.value?.let { CartPage.proceed(it) } }, modifier = Modifier.height(52.dp)) {
                            Text("Proceed to checkout")
                        }
                    }
                }
            } else if (Sensitive.isSensitive(url)) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                    Text(
                        "Amazon's checkout, untouched. You tap Place Order yourself.",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

// ---------------- Raw Amazon page ----------------

@Composable
fun WebRoute(nav: NavController, url: String, banner: String) {
    var current by remember { mutableStateOf(url) }
    Screen(
        title = runCatching { android.net.Uri.parse(current).host ?: "Amazon" }.getOrDefault("Amazon"),
        nav = nav,
        actions = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.Close, contentDescription = "Close") } },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (banner.isNotBlank()) Banner(banner)
            if (url.isBlank()) {
                EmptyState("Nothing to open", "No page address was given.")
            } else {
                AmazonPage(url, PageMode.RAW, Modifier.weight(1f), onUrl = { current = it })
            }
        }
    }
}

// ---------------- Settings ----------------

@Composable
fun SettingsScreen(nav: NavController) {
    val prefs by Graph.settings.prefs.collectAsStateWithLifecycle(initialValue = null)
    val sel by Graph.selectors.info.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var wipe by remember { mutableStateOf(false) }
    var presetMenu by remember { mutableStateOf(false) }
    var themeMenu by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val p = prefs ?: return

    Screen("Settings", nav) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { SectionHeader("Search") }
            item {
                SettingRow(
                    "Hide sponsored results",
                    if (p.hideSponsored) "Paid placements are removed from results." else "Paid placements stay, with a Sponsored tag.",
                    trailing = { Switch(checked = p.hideSponsored, onCheckedChange = { v -> scope.launch { Graph.settings.setHideSponsored(v) } }) },
                )
                Box {
                    SettingRow(
                        "Default ranking",
                        "Results open sorted by: " + Preset.from(p.preset).label,
                        onClick = { presetMenu = true },
                    )
                    DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                        Preset.entries.forEach { pr ->
                            DropdownMenuItem(text = { Text(pr.label) }, onClick = { presetMenu = false; scope.launch { Graph.settings.setPreset(pr.name) } })
                        }
                    }
                }
                SettingRow(
                    "Personalize ranking",
                    "Small boost for brands you bought and kept, small penalty for brands you returned. Needs order import.",
                    trailing = { Switch(checked = p.personalize, onCheckedChange = { v -> scope.launch { Graph.settings.setPersonalize(v) } }) },
                )
            }
            item { SectionHeader("For You") }
            item {
                SettingRow(
                    "Daily refresh on Wi-Fi",
                    "Reads up to 40 pages once a day, 3–8 seconds apart. Turn off if Amazon ever warns you.",
                    trailing = { Switch(checked = p.bgRefresh, onCheckedChange = { v -> scope.launch { Graph.settings.setBgRefresh(v) } }) },
                )
                SettingRow("Your interests", "Rename, mute or delete what the feed uses", onClick = { nav.navigate("interests") })
                SettingRow("Retake the start quiz", "Pick 5–10 things you shop for", onClick = { nav.navigate("quiz") })
            }
            item { SectionHeader("Look") }
            item {
                Box {
                    SettingRow(
                        "Theme",
                        when (p.theme) { "dark" -> "Dark"; "light" -> "Light"; else -> "Follows your phone" },
                        onClick = { themeMenu = true },
                    )
                    DropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }) {
                        listOf("system" to "Follow phone", "light" to "Light", "dark" to "Dark").forEach { (k, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { themeMenu = false; scope.launch { Graph.settings.setTheme(k) } })
                        }
                    }
                }
            }
            item { SectionHeader("Page reading") }
            item {
                SettingRow(
                    "Page-reading rules",
                    "Version ${sel.version}, ${sel.source}" + (sel.lastError?.let { " · last fetch failed: $it" } ?: ""),
                    onClick = {
                        scope.launch {
                            val ok = Graph.selectors.refreshFromRemote()
                            msg = if (ok) "Rules reloaded from GitHub" else "Couldn't reach GitHub; using saved rules"
                        }
                    },
                    trailing = { Icon(Icons.Filled.Refresh, contentDescription = "Reload rules") },
                )
                msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp)) }
                Text(
                    "When a field goes blank after Amazon changes its pages, edit config/selectors.json in the GitHub app, bump \"version\", commit, and reopen Cartwise.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item { SectionHeader("Your data") }
            item {
                SettingRow(
                    "Wipe taste data",
                    "Deletes signals, imported orders, interests and the feed from this phone. Keeps your watchlist and blocklist.",
                    onClick = { wipe = true },
                )
                SettingRow("Embedding model", Graph.embedder.name, onClick = null)
            }
        }
    }

    if (wipe) {
        AlertDialog(
            onDismissRequest = { wipe = false },
            title = { Text("Wipe taste data?") },
            text = { Text("This can't be undone. The feed starts over from the quiz.") },
            confirmButton = {
                TextButton(onClick = { wipe = false; scope.launch { Graph.taste.wipe(); msg = "Taste data wiped" } }) { Text("Wipe") }
            },
            dismissButton = { TextButton(onClick = { wipe = false }) { Text("Cancel") } },
        )
    }
}

// ---------------- Debug ----------------

@Composable
fun DebugScreen(nav: NavController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Screen("Debug", nav) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, edgePadding = 8.dp) {
                listOf("Signals", "Orders", "Page reader", "Engine").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> SignalsTab()
                1 -> OrdersTab()
                2 -> HealthTab()
                else -> EngineTab()
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("MMM d HH:mm", Locale.US)

@Composable
private fun SignalsTab() {
    val list by Graph.db.dao().recentSignals(300).collectAsStateWithLifecycle(initialValue = emptyList())
    val total by Graph.db.dao().signalCount().collectAsStateWithLifecycle(initialValue = 0)
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("$total signals logged (showing latest 300)", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(16.dp)) }
        items(list, key = { it.id }) { s ->
            ListItem(
                headlineContent = { Text(s.note.ifBlank { s.ref }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(SignalType.label(s.type) + " · " + timeFmt.format(Date(s.ts)) + " · " + s.ref) },
                trailingContent = { Text((if (s.weight > 0) "+" else "") + s.weight, style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
}

@Composable
private fun OrdersTab() {
    val list by Graph.db.dao().ordersFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "${list.size} order lines · ${list.count { it.returned }} returned",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(list, key = { it.orderId + it.asin }) { o ->
            ListItem(
                headlineContent = { Text(o.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text((if (o.date > 0) timeFmt.format(Date(o.date)) else "no date") + " · " + o.asin) },
                trailingContent = {
                    if (o.returned) Pill("Returned", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    else Pill("Kept", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                },
            )
        }
    }
}

@Composable
private fun HealthTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val health by HealthLog.state.collectAsStateWithLifecycle()
    val sel by Graph.selectors.info.collectAsStateWithLifecycle()
    val lastUrls by Graph.engine.lastUrls.collectAsStateWithLifecycle()
    var sharing by remember { mutableStateOf<String?>(null) }
    var shareError by remember { mutableStateOf<String?>(null) }

    fun share(h: com.emotionalleaves.cartwise.engine.Health) {
        val url = lastUrls[h.kind] ?: h.url
        sharing = h.kind
        shareError = null
        scope.launch {
            when (val out = PageReport.build(h.kind, url, h)) {
                is PageReport.Out.Text -> shareText(ctx, out.text, "Cartwise ${h.kind} page")
                is PageReport.Out.Error -> shareError = out.message
            }
            sharing = null
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Text(
                "Rules version ${sel.version} (${sel.source})" + (if (sel.fetchedAt > 0) ", fetched " + Fmt.ago(sel.fetchedAt) else ""),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            if (health.isEmpty()) Text("Open a search or product to see how well each field is read.", style = MaterialTheme.typography.bodySmall)
            else Text(
                "If a field is low, tap \"Share this page for fixing\" and paste it to an AI. It returns updated rules for config/selectors.json.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(health.values.sortedByDescending { it.at }, key = { it.kind }) { h ->
            Column(Modifier.padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(h.kind, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Pill(if (h.ok) "OK" else "Fallback", if (h.ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)
                }
                Text("${h.count} found · " + Fmt.ago(h.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (h.notes.isNotEmpty()) Text("Found another way: " + h.notes.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                h.fields.forEach { (k, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Text(k, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(90.dp))
                        LinearProgressIndicator(
                            progress = { v.toFloat() },
                            modifier = Modifier.weight(1f).height(6.dp),
                            color = if (v < 0.5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        Text("${(v * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(44.dp).padding(start = 6.dp))
                    }
                }
                Text(h.url, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { share(h) }, enabled = sharing == null, contentPadding = PaddingValues(0.dp)) {
                    Text(if (sharing == h.kind) "Reading the page…" else "Share this page for fixing")
                }
                if (shareError != null && sharing == null) Text(shareError!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun EngineTab() {
    val fg by Graph.engine.log.collectAsStateWithLifecycle()
    val bg by Graph.bgEngine.log.collectAsStateWithLifecycle()
    val log = remember(fg, bg) { (fg + bg).sortedByDescending { it.time }.take(80) }
    LazyColumn(Modifier.fillMaxSize()) {
        if (log.isEmpty()) item { Text("No pages loaded yet in this session.", modifier = Modifier.padding(16.dp)) }
        items(log) { e ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    timeFmt.format(Date(e.time)) + " · " + e.kind + " · " + e.ms + " ms",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(e.url, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(e.result, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
            }
        }
    }
}
