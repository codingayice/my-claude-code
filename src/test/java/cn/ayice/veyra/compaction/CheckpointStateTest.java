package cn.ayice.veyra.compaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckpointStateTest {

    @Test
    void commitsOnlyForwardCoverageAndAssignsVersionsInsideState() {
        CheckpointState state = new CheckpointState();

        CheckpointState.CommitResult first = state.commit(new CheckpointState.Candidate("first", 10));
        CheckpointState.CommitResult older = state.commit(new CheckpointState.Candidate("older", 9));
        CheckpointState.CommitResult second = state.commit(new CheckpointState.Candidate("second", 20));

        assertEquals(CheckpointState.CommitStatus.COMMITTED, first.status());
        assertEquals(1, first.checkpoint().orElseThrow().checkpointVersion());
        assertEquals(CheckpointState.CommitStatus.SKIPPED_OLDER_COVERAGE, older.status());
        assertEquals(2, second.checkpoint().orElseThrow().checkpointVersion());
        assertEquals(20, state.current().orElseThrow().coveredSequence());
    }

    @Test
    void closedStateRejectsLateCandidatesAndClearsCurrentCheckpoint() {
        CheckpointState state = new CheckpointState();
        state.commit(new CheckpointState.Candidate("first", 10));

        state.close();

        assertTrue(state.current().isEmpty());
        assertEquals(CheckpointState.CommitStatus.SKIPPED_CLOSED,
                state.commit(new CheckpointState.Candidate("late", 20)).status());
    }
}
