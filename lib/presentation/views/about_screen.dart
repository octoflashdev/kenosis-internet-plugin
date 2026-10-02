import 'package:flutter/material.dart';

import '../../app_version.dart';
import '../../legal/legal_document.dart';
import '../../legal/legal_document_repository.dart';
import '../../services/external_links_service.dart';
import '../widgets/confirm_open_link.dart';
import 'legal_document_screen.dart';
import 'permissions_screen.dart';

/// Plugin app info + legal links. A plugin's About is deliberately short:
/// what this app is (a companion of the main Kenosis AI app, which it cannot
/// run without), where to get the main app, its one-sentence privacy line,
/// and the legal pages. Thin view — the legal repository + links service
/// arrive via the constructor (no service locator).
///
/// Parameterized (title/icon/privacy line/source URL) so the file stays
/// byte-identical across the two plugin apps — they are hand-synced copies.
class AboutScreen extends StatelessWidget {
  final LegalDocumentRepository legalRepository;

  /// The plugin's launcher name, e.g. "Kenosis AI - Internet Search plugin".
  final String appTitle;

  /// The plugin's identity icon (matches the status screen's header icon).
  final IconData icon;

  /// The plugin's one-sentence privacy claim, per its own manifest honesty
  /// (the internet plugin and LiveCast word it differently).
  final String privacyLine;

  /// The privacy-line box icon (matches the claim: cloud-off for the internet
  /// plugin, Wi-Fi for LiveCast).
  final IconData privacyIcon;

  /// This plugin's public source repo on GitHub (Report-bug section).
  final String sourceUrl;

  /// Opens the main app's Play Store listing after the confirm alert (the
  /// one sanctioned external link in the plugins).
  final ExternalLinksService linksService;

  const AboutScreen({
    super.key,
    required this.legalRepository,
    required this.linksService,
    required this.appTitle,
    required this.icon,
    required this.privacyLine,
    required this.sourceUrl,
    this.privacyIcon = Icons.cloud_off_outlined,
  });

  void _openLegal(BuildContext context, LegalDocument document) {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => LegalDocumentScreen(
          legalRepository: legalRepository,
          document: document,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('About')),
      body: ListView(
        children: [
          // App identity
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 24),
            child: Column(
              children: [
                Icon(icon, size: 48, color: scheme.primary),
                const SizedBox(height: 12),
                Text(appTitle, style: Theme.of(context).textTheme.headlineSmall),
                const SizedBox(height: 4),
                Text('v$appVersion',
                    style: TextStyle(color: scheme.onSurfaceVariant)),
              ],
            ),
          ),
          const Divider(height: 1),

          // Companion-app line: a plugin cannot run alone. The Play hand-off
          // is the ONE sanctioned clickable link (plugins hold the INTERNET
          // permission; it opens only after the confirm alert). The site URL
          // stays plain selectable text as the fallback.
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
            child: Text(
              'This app is a companion plugin of Kenosis AI — it can\'t run '
              'alone. Get the main app:',
              style: Theme.of(context).textTheme.bodyMedium,
            ),
          ),
          ListTile(
            leading: const Icon(Icons.shop_outlined),
            title: const Text('Kenosis AI on Google Play'),
            onTap: () => confirmOpenLink(
              context,
              linksService,
              kenosisAiPlayStoreUrl,
              title: 'Open Google Play?',
              purpose: 'the Kenosis AI Play Store page',
            ),
          ),
          const Padding(
            padding: EdgeInsets.fromLTRB(20, 0, 16, 16),
            child: SelectableText(
              'www.kenosis-ai.com',
              style: TextStyle(fontFamily: 'monospace'),
            ),
          ),

          // The plugin's one-sentence privacy posture.
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
            child: Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              decoration: BoxDecoration(
                color: scheme.primaryContainer,
                borderRadius: BorderRadius.circular(10),
              ),
              child: Row(
                children: [
                  Icon(privacyIcon, size: 20, color: scheme.onPrimaryContainer),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      privacyLine,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color: scheme.onPrimaryContainer,
                            fontWeight: FontWeight.w600,
                          ),
                    ),
                  ),
                ],
              ),
            ),
          ),
          const Divider(height: 1),

          // Legal pages
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
            child: Text('Legal',
                style: Theme.of(context).textTheme.labelSmall?.copyWith(
                    color: scheme.onSurfaceVariant)),
          ),
          ListTile(
            dense: true,
            leading: const Icon(Icons.privacy_tip_outlined),
            title: Text(LegalDocument.privacyNotice.title),
            onTap: () => _openLegal(context, LegalDocument.privacyNotice),
          ),
          ListTile(
            dense: true,
            leading: const Icon(Icons.lock_outline),
            title: const Text('App permissions'),
            onTap: () => Navigator.push(
              context,
              MaterialPageRoute(builder: (_) => const PermissionsScreen()),
            ),
          ),
          ListTile(
            dense: true,
            leading: const Icon(Icons.gavel_outlined),
            title: Text(LegalDocument.license.title),
            onTap: () => _openLegal(context, LegalDocument.license),
          ),
          const Divider(height: 1),

          // Report bug — PLAIN TEXT only (no clickable URLs, repo rule).
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
            child: Text('Report bug',
                style: Theme.of(context).textTheme.labelSmall?.copyWith(
                    color: scheme.onSurfaceVariant)),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
            child: Text(
              'Found something broken? Reach us here:',
              style: Theme.of(context)
                  .textTheme
                  .bodySmall
                  ?.copyWith(color: scheme.onSurfaceVariant),
            ),
          ),
          const Padding(
            padding: EdgeInsets.fromLTRB(20, 0, 16, 4),
            child: SelectableText(
              'octoflash.sup@gmail.com',
              style: TextStyle(fontFamily: 'monospace'),
            ),
          ),
          // This plugin's own source tree (path injected per plugin).
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 16, 24),
            child: SelectableText(
              sourceUrl.replaceFirst('https://', ''),
              style: const TextStyle(fontFamily: 'monospace'),
            ),
          ),
        ],
      ),
    );
  }
}
