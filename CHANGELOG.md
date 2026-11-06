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

## Hardening (after round 10)
- Unit tests now run in CI before every APK build: parsing (prices, unit prices, delivery dates, ratings), ranking presets and clone grouping, the sensitive-page rules behind hard rule 3, and the page reader's output on mock Amazon pages (fixtures in `app/src/test/resources/fixtures`, regenerated with `tools/make-fixtures.js`). A failing test blocks the build.
- Orders are read with Amazon's desktop layout, which reliably links each item to its product page. Which page kinds use it is set by `engine.desktopKinds` in `config/selectors.json`.
- The engine re-reads a page once if it redirected itself while being read, instead of timing out.
- Mobile cart URL (`/gp/aw/c`) is recognized for the cart restyle and pinned total.
- Add to cart runs as one uninterrupted engine session, so a background feed refresh can't navigate away mid-add.
- The hidden engine refuses to leave Amazon's domains.
- **Debug → Page reader → "Share this page for fixing"** shares a trimmed copy of the page's HTML, the current rules and the field health. Paste it to an AI, put its answer in `config/selectors.json`, bump `version`, commit.
- Rules file is now version 2.

## Self-check and runtime tests
- **Account → Run self-check** runs the first on-phone tests in one tap: signed in, rules loaded, search reads 20+ results (title, price, stars, photo, delivery fill %), a product page reads, the cart reads, your Lists read, and whether the on-device model loaded. Anything amber or red has "Share this page for fixing" and "Open page" right under it. The share icon copies the whole result list.
- Robolectric tests now run in CI on a simulated Android 14 phone: every screen is drawn (For You empty and with shelves, Search, result card and Why this rank, product tabs, watchlist, Interests, quiz, Settings, Debug tabs, Account, Self-check, Cart, raw page), and the taste model runs for real (quiz to interests, kept items to named interests, fit ordering, wipe). Test reports go to the `ci-logs` branch under `tests/`.
- Every search result now has a visible "Why this rank" button (long-press still works), and results animate when hidden or regrouped.
- Account's sign-in status refreshes when you come back to the screen instead of polling.

## Photos, phone layouts, self-healing reader, infinite scroll
- **Photos:** Amazon puts grey/transparent placeholder pixels in `src` until a photo scrolls into view. The reader now picks the real photo from `srcset`, `data-src`, `data-a-dynamic-image` or the card's HTML, skips placeholders, and asks Amazon's image server for a sharp ~600 px copy. If a photo truly can't load, the tile shows a small cart icon instead of a blank square.
- **Titles:** on phone-layout search pages the brand sits in its own heading; the title is now the longest heading (brand kept separately), so cards no longer say just "TCL" or "LG".
- **Self-healing product pages:** every key field has a fallback that doesn't depend on selectors. Category: breadcrumbs → Best Sellers Rank ("#1,234 in Kitchen & Dining … #12 in Burr Coffee Grinders") → "See Top 100 in" links → the page title. Title, photos, price, rating, review count, delivery, sold by, ships from, returns and the Add to cart button have text-based fallbacks too. Debug → Page reader lists any field that was "found another way".
- **Fix reports that fit:** "Share this page for fixing" now sends ~15k characters aimed at the missing fields (the page blocks that mention them, plus an outline of the main content), with Amazon's nav bar, ads, scripts and tracking pixels stripped. The old report blew past the share sheet's limit and only contained the nav bar.
- **Infinite scroll in search:** keeps loading Amazon's next page until it returns nothing new (up to 40 pages), instead of trusting a "next" link the phone layout doesn't have. Each page is ranked on its own and appended, so nothing above you reshuffles. Shows a retry row on errors and an end-of-results line.
- **Infinite For You:** under the shelves, "More for you" keeps going as you scroll: it rotates through your interests (best match, newest, most reviewed, deeper pages each round) with a wildcard every 4th page, scores everything with the same taste model and Fit cutoff, keeps max 3 cards per brand and 1 in 8 wildcards, and loads one page at a time at least 3 seconds apart.

## Faster loading
- Pages are read as soon as their HTML is parsed instead of after every image, ad and script finishes; the rest of the page is then cancelled. Product pages get one short second look only if price, delivery or photos were still filling in.
- Ads, trackers, telemetry, web fonts and video are skipped in the hidden browsers, and on ordinary visible pages like the cart. Sign-in, payment and checkout pages are never touched.
- Two hidden browsers: one for what you tap, one for feed refreshes, "More for you", price checks and order import, so your taps never wait behind background work.
- The connection to Amazon is opened when the app starts, so the first search doesn't pay for it.
- Search, product, best-seller and Lists pages are kept for a few minutes: going back or reopening is instant. Price checks and the self-check always read fresh.
- Tapping a product shows its photo, title, price and stars instantly while delivery, seller and options load.
- Photos are fetched at the size they're shown, kept in a 200 MB disk cache, and fade in.
- Infinite scroll starts loading the next page well before you reach the bottom.

