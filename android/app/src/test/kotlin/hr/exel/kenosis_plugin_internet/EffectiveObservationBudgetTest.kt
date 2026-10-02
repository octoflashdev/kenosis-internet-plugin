package hr.exel.kenosis_plugin_internet

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Boundary tests for InternetPluginService.effectiveObservationBudget — the
 * +73 j plugin-side clamp of the host's per-round `maxChars` tool arg. The
 * host scales the observation budget to its FREE context window (floor 6144
 * — the pre-+73 fixed cap — ceiling 24576, the WebView extraction cap); the
 * plugin clamps whatever arrives so a stale or buggy host can never gut a
 * page or ask the pipeline for more than it can extract.
 */
class EffectiveObservationBudgetTest {

    @Test
    fun `missing budget keeps today's fixed cap`() {
        assertEquals(InternetPluginService.OBSERVATION_BUDGET_CHARS,
            InternetPluginService.effectiveObservationBudget(0))
    }

    @Test
    fun `negative budget (stale host) keeps today's fixed cap`() {
        assertEquals(InternetPluginService.OBSERVATION_BUDGET_CHARS,
            InternetPluginService.effectiveObservationBudget(-1))
    }

    @Test
    fun `below the floor clamps up to the floor`() {
        assertEquals(InternetPluginService.OBSERVATION_BUDGET_CHARS,
            InternetPluginService.effectiveObservationBudget(100))
    }

    @Test
    fun `above the ceiling clamps down to the extraction cap`() {
        assertEquals(InternetPluginService.MAX_TEXT_CHARS,
            InternetPluginService.effectiveObservationBudget(999999))
    }

    @Test
    fun `in-range budgets pass through unchanged`() {
        assertEquals(16384,
            InternetPluginService.effectiveObservationBudget(16384))
    }

    @Test
    fun `the exact floor and ceiling are honored`() {
        assertEquals(InternetPluginService.OBSERVATION_BUDGET_CHARS,
            InternetPluginService.effectiveObservationBudget(
                InternetPluginService.OBSERVATION_BUDGET_CHARS))
        assertEquals(InternetPluginService.MAX_TEXT_CHARS,
            InternetPluginService.effectiveObservationBudget(
                InternetPluginService.MAX_TEXT_CHARS))
    }
}