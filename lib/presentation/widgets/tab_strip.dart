import 'package:flutter/material.dart';

/// One visible browser tab (SERP proof / I'm Lucky / fetch page).
class TabInfo {
  const TabInfo({
    required this.id,
    required this.title,
    required this.url,
    required this.isForeground,
  });

  final int id;
  final String title;
  final String url;
  final bool isForeground;

  factory TabInfo.fromJson(Map<String, dynamic> m) => TabInfo(
        id: (m['id'] as num?)?.toInt() ?? -1,
        title: (m['title'] as String?) ?? '',
        url: (m['url'] as String?) ?? '',
        isForeground: m['isForeground'] == true,
      );
}

/// Horizontal tab strip above the live WebView surface. Thin view — the
/// service owns the tabs; this only reports taps (activate / close).
class TabStrip extends StatelessWidget {
  const TabStrip({
    super.key,
    required this.tabs,
    required this.onActivate,
    required this.onClose,
  });

  final List<TabInfo> tabs;
  final void Function(int id) onActivate;
  final void Function(int id) onClose;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return SizedBox(
      height: 40,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        itemCount: tabs.length,
        separatorBuilder: (_, _) => const SizedBox(width: 6),
        itemBuilder: (context, i) {
          final t = tabs[i];
          final label = t.title.trim().isEmpty ? t.url : t.title;
          return Material(
            color: t.isForeground
                ? scheme.primaryContainer
                : scheme.surfaceContainerHighest,
            borderRadius: BorderRadius.circular(8),
            child: InkWell(
              borderRadius: BorderRadius.circular(8),
              onTap: () => onActivate(t.id),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 180, minWidth: 72),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const SizedBox(width: 8),
                    Flexible(
                      child: Text(
                        label,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 12,
                          color: t.isForeground
                              ? scheme.onPrimaryContainer
                              : scheme.onSurfaceVariant,
                        ),
                      ),
                    ),
                    IconButton(
                      visualDensity: VisualDensity.compact,
                      iconSize: 14,
                      icon: const Icon(Icons.close),
                      tooltip: 'Close tab',
                      onPressed: () => onClose(t.id),
                    ),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// The URL bar under the tab strip — shows the active tab's live URL
/// (redirects included). Read-only: the model drives navigation; the user
/// inspects. Plain selectable text (host no-clickable-urls rule).
class TabUrlBar extends StatelessWidget {
  const TabUrlBar({super.key, required this.url});

  final String url;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      color: scheme.surfaceContainerHighest,
      child: SelectableText(
        url.isEmpty ? 'No page open' : url,
        maxLines: 1,
        style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
      ),
    );
  }
}
