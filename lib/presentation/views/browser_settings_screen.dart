import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Load images / Enable JavaScript / Wipe on close — the plugin
/// mini-browser's three privacy knobs ([BrowserSettings] on the Kotlin side).
///
/// Thin view: reads/writes the `kenosis_plugin/ui` settings channel and
/// renders the three switches with their honest captions. Defaults are
/// Load images ON / Enable JavaScript ON / Wipe on close ON.
class BrowserSettingsScreen extends StatefulWidget {
  const BrowserSettingsScreen({super.key, this.channel});

  /// Injectable for tests; production uses the plugin UI channel.
  final MethodChannel? channel;

  @override
  State<BrowserSettingsScreen> createState() => _BrowserSettingsScreenState();
}

class _BrowserSettingsScreenState extends State<BrowserSettingsScreen> {
  static const _defaultChannel = MethodChannel('kenosis_plugin/ui');

  MethodChannel get _ch => widget.channel ?? _defaultChannel;

  bool? _loadImages;
  bool? _javaScript;
  bool? _wipeOnClose;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final json = await _ch.invokeMethod<String>('getSettings');
      if (json == null || !mounted) return;
      // Tiny payload — parse by key presence to avoid a JSON import for
      // three booleans. The Kotlin side emits a JSONObject string.
      setState(() {
        _loadImages = json.contains('"loadImages":true');
        _javaScript = json.contains('"javaScript":true');
        _wipeOnClose = json.contains('"wipeOnClose":true');
      });
    } on PlatformException catch (_) {
      // Channel down — leave the switches hidden (the caption explains).
    } on MissingPluginException catch (_) {
      // Widget tests without a mock handler land here — same degrade.
    }
  }

  Future<void> _set({
    bool? loadImages,
    bool? javaScript,
    bool? wipeOnClose,
  }) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final payload = <String, bool>{};
      if (loadImages != null) payload['loadImages'] = loadImages;
      if (javaScript != null) payload['javaScript'] = javaScript;
      if (wipeOnClose != null) payload['wipeOnClose'] = wipeOnClose;
      await _ch.invokeMethod<void>('setSettings', payload);
      setState(() {
        if (loadImages != null) _loadImages = loadImages;
        if (javaScript != null) _javaScript = javaScript;
        if (wipeOnClose != null) _wipeOnClose = wipeOnClose;
      });
    } on PlatformException catch (_) {
      // Channel down — keep the previous values visible.
    } on MissingPluginException catch (_) {
      // Same degrade in tests.
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final ready = _loadImages != null;
    return Scaffold(
      appBar: AppBar(title: const Text('Browser settings')),
      body: ListView(
        padding: const EdgeInsets.symmetric(vertical: 8),
        children: [
          if (!ready)
            const Padding(
              padding: EdgeInsets.all(24),
              child: Text('Settings unavailable — reopen this screen.'),
            )
          else ...[
            SwitchListTile(
              title: const Text('Load images'),
              subtitle: const Text(
                'On by default: a real browser loads images. Turn it off '
                'to save bandwidth.',
              ),
              value: _loadImages!,
              onChanged: _busy ? null : (v) => _set(loadImages: v),
            ),
            SwitchListTile(
              title: const Text('Enable JavaScript'),
              subtitle: const Text(
                'On by default: modern pages and captcha checks need it. '
                'Turning it off is your choice.',
              ),
              value: _javaScript!,
              onChanged: _busy ? null : (v) => _set(javaScript: v),
            ),
            SwitchListTile(
              title: const Text('Wipe on close'),
              subtitle: const Text(
                'On by default: cookies, site storage and cache are erased '
                'when the plugin app closes. This is a tool, not an identity.',
              ),
              value: _wipeOnClose!,
              onChanged: _busy ? null : (v) => _set(wipeOnClose: v),
            ),
          ],
        ],
      ),
    );
  }
}
