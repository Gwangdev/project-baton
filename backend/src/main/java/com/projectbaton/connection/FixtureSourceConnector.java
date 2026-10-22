package com.projectbaton.connection;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class FixtureSourceConnector implements SourceConnector {
    private static final List<SourceDocument> DOCUMENTS = List.of(
            new SourceDocument(
                    "fixture-brief", "v1",
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "Synthetic fixture: handover brief", "Fixture Author", LocalDate.of(2026, 1, 10)),
            new SourceDocument(
                    "fixture-risk", "v1",
                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    "Synthetic fixture: open risk", "Fixture Author", LocalDate.of(2026, 1, 11)));

    @Override
    public SourceBatch fetch(long connectionId, String checkpoint, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        int start = checkpoint == null ? 0 : Integer.parseInt(checkpoint);
        if (start < 0 || start > DOCUMENTS.size()) {
            throw new IllegalArgumentException("invalid fixture checkpoint");
        }
        int end = Math.min(start + batchSize, DOCUMENTS.size());
        return new SourceBatch(DOCUMENTS.subList(start, end), String.valueOf(end));
    }
}
