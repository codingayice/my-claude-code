package cn.ayice.veyra.session;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Read-only session metadata exposed by the runtime host.
 */
public record SessionSummary(
        String sessionId,
        String title,
        Instant createdAt,
        Instant updatedAt,
        Path transcriptPath
) {
}
