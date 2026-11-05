# Cartwise — builder instructions

You are building Cartwise, a personal Android app that gives a cleaner UI over
the user's own Amazon account, with its own personal For You feed. The user
works from a phone only: every build must compile in GitHub Actions and produce
a signed APK artifact. Never assume Android Studio or a local machine.

## Stack
- Kotlin, Jetpack Compose, Material 3; single-activity, Navigation Compose
- minSdk 26, targetSdk 34
- Room (signals, items, clusters, watchlist, blocklist), DataStore (settings),
  WorkManager (nightly clustering, daily feed refresh)
- MediaPipe Text Embedder (com.google.mediapipe:tasks-text) for on-device item vectors
- One shared android.webkit.WebView "engine", off-screen, used to load amazon.com mobile pages
- Page reading = JavaScript injected after load that returns JSON via a JavascriptInterface
- CSS selectors live in /config/selectors.json; fetch from the repo's raw GitHub URL at launch,
  cache it, ship a bundled copy as fallback
- Package: com.emotionalleaves.cartwise

## Hard rules (never break)
1. Never read, store or autofill passwords, 2FA codes or payment details.
2. Never programmatically tap Place Order, Buy Now or any payment confirmation.
3. No script or CSS injection on sign-in, payment or order-confirmation pages,
   except a colours-only dark-mode stylesheet.
4. No affiliate tags or URL rewriting.
5. Background page loads only for the feed refresh and watchlist check:
   max 40 pages per refresh, 3-8 s apart, once/day on unmetered network,
   plus user pull-to-refresh.
6. No Amazon name or logo anywhere in the app.
7. All taste data stays on the phone. No analytics, no uploads.
8. The feed optimizes for items the user would buy and keep, never for taps
   or time in app.

## Every round
- Only build what the round asks. Don't refactor earlier rounds unless needed.
- If extraction returns empty fields, fall back to showing the real page.
- Update CHANGELOG.md with what changed and how to test it on the phone.
- The GitHub Actions build must pass before you finish.
