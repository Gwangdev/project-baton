#!/usr/bin/env python3
"""승인된 공개 API 계약과 SPEC이 어긋나면 바로 실패한다."""

from __future__ import annotations

import argparse
import re
from pathlib import Path
from typing import Any

import yaml


class ContractError(ValueError):
    pass


class StrictSafeLoader(yaml.SafeLoader):
    pass


def _construct_unique_mapping(loader: StrictSafeLoader, node: yaml.nodes.MappingNode, deep: bool = False) -> dict[Any, Any]:
    mapping: dict[Any, Any] = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise ContractError(f"duplicate YAML key: {key}")
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


StrictSafeLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _construct_unique_mapping)


def load_yaml(text: str) -> dict[str, Any]:
    return yaml.load(text, Loader=StrictSafeLoader)


HTTP_METHODS = {"get", "post", "put", "patch", "delete"}
FORBIDDEN_PUBLIC_FIELDS = {"credentialHash", "credential_hash", "correctChoiceId", "acceptedAnswers", "expectedAnswer"}
REQUIRED_NEW_OPERATIONS = {
    "getCsrfToken",
    "createRecordRelation",
    "createManualDraft",
    "retrySourceProcessing",
    "regenerateHandover",
    "acknowledgeHandoverItem",
}
EXPECTED_DBML_TABLES = {
    "organizations", "users", "projects", "project_members", "source_connections",
    "record_sources", "personal_notes", "work_records", "record_revisions",
    "record_relations", "handovers", "handover_scope_types", "handover_items",
    "qa_messages", "qa_citations", "assessments", "assessment_questions",
    "assessment_attempts", "assessment_answers", "jobs", "idempotency_requests",
    "audit_events", "handover_generations", "handover_snapshot_records",
    "handover_item_conflicts", "handover_item_acknowledgements", "record_embeddings",
    "spring_session", "spring_session_attributes", "credential_tokens",
}
REQUIRED_DBML_FIELDS = {
    "users": {"credential_hash", "auth_provider", "external_subject", "external_issuer", "version"},
    "source_connections": {"connector_kind", "checkpoint", "version"},
    "record_sources": {"project_id", "external_document_id", "external_version", "content_hash", "source_body", "processing_status", "failure_code", "source_author_name"},
    "work_records": {"author_name", "version", "source_version", "extraction_index", "search_text"},
    "record_revisions": {"before_data", "after_data"},
    "record_relations": {"project_id", "from_record_id", "to_record_id"},
    "handovers": {"generation", "version", "revision_comment"},
    "handover_items": {"generation_id", "project_id", "version"},
    "qa_messages": {"generation_id", "reply_to_id", "failure_code", "model_id", "prompt_version"},
    "qa_citations": {"snapshot_record_id"},
    "assessments": {"generation_id", "generation"},
    "assessment_questions": {"choices", "accepted_answers", "correct_choice_id", "generation_id"},
    "jobs": {"kind", "target_id", "generation", "dedup_key", "status", "attempts", "available_at", "deadline_at", "lease_token", "lease_until", "failure_code"},
    "idempotency_requests": {"actor_id", "method", "path", "key", "request_hash", "state", "target_id", "http_status", "response_json", "expires_at"},
    "audit_events": {"actor_id", "action", "target_type", "target_id", "result", "trace_id", "occurred_at"},
}
VERSIONED_COMMANDS = {
    "reviewRecord", "updateSourceConnection", "deleteSourceConnection",
    "retryHandoverGeneration", "resolveHandoverItem", "decideHandoverCompletion",
    "regenerateHandover", "acknowledgeHandoverItem", "updateOrganization",
    "deleteOrganization", "updateUser", "deleteUser", "updateProject",
    "deleteProject", "updateProjectMember", "deleteProjectMember",
}
# Idempotency-Key를 받지 않는 쓰기. 로그인과 로그아웃은 업무 데이터가 아니라 세션을 바꾼다. 자격 증명 명령 둘은 저장된
# 응답으로 재실행할 수 없다. 발급 응답은 평문 일회용 코드이고, 자격 증명 설정은 코드를 소모해서 이미 한 번만 쓰인다
# (contracts.md C11, 설정 코드 개정).
AUTH_EXEMPT_WRITES = {"signIn", "signOut", "setCredential", "issueCredentialToken"}
COMMON_WRITE_ERRORS = {"400", "401", "403", "409"}
# 영속 작업을 큐에 넣는 명령. README 6은 포화된 큐에 503 + Retry-After로 답한다.
QUEUE_COMMANDS = {"requestHandover", "retryHandoverGeneration", "regenerateHandover", "retrySourceProcessing",
                  "reviewRecord", "sharePersonalNoteSummary"}
