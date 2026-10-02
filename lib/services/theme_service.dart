import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

/// App theme mode (light / dark / system) persistence — the host's +69
/// drawer switcher, copied for the plugin shell.
///
/// The choice persists across cold starts via [KeyValueStorage] (encrypted at
/// rest, per the no-plaintext-storage policy), and the impl exposes a
/// [ValueListenable] so [MaterialApp] re-themes on toggle and the drawer
/// switcher's icon tracks the current mode. Views never touch the storage —
/// the service arrives through constructors ([ThemeScope] carries the
/// listenable + setter into the tree).
///
/// Only Flutter's [ThemeMode] value type is referenced (no widgets), so this
/// stays a thin, testable interface.
abstract class ThemeService {
  /// The current theme mode. Defaults to [ThemeMode.system] until [hydrate]
  /// loads the persisted choice.
  ThemeMode get currentMode;

  /// A listenable that notifies when [currentMode] changes. [MaterialApp]
  /// listens so a toggle re-themes the whole app; the drawer switcher reads
  /// `.value` for its current-mode icon.
  ValueListenable<ThemeMode> get modeListenable;

  /// Persist + apply a new theme mode. Idempotent — a no-op if [mode] equals
  /// the current mode.
  Future<void> setMode(ThemeMode mode);

  /// Load the persisted mode once during app start so [currentMode] reflects
  /// the saved choice (default [ThemeMode.system]) without a per-call storage
  /// read. Idempotent.
  Future<void> hydrate();
}
