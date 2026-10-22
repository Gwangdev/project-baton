-- Retrieval index for confirmed record summaries (C09, README §7). The column is fixed at 256 dimensions, the size of
-- the offline stub model, because no real embedding model could be pinned yet. Adopting a model with another size
-- needs a new migration that re-creates the column; vectors of different sizes are never mixed in one column.
CREATE EXTENSION vector;

CREATE TABLE record_embeddings (
    id uuid PRIMARY KEY,
    project_id bigint NOT NULL REFERENCES projects(id),
    record_id bigint NOT NULL REFERENCES work_records(id),
    record_version bigint NOT NULL,
    chunk_no integer NOT NULL,
    chunk_text text NOT NULL,
    embedding_model varchar(120) NOT NULL,
    embedding vector(256) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT record_embeddings_record_version_chunk_model_key UNIQUE (record_id, record_version, chunk_no, embedding_model),
    CONSTRAINT record_embeddings_project_record_fkey FOREIGN KEY (project_id, record_id) REFERENCES work_records(project_id, id),
    CONSTRAINT record_embeddings_chunk_no CHECK (chunk_no >= 0),
    CONSTRAINT record_embeddings_record_version CHECK (record_version >= 0)
);
CREATE INDEX record_embeddings_project_id_idx ON record_embeddings(project_id);
