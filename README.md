# Kenosis Internet Search

The network plugin for **[Kenosis AI](https://play.google.com/store/apps/details?id=hr.exel.kenosis_ai)** — an offline-first, on-device AI assistant.

## What this is

Kenosis AI has **no `INTERNET` permission** — the host app cannot touch the
network. Plugins are the ecosystem's **only components with network access**;
this is the first of them (a LiveCast plugin is in preparation). It is a
separate Android app the host discovers, binds over a **local AIDL binder**,
and calls to fetch live web content. The host and plugins talk only over the
binder, never over a socket.

Its launcher screen is a **live, transparent log of every request it makes**
on the host's behalf — the search-engine request itself (the query you asked,
encoded in the request URL) is logged alongside every result page it fetched —
open-source transparency so you can see exactly what was requested. Tap a row
for details.

## Tools

- **`web_search`** — search the public web, return a text observation. The host
  picks the engine (`google` | `duckduckgo` | `qwant`).
- **`browser_fetch`** — fetch a single URL and return its text (HTTP + HTML→text,
  with a WebView fallback for JS pages).

## Get the main app

➡️ **[Kenosis AI on Google Play](https://play.google.com/store/apps/details?id=hr.exel.kenosis_ai)**

The host app is distributed separately and is not covered by this repository's
license.

## Build

Flutter app (not a shared package). The Gradle wrapper is committed.

```bash
flutter pub get
flutter build apk --debug     # debug build, .debug applicationId suffix
```

## License

**Apache License 2.0** — see [LICENSE](LICENSE). Dependencies: OkHttp 4.12.0
(Apache-2.0), jsoup 1.18.1 (MIT). No copyleft code is linked into the APK.