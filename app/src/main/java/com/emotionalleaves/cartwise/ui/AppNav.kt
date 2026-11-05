@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.emotionalleaves.cartwise.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.emotionalleaves.cartwise.Graph

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("foryou", "For You", Icons.Filled.Home),
    Tab("search", "Search", Icons.Filled.Search),
    Tab("outlet", "Outlet", OutletIcon),
    Tab("lists", "Lists", Icons.Filled.Favorite),
    Tab("cart", "Cart", Icons.Filled.ShoppingCart),
)

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "foryou"
    var tab by rememberSaveable { mutableStateOf("foryou") }
    LaunchedEffect(route) { if (TABS.any { it.route == route }) tab = route }

    val pending by Graph.pendingAsin.collectAsStateWithLifecycle()
    val cartCount by com.emotionalleaves.cartwise.engine.AmazonSession.cartCount.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        val a = pending
        if (a != null) {
            nav.openProduct(a)
            Graph.pendingAsin.value = null
        }
    }

    val fullScreen = route.startsWith("web") || route == "login" || route == "quiz"

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!fullScreen) {
                NavigationBar {
                    TABS.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t.route,
                            onClick = {
                                tab = t.route
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                val count = if (t.route == "cart") cartCount else null
                                if (count != null && count > 0) {
                                    BadgedBox(badge = { Badge { Text(count.toString()) } }) { Icon(t.icon, contentDescription = "${t.label}, $count items") }
                                } else {
                                    Icon(t.icon, contentDescription = t.label)
                                }
                            },
                            label = { Text(t.label, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(
            navController = nav,
            startDestination = "foryou",
            modifier = Modifier.padding(pad).consumeWindowInsets(pad),
        ) {
            composable("foryou") { ForYouScreen(nav) }
            composable("search") { SearchScreen(nav) }
            composable("outlet") { OutletScreen(nav) }
            composable("lists") { ListsScreen(nav) }
            composable("cart") { CartScreen(nav) }
            composable("account") { AccountScreen(nav) }
            composable("results/{q}", arguments = listOf(navArgument("q") { type = NavType.StringType })) { e ->
                ResultsScreen(nav, e.arguments?.getString("q") ?: "")
            }
            composable("product/{asin}", arguments = listOf(navArgument("asin") { type = NavType.StringType })) { e ->
                ProductScreen(nav, e.arguments?.getString("asin") ?: "")
            }
            composable(
                "web?url={url}&banner={banner}",
                arguments = listOf(
                    navArgument("url") { type = NavType.StringType; defaultValue = "" },
                    navArgument("banner") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { e ->
                WebRoute(nav, e.arguments?.getString("url") ?: "", e.arguments?.getString("banner") ?: "")
            }
            composable("login") { LoginScreen(nav) }
            composable("interests") { InterestsScreen(nav) }
            composable("quiz") { QuizScreen(nav) }
            composable("settings") { SettingsScreen(nav) }
            composable("debug") { DebugScreen(nav) }
            composable("check") { SelfCheckScreen(nav) }
        }
    }
}
