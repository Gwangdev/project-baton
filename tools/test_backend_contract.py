import copy
import unittest
from pathlib import Path

import yaml

from check_backend_contract import ContractError, endpoint_set, load_yaml, validate_contract, validate_dbml


def valid_contract():
    return {
        "openapi": "3.0.3",
        "paths": {
            "/auth/csrf": {
                "get": {
                    "operationId": "getCsrfToken",
                    "security": [],
                    "responses": {"200": {"description": "ok"}},
                }
            }
        },
        "components": {
            "schemas": {"SafeDto": {"type": "object", "properties": {"name": {"type": "string"}}}}
        },
    }


class BackendContractValidationTest(unittest.TestCase):
    def repository_contract(self):
        document = yaml.safe_load(Path("ProjectBaton-API.yml").read_text())
        specification = yaml.safe_load(Path("SPEC.yaml").read_text())
        return document, endpoint_set(specification)
    def test_rejects_operation_without_internal_server_error_response(self):
        document, specification = self.repository_contract()
        del document["paths"]["/me"]["get"]["responses"]["500"]

        with self.assertRaisesRegex(ContractError, "getCurrentUser lacks 500"):
            validate_contract(document, specification)

    def test_rejects_queue_command_without_service_unavailable_response(self):
        document, specification = self.repository_contract()
        del document["paths"]["/projects/{projectId}/handovers"]["post"]["responses"]["503"]

        with self.assertRaisesRegex(ContractError, "queue command requestHandover lacks 503"):
            validate_contract(document, specification)

    def test_rejects_generation_read_without_conflict_response(self):
        # 인수인계의 현재 회차를 아직 만드는 동안 getAssessment는 409로 답한다(60 RPS 부하 실행을 검토하다 찾았다).
        document, specification = self.repository_contract()
        document["paths"]["/handovers/{handoverId}/assessment"]["get"]["responses"].pop("409", None)

        with self.assertRaisesRegex(ContractError, "generation read getAssessment lacks 409"):
            validate_contract(document, specification)

    def test_repository_declares_conflict_for_generation_reads(self):
        document, specification = self.repository_contract()
        validate_contract(document, specification)
        self.assertIn("409", document["paths"]["/handovers/{handoverId}/assessment"]["get"]["responses"])

    def test_rejects_duplicate_operation_id(self):
        document = valid_contract()
        document["paths"]["/other"] = {"get": copy.deepcopy(document["paths"]["/auth/csrf"]["get"])}

        with self.assertRaisesRegex(ContractError, "duplicate operationId"):
            validate_contract(document, {("GET", "/api/v1/auth/csrf"), ("GET", "/api/v1/other")})

    def test_rejects_secret_field_in_public_dto(self):
        document = valid_contract()
        document["components"]["schemas"]["SafeDto"]["properties"]["credentialHash"] = {"type": "string"}

        with self.assertRaisesRegex(ContractError, "forbidden public field"):
            validate_contract(document, {("GET", "/api/v1/auth/csrf")})

    def test_rejects_spec_path_that_does_not_match_openapi(self):
        with self.assertRaisesRegex(ContractError, "SPEC/OAS endpoint mismatch"):
            validate_contract(valid_contract(), {("GET", "/api/v1/me")})

    def test_rejects_authenticated_write_without_csrf_and_idempotency_headers(self):
        document = valid_contract()
        document["paths"] = {
            "/projects": {
                "post": {"operationId": "createProject", "responses": {"201": {"description": "created"}}}
            }
        }

        with self.assertRaisesRegex(ContractError, "CSRF and Idempotency-Key"):
            validate_contract(document, {("POST", "/api/v1/projects")})

    def test_rejects_versioned_command_without_if_match_and_preconditions(self):
        document = valid_contract()
        document["paths"] = {
            "/records/{recordId}/review": {
                "parameters": [{"name": "recordId", "in": "path", "required": True, "schema": {"type": "integer", "format": "int64"}}],
                "post": {
                    "operationId": "reviewRecord",
                    "parameters": [{"name": "X-CSRF-TOKEN"}, {"name": "Idempotency-Key"}],
                    "responses": {"200": {"description": "reviewed"}},
                }
            }
        }

        with self.assertRaisesRegex(ContractError, "If-Match"):
            validate_contract(document, {("POST", "/api/v1/records/{recordId}/review")})

    def test_rejects_path_parameter_that_is_not_required_integer(self):
        document = valid_contract()
        document["paths"] = {
            "/records/{recordId}": {
                "parameters": [{"name": "recordId", "in": "path", "required": False, "schema": {"type": "string"}}],
                "get": {"operationId": "getRecord", "responses": {"200": {"description": "ok"}}},
            }
        }

        with self.assertRaisesRegex(ContractError, "path parameter"):
            validate_contract(document, {("GET", "/api/v1/records/{recordId}")})

    def test_rejects_required_name_without_property(self):
        document = valid_contract()
        document["components"]["schemas"]["SafeDto"]["required"] = ["missing"]

        with self.assertRaisesRegex(ContractError, "required field missing"):
            validate_contract(document, {("GET", "/api/v1/auth/csrf")})

    def test_rejects_page_limit_without_upper_bound(self):
        document = valid_contract()
        document["components"]["parameters"] = {
            "Limit": {"name": "limit", "in": "query", "schema": {"type": "integer", "minimum": 1}}
        }

        with self.assertRaisesRegex(ContractError, "limit maximum"):
            validate_contract(document, {("GET", "/api/v1/auth/csrf")})

    def test_rejects_authenticated_write_without_common_errors(self):
        document = valid_contract()
        document["paths"] = {
            "/projects": {"post": {
                "operationId": "createProject",
                "parameters": [{"name": "X-CSRF-TOKEN"}, {"name": "Idempotency-Key"}],
                "responses": {"201": {"description": "created"}},
            }}
        }

        with self.assertRaisesRegex(ContractError, "common error responses"):
            validate_contract(document, {("POST", "/api/v1/projects")})

    def test_rejects_incomplete_fr_mapping(self):
        document = valid_contract()
        document["x-fr-requirements"] = {"FR-01": ["getCsrfToken"]}

        with self.assertRaisesRegex(ContractError, "FR mapping"):
            validate_contract(document, {("GET", "/api/v1/auth/csrf")})

    def test_rejects_mutable_dto_without_version(self):
        document, endpoints = self.repository_contract()
        document["components"]["schemas"]["Organization"].get("properties", {}).pop("version", None)
        with self.assertRaisesRegex(ContractError, "version field"):
            validate_contract(document, endpoints)

    def test_rejects_items_only_list_without_page(self):
        document, endpoints = self.repository_contract()
        document["components"]["schemas"]["PersonalNoteList"].get("properties", {}).pop("page", None)
        with self.assertRaisesRegex(ContractError, "page field"):
            validate_contract(document, endpoints)

    def test_rejects_assessment_question_without_choices(self):
        document, endpoints = self.repository_contract()
        document["components"]["schemas"]["AssessmentQuestion"].get("properties", {}).pop("choices", None)
        with self.assertRaisesRegex(ContractError, "choices"):
            validate_contract(document, endpoints)

    def test_rejects_question_operation_without_sse_response(self):
        document, endpoints = self.repository_contract()
        document["paths"]["/handovers/{handoverId}/questions"]["post"]["responses"].pop("200", None)
        with self.assertRaisesRegex(ContractError, "text/event-stream"):
            validate_contract(document, endpoints)

    def test_rejects_anonymous_sign_out(self):
        document, endpoints = self.repository_contract()
        document["paths"]["/auth/sign-out"]["post"]["security"] = []
        with self.assertRaisesRegex(ContractError, "signOut must require cookieAuth"):
            validate_contract(document, endpoints)

    def test_rejects_dbml_with_missing_operational_table(self):
        dbml = Path("ProjectBaton-DB.dbml").read_text().replace("Table jobs {", "Table removed_jobs {")
        with self.assertRaisesRegex(ContractError, "DBML table set"):
            validate_dbml(dbml)

    def test_rejects_dbml_that_exposes_legacy_expected_answer(self):
        dbml = Path("ProjectBaton-DB.dbml").read_text().replace(
            "  choices          jsonb", "  expected_answer  varchar(300)\n  choices          jsonb"
        )
        with self.assertRaisesRegex(ContractError, "expected_answer"):
            validate_dbml(dbml)

    def test_rejects_duplicate_yaml_mapping_key(self):
        with self.assertRaisesRegex(ContractError, "duplicate YAML key"):
            load_yaml("responses:\n  '400': first\n  '400': second\n")

    def test_rejects_sign_in_without_csrf_header(self):
        document, endpoints = self.repository_contract()
        document["paths"]["/auth/sign-in"]["post"]["parameters"] = []
        with self.assertRaisesRegex(ContractError, "signIn requires CSRF"):
            validate_contract(document, endpoints)

    def test_rejects_nested_private_answer_field(self):
        document, endpoints = self.repository_contract()
        choices = document["components"]["schemas"]["AssessmentQuestion"]["properties"]["choices"]
        choices["items"]["properties"]["correctChoiceId"] = {"type": "string"}
        with self.assertRaisesRegex(ContractError, "forbidden public field"):
            validate_contract(document, endpoints)

    def test_rejects_int64_without_safe_maximum(self):
        document, endpoints = self.repository_contract()
        document["components"]["parameters"]["ProjectId"]["schema"].pop("maximum")
        with self.assertRaisesRegex(ContractError, "safe integer maximum"):
            validate_contract(document, endpoints)

    def test_rejects_dbml_missing_idempotency_request_hash(self):
        dbml = Path("ProjectBaton-DB.dbml").read_text().replace("  request_hash char(64) [not null]\n", "")
        with self.assertRaisesRegex(ContractError, "idempotency_requests.request_hash"):
            validate_dbml(dbml)

    def test_rejects_dbml_without_cross_project_relation_guard(self):
        dbml = Path("ProjectBaton-DB.dbml").read_text().replace(
            "Ref: record_relations.(project_id, from_record_id) > work_records.(project_id, id)\n", ""
        )
        with self.assertRaisesRegex(ContractError, "composite reference"):
            validate_dbml(dbml)

    def test_rejects_versioned_success_without_etag(self):
        document, endpoints = self.repository_contract()
        response = document["paths"]["/records/{recordId}/review"]["post"]["responses"]["200"]
        response.pop("headers", None)
        with self.assertRaisesRegex(ContractError, "ETag"):
            validate_contract(document, endpoints)

    def test_rejects_unapproved_extra_operation_even_when_spec_matches(self):
        document, endpoints = self.repository_contract()
        document["paths"]["/extra"] = {"get": {"operationId": "extraOperation", "responses": {"200": {"description": "ok"}}}}
        endpoints.add(("GET", "/api/v1/extra"))
        with self.assertRaisesRegex(ContractError, "56 operations"):
            validate_contract(document, endpoints)

    def test_rejects_unapproved_extra_schema(self):
        document, endpoints = self.repository_contract()
        document["components"]["schemas"]["ExtraDto"] = {"type": "object", "properties": {}}
        with self.assertRaisesRegex(ContractError, "86 schemas"):
            validate_contract(document, endpoints)


if __name__ == "__main__":
    unittest.main()
