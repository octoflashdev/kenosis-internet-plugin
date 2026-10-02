/// The one external URL the shell may open: the main app's Play Store
/// listing (drawer hand-off + About).
///
/// Link-opening rule: the PLUGINS hold the INTERNET permission — that is the
/// plugin model (the host stays offline; the plugin earns its own network) —
/// so unlike the host app (whose no-clickable-URL rule keeps it
/// air-gap-enforced), a plugin may open links. Every launch is
/// user-initiated AND always behind a confirm alert (see
/// `confirm_open_link.dart`); everything except this Play hand-off renders
/// as plain selectable text.
const String kenosisAiPlayStoreUrl =
    'https://play.google.com/store/apps/details?id=hr.exel.kenosis_ai';

/// Opens external URLs with the system handler (the Play Store app
/// intercepts its own deep links; a browser is the fallback). Best-effort:
/// returns false when nothing could handle the URL, never throws. Injected
/// through constructors (the plugin apps have no service locator).
abstract class ExternalLinksService {
  Future<bool> open(String url);
}
