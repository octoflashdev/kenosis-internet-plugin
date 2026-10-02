/// Minimal key-value storage interface for persisting data.
/// Extracted so tests can inject an in-memory fake without platform channels.
abstract class KeyValueStorage {
  Future<String?> read(String key);
  Future<void> write(String key, String value);
  Future<void> delete(String key);
}
