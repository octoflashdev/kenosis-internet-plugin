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
}