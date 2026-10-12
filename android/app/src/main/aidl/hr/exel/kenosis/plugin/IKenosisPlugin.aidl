// Kenosis plugin contract — binder IPC between the Kenosis host app (offline,
// no INTERNET) and a plugin app (the only component with network access).
//
// This file is copied IDENTICALLY into both apps:
//   host:   the Kenosis host app's mirrored copy
//   plugin: this file
// Same package + interface name on both sides → AIDL generates binder-compatible
// Stub/Proxy pairs. No shared Gradle module for Phase 1 (one plugin + one tool);
// extract a :plugin-contract module only when a second plugin arrives.
//
// No cancel() — dead weight for Phase 1 (both apps are ours); add it when
// multi-step cancellation is real.

package hr.exel.kenosis.plugin;

// Interface-typed parameter — AIDL needs an explicit import even in-package.
import hr.exel.kenosis.plugin.IKenosisProgressListener;

interface IKenosisPlugin {
    /// Returns a JSON manifest of the plugin:
    /// {"name":"…","version":"…","tools":[{"name":"…","description":"…",
    ///   "parameters":{"url":{"type":"string","description":"…"}}}]}
    /// Called by the host once per attach (after bind); the tool list is never
    /// duplicated in the plugin's AndroidManifest meta-data — this is the ONE
    /// representation.
    String getManifest();

    /// Invokes one tool. [toolName] must appear in the manifest; [paramsJson]
    /// is a JSON object of the tool's parameters.
    /// Returns JSON: {"ok":true,"data":{…}} or {"ok":false,"error":"…"}.
    /// For text-bearing tools, data.text is ALREADY truncated by the plugin to
    /// the observation budget (~4–6 KB) — the host enforces a second cap but
    /// must not rely on it for transport size.
    String invoke(String toolName, String paramsJson);

    /// Names the plugin's own UI for a presentation switch.
    ///
    /// The host still owns the tool round that pulls data (`invoke` runs in
    /// the host process and returns the observation) — this call only decides
    /// WHICH WINDOW the user is looking at. "Search in Plugin" resolves the
    /// query approval as SEND and then switches windows, so the user watches
    /// the visible tabs the search opens (the Firefox-style "open the search
    /// here" affordance). Not a tool: never in the manifest, never
    /// model-callable.
    ///
    /// The plugin must NOT startActivity() itself: it is a bound background
    /// service with no visible window, so Android 10+ blocks the launch
    /// (BAL_BLOCK). This method only returns the component to show; the
    /// HOST — the visible app the user just tapped in — performs the
    /// startActivity.
    ///
    /// Returns JSON: {"ok":true,"component":"pkg/cls"} or {"ok":false,"error":"…"}.
    String presentUi();

    /// Registers a one-way progress listener (IKenosisProgressListener, same
    /// package — copied identically into both apps). The host calls this once
    /// per bind, right after the proxy is set; the plugin then pushes display
    /// progress events during tool rounds (see the listener file for the
    /// payload shape and the seq/tMs semantics).
    ///
    /// SKEW: an OLD plugin (Stub generated before this method existed) throws
    /// RemoteException on this unknown transaction — the HOST must catch and
    /// degrade (a plugin without a listener is fully functional; only the
    /// live step bubble is host-side-only). A NEW plugin against an OLD host
    /// simply never gets called: its listener stays null and progress emits
    /// are no-ops.
    ///
    /// Registration REPLACES any previous listener (one listener per plugin —
    /// the host re-registers on every bind/rebind; a rebind delivers a new
    /// stub). The plugin must tolerate a null/dead listener on every emit.
    oneway void setProgressListener(in IKenosisProgressListener l);
}