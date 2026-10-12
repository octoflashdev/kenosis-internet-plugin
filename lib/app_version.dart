/// The plugin app's user-facing version, shown in About.
///
/// HAND-SYNCED with `pubspec.yaml`'s `version:` — the host bump script
/// (the version-bump script) manages only the host app, never the plugins.
/// When bumping the plugin, change BOTH lines in the same commit.
/// (Recovered 2026-10-12: this line had drifted to 1.9.2+2 while the pubspec
/// was already 1.11.0+3 — re-synced at the 1.12.0+4 bump.)
const String appVersion = '1.12.0+4';