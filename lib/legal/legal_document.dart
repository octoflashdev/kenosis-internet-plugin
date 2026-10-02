/// The legal documents bundled with the plugin app, shown in-app (offline).
///
/// A plugin ships no Terms document — the host app governs the ecosystem;
/// a plugin carries its own Privacy Notice + the open-source license only.
enum LegalDocument {
  privacyNotice('Privacy Notice'),
  license('Open-source licenses');

  const LegalDocument(this.title);

  /// Human-readable title for the AppBar / drawer entry.
  final String title;
}
