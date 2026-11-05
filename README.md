# Cartwise

A personal Android app that puts a calmer UI and its own For You feed on top of your own Amazon account. It drives the real Amazon mobile site in a hidden WebView under your login, redraws what it reads in native Compose screens, and leaves cart, checkout and Place Order on Amazon's own pages.

**Personal use only.** Not published, not affiliated with any retailer, no retailer name or logo in the app.

## Get the APK
Every push to `main` builds a signed APK in GitHub Actions. On your phone, open **Releases → latest** and install `Cartwise.apk`. The signing key in `keystore/` is deliberately non-secret so each build installs over the last one.

## Layout
| Path | What it is |
| --- | --- |
| `config/selectors.json` | Page-reading rules. Edit from the GitHub app to fix blank fields; bump `version`. |
| `app/src/main/assets/extractor.js` | The bundled page reader (also holds the never-press-Buy guard). |
| `app/src/main/java/.../engine` | Hidden WebView engine, parsing, Amazon calls. |
| `.../rank` | Search ranking and clone grouping. |
| `.../taste` | Signals, embeddings, interest clusters, order import, quiz. |
| `.../feed` | For You feed builder, background workers, price alerts. |
| `.../ui` | Compose screens. |
| `INSTRUCTIONS.md` | Rules for any AI that works on this repo. |
| `CHANGELOG.md` | What each round built and how to test it on the phone. |

The `ci-logs` branch holds the last build log so a phone-only workflow can read compile errors without opening Actions logs.
