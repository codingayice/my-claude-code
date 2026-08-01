package cn.ayice.veyra.compaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionCheckpointStateTest {

    @Test
    void commitsOnlyForwardCoverageAndAssignsVersionsInsideState() {
        SessionCheckpointState state = new SessionCheckpointState();

        SessionCheckpointState.CommitResult first = state.commit(new CheckpointCandidate("first", 10));
        SessionCheckpointState.CommitResult older = state.commit(new CheckpointCandidate("older", 9));
        SessionCheckpointState.CommitResult second = state.commit(new CheckpointCandidate("second", 20));

        assertEquals(SessionCheckpointState.CommitStatus.COMMITTED, first.status());
        assertEquals(1, first.checkpoint().orElseThrow().checkpointVersion());
        assertEquals(SessionCheckpointState.CommitStatus.SKIPPED_OLDER_COVERAGE, older.status());
        assertEquals(2, second.checkpoint().orElseThrow().checkpointVersion());
        assertEquals(20, state.current().orElseThrow().coveredSequence());
    }

    @Test
    void closedStateRejectsLateCandidatesAndClearsCurrentCheckpoint() {
        SessionCheckpointState state = new SessionCheckpointState();
        state.commit(new CheckpointCandidate("first", 10));

        state.close();

        assertTrue(state.current().isEmpty());
        assertEquals(SessionCheckpointState.CommitStatus.SKIPPED_CLOSED,
                state.commit(new CheckpointCandidate("late", 20)).status());
    }
}
