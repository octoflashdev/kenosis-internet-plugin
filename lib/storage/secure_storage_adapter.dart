import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'key_value_storage.dart';

/// [KeyValueStorage] adapter backed by FlutterSecureStorage (AES at rest
/// on Android via Android Keystore).
class SecureStorageAdapter implements KeyValueStorage {
  final FlutterSecureStorage _storage;
  SecureStorageAdapter([FlutterSecureStorage? storage])
      : _storage = storage ?? const FlutterSecureStorage();

  @override
  Future<String?> read(String key) => _storage.read(key: key);

  @override
  Future<void> write(String key, String value) =>
      _storage.write(key: key, value: value);

  @override
  Future<void> delete(String key) => _storage.delete(key: key);
}
