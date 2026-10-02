import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:kenosis_plugin_internet/legal/legal_document.dart';
import 'package:kenosis_plugin_internet/legal/legal_document_repository.dart';
import 'package:kenosis_plugin_internet/main.dart';
import 'package:kenosis_plugin_internet/services/external_links_service.dart';
import 'package:kenosis_plugin_internet/services/theme_service.dart';

class FakeThemeService implements ThemeService {
  FakeThemeService([ThemeMode initial = ThemeMode.system]);

  final ValueNotifier<ThemeMode> _mode =
      ValueNotifier<ThemeMode>(ThemeMode.system);

  @override
  ThemeMode get currentMode => _mode.value;

  @override
  ValueListenable<ThemeMode> get modeListenable => _mode;

  @override
  Future<void> setMode(ThemeMode mode) async {
    _mode.value = mode;
  }

  @override
  Future<void> hydrate() async {}
}

class FakeLegalDocumentRepository implements LegalDocumentRepository {
  const FakeLegalDocumentRepository(this.body);
  final String body;

  @override
  Future<String> load(LegalDocument document) async => body;
}

class FakeLinksService implements ExternalLinksService {
  final opened = <String>[];
  bool succeed = true;

  @override
  Future<bool> open(String url) async {
    opened.add(url);
    return succeed;
  }
}

