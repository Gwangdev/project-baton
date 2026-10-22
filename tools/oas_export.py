"""ProjectBaton-API.yml에서 발표용 OAS 컴포넌트 맵 자료를 생성한다.

Swagger 화면을 통째로 붙이면 「경로가 많다」는 것 말고는 읽히지 않는다.
발표에서 설명해야 하는 것은 화면의 행동 하나가 어떤 method·path·schema로
내려가는가이므로, 그 연결을 명세에서 직접 뽑는다.
"""
import json
import os
import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "artifacts", "oas")
RAW = open(os.path.join(ROOT, "ProjectBaton-API.yml"), encoding="utf-8").read()
spec = yaml.safe_load(RAW)

METHODS = ("get", "post", "put", "patch", "delete")
ops = []
for path, item in spec["paths"].items():
    for method in METHODS:
        if method not in item:
            continue
        op = item[method]
        body = op.get("requestBody", {}).get("content", {}).get("application/json", {}).get("schema", {})
        # 질문 API는 200으로 event stream을, 201로 JSON을 답한다. 맵은 JSON 스키마를 보여 주므로 JSON 본문이 있는 첫
        # 성공 응답을 첫 성공 응답보다 앞세운다.
        success = [c for c in op["responses"] if c.startswith("2")]
        okc = next((c for c in success if "application/json" in op["responses"][c].get("content", {})),
                   success[0] if success else "")
        ok = op["responses"].get(okc, {})
        okschema = ok.get("content", {}).get("application/json", {}).get("schema", {})
        ops.append({
            "method": method.upper(),
            "path": path,
            "tag": op["tags"][0],
            "operationId": op["operationId"],
            "summary": op["summary"],
            "request": body.get("$ref", "").split("/")[-1],
            "successCode": okc,
            "response": okschema.get("$ref", "").split("/")[-1],
            "errors": sorted(c for c in op["responses"] if not c.startswith("2")),
        })

tag_desc = {t["name"]: t["description"] for t in spec["tags"]}
groups = {}
for o in ops:
    groups.setdefault(o["tag"], []).append(o)

# 발표 서사에 실제로 등장하는 행동만 고른다. 전부 나열하면 슬라이드가 다시 Swagger 목록이 된다.
REPRESENTATIVE = [
    ("S01", "사내 계정으로 로그인", "signIn"),
    ("S05", "AI 초안을 수정 후 승인", "reviewRecord"),
    ("S06", "메모의 선택 요약만 공유", "sharePersonalNoteSummary"),
    ("S06", "승인 폴더 연결·일시 중지·해제", "updateSourceConnection"),
    ("S07", "담당자 교체 요청", "requestHandover"),
    ("S08", "충돌의 최종 근거 선택", "resolveHandoverItem"),
    ("S09", "근거 기반 질문", "askQuestion"),
    ("S10", "준비도 답안 제출", "submitAssessmentAttempt"),
    ("S11", "완료 승인 또는 보완 요청", "decideHandoverCompletion"),
    ("S12", "사용자 비활성화", "updateUser"),
]
by_id = {o["operationId"]: o for o in ops}
rows = [{**by_id[oid], "screen": s, "action": a} for s, a, oid in REPRESENTATIVE]

schemas = spec["components"]["schemas"]
stats = {
    "operations": len(ops),
    "paths": len(spec["paths"]),
    "tags": len(spec["tags"]),
    "schemas": len(schemas),
    "refs": RAW.count("$ref:"),
    "async": sum(1 for o in ops if o["successCode"] == "202"),
    "deletes": sum(1 for o in ops if o["method"] == "DELETE"),
    "errorCodes": sorted({c for o in ops for c in o["errors"]}),
}

open(os.path.join(OUT, "oas-data.js"), "w", encoding="utf-8").write(
    "window.__OAS = " + json.dumps({
        "groups": [{"tag": t, "desc": tag_desc[t], "count": len(v)} for t, v in groups.items()],
        "rows": rows,
        "full": [{"tag": t, "desc": tag_desc[t],
                  "ops": [{k: o[k] for k in
                           ("method", "path", "summary", "successCode", "request", "response", "errors")}
                          for o in v]}
                 for t, v in groups.items()],
        "stats": stats,
    }, ensure_ascii=False, indent=1) + ";\n"
)

