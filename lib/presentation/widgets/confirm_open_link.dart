import 'package:flutter/material.dart';

import '../../services/external_links_service.dart';

/// Confirm-then-open — the plugins' link-opening gate. A plugin may launch
/// external links (it holds the INTERNET permission; the offline host may
/// not), but NEVER directly: this shows an alert naming what will open, and
/// only OK proceeds to [ExternalLinksService.open]. A failed launch (no
/// Play Store / no browser on the device) degrades to a SnackBar, never a
/// crash. Thin view helper — the service arrives as a parameter.
Future<void> confirmOpenLink(
  BuildContext context,
  ExternalLinksService linksService,
  String url, {
  required String title,
  required String purpose,
}) async {
  final confirmed = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      scrollable: true,
      title: Text(title),
      content: Text('This will open $purpose outside the app. Continue?'),
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
  final ok = await linksService.open(url);
  if (!ok && context.mounted) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text('Could not open $purpose.'),
    ));
  }
}
