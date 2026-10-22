package com.projectbaton.assessment;

import com.projectbaton.handover.HandoverRequestService;
import com.projectbaton.project.ProjectAccessPolicy;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AssessmentService {
    private final JdbcTemplate jdbc;
    private final ProjectAccessPolicy projects;
    private final ObjectMapper json;

    public AssessmentService(JdbcTemplate jdbc, ProjectAccessPolicy projects, ObjectMapper json) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Detail detail(long actorId, long handoverId) {
        Handover handover = handover(handoverId, false);
        authorizeRead(actorId, handover);
        Assessment assessment = assessment(handoverId, handover.generation(), false);
        return new Detail(handover.generation(), assessment.status(), assessment.passThreshold(), questions(assessment.id()), attempts(assessment.id()));
    }

    @Transactional(readOnly = true)
    public void authorizeSubmission(long actorId, long handoverId) {
        Handover handover = handover(handoverId, false);
        authorizeSubmit(actorId, handover);
        assessment(handoverId, handover.generation(), false);
    }

    @Transactional
    public Attempt submit(long actorId, long handoverId, List<SubmittedAnswer> submitted) {
        Handover handover = handover(handoverId, false);
        lockProject(handover.projectId());
        handover = handover(handoverId, true);
        authorizeSubmit(actorId, handover);
        Assessment assessment = assessment(handoverId, handover.generation(), true);
        List<QuestionKey> questions = questionKeys(assessment.id(), assessment.generationId());
        Map<Long, SubmittedAnswer> byQuestion = validateAnswers(handoverId, submitted, questions);
        int attemptNo = jdbc.queryForObject("select coalesce(max(attempt_no),0)+1 from assessment_attempts where assessment_id=?",
                Integer.class, assessment.id());
        long attemptId = jdbc.queryForObject("""
                insert into assessment_attempts(assessment_id,generation_id,attempt_no,taken_by,score,total,submitted_at)
                values(?,?,?,?,0,?,now()) returning id
                """, Long.class, assessment.id(), assessment.generationId(), attemptNo, actorId, questions.size());
        int score = 0;
        List<Answer> answers = new java.util.ArrayList<>();
        for (QuestionKey question : questions) {
            String answerText = byQuestion.get(question.id()).answerText();
            boolean correct = correct(question, answerText);
            if (correct) score++;
            jdbc.update("""
                    insert into assessment_answers(attempt_id,generation_id,question_id,answer_text,is_correct)
                    values(?,?,?,?,?)
                    """, attemptId, assessment.generationId(), question.id(), answerText, correct);
            answers.add(new Answer(question.id(), question.questionNo(), answerText, correct,
                    correct ? null : question.linkedRecordCode()));
        }
        jdbc.update("update assessment_attempts set score=? where id=?", score, attemptId);
        jdbc.update("update assessments set status='SUBMITTED' where id=?", assessment.id());
        // 보완 요청에는 현재 회차로 다시 제출해 답한다(README §5.2). 승인이 최신 응시를 다시 판정하므로 점수와
        // 상관없이 패키지는 READY로 돌아가고, 버전을 올려 보완 중에 관리자가 받아 둔 If-Match를 낡은 값으로 만든다.
        if ("REVIEW_REQUESTED".equals(handover.status())) {
            jdbc.update("update handovers set status='READY', version=version+1 where id=?", handoverId);
        }
        OffsetDateTime submittedAt = jdbc.queryForObject("select submitted_at from assessment_attempts where id=?", OffsetDateTime.class, attemptId);
        return new Attempt(attemptNo, score, questions.size(), submittedAt, answers);
    }

    /**
     * 정확히 이 회차에서 신규 담당자가 마지막으로 채점받은 응시를 돌려준다. 다시 만든 패키지는 문항이 모두 바뀔 수
     * 있어 이전 패키지의 만점은 새 패키지에 대해 아무것도 말해 주지 않으므로, 잠금 트랜잭션 안에서 도는 승인은 이전
     * 회차로 물러서면 안 된다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LatestResult> latestResult(long handoverId, int generation, long takenBy) {
        return jdbc.query("""
                select attempt.score, assessment.pass_threshold
                from assessments assessment
                join assessment_attempts attempt on attempt.assessment_id=assessment.id and attempt.generation_id=assessment.generation_id
                where assessment.handover_id=? and assessment.generation=? and attempt.taken_by=?
                order by attempt.attempt_no desc limit 1
                """, (rs, row) -> new LatestResult(rs.getInt(1), rs.getInt(2)), handoverId, generation, takenBy).stream().findFirst();
    }

    private Map<Long, SubmittedAnswer> validateAnswers(long handoverId, List<SubmittedAnswer> submitted, List<QuestionKey> questions) {
        if (submitted == null || submitted.size() != questions.size()) throw new InvalidAssessmentInputException();
        Map<Long, QuestionKey> known = new HashMap<>();
        questions.forEach(question -> known.put(question.id(), question));
        Map<Long, SubmittedAnswer> answers = new HashMap<>();
        for (SubmittedAnswer answer : submitted) {
            if (answer.questionId() < 1 || answer.answerText() == null || answer.answerText().isBlank()
                    || answer.answerText().length() > 300 || answers.putIfAbsent(answer.questionId(), answer) != null) {
                throw new InvalidAssessmentInputException();
            }
            if (!known.containsKey(answer.questionId())) {
                if (belongsToHandover(answer.questionId(), handoverId)) {
                    throw new HandoverRequestService.HandoverConflictException("Question is not in the current generation.");
                }
                throw new InvalidAssessmentInputException();
            }
        }
        if (answers.size() != known.size()) throw new InvalidAssessmentInputException();
        return answers;
    }

    private boolean belongsToHandover(long questionId, long handoverId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(
                    select 1 from assessment_questions question join assessments assessment on assessment.id=question.assessment_id
                    where question.id=? and assessment.handover_id=?
                )
                """, Boolean.class, questionId, handoverId));
    }

    private boolean correct(QuestionKey question, String answerText) {
        if ("MULTIPLE_CHOICE".equals(question.questionType())) return question.correctChoiceId().equals(answerText);
        return acceptedAnswers(question.acceptedAnswersJson()).stream().map(this::normalize).anyMatch(normalize(answerText)::equals);
    }

    private List<String> acceptedAnswers(String source) {
        try {
            JsonNode node = json.readTree(source);
            return java.util.stream.StreamSupport.stream(node.spliterator(), false).map(JsonNode::asText).toList();
        } catch (JacksonException exception) {
            throw new IllegalStateException("Persisted assessment answer key is invalid.", exception);
        }
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private List<Question> questions(long assessmentId) {
        return jdbc.query("""
                select question.id,question.question_no,question.question_type::text,question.prompt,record.code,question.choices::text
                from assessment_questions question join work_records record on record.id=question.linked_record_id
                where question.assessment_id=? order by question.question_no
                """, (rs, row) -> new Question(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), choices(rs.getString(6))), assessmentId);
    }

    private List<Choice> choices(String source) {
        if (source == null) return null;
        try {
            JsonNode node = json.readTree(source);
            return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                    .map(choice -> new Choice(choice.required("id").asText(), choice.required("text").asText())).toList();
        } catch (JacksonException exception) {
            throw new IllegalStateException("Persisted assessment choices are invalid.", exception);
        }
    }

    private List<Attempt> attempts(long assessmentId) {
        return jdbc.query("select attempt_no,score,total,submitted_at from assessment_attempts where assessment_id=? order by attempt_no",
                (rs, row) -> new Attempt(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getObject(4, OffsetDateTime.class),
                        attemptAnswers(assessmentId, rs.getInt(1))), assessmentId);
    }

    private List<Answer> attemptAnswers(long assessmentId, int attemptNo) {
        return jdbc.query("""
                select answer.question_id,question.question_no,answer.answer_text,answer.is_correct,record.code
                from assessment_answers answer join assessment_attempts attempt on attempt.id=answer.attempt_id
                join assessment_questions question on question.id=answer.question_id join work_records record on record.id=question.linked_record_id
                where attempt.assessment_id=? and attempt.attempt_no=? order by question.question_no
                """, (rs, row) -> new Answer(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getBoolean(4),
                rs.getBoolean(4) ? null : rs.getString(5)), assessmentId, attemptNo);
    }

    private List<QuestionKey> questionKeys(long assessmentId, long generationId) {
        return jdbc.query("""
                select question.id,question.question_no,question.question_type::text,question.correct_choice_id,question.accepted_answers::text,record.code
                from assessment_questions question join work_records record on record.id=question.linked_record_id
                where question.assessment_id=? and question.generation_id=? order by question.question_no
                """, (rs, row) -> new QuestionKey(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), assessmentId, generationId);
    }

    private void authorizeRead(long actorId, Handover handover) {
        projects.requireMember(actorId, handover.projectId());
        if (actorId != handover.incomingOwnerId() && !projects.isManager(actorId, handover.projectId())) throw new AssessmentAccessDeniedException();
    }

    private void authorizeSubmit(long actorId, Handover handover) {
        projects.requireMember(actorId, handover.projectId());
        if (actorId != handover.incomingOwnerId()) throw new AssessmentAccessDeniedException();
        if (!"READY".equals(handover.status()) && !"REVIEW_REQUESTED".equals(handover.status())) {
            throw new HandoverRequestService.HandoverConflictException("Assessment submission is unavailable in the current handover state.");
        }
    }

    private Handover handover(long handoverId, boolean lock) {
        List<Handover> values = jdbc.query("select project_id,incoming_owner_id,status::text,generation from handovers where id=?" + (lock ? " for update" : ""),
                (rs, row) -> new Handover(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4)), handoverId);
        if (values.isEmpty()) throw new ProjectAccessPolicy.ProjectNotFoundException();
        return values.getFirst();
    }

    private Assessment assessment(long handoverId, int generation, boolean lock) {
        List<Assessment> values = jdbc.query("select id,generation_id,status::text,pass_threshold from assessments where handover_id=? and generation=?" + (lock ? " for update" : ""),
                (rs, row) -> new Assessment(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4)), handoverId, generation);
        if (values.isEmpty()) throw new HandoverRequestService.HandoverConflictException("Current generation assessment is unavailable.");
        return values.getFirst();
    }

    private void lockProject(long projectId) {
        if (jdbc.queryForList("select id from projects where id=? for update", projectId).isEmpty()) throw new ProjectAccessPolicy.ProjectNotFoundException();
    }

    private record Handover(long projectId, long incomingOwnerId, String status, int generation) { }
    private record Assessment(long id, long generationId, String status, int passThreshold) { }
    private record QuestionKey(long id, int questionNo, String questionType, String correctChoiceId, String acceptedAnswersJson, String linkedRecordCode) { }
    public record Detail(int generation, String status, int passThreshold, List<Question> questions, List<Attempt> attempts) { }
    public record Question(long id, int questionNo, String questionType, String prompt, String linkedRecordCode, List<Choice> choices) { }
    public record Choice(String id, String text) { }
    public record Attempt(int attemptNo, int score, int total, OffsetDateTime submittedAt, List<Answer> answers) { }
    public record Answer(long questionId, int questionNo, String answerText, boolean isCorrect, String linkedRecordCode) { }
    public record SubmittedAnswer(long questionId, String answerText) { }
    public record LatestResult(int score, int passThreshold) { public boolean passed() { return score >= passThreshold; } }
    public static class InvalidAssessmentInputException extends RuntimeException { }
    public static class AssessmentAccessDeniedException extends RuntimeException { }
}
