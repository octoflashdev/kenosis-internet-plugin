import 'legal_document.dart';

abstract class LegalDocumentRepository {
  /// Loads the bundled (offline) markdown text for the given document.
  Future<String> load(LegalDocument document);
}
