import 'package:flutter/material.dart';

import '../../legal/legal_document.dart';
import '../../legal/legal_document_repository.dart';
import '../../services/external_links_service.dart';
import '../widgets/theme_scope.dart';
import 'about_screen.dart';
import 'legal_document_screen.dart';
import 'permissions_screen.dart';

/// The plugin app's navigation drawer — the host's ChatHistoryDrawer shape,
/// trimmed to a plugin's surface: a header row with the light/dark/auto
/// theme switcher (copied from the host), a "Get Kenosis AI" hand-off, and
/// the legal pages. Thin view — the legal repository + links service arrive
/// via the constructor (no service locator); the theme listenable + setter
/// come from [ThemeScope].
///
/// Parameterized by [appTitle] + a pre-built [aboutScreen] so the file stays
/// byte-identical across the two plugin apps (hand-synced copies); main.dart
/// supplies each plugin's identity.
class PluginDrawer extends StatelessWidget {
  final LegalDocumentRepository legalRepository;

  /// The drawer header title — the plugin's short name.
  final String appTitle;

  /// The plugin's About screen, pre-built by the app widget (which owns the
  /// plugin's icon / privacy-line identity).
  final AboutScreen aboutScreen;

  /// Opens the main app's Play Store listing after the confirm alert (the
  /// one sanctioned external link in the plugins).
  final ExternalLinksService linksService;

  const PluginDrawer({
    super.key,
    required this.legalRepository,
    required this.linksService,
    required this.appTitle,
    required this.aboutScreen,
  });

  @override
  Widget build(BuildContext context) {
    return Drawer(
      child: SafeArea(
        child: Column(
          children: [
            // Header: plugin name + theme switcher (host drawer pattern).
            Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  Expanded(
                    child: Text(appTitle,
                        style: Theme.of(context).textTheme.titleLarge),
                  ),
                  const _ThemeModeSwitcher(),
                ],
              ),
            ),
            const Divider(height: 1),
            // Get the main app — a plugin can't run alone.
            ListTile(
              leading: const Icon(Icons.download_outlined),
              title: const Text('Get Kenosis AI (main app)'),
              onTap: () => _showGetMainApp(context),
            ),
            const Divider(height: 1),
            ListTile(
              leading: const Icon(Icons.info_outline),
              title: const Text('About'),
              onTap: () => _push(context, aboutScreen),
            ),
            ListTile(
              leading: const Icon(Icons.privacy_tip_outlined),
              title: const Text('Privacy Notice'),
              onTap: () => _push(
                context,
                LegalDocumentScreen(
                  legalRepository: legalRepository,
                  document: LegalDocument.privacyNotice,
                ),
              ),
            ),
            ListTile(
              leading: const Icon(Icons.lock_outline),
              title: const Text('App permissions'),
              onTap: () => _push(context, const PermissionsScreen()),
            ),
            ListTile(
              leading: const Icon(Icons.gavel_outlined),
              title: const Text('Open-source licenses'),
              onTap: () => _push(
                context,
                LegalDocumentScreen(
                  legalRepository: legalRepository,
                  document: LegalDocument.license,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// Close the drawer first (host pattern), then push the route.
  void _push(BuildContext context, Widget route) {
    Navigator.pop(context); // close drawer
    Navigator.push(context, MaterialPageRoute(builder: (_) => route));
  }

  /// Get-the-main-app hand-off: a WARN dialog first — only an explicit OK
  /// opens the main app's Play Store listing (the plugins hold INTERNET, so
  /// a user-confirmed store launch is sanctioned; see the class doc). The
  /// site URL stays plain selectable text as the no-Play-Store fallback.
  Future<void> _showGetMainApp(BuildContext context) async {
    // Capture before the drawer pops — this context unmounts with it, the
    // messenger outlives it (used for the launch-failed SnackBar below).
    final messenger = ScaffoldMessenger.of(context);
    Navigator.pop(context); // close drawer
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        scrollable: true,
        title: const Text('Get Kenosis AI'),
        content: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
              'This app is a companion plugin — it can\'t run alone. The '
              'main Kenosis AI app is on Google Play.',
            ),
            const SizedBox(height: 12),
            const Text('Tap OK to open its Play Store page.'),
            const SizedBox(height: 12),
            const SelectableText(
              'www.kenosis-ai.com',
              style: TextStyle(fontFamily: 'monospace'),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('OK'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    final ok = await linksService.open(kenosisAiPlayStoreUrl);
    if (!ok) {
      messenger.showSnackBar(const SnackBar(
        content: Text(
          'Could not open the Play Store — search "Kenosis AI" or visit '
          'www.kenosis-ai.com',
        ),
      ));
    }
  }
}

/// Light/dark/auto theme switcher — copied from the host's drawer
/// (`chat_history_drawer.dart`). Reads the current mode + write callback
/// from [ThemeScope] (no service-locator lookup) so it stays a thin view. A
/// compact [PopupMenuButton] whose icon reflects the active mode; selecting
/// an entry persists the choice (via [ThemeService.setMode]) and re-themes
/// the whole app — the [MaterialApp] subscribes to the same listenable and
/// rebuilds.
class _ThemeModeSwitcher extends StatelessWidget {
  const _ThemeModeSwitcher();

  @override
  Widget build(BuildContext context) {
    final scope = ThemeScope.of(context);
    final accent = Theme.of(context).colorScheme.primary;
    return ListenableBuilder(
      listenable: scope.modeListenable,
      builder: (context, _) {
        final mode = scope.modeListenable.value;
        return PopupMenuButton<ThemeMode>(
          icon: Icon(_modeIcon(mode), size: 22, color: accent),
          tooltip: 'Theme',
          onSelected: scope.onChanged,
          itemBuilder: (context) => [
            for (final m in ThemeMode.values)
              PopupMenuItem<ThemeMode>(
                value: m,
                child: Row(
                  children: [
                    Icon(_modeIcon(m), size: 20),
                    const SizedBox(width: 12),
                    Text(_modeLabel(m)),
                  ],
                ),
              ),
          ],
        );
      },
    );
  }

  IconData _modeIcon(ThemeMode m) {
    switch (m) {
      case ThemeMode.light:
        return Icons.light_mode_outlined;
      case ThemeMode.dark:
        return Icons.dark_mode_outlined;
      case ThemeMode.system:
        return Icons.brightness_auto_outlined;
    }
  }

  String _modeLabel(ThemeMode m) {
    switch (m) {
      case ThemeMode.light:
        return 'Light';
      case ThemeMode.dark:
        return 'Dark';
      case ThemeMode.system:
        return 'Auto';
    }
  }
}
