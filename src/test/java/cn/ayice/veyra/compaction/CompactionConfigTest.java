package cn.ayice.veyra.compaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionConfigTest {

    @Test
    void defaultThresholdsMatchSimplifiedClaudeCodeBudget() {
        CompactionConfig config = new CompactionConfig(128_000, 4_096, true, true, null, true);

        assertEquals(123_904, config.effectiveWindow());
        assertEquals(110_904, config.threshold());
        assertEquals(90_904, config.warningThreshold());
        assertEquals(120_904, config.blockingLimit());
    }

    @Test
    void tokenStateTracksWarningThresholdAndBlockingLimit() {
        CompactionConfig config = new CompactionConfig(128_000, 4_096, true, true, null, true);

        CompactionConfig.TokenState belowWarning = config.evaluate(config.warningThreshold() - 1);
        assertFalse(belowWarning.aboveWarning());
        assertFalse(belowWarning.aboveThreshold());
        assertFalse(belowWarning.atBlockingLimit());

        CompactionConfig.TokenState warning = config.evaluate(config.warningThreshold());
        assertTrue(warning.aboveWarning());
        assertFalse(warning.aboveThreshold());
        assertFalse(warning.atBlockingLimit());

        CompactionConfig.TokenState threshold = config.evaluate(config.threshold());
        assertTrue(threshold.aboveWarning());
        assertTrue(threshold.aboveThreshold());
        assertFalse(threshold.atBlockingLimit());

        CompactionConfig.TokenState blocking = config.evaluate(config.blockingLimit());
        assertTrue(blocking.aboveWarning());
        assertTrue(blocking.aboveThreshold());
        assertTrue(blocking.atBlockingLimit());
    }

    @Test
    void percentLeftUsesBackendEvaluationThresholdDirectly() {
        CompactionConfig config = new CompactionConfig(128_000, 4_096, true, true, null, true);

        CompactionConfig.TokenState state = config.evaluate(config.threshold());
        assertEquals(110_904, state.tokenCount());
        assertEquals(0, state.percentLeft());
    }
}
