package com.projectbaton.handover;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.projectbaton.shared.BusinessCalendar;
import com.projectbaton.shared.IfMatchRequiredException;
import com.projectbaton.shared.InvalidInputException;
import com.projectbaton.shared.JsonIds;
import tools.jackson.databind.JsonNode;

/** 인수인계 controller들이 함께 쓰는 헤더와 본문 해석. 형식이 잘못된 입력은 언제나 400이다(contracts.md C01). */
final class HandoverRequests {
    private HandoverRequests() {
    }

    static UUID idempotencyKey(String key) {
        try {
            return UUID.fromString(key);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new InvalidInputException();
        }
    }

    static long ifMatch(String header) {
        if (header == null) {
            throw new IfMatchRequiredException();
        }
        try {
            return Long.parseLong(header.replace("\"", ""));
        } catch (NumberFormatException invalid) {
            throw new InvalidInputException();
        }
    }

    /**
     * {@code create}이면 CreateHandoverRequest, 아니면 RegenerateHandoverRequest. OAS의 선언대로 새 요청에서
     * includeNoteDerivedRecords의 기본값은 true다. 값이 없으면 예전에는 NullPointerException으로 끝났다.
     */
    static HandoverRequestService.Command command(JsonNode body, boolean create) {
        if (body == null || !body.isObject()) {
            throw new InvalidInputException();
        }
        long outgoing = create ? id(body, "outgoingOwnerId") : 0;
        long incoming = create ? id(body, "incomingOwnerId") : 0;
        LocalDate effectiveOn = create ? date(body, "effectiveOn") : BusinessCalendar.today();
        JsonNode typeValues = body.get("scopeRecordTypes");
        if (typeValues == null || !typeValues.isArray()) {
            throw new InvalidInputException();
        }
        Set<String> types = new HashSet<>();
        for (JsonNode type : typeValues) {
            if (!type.isTextual()) {
                throw new InvalidInputException();
            }
            types.add(type.stringValue());
        }
        JsonNode include = body.get("includeNoteDerivedRecords");
        boolean includeNotes;
        if (include == null && create) {
            includeNotes = true;
        } else if (include != null && include.isBoolean()) {
            includeNotes = include.booleanValue();
        } else {
            throw new InvalidInputException();
        }
        return new HandoverRequestService.Command(outgoing, incoming, effectiveOn, date(body, "scopeFrom"), date(body, "scopeTo"),
                types, includeNotes);
    }

    static long id(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (!JsonIds.isId(value)) {
            throw new InvalidInputException();
        }
        return value.longValue();
    }

    private static LocalDate date(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual()) {
            throw new InvalidInputException();
        }
        try {
            return LocalDate.parse(value.stringValue());
        } catch (DateTimeParseException invalid) {
            throw new InvalidInputException();
        }
    }
}
