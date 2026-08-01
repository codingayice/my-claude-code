package cn.ayice.veyra.compaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoCompactConfigTest {

    @Test
    void defaultThresholdsMatchSimplifiedClaudeCodeBudget() {
        AutoCompactConfig config = new AutoCompactConfig(128_000, 4_096, true, true, null, true);

        assertEquals(123_904, config.effectiveWindow());
        assertEquals(110_904, config.threshold());
        assertEquals(90_904, config.warningThreshold());
        assertEquals(120_904, config.blockingLimit());
    }

    @Test
    void tokenStateTracksWarningThresholdAndBlockingLimit() {
        AutoCompactConfig config = new AutoCompactConfig(128_000, 4_096, true, true, null, true);

        AutoCompactConfig.TokenState belowWarning = config.evaluate(config.warningThreshold() - 1);
        assertFalse(belowWarning.aboveWarning());
        assertFalse(belowWarning.aboveThreshold());
        assertFalse(belowWarning.atBlockingLimit());

        AutoCompactConfig.TokenState warning = config.evaluate(config.warningThreshold());
        assertTrue(warning.aboveWarning());
        assertFalse(warning.aboveThreshold());
        assertFalse(warning.atBlockingLimit());

        AutoCompactConfig.TokenState threshold = config.evaluate(config.threshold());
        assertTrue(threshold.aboveWarning());
        assertTrue(threshold.aboveThreshold());
        assertFalse(threshold.atBlockingLimit());

        AutoCompactConfig.TokenState blocking = config.evaluate(config.blockingLimit());
        assertTrue(blocking.aboveWarning());
        assertTrue(blocking.aboveThreshold());
        assertTrue(blocking.atBlockingLimit());
    }

    @Test
    void percentLeftUsesBackendEvaluationThresholdDirectly() {
        AutoCompactConfig config = new AutoCompactConfig(128_000, 4_096, true, true, null, true);

        AutoCompactConfig.TokenState state = config.evaluate(config.threshold());
        assertEquals(110_904, state.tokenCount());
        assertEquals(0, state.percentLeft());
    }
}
