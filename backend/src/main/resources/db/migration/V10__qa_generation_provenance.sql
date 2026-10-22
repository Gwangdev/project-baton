-- Q&A provenance (C07). Every message belongs to one handover generation, an answer points at exactly one question,
-- and a citation names the snapshot row it quoted, so an answer can be reproduced from what the package showed.
-- qa_messages had no writer before the Q&A flow existed; adding NOT NULL columns fails loudly if a stray row exists.
ALTER TABLE handover_generations ADD CONSTRAINT handover_generations_id_handover_key UNIQUE (id, handover_id);

ALTER TABLE qa_messages ADD COLUMN generation_id bigint NOT NULL;
ALTER TABLE qa_messages ADD COLUMN reply_to_id bigint REFERENCES qa_messages(id);
ALTER TABLE qa_messages ADD COLUMN failure_code varchar(60);
ALTER TABLE qa_messages ADD COLUMN model_id varchar(120);
ALTER TABLE qa_messages ADD COLUMN prompt_version varchar(60);
ALTER TABLE qa_messages ADD CONSTRAINT qa_messages_generation_handover_fkey
    FOREIGN KEY (generation_id, handover_id) REFERENCES handover_generations(id, handover_id);
ALTER TABLE qa_messages ADD CONSTRAINT qa_messages_reply_to_key UNIQUE (reply_to_id);
ALTER TABLE qa_messages ADD CONSTRAINT qa_messages_role_shape CHECK (
    (role = 'USER' AND reply_to_id IS NULL AND answer_status IS NULL AND failure_code IS NULL)
    OR (role = 'ASSISTANT' AND reply_to_id IS NOT NULL AND answer_status IS NOT NULL
        AND (answer_status = 'FAILED') = (failure_code IS NOT NULL))
);
CREATE INDEX qa_messages_generation_id_idx ON qa_messages(generation_id, handover_id);

ALTER TABLE qa_citations ADD COLUMN snapshot_record_id bigint NOT NULL REFERENCES handover_snapshot_records(id);
CREATE INDEX qa_citations_snapshot_record_id_idx ON qa_citations(snapshot_record_id);
