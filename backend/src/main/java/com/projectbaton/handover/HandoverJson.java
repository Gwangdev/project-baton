package com.projectbaton.handover;

import com.projectbaton.shared.PageMeta;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 인수인계 자원을 OAS 모양(Handover, HandoverDetail, HandoverItem, HandoverItemAcknowledgement)으로 그린다. */
@Component
public class HandoverJson {
    private final ObjectMapper json;

    public HandoverJson(ObjectMapper json) {
        this.json = json;
    }

    public ObjectNode summary(HandoverQueryService.Summary handover) {
        ObjectNode node = json.createObjectNode();
        node.put("id", handover.id());
        node.put("code", handover.code());
        node.put("projectId", handover.projectId());
        node.put("status", handover.status());
        node.put("outgoingOwnerName", handover.outgoingOwnerName());
        node.put("incomingOwnerName", handover.incomingOwnerName());
        node.put("effectiveOn", handover.effectiveOn().toString());
        node.put("generation", handover.generation());
        node.put("version", handover.version());
        node.put("progressPercent", handover.progressPercent());
        node.put("requestedAt", handover.requestedAt().toString());
        return node;
    }

    public ObjectNode detail(HandoverQueryService.Detail detail) {
        ObjectNode node = summary(detail.summary());
        node.put("scopeFrom", detail.summary().scopeFrom().toString());
        node.put("scopeTo", detail.summary().scopeTo().toString());
        ArrayNode types = node.putArray("scopeRecordTypes");
        detail.scopeRecordTypes().forEach(types::add);
        node.put("includeNoteDerivedRecords", detail.includeNoteDerivedRecords());
        node.put("includedCount", detail.includedCount());
        putNullable(node, "revisionComment", detail.revisionComment());
        putNullable(node, "failureReason", detail.failureReason());
        ArrayNode conflicts = node.putArray("conflicts");
        for (HandoverQueryService.Conflict conflict : detail.conflicts()) {
            ObjectNode relation = conflicts.addObject();
            relation.put("id", conflict.id());
            relation.put("version", conflict.version());
            relation.put("projectId", conflict.projectId());
            relation.put("fromRecordId", conflict.fromRecordId());
            relation.put("toRecordId", conflict.toRecordId());
            relation.put("relationType", conflict.relationType());
            putNullable(relation, "note", conflict.note());
            // 이 회차를 만든 뒤 철회된 관계도 철회 정보와 함께 목록에 남겨서, 화면이 그 쌍은 더 해소할 수 없고
            // 패키지를 다시 만들어야 한다고 알릴 수 있게 한다.
            putNullable(relation, "withdrawnAt", conflict.withdrawnAt());
            putNullable(relation, "withdrawalReason", conflict.withdrawalReason());
        }
        ArrayNode unconfirmed = node.putArray("unconfirmedRecords");
        detail.unconfirmedRecords().forEach(record -> unconfirmed.add((JsonNode) json.valueToTree(record)));
        ArrayNode blockers = node.putArray("approvalBlockers");
        detail.approvalBlockers().forEach(blockers::add);
        putNullable(node, "readyAt", detail.readyAt());
        putNullable(node, "completedAt", detail.completedAt());
        return node;
    }

    public ObjectNode item(HandoverItemQueryService.Item item) {
        ObjectNode node = itemBase(item.id(), item.itemKind(), item.title(), item.body(), item.displayOrder(), item.isRequired(),
                item.generation(), item.acknowledged(), item.version());
        if (item.conflictId() == null) {
            node.putNull("conflictId");
        } else {
            node.put("conflictId", item.conflictId());
            node.putArray("candidateRecordIds").add(item.fromRecordId()).add(item.toRecordId());
        }
        putNullable(node, "resolvedByRecordCode", item.resolvedByRecordCode());
        putNullable(node, "resolutionNote", item.resolutionNote());
        putNullable(node, "assigneeName", item.assigneeName());
        putNullable(node, "dueOn", item.dueOn());
        if (item.evidence() != null) {
            node.set("evidence", evidence(item.evidence()));
        }
        return node;
    }

    public ObjectNode resolvedItem(HandoverConflictService.ResolvedItem item) {
        ObjectNode node = itemBase(item.id(), item.itemKind(), item.title(), item.body(), item.displayOrder(), item.isRequired(),
                item.generation(), item.acknowledged(), item.version());
        node.put("conflictId", item.conflictId());
        ArrayNode candidates = node.putArray("candidateRecordIds");
        item.candidateRecordIds().forEach(candidates::add);
        node.put("resolvedByRecordCode", item.resolvedByRecordCode());
        node.put("resolutionNote", item.resolutionNote());
        return node;
    }

    public ObjectNode acknowledgement(HandoverAcknowledgementService.Acknowledgement acknowledgement) {
        ObjectNode node = json.createObjectNode();
        node.put("itemId", acknowledgement.itemId());
        node.put("userId", acknowledgement.userId());
        node.put("generation", acknowledgement.generation());
        node.put("acknowledgedAt", acknowledgement.acknowledgedAt().toString());
        return node;
    }

    public ObjectNode page(ArrayNode items, PageMeta page) {
        ObjectNode node = json.createObjectNode();
        node.set("items", items);
        node.set("page", json.valueToTree(page));
        return node;
    }

    public ArrayNode array() {
        return json.createArrayNode();
    }

    private ObjectNode itemBase(long id, String kind, String title, String body, int displayOrder, boolean required,
                                int generation, boolean acknowledged, long version) {
        ObjectNode node = json.createObjectNode();
        node.put("id", id);
        node.put("itemKind", kind);
        node.put("title", title);
        node.put("body", body);
        node.put("displayOrder", displayOrder);
        node.put("isRequired", required);
        node.put("generation", generation);
        node.put("acknowledged", acknowledged);
        node.put("version", version);
        return node;
    }

    private ObjectNode evidence(HandoverItemQueryService.Evidence record) {
        ObjectNode node = json.createObjectNode();
        node.put("id", record.id());
        node.put("code", record.code());
        node.put("projectId", record.projectId());
        node.put("recordType", record.recordType());
        node.put("title", record.title());
        node.put("summary", record.summary());
        node.put("occurredOn", record.occurredOn().toString());
        node.put("trustStatus", record.trustStatus());
        node.put("authorName", record.authorName());
        node.put("version", record.version());
        if (record.dueOn() != null) {
            node.put("dueOn", record.dueOn().toString());
        }
        return node;
    }

    private void putNullable(ObjectNode node, String field, Object value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.toString());
        }
    }
}
