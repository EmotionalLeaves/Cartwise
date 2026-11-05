# Changelog

All ten rounds from the implementation plan were built in one pass. Each section says how to test it on your phone, in the plan's order. Do Round 1 first: if Amazon sign-in doesn't work in the app, stop there.

Install: open the repo's **Releases → latest** on your phone and tap `Cartwise.apk` (or grab the `Cartwise-apk` artifact from the newest Actions run).

## Round 1 — Shell + login
- Compose app, five-tab bar (For You, Search, Lists, Cart, Account), one shared hidden WebView engine behind the UI, cookie persistence (flushed on pause and after every page), GitHub Actions workflow that outputs a signed APK.
- **Test:** Account → Sign in to Amazon. Sign in on Amazon's page. Close the app fully, reopen → Account still says "Signed in to Amazon".
- If passkeys fail in the WebView, use password + 2FA instead.

## Round 2 — Page reader
- `config/selectors.json` (fetched from GitHub at launch, cached, bundled fallback); `assets/extractor.js` turns pages into JSON; health check per page kind.
- **Test:** Search "usb c cable" → 20+ items with title, price, stars and link. Account → Debug → Page reader shows fill % per field.

## Round 3 — Results screen
- Native results list, infinite scroll (loads Amazon's next page), skeleton loading, raw-page fallback with a "simplified view unavailable" banner.
- **Test:** Results scroll smoothly; airplane mode → a clear "You're offline" retry state.

## Round 4 — Ranking
- Bayesian quality, unit-price value, delivery, seller, match; sponsored removal (or tag), clone grouping ("+N similar"), preset chips, "Adjust" sliders, long-press "Why this rank", brand blocklist.
- **Test:** Same search under *Cheapest* vs *Most trusted* gives visibly different, sensible orders.

## Round 5 — Product page + signal log
- Native summary card (price, unit price, rating bars, delivery, sold by, returns), Variants / Reviews / Details tabs, variant picker. "Add to cart" taps Amazon's real button in the hidden engine and then checks the cart to confirm. Signals (opens, 20 s+ dwell, searches) logged to Room.
- **Test:** Add a product, then open the Cart tab → it's in Amazon's own cart. Account → Debug → Signals lists what was logged.

## Round 6 — Cart + checkout
- Amazon's cart page restyled with injected CSS (bigger tap targets, upsells hidden) and a pinned subtotal bar. Checkout is Amazon's page untouched, apart from the WebView's own dark mode.
- **Test:** Complete a real low-cost order end to end. The app never taps Place Order.

## Round 7 — Lists + orders import
- Amazon Lists view, local watchlist with target price and daily price check, Keepa / CamelCamelCamel links. Opt-in one-time import of your Orders pages (kept vs returned) into the signal log.
- **Test:** Account → Import order history. Then Debug → Orders shows your past orders with Kept / Returned marked correctly.

## Round 8 — Taste model
- On-device embeddings (MediaPipe Universal Sentence Encoder, downloaded by CI; built-in hashing fallback), nightly clustering into up to 8 interests, signal weights with 60-day / 180-day half-lives, start quiz, Interests screen (rename, mute, delete, never-show list).
- **Test:** After the quiz plus your order import, For You → ★ shows interest names you'd agree with.

## Round 9 — For You feed
- Candidate sources (restock, watchlist and List price drops, goes-with, new in your interests, popular in your categories, wildcard), the feed score, Fit cutoff 0.35, variety rules (no near-duplicates, max 3 cards per brand, 1 in 8 wildcard), "Why this?" reason on every card, thumbs up/down, never-show menu, page budget (40 pages max, 3–8 s apart, once a day on Wi-Fi, plus pull-to-refresh at 20 pages).
- **Test:** A week in, at least 7 of the top 10 cards are things you'd actually consider buying.

## Round 10 — Polish
- Light and dark themes, skeletons, swipe left to hide / right to watchlist, plain-language settings with one-line explanations, accessibility labels, crash log you can share from Account.
- **Test:** Use it for a week of real shopping without opening the stock app.

## Fixing breakage without a rebuild
When a field goes blank after Amazon changes a page: open `config/selectors.json` in the GitHub app, paste a snippet of the page to an AI and ask it to update the broken selector, **bump `"version"`**, commit. Reopen Cartwise (or Settings → Page-reading rules to reload now). Editing that file doesn't trigger a new APK build.
