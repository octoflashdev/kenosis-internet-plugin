import 'package:flutter/material.dart';

/// Lists every Android permission THIS plugin's manifest requests, with a
/// plain-English reason for each. The list is short and honest — it must
/// match the merged manifest exactly (INTERNET + POST_NOTIFICATIONS; see
/// `android/app/src/main/AndroidManifest.xml`). Read-only view.
///
/// Unlike the sibling LiveCast plugin this app IS the ecosystem's network
/// component — the INTERNET row is its whole job, and every request it makes
/// is logged on the home screen. No license section here: the licenses view
/// is a separate page (drawer / About → Open-source licenses).
class PermissionsScreen extends StatelessWidget {
  const PermissionsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('App permissions')),
      body: ListView(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        children: [
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Text(
              'Every permission the installed app requests, and why.',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
          _sectionHeader('Permissions we request', theme),
          _item(
            context,
            Icons.public,
            'INTERNET',
            'The only network permission in the Kenosis ecosystem — this app '
                'is the component that has it, so the main Kenosis AI app '
                'itself can stay fully offline. It downloads exactly the '
                'pages you (or the main-app chat) request — nothing else. '
                'Every request is logged on this app\'s home screen, so you '
                'can always see what was fetched.',
          ),
          _item(
            context,
            Icons.notifications_active_outlined,
            'POST_NOTIFICATIONS',
            'Show a local notification when a search engine asks for a human '
                'check while a fetch runs in the background — so you know to '
                'open this app and complete the check. Runtime permission on '
                'Android 13+, asked once when you open the app; a denial '
                'silently degrades to the in-app banner only.',
          ),
          const SizedBox(height: 24),
          _footer(context, theme),
          const SizedBox(height: 24),
        ],
      ),
    );
  }

  Widget _sectionHeader(String title, ThemeData theme) {
    return Padding(
      padding: const EdgeInsets.only(top: 8, bottom: 4),
      child: Text(
        title,
        style:
            theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600),
      ),
    );
  }

  Widget _item(
    BuildContext context,
    IconData icon,
    String permId,
    String reason,
  ) {
    final theme = Theme.of(context);
    return ListTile(
      leading: Icon(icon, size: 22),
      title: Text(
        permId,
        style: theme.textTheme.bodySmall?.copyWith(
          fontWeight: FontWeight.w600,
          fontFamily: 'monospace',
        ),
      ),
      subtitle: Text(reason, style: theme.textTheme.bodySmall),
      contentPadding: const EdgeInsets.symmetric(horizontal: 4),
      dense: true,
    );
  }

  Widget _footer(BuildContext context, ThemeData theme) {
    // Open-source note + source link as PLAIN TEXT only — never a tappable
    // link (repo rule: no URL launching anywhere in the ecosystem).
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            'This app is fully open source — every line it runs is public '
            'and auditable.',
            textAlign: TextAlign.center,
            style: theme.textTheme.bodySmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
              fontStyle: FontStyle.italic,
            ),
          ),
          const SizedBox(height: 6),
          const Text(
            'github.com/octoflashdev/kenosis-internet-plugin',
            textAlign: TextAlign.center,
            style: TextStyle(
              fontFamily: 'monospace',
              fontSize: 12,
            ),
          ),
        ],
      ),
    );
  }
}
