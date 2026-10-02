import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../storage/key_value_storage.dart';
import 'theme_service.dart';

/// [ThemeService] backed by the encrypted [KeyValueStorage]. One key
/// (`theme_mode`) holds "light" | "dark" | "system". A missing / unrecognized
/// value decodes to [ThemeMode.system], so a fresh install follows the system
/// setting. A [ValueNotifier] drives [modeListenable] so [MaterialApp]
/// rebuilds on toggle.
class ThemeServiceImpl implements ThemeService {
  ThemeServiceImpl(this._kv);

  static const _kThemeMode = 'theme_mode';

  final KeyValueStorage _kv;
  final ValueNotifier<ThemeMode> _mode =
      ValueNotifier<ThemeMode>(ThemeMode.system);

  @override
  ThemeMode get currentMode => _mode.value;

  @override
  ValueListenable<ThemeMode> get modeListenable => _mode;

  @override
  Future<void> setMode(ThemeMode mode) async {
    if (_mode.value == mode) return;
    _mode.value = mode;
    await _kv.write(_kThemeMode, _encode(mode));
  }

  /// Hydrate the persisted mode from storage. Called once during app start.
  /// Idempotent.
  @override
  Future<void> hydrate() async {
    final raw = await _kv.read(_kThemeMode);
    _mode.value = _decode(raw);
  }

  static String _encode(ThemeMode m) {
    switch (m) {
      case ThemeMode.light:
        return 'light';
      case ThemeMode.dark:
        return 'dark';
      case ThemeMode.system:
        return 'system';
    }
  }

  static ThemeMode _decode(String? raw) {
    switch (raw) {
      case 'light':
        return ThemeMode.light;
      case 'dark':
        return ThemeMode.dark;
      default:
        return ThemeMode.system;
    }
  }
}
