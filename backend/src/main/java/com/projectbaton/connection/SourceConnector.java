package com.projectbaton.connection;

import java.time.LocalDate;
import java.util.List;

public interface SourceConnector {
    SourceBatch fetch(long connectionId, String checkpoint, int batchSize);

    record SourceBatch(List<SourceDocument> documents, String nextCheckpoint) {
    }

    record SourceDocument(
            String externalId,
            String version,
            String contentHash,
            String body,
            String authorName,
            LocalDate occurredOn) {
    }
}
