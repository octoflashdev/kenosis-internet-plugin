# Plan: Plugin-local page viewer + future login scraping

Status: PLAN (not implemented). Drafted 2026-10-03 after the +77 browser_fetch
refusal investigation. Two phases; Phase 1 is the enabler for Phase 2.

## Phase 1 — Local page viewer in the plugin UI

Goal: the plugin's own UI can show what the service actually fetched — a local
webview rendering of the page, offline-first.

### Data flow (mostly exists already)
- `InternetPluginService` already carries the stripped-HTML reader sidecar
  (`html`) in `FetchOutcome`, the success envelope (`data.html`,
  `InternetPluginService.kt:583-584`, `:1292-1305`), and every fetch-log entry
  (`:696`, `:1010`, `:1170`).
- Persist snapshots: alongside `fetch_log.json`, add `pages/<log-id>.html`
  (plugin-private files dir). Retention: keep last ~20 pages / ~25 MB, prune
  oldest on write; prune on `clearFetchLog` too.

### UI
- Each "Requested URLs" row in the plugin home gets a **View** action →
  viewer screen (new Flutter view) hosting `webview_flutter` (already a
  dependency, same stack as the captcha Solve-now webview).
- Load via `WebViewController.loadHtmlString(html, baseUrl: finalUrl)` — a
  local snapshot render, not a live navigation.
  - JS **disabled** in the viewer (it is a snapshot reader, not a browser).
  - Navigation clicks: blocked in-viewer; long-press/copy only. (Optional
    later: "open externally" behind `confirm_open_link`.)
  - Subresources: **offline by default** (no image/CSS fetch — privacy +
    speed); per-view "Load images" toggle flips it for that render only.
- Optional Phase 1b (host side): a chat document bubble "view source page"
  action deep-links into the plugin viewer (host `confirmOpenLink` alert
  pattern — see no-clickable-urls rule).

### Tests
- Page-store: retention prune, JSON log round-trip, clearFetchLog wipes pages.
- Viewer widget test: renders snapshot HTML, JS off, navigation blocked.

## Phase 2 — Login via plugin, scrape behind login (future)

Principle: **login is explicit, per-site, opt-in.** The +75 default —
"fetch-and-forget, no cookies at rest", first-party cookies DENIED outside a
pending captcha check — stays untouched for all non-opted traffic.

1. **Entry** — viewer screen gains "Log in to <host>…" → login session opens a
   SEPARATE webview (JS ON, first-party cookies ON for this session only,
   third-party OFF). The user types credentials directly into the site; plugin
   code never sees them.
2. **Capture** — user confirms "I'm logged in" → enumerate `CookieManager`
   cookies for that host → store per-domain records `{host, cookies, ts,
   expiry}` in a **Keystore-encrypted** plugin-private store (AES-256-GCM,
   mirroring the host's at-rest doctrine — never plain SharedPreferences).
3. **Fetch-time injection** — `WebViewPageFetcher` checks the target host
   against stored domains: on a match, install those cookies on the process
   `CookieManager` for the duration of THAT fetch, then remove them. Anonymous
   fetch-and-forget posture intact for everything else; third-party stays off;
   prefer session-scoped cookies (no disk cookies in the webview jar itself).
4. **UX** — "Logged-in sites" list in the plugin drawer; per-site "Log out"
   (delete record; attempt the site's logout URL). Expired session → fetch
   returns logged-out content; a login-wall heuristic marks the envelope so
   the host can coach "session expired — log in again via the plugin".
5. **Privacy stance** — cookies never leave the plugin sandbox, never synced
   to the host; the host just receives better page text. Per-site ToS is the
   user's responsibility (documented in the plugin About screen).

### Risks / sequencing notes
- The process `CookieManager` is global: a login webview and a headless fetch
  must not race. Serialize login sessions against the existing fetch
  discipline (same pattern as "one fetch() session per deliver()").
- DataDome-class walls may flag automated logged-in fetches; captcha flow
  (visible solve webview) must work identically in logged-in mode. Test captcha
  on the RELEASE build only (debuggable webviews are refused).
- Session expiry: degrade to logged-out content is acceptable; never
  auto-retry credentials (plugin never holds them).

## Decisions still open
- Snapshot format: the stripped reader sidecar vs full raw HTML (raw renders
  true-to-site but bloats storage; sidecar is already sanitized by
  WEB_SAFELIST).
- Whether the host ever gets a "view source" deep-link (Phase 1b) or the
  viewer stays plugin-internal.
