import 'asset_legal_document_data_source.dart';
import 'legal_document.dart';
import 'legal_document_repository.dart';

class LegalDocumentRepositoryImpl implements LegalDocumentRepository {
  LegalDocumentRepositoryImpl([AssetLegalDocumentDataSource? dataSource])
      : _dataSource = dataSource ?? AssetLegalDocumentDataSource();

  final AssetLegalDocumentDataSource _dataSource;

  @override
  Future<String> load(LegalDocument document) => _dataSource.load(document);
}
