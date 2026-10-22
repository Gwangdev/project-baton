package com.projectbaton.qa;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 공개 QaAnswer와 QaCitation 모양을 만든다. 모든 읽기를 대화 주인으로 거르고, 인용의 원문 이름은 README §4.2의 기록
 * 비공개 규칙을 따른다. 개인 폴더의 파일 이름은 폴더 주인에게만 보이고, 공유한 메모는 비공개 메모 내용이 아니라 공유
 * 메모라고만 표시한다.
 */
@Repository
public class QaHistoryRepository {
    static final String PRIVATE_SOURCE = "비공개 출처";
    static final String SHARED_NOTE = "공유 메모";
    static final String UNKNOWN_SOURCE = "알 수 없는 출처";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public QaHistoryRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public long count(long handoverId, long actorId) {
        Long total = jdbc.queryForObject("select count(*) from qa_messages where handover_id=? and session_user_id=?", Long.class, handoverId, actorId);
        return total == null ? 0 : total;
    }

    public List<ObjectNode> page(long handoverId, long actorId, Long afterId, int limit, int offset) {
        List<Object> arguments = new ArrayList<>(List.of(handoverId, actorId));
        String after = "";
        if (afterId != null) {
            after = " and id > ?";
            arguments.add(afterId);
        }
        arguments.add(limit);
        arguments.add(offset);
        List<Message> messages = jdbc.query("""
                select id, role::text, content, created_at, answer_status::text, suggested_contacts, suggested_documents
                from qa_messages where handover_id=? and session_user_id=?""" + after + " order by id limit ? offset ?",
                (rs, row) -> new Message(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class),
                        rs.getString(5), rs.getString(6), rs.getString(7)), arguments.toArray());
        Map<Long, ArrayNode> citations = citationsOf(messages.stream().filter(message -> "ASSISTANT".equals(message.role()))
                .map(Message::id).toList(), actorId);
        return messages.stream().map(message -> view(message, citations)).toList();
    }

    /** 이 행위자 대화의 assistant 메시지 하나에 대한 QaAnswer. */
    public ObjectNode answer(long assistantId, long actorId) {
        Message message = jdbc.queryForObject("""
                select id, role::text, content, created_at, answer_status::text, suggested_contacts, suggested_documents
                from qa_messages where id=? and role='ASSISTANT' and session_user_id=?
                """, (rs, row) -> new Message(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class),
                rs.getString(5), rs.getString(6), rs.getString(7)), assistantId, actorId);
        return view(message, citationsOf(List.of(assistantId), actorId));
    }

    /** SSE evidence 이벤트에 쓸, 아직 저장하지 않은 snapshot 후보의 인용 모양. */
    public ArrayNode candidates(List<Long> snapshotIds, long actorId) {
        ArrayNode views = json.createArrayNode();
        if (snapshotIds.isEmpty()) return views;
        Map<Long, ObjectNode> byId = new HashMap<>();
        jdbc.query(citationSelect(false) + " where snapshot.id in (" + placeholders(snapshotIds.size()) + ")", rs -> {
            byId.put(rs.getLong("snapshot_id"), citation(rs, actorId, rs.getString("snapshot_trust"), true));
        }, snapshotIds.toArray());
        snapshotIds.stream().distinct().map(byId::get).filter(java.util.Objects::nonNull).forEach(views::add);
        return views;
    }

    private Map<Long, ArrayNode> citationsOf(List<Long> assistantIds, long actorId) {
        if (assistantIds.isEmpty()) return Collections.emptyMap();
        Map<Long, ArrayNode> result = new HashMap<>();
        jdbc.query(citationSelect(true) + " join qa_citations citation on citation.snapshot_record_id=snapshot.id"
                        + " where citation.qa_message_id in (" + placeholders(assistantIds.size()) + ") order by citation.id",
                rs -> {
                    result.computeIfAbsent(rs.getLong("qa_message_id"), id -> json.createArrayNode())
                            .add(citation(rs, actorId, rs.getString("trust_at_answer"), rs.getBoolean("is_primary")));
                }, assistantIds.toArray());
        return result;
    }

    private String citationSelect(boolean stored) {
        return "select snapshot.id as snapshot_id, record.id as record_id, record.code, snapshot.content_json->>'title' as title,"
                + " snapshot.content_json->>'occurredOn' as occurred_on, snapshot.trust_status::text as snapshot_trust,"
                + " source.display_name, connection.owner_user_id, record.source_note_id"
                + (stored ? ", citation.qa_message_id, citation.trust_status_at_answer::text as trust_at_answer, citation.is_primary" : "")
                + " from handover_snapshot_records snapshot"
                + " join work_records record on record.id=snapshot.work_record_id"
                + " left join record_sources source on source.id=record.record_source_id"
                + " left join source_connections connection on connection.id=source.source_connection_id";
    }

    private ObjectNode citation(java.sql.ResultSet rs, long actorId, String trust, boolean primary) throws java.sql.SQLException {
        ObjectNode view = json.createObjectNode();
        view.put("recordId", rs.getLong("record_id"));
        view.put("recordCode", rs.getString("code"));
        view.put("recordTitle", rs.getString("title"));
        view.put("sourceName", sourceName(rs, actorId));
        if (rs.getString("occurred_on") == null) view.putNull("occurredOn"); else view.put("occurredOn", rs.getString("occurred_on"));
        view.put("trustStatusAtAnswer", trust);
        view.put("isPrimary", primary);
        return view;
    }

    private String sourceName(java.sql.ResultSet rs, long actorId) throws java.sql.SQLException {
        if (rs.getObject("source_note_id") != null) return SHARED_NOTE;
        Long owner = rs.getObject("owner_user_id", Long.class);
        if (owner != null && owner != actorId) return PRIVATE_SOURCE;
        String name = rs.getString("display_name");
        return name == null ? UNKNOWN_SOURCE : name;
    }

    private ObjectNode view(Message message, Map<Long, ArrayNode> citations) {
        ObjectNode view = json.createObjectNode();
        view.put("id", message.id());
        view.put("role", message.role());
        view.put("content", message.content());
        view.put("createdAt", message.createdAt().toString());
        if ("ASSISTANT".equals(message.role())) {
            view.put("answerStatus", message.answerStatus());
            view.set("citations", citations.getOrDefault(message.id(), json.createArrayNode()));
            if (message.suggestedContacts() == null) view.putNull("suggestedContacts"); else view.put("suggestedContacts", message.suggestedContacts());
            if (message.suggestedDocuments() == null) view.putNull("suggestedDocuments"); else view.put("suggestedDocuments", message.suggestedDocuments());
        }
        return view;
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private record Message(long id, String role, String content, OffsetDateTime createdAt, String answerStatus,
                           String suggestedContacts, String suggestedDocuments) { }
}