md = [
    "# OAS 컴포넌트 맵 — 작성 근거",
    "",
    f"> `ProjectBaton-API.yml`에서 생성 / 경로 {stats['paths']}개 · 오퍼레이션 {stats['operations']}개 · 스키마 {stats['schemas']}개",
    "",
    "## 왜 Swagger 전체 화면을 쓰지 않는가",
    "",
    "Swagger 화면을 그대로 붙이면 청중이 읽는 것은 「경로가 많다」 하나뿐이다.",
    "발표에서 설명해야 하는 것은 **화면의 행동 하나가 어떤 method·path·schema로 내려가는가**이고,",
    "그것이 데이터 모델과 어긋나지 않는다는 사실이다. 그래서 시연 서사에 실제로 등장하는",
    f"행동 {len(rows)}개만 뽑아 연결을 보인다. 전체 목록은 API 문서 파일로 대신한다.",
    "",
    "## 화면 행동 ↔ 오퍼레이션 ↔ 스키마",
    "",
    "| 화면 | 사용자 행동 | Method · Path | 요청 스키마 | 성공 | 응답 스키마 |",
    "|---|---|---|---|---|---|",
]
for r in rows:
    req = f"`{r['request']}`" if r["request"] else "--"
    md.append(f"| {r['screen']} | {r['action']} | `{r['method']} {r['path']}` | {req} | `{r['successCode']}` | `{r['response']}` |")

md += ["", "## 기능군", "", "| 기능군 | 오퍼레이션 | 역할 |", "|---|---:|---|"]
for t, v in groups.items():
    md.append(f"| `{t}` | {len(v)} | {tag_desc[t]} |")

md += [
    "",
    "## 명세 전체에 건 공통 규칙",
    "",
    "| 규칙 | 값 | 이유 |",
    "|---|---|---|",
    f"| 오류 응답 | `application/problem+json` · {' · '.join(stats['errorCodes'])} | 화면이 오류를 같은 모양으로 읽어야 처리도 한 곳에 모인다 |",
    f"| 비동기 접수 | `202 Accepted` {stats['async']}건 + `Location` 헤더 | 패키지 생성은 오래 걸린다. 접수와 완료를 나누지 않으면 화면이 멈춘다 |",
    f"| 스키마 재사용 | `$ref` {stats['refs']}회 · 스키마 {stats['schemas']}개 | 같은 개체를 두 번 정의하면 한쪽만 고쳐져 ERD와 어긋난다 |",
    "| 권한 조건 | 경로별 `401`·`403` 명시 | FR-01·FR-22의 접근 통제를 명세에서 읽을 수 있어야 한다 |",
    f"| 삭제 | `DELETE` {stats['deletes']}건은 오생성 취소용, 참조가 남아 있으면 `409`. 쓰인 대상은 `PATCH`의 `isActive` | 확인자를 지우면 기록의 근거가 끊긴다 |",
    "",
    "## 데이터 모델과의 대응",
    "",
    "| OAS 스키마 | DBML 테이블 |",
    "|---|---|",
    "| `WorkRecord` · `RecordDetail` | `work_records` · `record_revisions` · `record_relations` |",
    "| `PersonalNote` | `personal_notes` |",
    "| `SourceConnection` · `CollectedFile` | `source_connections` · `record_sources` |",
    "| `Handover` · `HandoverDetail` · `HandoverItem` | `handovers` · `handover_scope_types` · `handover_items` |",
    "| `QaAnswer` · `QaCitation` | `qa_messages` · `qa_citations` |",
    "| `AssessmentDetail` · `AssessmentAttempt` · `AssessmentAnswer` | `assessments` · `assessment_questions` · `assessment_attempts` · `assessment_answers` |",
    "| `Organization` · `User` · `Project` · `ProjectMember` | `organizations` · `users` · `projects` · `project_members` |",
    "",
]
open(os.path.join(OUT, "oas-map.md"), "w", encoding="utf-8").write("\n".join(md))
print(json.dumps(stats, ensure_ascii=False))


# 데이터가 바뀌면 그림도 같이 바뀌어야 한다. 캡처를 따로 돌리게 두면 문서만
# 옛것으로 남는다.
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from html_png import render

code = render([
    (os.path.join(OUT, "oas.html"), "#map", os.path.join(OUT, "OAS-본문-컴포넌트맵.png")),
    (os.path.join(OUT, "oas-appendix.html"), "#page", os.path.join(OUT, "API-Appendix-전체명세.png")),
])
if code:
    raise SystemExit(code)
