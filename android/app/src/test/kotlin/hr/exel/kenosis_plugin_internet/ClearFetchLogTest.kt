package hr.exel.kenosis_plugin_internet

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the UI "Delete all" path (the badge on the plugin's home screen):
 * [InternetPluginService.clearFetchLog] empties the shared log and returns
 * exactly the JSON [InternetPluginService.persistFetchLog]-equivalent writes
 * to fetch_log.json — the EMPTY array — so a process restart rehydrating from
 * disk cannot resurrect the cleared rows ("disk and RAM agree").
 *
 * In JVM tests logFile is null (no Context), so the executor write step is
 * skipped and the returned encoded payload IS the by-construction disk
 * content — the same encode path the live append-persist uses.
 *
 * Runs on the dev machine only:
 *   cd plugins/internet/android && ./gradlew :app:testDebugUnitTest
 */
class ClearFetchLogTest {

    @Test
    fun `clear returns the empty array — the persisted payload`() {
        assertEquals("[]", InternetPluginService.clearFetchLog())
    }

    @Test
    fun `snapshot after clear is empty — RAM agrees with disk`() {
        InternetPluginService.clearFetchLog()
        assertEquals("[]", InternetPluginService.snapshotFetchLogJson())
    }

    @Test
    fun `empty encode is the empty array (what persist writes)`() {
        assertEquals(
            "[]",
            InternetPluginService.encodeFetchLogOldestFirst(emptyList()),
        )
    }
}
