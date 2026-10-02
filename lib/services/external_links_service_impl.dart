import 'package:flutter/services.dart';

import 'external_links_service.dart';

/// MethodChannel-backed [ExternalLinksService] over the app's existing UI
/// channel (the same one the status screen polls — one channel, several
/// methods). Android side it is one ACTION_VIEW startActivity: no
/// url_launcher dependency and no manifest `<queries>` entry (startActivity
/// is not subject to package-visibility filtering).
class ChannelExternalLinksService implements ExternalLinksService {
  const ChannelExternalLinksService(this._channel);

  final MethodChannel _channel;

  @override
  Future<bool> open(String url) async {
    try {
      final ok = await _channel.invokeMethod<bool>(
        'openExternalUrl',
        <String, String>{'url': url},
      );
      return ok ?? false;
    } on PlatformException {
      // No handler / no activity could take the intent.
      return false;
    } on MissingPluginException {
      // No engine (widget tests).
      return false;
    }
  }
}