void main() {
  // NOTE: never pumpAndSettle here — StatusScreen polls every 2 s (Timer),
  // so the frame pipeline never settles. Pump fixed durations instead.
  const routePump = Duration(milliseconds: 400);

  testWidgets('plugin status screen shows ready', (tester) async {
    await tester.pumpWidget(const KenosisPluginApp());
    expect(find.text('Status: ready'), findsOneWidget);
    expect(find.textContaining('Internet Search plugin'), findsWidgets);
  });

  group('plugin shell — drawer, theme, legal pages', () {
    const legalBody =
        '# Privacy Notice\nFirst sentence lives here\nand continues on the '
        'next source line.';

    Future<void> pumpApp(
      WidgetTester tester, {
      ThemeService? theme,
      String legal = legalBody,
      FakeLinksService? links,
    }) async {
      await tester.pumpWidget(KenosisPluginApp(
        themeService: theme ?? FakeThemeService(),
        legalRepository: FakeLegalDocumentRepository(legal),
        linksService: links ?? FakeLinksService(),
      ));
      await tester.pump();
      await tester.pump();
    }

    Future<void> openDrawer(WidgetTester tester) async {
      await tester.tap(find.byIcon(Icons.menu));
      await tester.pump();
      await tester.pump(routePump);
    }

    testWidgets('drawer opens with the main-app hand-off and legal pages',
        (tester) async {
      await pumpApp(tester);
      await openDrawer(tester);

      expect(find.text('Internet Search plugin'), findsWidgets);
      expect(find.text('Get Kenosis AI (main app)'), findsOneWidget);
      expect(find.text('About'), findsOneWidget);
      expect(find.text('Privacy Notice'), findsOneWidget);
      expect(find.text('App permissions'), findsOneWidget);
      expect(find.text('Open-source licenses'), findsOneWidget);
    });

    testWidgets('theme switcher re-themes the app (system → dark)',
        (tester) async {
      final theme = FakeThemeService();
      await pumpApp(tester, theme: theme);
      await openDrawer(tester);

      expect(theme.currentMode, ThemeMode.system);
      expect(
        tester.widget<MaterialApp>(find.byType(MaterialApp)).themeMode,
        ThemeMode.system,
      );

      await tester.tap(find.byTooltip('Theme'));
      await tester.pump();
      await tester.pump(routePump);
      await tester.tap(find.text('Dark'));
      await tester.pump();
      await tester.pump(routePump);

      expect(theme.currentMode, ThemeMode.dark);
      expect(
        tester.widget<MaterialApp>(find.byType(MaterialApp)).themeMode,
        ThemeMode.dark,
      );
    });

    testWidgets('Get Kenosis AI: warn dialog, OK opens the Play listing',
        (tester) async {
      final links = FakeLinksService();
      await pumpApp(tester, links: links);
      await openDrawer(tester);

      await tester.tap(find.text('Get Kenosis AI (main app)'));
      await tester.pump();
      await tester.pump(routePump);

      // The alert WARNS what OK will do (open Google Play); no store page
      // may launch without it. The site address stays plain selectable text.
      expect(find.text('Get Kenosis AI'), findsOneWidget);
      expect(find.textContaining('Google Play'), findsWidgets);
      expect(find.text('www.kenosis-ai.com'), findsOneWidget);
      expect(find.text('Cancel'), findsOneWidget);
      expect(links.opened, isEmpty);

      await tester.tap(find.text('OK'));
      await tester.pump();
      await tester.pump(routePump);

      expect(links.opened, [kenosisAiPlayStoreUrl]);
    });

    testWidgets('Get Kenosis AI: a failed store launch degrades to a SnackBar',
        (tester) async {
      final links = FakeLinksService()..succeed = false;
      await pumpApp(tester, links: links);
      await openDrawer(tester);

      await tester.tap(find.text('Get Kenosis AI (main app)'));
      await tester.pump();
      await tester.pump(routePump);
      await tester.tap(find.text('OK'));
      await tester.pump();
      await tester.pump(routePump);

      expect(find.textContaining('Could not open the Play Store'),
          findsOneWidget);
    });

    testWidgets('About renders the companion line, version and legal tiles',
        (tester) async {
      await pumpApp(tester);
      await openDrawer(tester);

      await tester.tap(find.text('About'));
      await tester.pump();
      await tester.pump(routePump);

      expect(find.textContaining('companion plugin of Kenosis AI'),
          findsOneWidget);
      expect(find.text('v1.9.2+2'), findsOneWidget);
      // The Play hand-off row is present; the site URL stays plain text.
      expect(find.text('Kenosis AI on Google Play'), findsOneWidget);
      expect(find.text('www.kenosis-ai.com'), findsOneWidget);
      // The list is taller than the test viewport (lazy ListView) — walk it
      // section by section.
      final scrollable = find.byType(Scrollable).first;
      await tester.scrollUntilVisible(
        find.text('Open-source licenses'), 200, scrollable: scrollable);
      expect(find.text('Privacy Notice'), findsOneWidget);
      expect(find.text('App permissions'), findsOneWidget);
      expect(find.text('Open-source licenses'), findsOneWidget);
      await tester.scrollUntilVisible(
        find.text('octoflash.sup@gmail.com'), 200, scrollable: scrollable);
      expect(find.text('octoflash.sup@gmail.com'), findsOneWidget);
    });

    testWidgets('About Play row opens the listing only after the confirm alert',
        (tester) async {
      final links = FakeLinksService();
      await pumpApp(tester, links: links);
      await openDrawer(tester);
      await tester.tap(find.text('About'));
      await tester.pump();
      await tester.pump(routePump);

      await tester.tap(find.text('Kenosis AI on Google Play'));
      await tester.pump();
      await tester.pump(routePump);

      // Alert first — nothing opens without OK.
      expect(find.text('Open Google Play?'), findsOneWidget);
      expect(links.opened, isEmpty);

      await tester.tap(find.text('OK'));
      await tester.pump();
      await tester.pump(routePump);
      expect(links.opened, [kenosisAiPlayStoreUrl]);
    });

    testWidgets('About report-bug section names this plugin\'s source tree',
        (tester) async {
      await pumpApp(tester);
      await openDrawer(tester);
      await tester.tap(find.text('About'));
      await tester.pump();
      await tester.pump(routePump);

      final scrollable = find.byType(Scrollable).first;
      await tester.scrollUntilVisible(
        find.textContaining('github.com/octoflashdev/kenosis-internet-plugin'),
        200,
        scrollable: scrollable);
      expect(find.textContaining('github.com/octoflashdev/kenosis-internet-plugin'),
          findsOneWidget);
      // Plain selectable text, not a button — nothing else is tappable.
      expect(find.text('octoflash.sup@gmail.com'), findsOneWidget);
    });

    testWidgets('privacy notice joins hard-wrapped source lines into one paragraph',
        (tester) async {
      await pumpApp(tester);
      await openDrawer(tester);

      await tester.tap(find.text('Privacy Notice'));
      await tester.pump();
      await tester.pump(routePump);

      // The two physical lines render as ONE continuous sentence (soft-wrap
      // joining), not two fragments. Paragraphs are RichText blocks.
      final paragraphs = tester.widgetList<RichText>(find.byType(RichText))
          .map((r) => r.text.toPlainText())
          .toList();
      expect(
        paragraphs,
        contains('First sentence lives here and continues on the next '
            'source line.'),
      );
    });

    testWidgets('app permissions lists the manifest rows', (tester) async {
      await pumpApp(tester);
      await openDrawer(tester);

      await tester.tap(find.text('App permissions'));
      await tester.pump();
      await tester.pump(routePump);

      expect(find.text('INTERNET'), findsOneWidget);
      expect(find.text('POST_NOTIFICATIONS'), findsOneWidget);
    });

    testWidgets('licenses render the bundled text verbatim monospace',
        (tester) async {
      const licenseText = 'Apache License\n   Version 2.0, January 2004';
      await pumpApp(tester, legal: licenseText);
      await openDrawer(tester);

      await tester.tap(find.text('Open-source licenses'));
      await tester.pump();
      await tester.pump(routePump);

      final body = tester.widget<Text>(find.byWidgetPredicate(
        (w) => w is Text && (w.data ?? '').contains('Version 2.0'),
      ));
      expect(body.softWrap, false, reason: 'license renders verbatim, not wrapped');
      expect(body.style?.fontFamily, 'monospace');
    });
  });

  group('describeFetchError — known service signatures get a human gloss', () {
    test('no-usable-result (google SERP walk empty)', () {
      expect(
        describeFetchError(
          'error:no-usable-result (IFL pick unusable, SERP walk empty)',
        ),
        contains('own app'),
      );
    });

    test('no-results (SERP parsed but zero result links)', () {
      expect(
        describeFetchError('error:no-results (serp 4211 chars)'),
        contains('no result links'),
      );
    });

    test('all-results-app-gated', () {
      expect(
        describeFetchError('error:all-results-app-gated (3 results)'),
        contains('Facebook'),
      );
    });

    test('bot protection rewrap', () {
      expect(
        describeFetchError(
          'error:the search engine blocked or could not serve its results '
          '(its bot protection may be blocking this device)',
        ),
        contains('blocked this device'),
      );
    });

    test('human check envelope maps to the in-app action (+75)', () {
      expect(
        describeFetchError(
          'error:qwant is showing a human check (captcha). Open the '
              'Internet Search plugin app, complete the check, then '
              'search again.',
        ),
        contains('Complete the check'),
      );
    });

    test('WebView JS extraction null', () {
      expect(
        describeFetchError('error:WebView JS extraction returned null'),
        contains('could not be extracted'),
      );
    });

    test('webview-reported unreachable names the dead network path (+75)', () {
      // The exact 2026-09-26 on-device string: an invented bbmedia.com URL.
      expect(
        describeFetchError(
          'error:java.lang.IllegalStateException: the page could not be '
          'loaded (net::ERR_ADDRESS_UNREACHABLE)',
        ),
        contains('could not be reached'),
      );
    });

    test('DNS failure says the address was wrong or made up (+75)', () {
      expect(
        describeFetchError(
          'error:Unable to resolve host "www.nosuchsite.example": No '
          'address associated with hostname',
        ),
        contains('made up'),
      );
    });

    test('timeout', () {
      expect(describeFetchError('error:timeout'), contains('too long'));
    });

    test('HTTP 4xx / 5xx keep the code visible', () {
      expect(describeFetchError('error:HTTP 403'), contains('403'));
      expect(describeFetchError('error:HTTP 500'), contains('500'));
    });

    test('unknown message passes through verbatim, prefix stripped', () {
      expect(
        describeFetchError('error:something new happened'),
        'something new happened',
      );
    });

    test('non-error status passes through unchanged', () {
      expect(describeFetchError('ok'), 'ok');
    });
  });

  group('fetch log details — red URLs show what happened', () {
    const channel = MethodChannel('kenosis_plugin/ui');

    String fetchLogJson() => jsonEncode([
      {
        'timestamp': 1757941440000,
        'tool': 'web_search',
        'query': 'gobekli tepe age',
        'requestedUrl': 'https://www.google.com/search?q=gobekli+tepe+age',
        'finalUrl': null,
        'title': '',
        'chars': 0,
        'path': '',
        'status': 'error:no-usable-result (IFL pick unusable, SERP walk empty)',
      },
      {
        'timestamp': 1757941400000,
        'tool': 'browser_fetch',
        'query': null,
        'requestedUrl': 'https://example.com/history',
        'finalUrl': 'https://example.com/history',
        'title': 'History',
        'chars': 1234,
        'path': 'http',
        'status': 'ok',
      },
    ]);

    Future<void> pumpScreen(WidgetTester tester) async {
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        (call) async => call.method == 'getFetchLog' ? fetchLogJson() : null,
      );
      await tester.pumpWidget(const KenosisPluginApp());
      // initState's _loadFetchLog resolves on the next pump.
      await tester.pump();
    }

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    testWidgets('failed row shows the raw reason collapsed; tap expands it',
        (tester) async {
      await pumpScreen(tester);
      expect(find.byIcon(Icons.error_outline), findsOneWidget);
      // Collapsed: the raw status is already visible (truncated), but the
      // human explanation is not.
      expect(find.textContaining('no-usable-result'), findsOneWidget);
      expect(find.textContaining('own app'), findsNothing);

      await tester.tap(find.byIcon(Icons.error_outline));
      await tester.pump();
      // Expanded: gloss + raw reason (also kept in the collapsed subtitle) +
      // requested URL all visible.
      expect(find.textContaining("Google's top pick"), findsOneWidget);
      expect(find.textContaining('no-usable-result'), findsWidgets);
      expect(find.textContaining('https://www.google.com/search'), findsWidgets);
    });

    testWidgets('tapping again collapses the details', (tester) async {
      await pumpScreen(tester);
      await tester.tap(find.byIcon(Icons.error_outline));
      await tester.pump();
      expect(find.textContaining("Google's top pick"), findsOneWidget);

      await tester.tap(find.byIcon(Icons.error_outline));
      await tester.pump();
      expect(find.textContaining("Google's top pick"), findsNothing);
    });

    testWidgets('successful row expands to its own (non-error) details',
        (tester) async {
      await pumpScreen(tester);
      await tester.tap(find.byIcon(Icons.language));
      await tester.pump();
      expect(find.textContaining('Served via'), findsOneWidget);
      expect(find.textContaining('http · 1234 chars'), findsOneWidget);
      expect(find.textContaining('What happened'), findsNothing);
    });
  });

  group('human-check banner (+75) — the pending gate surfaces as a card', () {
    const channel = MethodChannel('kenosis_plugin/ui');

    String captchaJson() => jsonEncode({
      'engine': 'qwant',
      'url': 'https://api.qwant.com/v3/search/web?q=test&count=10',
      'reason': 'HTTP 403',
      'sinceMs': 1757941440000,
    });

    Future<void> pumpScreen(
      WidgetTester tester, {
      bool captcha = true,
    }) async {
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        (call) async {
          if (call.method == 'getFetchLog') return '[]';
          if (call.method == 'getCaptcha') return captcha ? captchaJson() : null;
          return null;
        },
      );
      await tester.pumpWidget(const KenosisPluginApp());
      // initState's _refresh (log + captcha) resolves on the next pump.
      await tester.pump();
      await tester.pump();
    }

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    testWidgets('pending gate shows the banner + action-needed status',
        (tester) async {
      await pumpScreen(tester);
      expect(find.text('Qwant needs a human check'), findsOneWidget);
      expect(find.text('Status: human check needed'), findsOneWidget);
      expect(find.text('Solve now'), findsOneWidget);
    });

    testWidgets('no pending gate — plain ready screen, no banner',
        (tester) async {
      await pumpScreen(tester, captcha: false);
      expect(find.text('Qwant needs a human check'), findsNothing);
      expect(find.text('Status: ready'), findsOneWidget);
    });

    testWidgets('dismiss hides the banner until a NEW gate arms',
        (tester) async {
      await pumpScreen(tester);
      await tester.tap(find.text('Dismiss'));
      await tester.pump();
      await tester.pump();
      // The poll keeps returning the SAME gate (same sinceMs) — stays hidden.
      expect(find.text('Qwant needs a human check'), findsNothing);
      expect(find.text('Status: ready'), findsOneWidget);
    });
  });

  group('delete-all badge — clear the local URL log', () {
    const channel = MethodChannel('kenosis_plugin/ui');
    bool cleared = false;

    String twoRecordsJson() => jsonEncode([
          {
            'timestamp': 1757941440000,
            'tool': 'browser_fetch',
            'query': null,
            'requestedUrl': 'https://example.com/a',
            'finalUrl': 'https://example.com/a',
            'title': 'A',
            'chars': 10,
            'path': 'http',
            'status': 'ok',
          },
          {
            'timestamp': 1757941400000,
            'tool': 'browser_fetch',
            'query': null,
            'requestedUrl': 'https://example.com/b',
            'finalUrl': 'https://example.com/b',
            'title': 'B',
            'chars': 20,
            'path': 'http',
            'status': 'ok',
          },
        ]);

    Future<void> pumpWithLog(WidgetTester tester) async {
      cleared = false;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'getFetchLog') {
          return cleared ? '[]' : twoRecordsJson();
        }
        if (call.method == 'clearFetchLog') {
          cleared = true;
          return null;
        }
        return null;
      });
      await tester.pumpWidget(KenosisPluginApp(
        themeService: FakeThemeService(),
        legalRepository: const FakeLegalDocumentRepository('x'),
      ));
      await tester.pump();
    }

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    testWidgets('badge shows the log count; tapping opens the sheet',
        (tester) async {
      await pumpWithLog(tester);

      // Badge.count bakes the number into its label Text.
      expect(find.descendant(of: find.byType(Badge), matching: find.text('2')),
          findsOneWidget);

      await tester.tap(find.byTooltip('Requested URLs'));
      await tester.pump();
      await tester.pump(routePump);

      // The title appears TWICE: the status screen's section header behind
      // the sheet + the sheet's own header.
      expect(find.text('Requested URLs (2)'), findsNWidgets(2));
      // The URLs are selectable plain text (repo rule: no clickable URLs).
      expect(find.byType(SelectableText), findsNWidgets(2));
      expect(find.byType(FilledButton), findsOneWidget);
    });

    testWidgets('confirm Delete all calls clearFetchLog and empties the list',
        (tester) async {
      await pumpWithLog(tester);
      await tester.tap(find.byTooltip('Requested URLs'));
      await tester.pump();
      await tester.pump(routePump);

      await tester.tap(find.byType(FilledButton));
      await tester.pump();
      await tester.pump(routePump);

      // Confirm dialog with the honest scope line.
      expect(find.text('Delete all requested URLs?'), findsOneWidget);
      expect(find.text('This removes the local log only.'), findsOneWidget);
      await tester.tap(find.descendant(
        of: find.byType(AlertDialog),
        matching: find.text('Delete all'),
      ));
      await tester.pump();
      await tester.pump(routePump);
      await tester.pump(routePump);

      expect(cleared, isTrue, reason: 'the service-side wipe must be invoked');
      // The sheet closed; the empty log renders as the empty state and the
      // badge label hides.
      expect(find.text('No URLs requested yet.'), findsOneWidget);
      // Badge label hidden when the log is empty (count 0).
      expect(find.descendant(of: find.byType(Badge), matching: find.text('0')),
          findsNothing);
    });

    testWidgets('cancelling the confirm leaves the log untouched',
        (tester) async {
      await pumpWithLog(tester);
      await tester.tap(find.byTooltip('Requested URLs'));
      await tester.pump();
      await tester.pump(routePump);
      await tester.tap(find.byType(FilledButton));
      await tester.pump();
      await tester.pump(routePump);

      await tester.tap(find.descendant(
        of: find.byType(AlertDialog),
        matching: find.text('Cancel'),
      ));
      await tester.pump();
      await tester.pump(routePump);

      expect(cleared, isFalse);
      // Back on the sheet with both rows still listed (screen header + sheet
      // header again).
      expect(find.text('Requested URLs (2)'), findsNWidgets(2));
    });
  });
}
