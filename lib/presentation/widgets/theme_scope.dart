import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../../services/theme_service.dart';

/// Provides the app's theme-mode listenable + change callback to the widget
/// tree (the drawer's theme switcher) WITHOUT a service-locator lookup.
/// Inserted once at the composition root (`main.dart` `MaterialApp.builder`).
/// Descendants obtain it via [ThemeScope.of].
///
/// This is dependency **injection** via the widget tree (an [InheritedWidget]):
/// the [ThemeService]'s listenable + setter are provided at the root and read
/// through `context`, so the drawer switcher stays thin and testable (a test
/// wraps its pump with a scope carrying a fake listenable + callback).
class ThemeScope extends InheritedWidget {
  const ThemeScope({
    super.key,
    required this.modeListenable,
    required this.onChanged,
    required super.child,
  });

  /// The theme-mode listenable; `.value` is the current [ThemeMode].
  final ValueListenable<ThemeMode> modeListenable;

  /// Sets a new theme mode (persists + notifies → [MaterialApp] re-themes).
  final ValueChanged<ThemeMode> onChanged;

  /// Returns the [ThemeScope] provided by the nearest ancestor. Asserts if
  /// none is found — the drawer switcher is always rendered under the root
  /// scope, so this is safe at the production call site.
  static ThemeScope of(BuildContext context) {
    final scope = context.dependOnInheritedWidgetOfExactType<ThemeScope>();
    assert(
      scope != null,
      'ThemeScope not found in the widget tree. Wrap the app (or the test '
      'pump) with ThemeScope.',
    );
    return scope!;
  }

  @override
  bool updateShouldNotify(ThemeScope oldWidget) =>
      modeListenable != oldWidget.modeListenable ||
      onChanged != oldWidget.onChanged;
}
