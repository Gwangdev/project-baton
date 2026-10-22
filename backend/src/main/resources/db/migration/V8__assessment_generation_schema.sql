DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM assessment_questions WHERE question_type = 'MULTIPLE_CHOICE'
    ) THEN
        RAISE EXCEPTION 'V8 cannot safely convert existing MULTIPLE_CHOICE expected_answer values without reviewed choices';
    END IF;
END $$;

ALTER TABLE handover_generations
    ADD CONSTRAINT handover_generations_id_generation_key UNIQUE (id, generation),
    ADD CONSTRAINT handover_generations_handover_id_id_key UNIQUE (handover_id, id);

ALTER TABLE assessments
    DROP CONSTRAINT assessments_handover_id_key,
    ADD COLUMN generation_id bigint,
    ADD COLUMN generation integer;

UPDATE assessments assessment
SET generation_id = generation.id,
    generation = generation.generation
FROM handover_generations generation
JOIN handovers handover ON handover.id = generation.handover_id
WHERE assessment.handover_id = handover.id
  AND generation.generation = handover.generation;

ALTER TABLE assessments
    ALTER COLUMN generation_id SET NOT NULL,
    ALTER COLUMN generation SET NOT NULL,
    ADD CONSTRAINT assessments_handover_generation_key UNIQUE (handover_id, generation),
    ADD CONSTRAINT assessments_generation_id_key UNIQUE (generation_id),
    ADD CONSTRAINT assessments_generation_id_id_key UNIQUE (generation_id, id),
    ADD CONSTRAINT assessments_handover_generation_fkey FOREIGN KEY (handover_id, generation_id)
        REFERENCES handover_generations(handover_id, id),
    ADD CONSTRAINT assessments_generation_number_fkey FOREIGN KEY (generation_id, generation)
        REFERENCES handover_generations(id, generation);

CREATE FUNCTION assessment_choices_valid(choices jsonb, correct_choice_id varchar)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT jsonb_typeof(choices) = 'array'
       AND jsonb_array_length(choices) BETWEEN 2 AND 5
       AND NOT EXISTS (
            SELECT 1
            FROM jsonb_array_elements(choices) choice
            WHERE jsonb_typeof(choice) <> 'object'
               OR jsonb_typeof(choice -> 'id') <> 'string'
               OR jsonb_typeof(choice -> 'text') <> 'string'
               OR length(choice ->> 'id') NOT BETWEEN 1 AND 32
               OR length(choice ->> 'text') NOT BETWEEN 1 AND 300
       )
       AND (SELECT count(*) = count(DISTINCT choice ->> 'id') FROM jsonb_array_elements(choices) choice)
       AND EXISTS (
            SELECT 1 FROM jsonb_array_elements(choices) choice
            WHERE choice ->> 'id' = correct_choice_id
       );
$$;

CREATE FUNCTION assessment_accepted_answers_valid(accepted_answers jsonb)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT jsonb_typeof(accepted_answers) = 'array'
       AND jsonb_array_length(accepted_answers) > 0
       AND NOT EXISTS (
            SELECT 1 FROM jsonb_array_elements(accepted_answers) answer
            WHERE jsonb_typeof(answer) <> 'string'
               OR length(btrim(answer #>> '{}')) = 0
               OR length(answer #>> '{}') > 300
       );
$$;

ALTER TABLE assessment_questions
    ADD COLUMN generation_id bigint,
    ADD COLUMN choices jsonb,
    ADD COLUMN accepted_answers jsonb,
    ADD COLUMN correct_choice_id varchar(32);

UPDATE assessment_questions question
SET generation_id = assessment.generation_id,
    accepted_answers = jsonb_build_array(question.expected_answer)
FROM assessments assessment
WHERE question.assessment_id = assessment.id
  AND question.question_type = 'SHORT_ANSWER';

ALTER TABLE assessment_questions
    ALTER COLUMN generation_id SET NOT NULL,
    DROP COLUMN expected_answer,
    ADD CONSTRAINT assessment_questions_generation_id_id_key UNIQUE (generation_id, id),
    ADD CONSTRAINT assessment_questions_generation_assessment_fkey FOREIGN KEY (generation_id, assessment_id)
        REFERENCES assessments(generation_id, id),
    ADD CONSTRAINT assessment_questions_generation_snapshot_record_fkey FOREIGN KEY (generation_id, linked_record_id)
        REFERENCES handover_snapshot_records(generation_id, work_record_id),
    ADD CONSTRAINT assessment_questions_answer_shape CHECK (
        (question_type = 'MULTIPLE_CHOICE'
            AND choices IS NOT NULL
            AND correct_choice_id IS NOT NULL
            AND accepted_answers IS NULL
            AND assessment_choices_valid(choices, correct_choice_id))
        OR
        (question_type = 'SHORT_ANSWER'
            AND choices IS NULL
            AND correct_choice_id IS NULL
            AND accepted_answers IS NOT NULL
            AND assessment_accepted_answers_valid(accepted_answers))
    );

ALTER TABLE assessment_attempts
    ADD COLUMN generation_id bigint;

UPDATE assessment_attempts attempt
SET generation_id = assessment.generation_id
FROM assessments assessment
WHERE attempt.assessment_id = assessment.id;

ALTER TABLE assessment_attempts
    ALTER COLUMN generation_id SET NOT NULL,
    ADD CONSTRAINT assessment_attempts_generation_id_id_key UNIQUE (generation_id, id),
    ADD CONSTRAINT assessment_attempts_generation_assessment_fkey FOREIGN KEY (generation_id, assessment_id)
        REFERENCES assessments(generation_id, id);

ALTER TABLE assessment_answers
    ADD COLUMN generation_id bigint;

UPDATE assessment_answers answer
SET generation_id = attempt.generation_id
FROM assessment_attempts attempt
WHERE answer.attempt_id = attempt.id;

ALTER TABLE assessment_answers
    ALTER COLUMN generation_id SET NOT NULL,
    ADD CONSTRAINT assessment_answers_generation_attempt_fkey FOREIGN KEY (generation_id, attempt_id)
        REFERENCES assessment_attempts(generation_id, id),
    ADD CONSTRAINT assessment_answers_generation_question_fkey FOREIGN KEY (generation_id, question_id)
        REFERENCES assessment_questions(generation_id, id);
