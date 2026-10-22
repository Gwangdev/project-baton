package com.projectbaton.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** operation 두 개짜리 명세로 검사기 자체를 시험해서, 망가진 검사기가 실제 실행을 조용히 통과시키지 못하게 한다. */
class ResponseContractCheckTest {
    private static final String SPEC = """
            paths:
              /things/{thingId}:
                get:
                  responses:
                    '200':
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/Thing'
                    '404':
                      description: missing
            components:
              schemas:
                Thing:
                  type: object
                  required: [id, name]
                  properties:
                    id: {type: integer}
                    name: {type: string}
                    note: {type: string, nullable: true}
                    kind: {type: string, enum: [A, B]}
            """;

    private final ResponseContractCheck check = new ResponseContractCheck(new Yaml().load(SPEC));

    @Test
    void acceptsADeclaredResponseThatFitsItsSchema() {
        assertEquals(List.of(), check.check("GET", "/api/v1/things/7", 200, "application/json", "{\"id\":7,\"name\":\"a\",\"note\":null,\"kind\":\"A\"}"));
        assertEquals(List.of(), check.check("GET", "/api/v1/things/7", 404, "application/problem+json", "{}"));
    }

    @Test
    void reportsAnUndeclaredStatusAndAnUnknownOperation() {
        assertEquals(List.of("GET /things/{thingId} 403: status not declared"),
                check.check("GET", "/api/v1/things/7", 403, "application/problem+json", "{}"));
        assertEquals(List.of("POST /api/v1/things/7 400: no such operation in the OAS"),
                check.check("POST", "/api/v1/things/7", 400, "application/json", "{}"));
        assertEquals(List.of(), check.check("POST", "/api/v1/things/7", 405, "application/problem+json", "{}"));
    }

    @Test
    void reportsMissingRequiredUndeclaredNullAndWronglyTypedFields() {
        List<String> violations = check.check("GET", "/api/v1/things/7", 200, "application/json",
                "{\"id\":\"7\",\"note\":null,\"extra\":1,\"kind\":\"C\"}");

        assertEquals(List.of(
                "GET /things/{thingId} 200: $.name is required but missing",
                "GET /things/{thingId} 200: $.id should be an integer",
                "GET /things/{thingId} 200: $.extra is not declared",
                "GET /things/{thingId} 200: $.kind value C is not in the enum"), violations);
        assertEquals(List.of("GET /things/{thingId} 200: $.name is null but not nullable"),
                check.check("GET", "/api/v1/things/7", 200, "application/json", "{\"id\":7,\"name\":null}"));
    }

    private static final String TWO_OPERATIONS = """
            paths:
              /things/{thingId}:
                get:
                  responses:
                    '200': {description: ok}
                    '500': {description: failure}
                delete:
                  responses:
                    '204': {description: gone}
                    '500': {description: failure}
            """;

    @Test
    void anOperationNoTestCalledIsReportedAtTheEndOfTheRun() {
        ResponseContractCheck run = new ResponseContractCheck(new Yaml().load(TWO_OPERATIONS));
        run.check("GET", "/api/v1/things/7", 200, null, "");

        assertEquals(List.of("DELETE /things/{thingId}: no integration test called this operation"), run.finish(1));
        run.check("DELETE", "/api/v1/things/7", 204, null, "");
        assertEquals(List.of(), run.finish(2));
    }

    @Test
    void aRunThatObservedNothingFails() {
        ResponseContractCheck run = new ResponseContractCheck(new Yaml().load(TWO_OPERATIONS));

        assertEquals("no responses were observed; the integration tests did not record anything", run.finish(0).getFirst());
    }

    /** 모든 operation이 500을 선언해서, 상태 확인만으로는 서버 오류로 드러나는 결함을 잡지 못한다. */
    @Test
    void aServerErrorIsAViolationUnlessTheListOfIntendedFailuresNamesIt() {
        ResponseContractCheck strict = new ResponseContractCheck(new Yaml().load(TWO_OPERATIONS));
        assertEquals(List.of("GET /things/{thingId} 500: unexpected server error"),
                strict.check("GET", "/api/v1/things/7", 500, "application/problem+json", "{}"));

        ResponseContractCheck lenient = new ResponseContractCheck(new Yaml().load(TWO_OPERATIONS),
                Map.of("GET /things/{thingId}", "injected outage"));
        lenient.check("DELETE", "/api/v1/things/7", 204, null, "");
        assertEquals(List.of(), lenient.check("GET", "/api/v1/things/7", 500, "application/problem+json", "{}"));
        assertEquals(List.of("DELETE /things/{thingId} 500: unexpected server error"),
                lenient.check("DELETE", "/api/v1/things/7", 500, "application/problem+json", "{}"));
    }

    @Test
    void anIntendedFailureThatNoTestProducesAnymoreIsReported() {
        ResponseContractCheck run = new ResponseContractCheck(new Yaml().load(TWO_OPERATIONS),
                Map.of("GET /things/{thingId}", "injected outage"));
        run.check("GET", "/api/v1/things/7", 200, null, "");
        run.check("DELETE", "/api/v1/things/7", 204, null, "");

        assertEquals(List.of("GET /things/{thingId}: listed as an intended server error but no test produced one"), run.finish(2));
    }

    @Test
    void theListOfIntendedFailuresNeedsAReasonForEveryEntry() {
        assertEquals(Map.of("POST /a/{id}", "audit outage"), ResponseContractCheck.intendedServerErrors(
                new StringReader("# comment\n\nPOST /a/{id} | audit outage\n")));
        assertThrows(IllegalArgumentException.class, () -> ResponseContractCheck.intendedServerErrors(new StringReader("POST /a/{id}\n")));
        assertThrows(IllegalArgumentException.class, () -> ResponseContractCheck.intendedServerErrors(new StringReader("POST /a/{id} |  \n")));
    }
}
