import 'package:flutter/services.dart';

import 'legal_document.dart';

/// Loads the plugin's legal documents from bundled assets (offline, no
/// network). Kept as a separate class from the repository impl so tests can
/// fake the source, mirroring the host's data-layer shape.
class AssetLegalDocumentDataSource {
  static String _assetPath(LegalDocument document) => switch (document) {
        LegalDocument.privacyNotice => 'assets/legal/privacy_notice.md',
        LegalDocument.license => 'assets/legal/license.md',
      };

  Future<String> load(LegalDocument document) =>
      rootBundle.loadString(_assetPath(document));
}
