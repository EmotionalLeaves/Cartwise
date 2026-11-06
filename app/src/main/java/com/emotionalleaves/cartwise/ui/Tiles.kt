@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.emotionalleaves.cartwise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emotionalleaves.cartwise.engine.Fmt

/** Everything a product tile shows. Built from a Listing, a feed card or a watchlist row. */
data class TileData(
    val asin: String,
    val title: String,
    val image: String,
    val price: Double?,
    val priceText: String = "",
    val listPrice: Double? = null,
    val discountPct: Int = 0,
    val rating: Double? = null,
    val reviews: Int = 0,
    val unit: String? = null,
    val line: String = "",
    val lineAccent: Boolean = false,
    val tag: String? = null,
)

/**
 * The one product tile used across Search, Outlet and For You: photo first, then
 * title, price and a single quiet line. Tap opens; long-press shows options.
 */
@Composable
fun ProductTile(d: TileData, modifier: Modifier = Modifier, onOpen: () -> Unit, onLong: (() -> Unit)? = null, onShown: (suspend () -> Unit)? = null) {
    if (onShown != null) androidx.compose.runtime.LaunchedEffect(d.asin) { onShown() }
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .combinedClickable(onClick = onOpen, onLongClick = onLong, onLongClickLabel = "Options"),
    ) {
        Box {
            ProductImage(d.image, Modifier.fillMaxWidth().aspectRatio(1f), d.title, px = 500, radius = 0.dp, asin = d.asin)
            if (d.discountPct > 0) {
                Surface(
                    color = androidx.compose.ui.graphics.Color(0xFF1B1F22),
                    contentColor = androidx.compose.ui.graphics.Color(0xFFF2C77F),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                ) {
                    Text("−${d.discountPct}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                }
            }
            d.tag?.let {
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) {
                    Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                }
            }
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
            Text(
                d.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (d.price != null) Fmt.money(d.price) else d.priceText.ifBlank { "—" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (d.price == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                if (d.discountPct > 0 && d.listPrice != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        Fmt.money(d.listPrice),
                        style = MaterialTheme.typography.labelMedium,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                } else if (d.unit != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(d.unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (d.rating != null) {
                    Text(
                        Fmt.stars(d.rating) + " ★" + (if (d.reviews > 0) "  " + Fmt.count(d.reviews) else ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (d.line.isNotBlank()) {
                Text(
                    d.line,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (d.lineAccent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

fun com.emotionalleaves.cartwise.engine.Listing.tile(line: String = Fmt.delivery(deliveryDays, deliveryText), accent: Boolean = (deliveryDays ?: 9) <= 1, tag: String? = null) = TileData(
    asin = asin, title = title, image = image, price = price, priceText = priceText, listPrice = listPrice, discountPct = discountPct,
    rating = rating, reviews = reviews, unit = unitPrice?.label(), line = line, lineAccent = accent, tag = tag,
)

fun com.emotionalleaves.cartwise.data.FeedCardEntity.tile() = TileData(
    asin = asin, title = title, image = image, price = price, rating = rating, reviews = reviews,
    line = reason, lineAccent = true, tag = if (wildcard) "Wildcard" else null,
)
