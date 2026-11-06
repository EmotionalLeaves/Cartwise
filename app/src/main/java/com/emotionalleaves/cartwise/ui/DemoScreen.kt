package com.emotionalleaves.cartwise.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.emotionalleaves.cartwise.engine.Listing
import com.emotionalleaves.cartwise.engine.Product
import com.emotionalleaves.cartwise.engine.Review
import com.emotionalleaves.cartwise.engine.UnitPrice
import com.emotionalleaves.cartwise.engine.Variant
import com.emotionalleaves.cartwise.rank.Preset

/** Debug-only: the tile grid and product page with sample data, for design review when Amazon is unreachable. */
object Demo {
    private fun img(id: String) = "https://m.media-amazon.com/images/I/$id._AC_SL500_.jpg"
    val listings = listOf(
        Listing("D1", "Sound Bar, 100W Sound Bar for Smart TV with Bluetooth 5.3/ARC/Optical/AUX", price = 69.99, listPrice = 89.99, rating = 4.4, reviews = 32, image = img("61ZbQEbTjPL"), deliveryDays = 1, prime = true, brand = "Bestisan"),
        Listing("D2", "TCL S55H 2.1 Sound Bar with Wireless Subwoofer for Smart TV, Dolby Atmos", price = 119.99, listPrice = 159.99, rating = 4.4, reviews = 824, image = img("71v4afYtt8L"), deliveryDays = 1, prime = true, brand = "TCL"),
        Listing("D3", "LG S6FTR 5.1 ch Home Theater Soundbar with Dolby Atmos and rear speakers", price = 249.99, listPrice = 399.99, rating = 4.1, reviews = 14, image = img("611mnS6-qeL"), deliveryDays = 2, prime = true, brand = "LG"),
        Listing("D4", "Bose Smart Ultra Dolby Atmos Soundbar, All-in-One with Alexa built in", price = 649.00, listPrice = 999.00, rating = 4.2, reviews = 825, image = img("51Kw3PC65qL"), deliveryDays = 1, prime = true, brand = "Bose"),
        Listing("D5", "ULTIMEA 5.1CH Surround Sound Bar System with Dolby Atmos, Peak 410W", price = 139.99, rating = 4.3, reviews = 2920, image = img("61ZbQEbTjPL"), deliveryDays = 3, brand = "ULTIMEA", unitPrice = null),
        Listing("D6", "Bounty Quick-Size Paper Towels, 12 Family Rolls = 30 Regular Rolls", price = 31.49, rating = 4.8, reviews = 90123, image = img("81IldKDVMJL"), deliveryDays = 0, prime = true, brand = "Bounty", unitPrice = UnitPrice(1.05, "count")),
    )
    val product = Product(
        asin = "D2", title = "TCL S55H 2.1 Sound Bar with Wireless Subwoofer for Smart TV, Dolby Atmos, DTS Virtual:X, Bluetooth",
        brand = "TCL", price = 119.99, priceText = "$119.99", listPriceText = "$159.99", unitPrice = null,
        images = listOf(img("71v4afYtt8L"), img("611mnS6-qeL"), img("51Kw3PC65qL")), rating = 4.4, reviews = 824, histogram = listOf(68, 17, 7, 3, 5),
        deliveryText = "FREE delivery Tomorrow", deliveryDays = 1, soldBy = "Amazon.com", shipsFrom = "Amazon", returns = "FREE 30-day returns", availability = "In Stock",
        bullets = listOf("2.1 channel with wireless subwoofer", "Dolby Atmos and DTS Virtual:X", "HDMI eARC, optical, Bluetooth 5.3"),
        details = listOf("Brand" to "TCL", "Channels" to "2.1", "Connectivity" to "HDMI eARC, Optical, Bluetooth"),
        reviewList = listOf(Review("Big sound for the money", "Easy setup, bass is strong for the size. Dialogue mode helps a lot.", 5.0, "Sam", "October 2026")),
        breadcrumbs = listOf("Electronics", "Home Audio", "Sound Bars"),
        variants = listOf(Variant("Style", "S55H", "D2", true, true), Variant("Style", "Q75H", "D9", false, true)),
        related = emptyList(), canAddToCart = true, cartCount = 0, url = "https://www.amazon.com/dp/D2",
    )
}

@Composable
fun DemoScreen(nav: NavController) {
    var tab by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    Screen("Demo", nav, actions = { TextButton(onClick = { page = 1 - page }) { Text(if (page == 0) "Product" else "Grid") } }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (page == 0) Column {
                SortBar(Preset.BEST, false, saleOnly = false, onPick = {}, onAdjust = {}, count = Demo.listings.size)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(Demo.listings, key = { it.asin }) { l -> ProductTile(l.tile(tag = if (l.asin == "D5") "+3 similar" else null), onOpen = { page = 1 }) }
                }
            } else {
                ProductBody(Demo.product, nav, Demo.product.url, adding = false, watched = false, blockedSeller = false, onAdd = {}, onWatch = {}, tab = tab, onTab = { tab = it })
            }
        }
    }
}
