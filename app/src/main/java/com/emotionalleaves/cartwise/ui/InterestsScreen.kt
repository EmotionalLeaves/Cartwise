@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.emotionalleaves.cartwise.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.emotionalleaves.cartwise.Graph
import com.emotionalleaves.cartwise.data.BlockEntity
import com.emotionalleaves.cartwise.data.ClusterEntity
import com.emotionalleaves.cartwise.data.displayName
import com.emotionalleaves.cartwise.feed.Workers
import com.emotionalleaves.cartwise.taste.Quiz
import kotlinx.coroutines.launch

@Composable
fun InterestsScreen(nav: NavController) {
    val dao = Graph.db.dao()
    val clusters by dao.clustersFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val blocks by dao.blocksFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var rebuilding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ClusterEntity?>(null) }
    var deleting by remember { mutableStateOf<ClusterEntity?>(null) }

    Screen(
        "Your interests",
        nav,
        actions = {
            IconButton(enabled = !rebuilding, onClick = {
                rebuilding = true
                scope.launch { Graph.taste.rebuild(); rebuilding = false }
            }) { Icon(Icons.Filled.Refresh, contentDescription = "Rebuild interests now") }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text(
                    "Cartwise groups what you buy, keep, save and like into up to 8 interests. They're rebuilt every night on your phone and never leave it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (rebuilding) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
            if (clusters.isEmpty()) {
                item {
                    EmptyState("No interests yet", "Take the 30-second quiz, import your orders, or just shop for a bit.", "Start the quiz") { nav.navigate("quiz") }
                }
            }
            items(clusters, key = { it.id }) { c ->
                ClusterCard(
                    c,
                    onRename = { renaming = c },
                    onMute = { scope.launch { dao.muteCluster(c.id, !c.muted) } },
                    onDelete = { deleting = c },
                )
            }
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { nav.navigate("quiz") }) { Text("Retake the start quiz") }
                }
            }
            val never = blocks.filter { it.kind != "hidden" }
            val hidden = blocks.filter { it.kind == "hidden" }
            item { SectionHeader("Never show") }
            if (never.isEmpty()) {
                item {
                    Text(
                        "Nothing yet. Use the ⋮ on a feed card, or long-press a search result, to block an item, brand or topic.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            items(never, key = { "b-" + it.id }) { b -> BlockRow(b) { scope.launch { dao.deleteBlock(b.id) } } }
            if (hidden.isNotEmpty()) {
                item {
                    SettingRow(
                        "Hidden search results (${hidden.size})",
                        "Results you swiped away. Clear to see them again.",
                        trailing = { TextButton(onClick = { scope.launch { dao.deleteBlockKind("hidden") } }) { Text("Clear") } },
                    )
                }
            }
        }
    }

    renaming?.let { c ->
        var text by remember(c.id) { mutableStateOf(c.displayName()) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename interest") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { dao.renameCluster(c.id, text.trim().ifBlank { null }) }
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${c.displayName()}\"?") },
            text = { Text("Things like this stop appearing in your feed, even after tonight's rebuild. You can undo it under Never show.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        dao.insertBlock(BlockEntity(kind = "topic", value = "cluster:" + c.id, label = "Interest: " + c.displayName(), vector = c.centroid))
                        dao.deleteCluster(c.id)
                    }
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ClusterCard(c: ClusterEntity, onRename: () -> Unit, onMute: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.displayName(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (c.muted) Pill("Muted")
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options for ${c.displayName()}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text(if (c.muted) "Unmute" else "Mute") }, onClick = { menu = false; onMute() })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            val brands = c.topBrands.split("|").filter { it.isNotBlank() }.take(3)
            Text(
                "${c.itemCount} things" + (if (brands.isNotEmpty()) " · " + brands.joinToString(", ") else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            c.examples.split("|").filter { it.isNotBlank() }.take(3).forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 16.dp, top = 2.dp))
            }
        }
    }
}

@Composable
private fun BlockRow(b: BlockEntity, onRemove: () -> Unit) {
    val kind = when (b.kind) {
        "brand" -> "Brand"
        "seller" -> "Seller"
        "topic" -> "Topic"
        else -> "Item"
    }
    ListItem(
        headlineContent = { Text(b.label.ifBlank { b.value }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(kind) },
        trailingContent = { IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove from never show") } },
    )
}

@Composable
fun QuizScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val picked = remember { mutableStateListOf<String>() }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val prev = Graph.taste.quizPicks()
        if (picked.isEmpty()) picked.addAll(prev)
    }
    Screen("What do you shop for?", nav) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Text(
                    "Pick 5 to 10. This gives the For You feed somewhere to start; what you actually buy and keep takes over from here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Quiz.CHIPS.forEach { c ->
                        val on = c.slug in picked
                        FilterChip(
                            selected = on,
                            onClick = { if (on) picked.remove(c.slug) else if (picked.size < 10) picked.add(c.slug) },
                            label = { Text(c.label) },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = {
                        saving = true
                        val chosen = Quiz.CHIPS.filter { it.slug in picked }
                        Graph.scope.launch {
                            Graph.taste.applyQuiz(chosen)
                            Graph.feed.refreshNow(10)
                        }
                        nav.popBackStack("foryou", inclusive = false)
                    },
                    enabled = picked.size in 5..10 && !saving,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
                ) {
                    Text(
                        when {
                            saving -> "Building your feed…"
                            picked.size < 5 -> "Pick ${5 - picked.size} more"
                            else -> "Build my feed (${picked.size})"
                        },
                    )
                }
            }
        }
    }
}
