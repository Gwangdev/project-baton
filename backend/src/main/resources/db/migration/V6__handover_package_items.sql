DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM handover_items) THEN
        RAISE EXCEPTION 'V6 cannot infer a generation for existing handover_items; migrate historical items explicitly before applying this version';
    END IF;
END $$;

INSERT INTO handover_generations(handover_id, generation, project_id, created_by, status, created_at)
SELECT h.id, h.generation, h.project_id, h.requested_by, h.status, h.requested_at
FROM handovers h
ON CONFLICT (handover_id, generation) DO NOTHING;

ALTER TABLE handover_generations
    ADD CONSTRAINT handover_generations_project_handover_id_id_key UNIQUE (project_id, handover_id, id);

ALTER TABLE handover_items
    ADD COLUMN generation_id bigint,
    ADD COLUMN project_id bigint,
    ADD COLUMN version bigint NOT NULL DEFAULT 0;

ALTER TABLE handover_items
    ALTER COLUMN generation_id SET NOT NULL,
    ALTER COLUMN project_id SET NOT NULL,
    DROP CONSTRAINT handover_items_order_key,
    ADD CONSTRAINT handover_items_project_handover_generation_fkey
        FOREIGN KEY (project_id, handover_id, generation_id)
        REFERENCES handover_generations(project_id, handover_id, id),
    ADD CONSTRAINT handover_items_project_work_record_fkey
        FOREIGN KEY (project_id, work_record_id)
        REFERENCES work_records(project_id, id),
    ADD CONSTRAINT handover_items_generation_display_order_key UNIQUE (generation_id, display_order),
    ADD CONSTRAINT handover_items_generation_kind_record_key UNIQUE (generation_id, item_kind, work_record_id);

CREATE INDEX handover_items_project_generation_idx ON handover_items(project_id, generation_id);
