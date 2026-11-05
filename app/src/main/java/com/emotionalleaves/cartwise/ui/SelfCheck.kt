package com.emotionalleaves.cartwise.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.emotionalleaves.cartwise.BuildConfig
import com.emotionalleaves.cartwise.Graph
import com.emotionalleaves.cartwise.engine.Health
import com.emotionalleaves.cartwise.engine.Load
import com.emotionalleaves.cartwise.engine.Reason
import kotlinx.coroutines.launch

/**
 * One tap runs the plan's first on-phone tests (Rounds 1, 2, 5, 7) and says plainly what passed.
 * It loads at most 5 pages, one after another, like a person tapping through.
 */
class SelfCheckVm : ViewModel() {
    enum class Status { WAITING, RUNNING, PASS, WARN, FAIL, SKIPPED }

    data class Step(
        val title: String,
        val status: Status = Status.WAITING,
        val detail: String = "",
        val kind: String? = null,
        val url: String? = null,
        val health: Health? = null,
    )

    val steps = mutableStateListOf(
        Step("Signed in to Amazon"),
        Step("Page-reading rules loaded"),
        Step("Search reads 20+ results"),
        Step("Product page reads"),
        Step("Cart page reads"),
        Step("Your Lists read"),
        Step("On-device taste model"),
    )
    var running by mutableStateOf(false)
        private set

    private fun set(i: Int, s: Step) { steps[i] = s }

    private fun pct(h: Health?, field: String): Int = ((h?.fields?.get(field) ?: 0.0) * 100).toInt()

    fun run() {
        if (running) return
        running = true
        for (i in steps.indices) steps[i] = Step(steps[i].title)
        viewModelScope.launch {
            val signedIn = Graph.shop.isSignedIn()
            set(0, steps[0].copy(status = if (signedIn) Status.PASS else Status.WARN,
                detail = if (signedIn) "Your session cookie is saved, so you'll stay signed in." else "Not signed in. Search still works; cart, Lists and orders need it."))

            val info = Graph.selectors.info.value
            set(1, steps[1].copy(status = if (info.version > 0) Status.PASS else Status.FAIL,
                detail = "Version ${info.version}, ${info.source}" + (info.lastError?.let { ". Last fetch failed: $it" } ?: "")))

            set(2, steps[2].copy(status = Status.RUNNING))
            var firstAsin: String? = null
            val searchUrl = Graph.shop.url("search", "q" to "usb+c+cable", "page" to "1")
            when (val r = Graph.shop.search("usb c cable", 1)) {
                is Load.Ok -> {
                    val h = r.v.health
                    val n = r.v.items.size
                    firstAsin = r.v.items.firstOrNull { !it.sponsored }?.asin ?: r.v.items.firstOrNull()?.asin
                    val good = n >= 20 && pct(h, "title") >= 90 && pct(h, "price") >= 70 && pct(h, "rating") >= 70
                    set(2, steps[2].copy(
                        status = when { good -> Status.PASS; n > 0 -> Status.WARN; else -> Status.FAIL },
                        detail = "$n results · title ${pct(h, "title")}% · price ${pct(h, "price")}% · stars ${pct(h, "rating")}% · photo ${pct(h, "image")}% · delivery ${pct(h, "delivery")}%",
                        kind = "search", url = h.url.ifBlank { searchUrl }, health = h,
                    ))
                }
                is Load.Fail -> set(2, steps[2].copy(status = Status.FAIL, detail = explain(r), kind = "search", url = r.url ?: searchUrl))
            }

            set(3, steps[3].copy(status = Status.RUNNING))
            val asin = firstAsin
            if (asin == null) {
                set(3, steps[3].copy(status = Status.SKIPPED, detail = "Needs a search result to open."))
            } else {
                val purl = Graph.shop.url("product", "asin" to asin)
                when (val r = Graph.shop.product(asin)) {
                    is Load.Ok -> {
                        val p = r.v
                        val missing = buildList {
                            if (p.price == null) add("price")
                            if (p.images.isEmpty()) add("photos")
                            if (p.rating == null) add("rating")
                            if (p.deliveryText.isBlank()) add("delivery")
                            if (p.soldBy.isBlank()) add("sold by")
                            if (!p.canAddToCart) add("Add to cart button")
                            if (p.breadcrumbs.isEmpty()) add("category")
                        }
                        set(3, steps[3].copy(
                            status = when { missing.isEmpty() -> Status.PASS; missing.size <= 2 -> Status.WARN; else -> Status.FAIL },
                            detail = "\"" + com.emotionalleaves.cartwise.engine.Fmt.short(p.title, 50) + "\"" +
                                (if (missing.isEmpty()) " · everything read" else " · missing: " + missing.joinToString(", ")),
                            kind = "product", url = purl, health = com.emotionalleaves.cartwise.engine.HealthLog.state.value["product"],
                        ))
                    }
                    is Load.Fail -> set(3, steps[3].copy(status = Status.FAIL, detail = explain(r), kind = "product", url = purl))
                }
            }

            set(4, steps[4].copy(status = Status.RUNNING))
            val curl = Graph.shop.url("cart")
            when (val r = Graph.shop.cart()) {
                is Load.Ok -> {
                    val (count, asins) = r.v
                    set(4, steps[4].copy(
                        status = if (count != null) Status.PASS else Status.WARN,
                        detail = (if (count != null) "Cart count reads as $count" else "Couldn't read the cart count") + " · ${asins.size} items found on the page",
                        kind = "cartcount", url = curl,
                    ))
                }
                is Load.Fail -> set(4, steps[4].copy(status = if (r.reason == Reason.SIGNED_OUT) Status.SKIPPED else Status.FAIL, detail = explain(r), kind = "cartcount", url = r.url ?: curl))
            }

            if (!signedIn) {
                set(5, steps[5].copy(status = Status.SKIPPED, detail = "Sign in first."))
            } else {
                set(5, steps[5].copy(status = Status.RUNNING))
                val lurl = Graph.shop.url("lists")
                when (val r = Graph.shop.lists(null)) {
                    is Load.Ok -> set(5, steps[5].copy(
                        status = if (r.v.lists.isNotEmpty() || r.v.items.isNotEmpty()) Status.PASS else Status.WARN,
                        detail = "${r.v.lists.size} lists · ${r.v.items.size} items on \"${r.v.name.ifBlank { "your list" }}\"",
                        kind = "lists", url = lurl, health = com.emotionalleaves.cartwise.engine.HealthLog.state.value["lists"],
                    ))
                    is Load.Fail -> set(5, steps[5].copy(status = Status.FAIL, detail = explain(r), kind = "lists", url = r.url ?: lurl))
                }
            }

            val model = Graph.embedder.name
            set(6, steps[6].copy(status = if (Graph.embedder.usingModel) Status.PASS else Status.WARN, detail = model))
            running = false
        }
    }

