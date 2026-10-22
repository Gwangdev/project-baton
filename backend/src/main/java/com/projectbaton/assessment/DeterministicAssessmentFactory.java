package com.projectbaton.assessment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

@Service
public class DeterministicAssessmentFactory {
    private static final List<String> RECORD_TYPES = List.of(
            "SCHEDULE", "CHANGE_DECISION", "COMMITMENT", "BLOCKER", "SCOPE_EXCEPTION", "OPEN_ISSUE");
    // 문항은 한국어 화면에서 풀고, 보기 이름은 화면의 기록 유형 이름(frontend/src/components/labels.ts)과 같아서
    // 보기와 그 보기가 가리키는 기록이 같은 이름으로 읽힌다.
    private static final Map<String, String> RECORD_TYPE_LABELS = Map.of(
            "SCHEDULE", "일정", "CHANGE_DECISION", "변경 결정", "COMMITMENT", "고객 약속",
            "BLOCKER", "장애요인", "SCOPE_EXCEPTION", "범위 예외", "OPEN_ISSUE", "미해결 결정");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public DeterministicAssessmentFactory(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Question> plan(List<SnapshotFact> snapshots) {
        List<Question> questions = new ArrayList<>();
        for (SnapshotFact snapshot : snapshots) {
            Question question = question(snapshot, questions.size() + 1);
            if (question != null) {
                questions.add(question);
            }
            if (questions.size() == 5) {
                return questions;
            }
        }
        throw new InsufficientAssessmentEvidenceException();
    }

    public void persist(long handoverId, long generationId, int generation, List<Question> questions) {
        long assessmentId = jdbc.queryForObject("""
                insert into assessments(handover_id,generation_id,generation,status,pass_threshold)
                values(?,?,?,'NOT_STARTED',5) returning id
                """, Long.class, handoverId, generationId, generation);
        for (Question question : questions) {
            jdbc.update("""
                    insert into assessment_questions(assessment_id,generation_id,question_no,question_type,prompt,choices,accepted_answers,correct_choice_id,linked_record_id)
                    values(?,?,?,cast(? as question_type),?,cast(? as jsonb),cast(? as jsonb),?,?)
                    """, assessmentId, generationId, question.questionNo(), question.questionType(), question.prompt(),
                    question.choicesJson(), question.acceptedAnswersJson(), question.correctChoiceId(), question.linkedRecordId());
        }
    }

    private Question question(SnapshotFact snapshot, int questionNo) {
        if (!validCommonFields(snapshot)) {
            return null;
        }
        if ("SCHEDULE".equals(snapshot.recordType()) && snapshot.dueOn() != null) {
            return dateQuestion(snapshot, questionNo);
        }
        if (!RECORD_TYPES.contains(snapshot.recordType())) {
            return null;
        }
        return recordTypeQuestion(snapshot, questionNo);
    }

    private boolean validCommonFields(SnapshotFact snapshot) {
        return snapshot.title() != null && !snapshot.title().isBlank()
                && snapshot.summary() != null && !snapshot.summary().isBlank()
                && snapshot.recordType() != null && !snapshot.recordType().isBlank()
                && snapshot.occurredOn() != null;
    }

    private Question dateQuestion(SnapshotFact snapshot, int questionNo) {
        String prompt = "'" + snapshot.title() + "'의 확정 기한은 언제인가?";
        if (prompt.length() > 300) {
            return null;
        }
        ArrayNode answers = json.createArrayNode();
        answers.add(snapshot.dueOn().toString());
        answers.add(snapshot.dueOn().getMonthValue() + "월 " + snapshot.dueOn().getDayOfMonth() + "일");
        answers.add(snapshot.dueOn().getMonthValue() + "/" + snapshot.dueOn().getDayOfMonth());
        return new Question(questionNo, "SHORT_ANSWER", prompt, null, answers.toString(), null, snapshot.recordId());
    }

    private Question recordTypeQuestion(SnapshotFact snapshot, int questionNo) {
        String prompt = "어떤 유형의 기록인가? '" + snapshot.title() + "' — " + snapshot.summary();
        if (prompt.length() > 300) {
            return null;
        }
        ArrayNode choices = json.createArrayNode();
        addChoice(choices, snapshot.recordType());
        RECORD_TYPES.stream().filter(type -> !type.equals(snapshot.recordType())).limit(3).forEach(type -> addChoice(choices, type));
        String correctChoiceId = choiceId(snapshot.recordType());
        return new Question(questionNo, "MULTIPLE_CHOICE", prompt, choices.toString(), null, correctChoiceId, snapshot.recordId());
    }

    private void addChoice(ArrayNode choices, String recordType) {
        choices.addObject().put("id", choiceId(recordType)).put("text", RECORD_TYPE_LABELS.get(recordType));
    }

    private String choiceId(String recordType) {
        return "TYPE_" + recordType.toLowerCase(Locale.ROOT);
    }

    public record SnapshotFact(long recordId, String recordType, String title, String summary, LocalDate occurredOn, LocalDate dueOn) {
    }

    public record Question(int questionNo, String questionType, String prompt, String choicesJson,
                           String acceptedAnswersJson, String correctChoiceId, long linkedRecordId) {
    }

    public static class InsufficientAssessmentEvidenceException extends RuntimeException {
        public InsufficientAssessmentEvidenceException() {
            super("Five deterministic assessment questions require five complete snapshot records.");
        }
    }
}
