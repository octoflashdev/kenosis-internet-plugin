import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

/// Hosts a live tab WebView (the SAME instance the plugin's tool extracts
/// from — WYSIWYG) inside the Flutter shell.
///
/// Uses `initAndroidView` (TLHC, falling back to virtual display) — NOT
/// the expensive hybrid-composition factory. Hybrid composition renders
/// WHITE on Adreno/Samsung: the GPU overlay path fails (`format 59` alloc +
/// shader / pipeline create fail → `skip updating surface`) while the
/// WebView itself keeps working, so extraction succeeds and the tab looks
/// blank. `dispose` on the Kotlin side only detaches: TabHolder owns the
/// WebView lifecycle.
class TabViewport extends StatelessWidget {
  const TabViewport({super.key, required this.tabId});

  final int tabId;

  static const String viewType = 'kenosis_plugin/tab_view';

  @override
  Widget build(BuildContext context) {
    return PlatformViewLink(
      viewType: viewType,
      surfaceFactory: (context, controller) {
        return AndroidViewSurface(
          controller: controller as AndroidViewController,
          gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{},
          hitTestBehavior: PlatformViewHitTestBehavior.opaque,
        );
      },
      onCreatePlatformView: (params) {
        return PlatformViewsService.initAndroidView(
          id: params.id,
          viewType: viewType,
          layoutDirection: TextDirection.ltr,
          creationParams: <String, dynamic>{'tabId': tabId},
          creationParamsCodec: const StandardMessageCodec(),
          onFocus: () => params.onFocusChanged(true),
        )
          ..addOnPlatformViewCreatedListener(params.onPlatformViewCreated)
          ..create();
      },
    );
  }
}