# 패키지 생성이 회차마다 만드는 자원의 읽기. 요청이나 재생성과 그 생성의 끝 사이에는 돌려줄 것이 없어서 409 INVALID_STATE로
# 답한다(60 RPS 부하 실행을 검토하다 찾았다).
GENERATION_READS = {"getAssessment"}
MUTABLE_DTOS = {"Organization", "User", "Project", "ProjectMember", "SourceConnection", "WorkRecord", "Handover", "HandoverItem"}
PAGED_LISTS = {"SourceConnectionList", "PersonalNoteList", "HandoverList", "HandoverItemList"}


def endpoint_set(specification: dict[str, Any]) -> set[tuple[str, str]]:
    return {
        (entry.split(maxsplit=1)[0], entry.split(maxsplit=1)[1])
        for entry in specification.get("endpoints", [])
    }


def resolve_local_ref(document: dict[str, Any], ref: str) -> None:
    if not ref.startswith("#/"):
        raise ContractError(f"non-local $ref is not permitted: {ref}")
    value: Any = document
    for part in ref[2:].split("/"):
        if not isinstance(value, dict) or part not in value:
            raise ContractError(f"unresolvable local $ref: {ref}")
        value = value[part]


def walk(value: Any, document: dict[str, Any]) -> None:
    if isinstance(value, dict):
        if "$ref" in value:
            resolve_local_ref(document, value["$ref"])
        for child in value.values():
            walk(child, document)
    elif isinstance(value, list):
        for child in value:
            walk(child, document)


def parameter_names(operation: dict[str, Any], document: dict[str, Any]) -> set[str]:
    names: set[str] = set()
    for parameter in operation.get("parameters", []):
        if "$ref" in parameter:
            value: Any = document
            for part in parameter["$ref"][2:].split("/"):
                value = value[part]
            parameter = value
        names.add(parameter.get("name", ""))
    return names


def dereference(value: dict[str, Any], document: dict[str, Any]) -> dict[str, Any]:
    if "$ref" not in value:
        return value
    resolved: Any = document
    for part in value["$ref"][2:].split("/"):
        resolved = resolved[part]
    return resolved


def validate_path_parameters(path: str, item: dict[str, Any], operation: dict[str, Any], document: dict[str, Any]) -> None:
    declared = {}
    for value in item.get("parameters", []) + operation.get("parameters", []):
        parameter = dereference(value, document)
        if parameter.get("in") == "path":
            declared[parameter.get("name")] = parameter
    for name in re.findall(r"{([^}]+)}", path):
        parameter = declared.get(name)
        schema = (parameter or {}).get("schema", {})
        if not parameter or parameter.get("required") is not True or schema.get("type") != "integer" or schema.get("format") != "int64":
            raise ContractError(f"path parameter {name} must be a required int64")


