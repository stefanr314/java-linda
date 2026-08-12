package rs.ac.bg.etf.kdp.common;

import java.io.Serializable;
import java.util.UUID;

/**
 * Opaque identifier for a job. The server keys its {@code Map<JobId,
 * JobContext>} by this value, and it is the only thing (besides operation
 * and tuple payload) that crosses the wire between client/workstation and
 * server.
 *
 * @param value the underlying identifier string
 */
public record JobId(String value) implements Serializable {

    public JobId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("JobId value must not be null or blank");
        }
    }

    /**
     * Generates a fresh, randomly-assigned job id.
     *
     * @return a new, unique {@link JobId}
     */
    public static JobId newId() {
        return new JobId(UUID.randomUUID().toString());
    }
}
