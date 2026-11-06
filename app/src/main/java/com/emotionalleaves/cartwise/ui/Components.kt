@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.emotionalleaves.cartwise.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.emotionalleaves.cartwise.engine.Fmt
import com.emotionalleaves.cartwise.engine.Reason
import com.emotionalleaves.cartwise.engine.UnitPrice

/** Standard screen: top bar with optional back arrow, content below. */
@Composable
fun Screen(
    title: String,
    nav: NavController? = null,
    snackbar: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = {
                    if (nav != null) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad -> content(pad) }
}

/** Your account: sign-in, orders, interests, settings. Lives in the top bar of every tab. */
@Composable
fun AccountButton(nav: NavController) {
    IconButton(onClick = { nav.navigate("account") { launchSingleTop = true } }) {
        Icon(Icons.Filled.AccountCircle, contentDescription = "Account and settings")
    }
}

/** Product photos are shot on white, so they always sit on a white tile, also in dark mode. */
@Composable
fun ProductImage(url: String, modifier: Modifier = Modifier, contentDescription: String? = null, px: Int = 400, radius: androidx.compose.ui.unit.Dp = 14.dp) {
    var failed by remember(url) { mutableStateOf(false) }
    Box(
        modifier.clip(RoundedCornerShape(radius)).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNotBlank() && !failed) {
            AsyncImage(
                model = Fmt.image(url, px),
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize().padding(6.dp),
            )
        } else {
            Icon(
                Icons.Filled.ShoppingCart,
                contentDescription = "No photo",
                tint = Color(0xFFC9C4B9),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
fun SkeletonBox(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
}

@Composable
fun ResultSkeleton() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        SkeletonBox(Modifier.size(104.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SkeletonBox(Modifier.fillMaxWidth().height(16.dp))
            Spacer(Modifier.height(6.dp))
            SkeletonBox(Modifier.fillMaxWidth(0.7f).height(16.dp))
            Spacer(Modifier.height(10.dp))
            SkeletonBox(Modifier.width(90.dp).height(20.dp))
            Spacer(Modifier.height(8.dp))
            SkeletonBox(Modifier.width(140.dp).height(14.dp))
        }
    }
}

@Composable
fun RatingLine(rating: Double?, reviews: Int, modifier: Modifier = Modifier) {
    if (rating == null) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Star, contentDescription = null, tint = Star, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(3.dp))
        Text(Fmt.stars(rating), style = MaterialTheme.typography.labelLarge)
        if (reviews > 0) {
            Text(
                " (" + Fmt.count(reviews) + ")",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun PriceLine(price: Double?, priceText: String, unit: UnitPrice?, listPrice: String = "", big: Boolean = false) {
    Row(verticalAlignment = Alignment.Bottom) {
        val p = if (price != null) Fmt.money(price) else priceText.ifBlank { "See price on Amazon" }
        Text(
            p,
            style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (price == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        if (unit != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                unit.label(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        if (listPrice.isNotBlank() && Fmt.money(price) != listPrice) {
            Spacer(Modifier.width(6.dp))
            Text(
                listPrice,
                style = MaterialTheme.typography.labelMedium,
                textDecoration = TextDecoration.LineThrough,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}

@Composable
fun Pill(text: String, container: Color = MaterialTheme.colorScheme.surfaceVariant, content: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
fun DiscountPill(pct: Int) {
    Pill("−$pct%", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
}

@Composable
fun SellerBadge(score: Double) {
    when {
        score >= 1.0 -> Pill("Sold by Amazon", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        score >= 0.8 -> Pill("Prime", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        else -> Pill("Third-party")
    }
}

@Composable
fun Banner(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun ErrorState(reason: Reason, message: String, onRetry: () -> Unit, onOpenPage: (() -> Unit)? = null) {
    val title = when (reason) {
        Reason.OFFLINE -> "You're offline"
        Reason.TIMEOUT -> "Amazon is slow right now"
        Reason.SIGNED_OUT -> "Sign in to Amazon first"
        Reason.CAPTCHA -> "Amazon wants a quick check"
        else -> "Couldn't load this"
    }
    val body = when (reason) {
        Reason.OFFLINE -> "Check your connection and try again."
        Reason.CAPTCHA -> "Open Amazon's page to finish the check, then come back."
        else -> message
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry) { Text("Try again") }
            if (onOpenPage != null) OutlinedButton(onClick = onOpenPage) { Text("Open Amazon's page") }
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** A setting with a one-line plain explanation under it. No nested menus. */
@Composable
fun SettingRow(title: String, explanation: String, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

fun openExternal(c: Context, url: String) {
    try {
        c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

fun shareText(c: Context, text: String, subject: String = "Cartwise") {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, subject)
    c.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun NavController.openProduct(asin: String) = navigate("product/$asin")

/** Opens a product and shows what we already know about it instantly while the full page loads. */
fun NavController.openProduct(l: com.emotionalleaves.cartwise.engine.Listing) {
    if (l.title.isNotBlank()) com.emotionalleaves.cartwise.Graph.previews[l.asin] = l
    navigate("product/" + l.asin)
}

fun com.emotionalleaves.cartwise.data.FeedCardEntity.toListing() = com.emotionalleaves.cartwise.engine.Listing(
    asin = asin, title = title, price = price, rating = rating, reviews = reviews, image = image, brand = brand,
)

fun com.emotionalleaves.cartwise.data.WatchEntity.toListing() = com.emotionalleaves.cartwise.engine.Listing(
    asin = asin, title = title, price = price, image = image,
)

fun NavController.openWeb(url: String, banner: String = "") =
    navigate("web?url=" + Uri.encode(url) + "&banner=" + Uri.encode(banner))

fun NavController.openResults(q: String) = navigate("results/" + Uri.encode(q))