    private fun explain(r: Load.Fail): String = when (r.reason) {
        Reason.OFFLINE -> "You're offline."
        Reason.TIMEOUT -> "Amazon took too long. Try again on a better connection."
        Reason.CAPTCHA -> "Amazon showed a captcha. Open the page, solve it, then run the check again."
        Reason.SIGNED_OUT -> "Amazon asked you to sign in."
        Reason.EMPTY -> "The page loaded but nothing could be read. The rules need updating."
        else -> r.message
    }

    fun report(): String = buildString {
        appendLine("Cartwise self-check · build ${BuildConfig.VERSION_NAME} · rules v${Graph.selectors.info.value.version}")
        steps.forEach { appendLine("[${it.status}] ${it.title}: ${it.detail}") }
    }
}

@Composable
fun SelfCheckScreen(nav: NavController) {
    val vm: SelfCheckVm = viewModel { SelfCheckVm() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Screen(
        "Self-check",
        nav,
        actions = {
            IconButton(onClick = { shareText(ctx, vm.report(), "Cartwise self-check") }, enabled = !vm.running) {
                Icon(Icons.Filled.Share, contentDescription = "Share the results")
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text(
                    "Runs your first on-phone tests in one go: sign-in, reading a search, a product page, your cart and Lists. It loads 5 pages at most, one after another.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                Button(
                    onClick = { vm.run() },
                    enabled = !vm.running,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp),
                ) { Text(if (vm.running) "Checking…" else "Run self-check") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
                Spacer(Modifier.height(8.dp))
            }
            itemsIndexed(vm.steps) { i, s ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        when (s.status) {
                            SelfCheckVm.Status.RUNNING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            SelfCheckVm.Status.PASS -> Icon(Icons.Filled.CheckCircle, contentDescription = "Passed", tint = MaterialTheme.colorScheme.primary)
                            SelfCheckVm.Status.WARN -> Icon(Icons.Filled.Warning, contentDescription = "Needs a look", tint = MaterialTheme.colorScheme.secondary)
                            SelfCheckVm.Status.FAIL -> Icon(Icons.Filled.Warning, contentDescription = "Failed", tint = MaterialTheme.colorScheme.error)
                            else -> Icon(Icons.Filled.Info, contentDescription = "Not run", tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.bodyLarge)
                        if (s.detail.isNotBlank()) Text(s.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val kind = s.kind
                        val url = s.url
                        if ((s.status == SelfCheckVm.Status.WARN || s.status == SelfCheckVm.Status.FAIL) && kind != null && url != null) {
                            Row {
                                TextButton(
                                    onClick = {
                                        sharing = i; error = null
                                        scope.launch {
                                            when (val out = PageReport.build(kind, url, s.health)) {
                                                is PageReport.Out.Text -> shareText(ctx, out.text, "Cartwise $kind page")
                                                is PageReport.Out.Error -> error = out.message
                                            }
                                            sharing = null
                                        }
                                    },
                                    enabled = sharing == null && !vm.running,
                                    contentPadding = PaddingValues(horizontal = 0.dp),
                                ) { Text(if (sharing == i) "Reading the page…" else "Share this page for fixing") }
                                Spacer(Modifier.width(12.dp))
                                TextButton(onClick = { nav.openWeb(url) }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Open page") }
                            }
                        }
                        if (i == 0 && s.status == SelfCheckVm.Status.WARN) {
                            TextButton(onClick = { nav.navigate("login") }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Sign in") }
                        }
                    }
                }
            }
        }
    }
}