def validate_schema_nodes(value: Any, location: str = "root") -> None:
    if isinstance(value, dict):
        properties = value.get("properties", {})
        required = value.get("required", [])
        if isinstance(required, list):
            for field in required:
                if field not in properties:
                    raise ContractError(f"required field missing from properties: {location}.{field}")
        for field, child in properties.items():
            if field in FORBIDDEN_PUBLIC_FIELDS:
                raise ContractError(f"forbidden public field {field} in {location}")
            validate_schema_nodes(child, f"{location}.{field}")
        if value.get("type") == "integer" and value.get("format") == "int64":
            if value.get("maximum") != 9007199254740991:
                raise ContractError(f"int64 at {location} requires safe integer maximum")
        for key, child in value.items():
            if key != "properties":
                validate_schema_nodes(child, f"{location}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            validate_schema_nodes(child, f"{location}[{index}]")


def validate_contract(document: dict[str, Any], spec_endpoints: set[tuple[str, str]]) -> None:
    paths = document.get("paths", {})
    operation_ids: set[str] = set()
    operation_responses: dict[str, set[str]] = {}
    oas_endpoints: set[tuple[str, str]] = set()
    for path, item in paths.items():
        for method, operation in item.items():
            if method not in HTTP_METHODS:
                continue
            operation_id = operation.get("operationId")
            if not operation_id:
                raise ContractError(f"missing operationId for {method.upper()} {path}")
            if operation_id in operation_ids:
                raise ContractError(f"duplicate operationId: {operation_id}")
            operation_ids.add(operation_id)
            operation_responses[operation_id] = set(operation.get("responses", {}))
            oas_endpoints.add((method.upper(), f"/api/v1{path}"))
            validate_path_parameters(path, item, operation, document)
            headers = parameter_names(operation, document)
            if method in {"post", "put", "patch", "delete"}:
                if operation_id in AUTH_EXEMPT_WRITES and "X-CSRF-TOKEN" not in headers:
                    raise ContractError(f"{operation_id} requires CSRF header")
                if operation_id not in AUTH_EXEMPT_WRITES and not {"X-CSRF-TOKEN", "Idempotency-Key"}.issubset(headers):
                    raise ContractError(f"authenticated write {operation_id} requires CSRF and Idempotency-Key headers")
            if operation_id in VERSIONED_COMMANDS:
                headers = parameter_names(operation, document)
                if "If-Match" not in headers or not {"412", "428"}.issubset(operation.get("responses", {})):
                    raise ContractError(f"versioned command {operation_id} requires If-Match and 412/428 responses")
                if method != "delete":
                    for status in {"200", "201", "202"} & set(operation.get("responses", {})):
                        response_headers = operation["responses"][status].get("headers", {})
                        if "ETag" not in response_headers:
                            raise ContractError(f"versioned command {operation_id} success response requires ETag")
            if method in {"post", "put", "patch", "delete"} and operation_id not in AUTH_EXEMPT_WRITES:
                missing_errors = COMMON_WRITE_ERRORS - set(operation.get("responses", {}))
                if missing_errors:
                    raise ContractError(f"authenticated write {operation_id} lacks common error responses")
    if oas_endpoints != spec_endpoints:
        raise ContractError("SPEC/OAS endpoint mismatch")
    full_baton_contract = REQUIRED_NEW_OPERATIONS.issubset(operation_ids)
    if full_baton_contract and (len(paths) != 42 or len(operation_ids) != 56):
        raise ContractError("approved contract requires exactly 42 paths and 56 operations")
    if full_baton_contract:
        for operation_id, statuses in sorted(operation_responses.items()):
            if "500" not in statuses:
                raise ContractError(f"{operation_id} lacks 500 InternalServerError response")
        for operation_id in sorted(QUEUE_COMMANDS & operation_ids):
            if "503" not in operation_responses[operation_id]:
                raise ContractError(f"queue command {operation_id} lacks 503 ServiceUnavailable response")
        for operation_id in sorted(GENERATION_READS & operation_ids):
            if "409" not in operation_responses[operation_id]:
                raise ContractError(f"generation read {operation_id} lacks 409 Conflict response")
    walk(document, document)
    schemas = document.get("components", {}).get("schemas", {})
    if full_baton_contract and len(schemas) != 86:
        raise ContractError("approved contract requires exactly 86 schemas")
    for name in MUTABLE_DTOS:
        if name in schemas and "version" not in schemas[name].get("properties", {}):
            raise ContractError(f"mutable DTO {name} requires version field")
    for name in PAGED_LISTS:
        if name in schemas and "page" not in schemas[name].get("properties", {}):
            raise ContractError(f"list DTO {name} requires page field")
    if "AssessmentQuestion" in schemas and "choices" not in schemas["AssessmentQuestion"].get("properties", {}):
        raise ContractError("AssessmentQuestion requires public choices")
    if "AssessmentDetail" in schemas and "generation" not in schemas["AssessmentDetail"].get("properties", {}):
        raise ContractError("AssessmentDetail requires generation")
    validate_schema_nodes(document)
    sign_out = paths.get("/auth/sign-out", {}).get("post", {})
    if sign_out.get("security") == []:
        raise ContractError("signOut must require cookieAuth")
    ask = paths.get("/handovers/{handoverId}/questions", {}).get("post", {})
    sse = ask.get("responses", {}).get("200", {}).get("content", {})
    if ask and "text/event-stream" not in sse:
        raise ContractError("askQuestion requires text/event-stream response")
    limit = document.get("components", {}).get("parameters", {}).get("Limit")
    if limit and limit.get("schema", {}).get("maximum") != 100:
        raise ContractError("limit maximum must be 100")
    expected_fr = {f"FR-{number:02d}" for number in range(1, 23)}
    mapping = document.get("x-fr-requirements", {})
    if set(mapping) != expected_fr:
        raise ContractError("FR mapping must contain FR-01 through FR-22")
    unknown = {op for values in mapping.values() for op in values if op not in operation_ids}
    if unknown:
        raise ContractError(f"FR mapping references unknown operations: {', '.join(sorted(unknown))}")
    missing = REQUIRED_NEW_OPERATIONS - operation_ids
    if missing:
        raise ContractError(f"missing approved operations: {', '.join(sorted(missing))}")


def validate_dbml(text: str) -> None:
    tables = set(re.findall(r"^Table\s+([A-Za-z_][A-Za-z0-9_]*)\s*{", text, re.MULTILINE))
    if tables != EXPECTED_DBML_TABLES:
        missing = sorted(EXPECTED_DBML_TABLES - tables)
        extra = sorted(tables - EXPECTED_DBML_TABLES)
        raise ContractError(f"DBML table set mismatch; missing={missing}, extra={extra}")
    if re.search(r"^\s*expected_answer\s", text, re.MULTILINE):
        raise ContractError("legacy expected_answer must not remain in DBML")
    for table, required_fields in REQUIRED_DBML_FIELDS.items():
        match = re.search(rf"^Table\s+{table}\s*\{{\n(.*?)(?=^\}})", text, re.MULTILINE | re.DOTALL)
        fields = set(re.findall(r"^  ([a-z_][A-Za-z0-9_]*)\s+", match.group(1), re.MULTILINE)) if match else set()
        missing_fields = required_fields - fields
        if missing_fields:
            field = sorted(missing_fields)[0]
            raise ContractError(f"missing DBML field {table}.{field}")
    composite_refs = {
        "record_relations.(project_id, from_record_id) > work_records.(project_id, id)",
        "record_relations.(project_id, to_record_id) > work_records.(project_id, id)",
        "work_records.(project_id, record_source_id) > record_sources.(project_id, id)",
        "work_records.(project_id, source_note_id) > personal_notes.(project_id, id)",
    }
    declared_refs = set(re.findall(r"^Ref:\s*(.+)$", text, re.MULTILINE))
    if not composite_refs.issubset(declared_refs):
        raise ContractError("missing required project-scoped composite reference")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("openapi", type=Path, nargs="?", default=Path("ProjectBaton-API.yml"))
    parser.add_argument("spec", type=Path, nargs="?", default=Path("SPEC.yaml"))
    parser.add_argument("--dbml", type=Path, default=Path("ProjectBaton-DB.dbml"))
    args = parser.parse_args()
    document = load_yaml(args.openapi.read_text())
    specification = load_yaml(args.spec.read_text())
    validate_contract(document, endpoint_set(specification))
    validate_dbml(args.dbml.read_text())
    print("backend contract: valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
