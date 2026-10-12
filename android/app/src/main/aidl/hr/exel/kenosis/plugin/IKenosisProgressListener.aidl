// Kenosis plugin contract — binder IPC between the Kenosis host app (offline,
// no INTERNET) and a plugin app (the only component with network access).
//
// This file is copied IDENTICALLY into both apps:
//   host:   android/app/src/main/aidl/hr/exel/kenosis/plugin/IKenosisProgressListener.aidl
//   plugin: plugins/internet/android/app/src/main/aidl/hr/exel/kenosis/plugin/IKenosisProgressListener.aidl
// Same package + interface name on both sides → AIDL generates binder-compatible
// Stub/Proxy pairs (checked byte-identical by the plugin-contract CI check).
//
// VERSION SKEW / DEGRADE (both directions are safe by design):
//  - New host → old plugin: the plugin's Stub predates setProgressListener, so
//    the host's registration call throws RemoteException (unknown transaction).
//    The host catches it ONCE, logs, and degrades silently — the round still
//    works, only the live step bubble shows host-side steps.
//  - New plugin → old host: the old host never registers a listener, so the
//    plugin's progress() helper sees null and is a no-op.
// A listener whose process died is cleaned up by the binder runtime; the
// plugin emits through a @Volatile reference and never blocks on a dead peer
// (oneway + try/catch(Throwable) on every emit).
package hr.exel.kenosis.plugin;

oneway interface IKenosisProgressListener {
    /**
     * One progress event from the plugin: a JSON object
     * {"seq":N,"code":"…","tMs":ms,"params":{…}}.
     *
     * [seq] is a monotonically increasing per-plugin counter — the host may
     * use it to drop stale/out-of-order events. [tMs] is plugin-relative
     * (SystemClock.elapsedRealtime()) for logcat correlation only; display
     * timestamps come from the host's own arrival clock (no clock-domain
     * mixing). [code] is a stable event name (e.g. "serp_links",
     * "t1_result"); [params] carries the code-specific fields. Payloads are
     * display-only progress facts — they NEVER carry page text or query
     * content beyond short structural labels, and never enter the model
     * context.
     */
    void onProgress(String json);
}