## Outlet tab, discounts, prefetch
- **Outlet tab** (between Search and Lists): *For you* finds Outlet and like-new deals that match your interests, scored by the same taste model as the feed; *Outlet* (overstock, new condition), *Like new* (open-box/used resold by Amazon) and *Deals* (today's deals) are ranked with "Biggest discount" by default. Search within any section, sort with the chips, long-press or tap the heart to watch, scroll forever. The section addresses live in `config/selectors.json` (`urls.outlet`, `urls.resale`, `urls.deals`), so if one stops working it can be fixed from the GitHub app.
- **Discounts:** the reader picks up the struck-through List/Typical/Was price and "% off" badges on search results, best-seller tiles and product pages. Cards show the % off and the old price; product pages say "Save X% ($Y)".
- **Discount ranking:** a sixth ranking signal, *Discount* (50%+ off scores full), a *Biggest discount* preset, and an *On sale* chip on every search that keeps only items at least 5% off. "Why this rank" and "Adjust" include it.
- **Prefetch:** after a results page shows, the background browser quietly reads the next page and the top 3 products, so scrolling and tapping usually open instantly from the cache.
- Rules file is now version 3.

## Fast path, photos, emulator tour
- **Pages are no longer run in a browser to be read.** Search, product, best-seller, Lists and Orders pages are downloaded as plain HTML with your Amazon cookies and parsed offline, with Amazon's scripts stripped out. That removes almost all of the 6–7 seconds a page took before. The live hidden browser is only used if the fast path is blocked (captcha) or comes back empty, and for Add to cart.
- **Photos:** the raw HTML carries the real photo addresses; the grey placeholders you saw are inserted by Amazon's scripts, which no longer run. 
- **Refresh buttons** now run right away in the app and say what happened ("checked N pages; nothing new cleared the bar"). The Cart reload really reloads the page.
- **Five tabs** (For You, Search, Outlet, Lists, Cart) with a count badge on Cart; your account moved to the top-right of every tab.
- **Calmer colors:** the brown accents are gone; amber is only used for discount badges. Result cards show a small ⓘ for "Why this rank" instead of a text button.
- **Emulator tour:** pushing to the `tour` branch installs the app on an Android 14 emulator in GitHub Actions, drives every screen against live Amazon, and publishes screenshots, the engine timing log, reader health and photo addresses to the `tour-results` branch.

## Verified on the emulator
- Search results now render with real photos, titles, prices, struck list prices and discount badges (seen on the emulator against live Amazon).
- Pages read in 1–2 s via the fast path (was 6–7 s). If Amazon answers with its "Sorry, something went wrong" page, the app retries and then falls back to the live browser instead of showing that page.
- Best-seller pages are read in desktop layout (the phone layout only lists product IDs). Rules file is version 4.
- Removed the list fade-in animation: it hid rows in the test harness and added nothing.

## Design pass
- One product tile everywhere (Search, Outlet, For You): photo first, discount tag on the photo, two-line title, bold price with the old price struck, a quiet "4.4 ★ 824" line and one delivery/reason line. Two-column grid.
- Sorting is one "Best overall ▾" control plus an "On sale" toggle. Long-press a tile for watch, hide, hide brand, near-identical listings and "Why this rank".
- Product page: swipeable photo carousel, brand/title/price block, a facts card, Options/Reviews/Details tabs, and a sticky Add to cart bar (the bottom tabs hide on this screen).
- Type scale and spacing set deliberately; deeper dark palette; amber used only for discounts.
- Debug builds have a `demo` route with sample data so the design can be reviewed on the emulator even when Amazon rate-limits it.

## Faster refresh
- A manual refresh (button or pull) now reads 10 pages with ~0.6 s between them instead of 20 pages at 3–8 s, and each shelf appears as soon as its source is read rather than all at the end. Expect the first shelf in a few seconds and the whole refresh in roughly 20–30 s. The automatic nightly refresh keeps the slow, polite pace.

## Even faster
- Pages are fetched over one pooled HTTP/2 connection to Amazon (opened at launch), so there's no new TLS handshake per page.
- Fetches no longer wait in line: downloads run in parallel and only the short parse step takes turns. Pre-loading the next results page and the top products now happens all at once.
- Manual feed refresh gap cut to 0.25 s between pages.

## Fixing breakage without a rebuild
When a field goes blank after Amazon changes a page: open `config/selectors.json` in the GitHub app, paste a snippet of the page to an AI and ask it to update the broken selector, **bump `"version"`**, commit. Reopen Cartwise (or Settings → Page-reading rules to reload now). Editing that file doesn't trigger a new APK build.
