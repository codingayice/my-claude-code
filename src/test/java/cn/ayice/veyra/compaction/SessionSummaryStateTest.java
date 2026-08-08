package cn.ayice.veyra.compaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionSummaryStateTest {

    @Test
    void doesNotPublishSummaryWhenPersistenceFails() {
        SessionSummaryState state = new SessionSummaryState(null, summary -> {
            throw new IllegalStateException("disk failure");
        });

        assertThrows(IllegalStateException.class,
                () -> state.commit(new SessionSummaryState.SummaryCandidate("summary", 3)));
        assertTrue(state.current().isEmpty());
    }

    @Test
    void commitsOnlyForwardCoverageAndAssignsVersionsInsideState() {
        SessionSummaryState state = new SessionSummaryState();

        SessionSummaryState.CommitResult first = state.commit(new SessionSummaryState.SummaryCandidate("first", 10));
        SessionSummaryState.CommitResult older = state.commit(new SessionSummaryState.SummaryCandidate("older", 9));
        SessionSummaryState.CommitResult second = state.commit(new SessionSummaryState.SummaryCandidate("second", 20));

        assertEquals(SessionSummaryState.CommitStatus.COMMITTED, first.status());
        assertEquals(1, first.summary().orElseThrow().summaryVersion());
        assertEquals(SessionSummaryState.CommitStatus.SKIPPED_OLDER_COVERAGE, older.status());
        assertEquals(2, second.summary().orElseThrow().summaryVersion());
        assertEquals(20, state.current().orElseThrow().coveredSequence());
    }

    @Test
    void closedStateRejectsLateCandidatesAndClearsCurrentSummary() {
        SessionSummaryState state = new SessionSummaryState();
        state.commit(new SessionSummaryState.SummaryCandidate("first", 10));

        state.close();

        assertTrue(state.current().isEmpty());
        assertEquals(SessionSummaryState.CommitStatus.SKIPPED_CLOSED,
                state.commit(new SessionSummaryState.SummaryCandidate("late", 20)).status());
    }
}
