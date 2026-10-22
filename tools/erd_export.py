"""ProjectBaton-DB.dbml에서 발표용 ERD 자료를 생성한다.

손으로 다시 그리면 스키마가 바뀔 때 그림만 옛것으로 남는다. 그래서 DBML을 읽어
Mermaid 정의와 도메인별 테이블 목록을 함께 만든다. 원본이 하나이므로 어긋날 수 없다.

출력:
  artifacts/erd/full.mmd          Appendix용 전체 ERD (Mermaid)
  artifacts/erd/db-list.md        Appendix용 도메인별 테이블 목록
"""
import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DBML = os.path.join(ROOT, "ProjectBaton-DB.dbml")
OUT = os.path.join(ROOT, "artifacts", "erd")

src = open(DBML, encoding="utf-8").read()

# 파싱
enums = {}
for m in re.finditer(r"^enum (\w+) \{(.*?)^\}", src, re.S | re.M):
    values = re.findall(r"^\s{2}(\w+)", m.group(2), re.M)
    enums[m.group(1)] = values

tables = {}
for m in re.finditer(r"^Table (\w+) \{(.*?)^\}", src, re.S | re.M):
    name, body = m.group(1), m.group(2)
    cols = []
    for line in body.splitlines():
        line = line.strip()
        if not line or line.startswith(("//", "Note", "Indexes", "'''")):
            continue
        cm = re.match(r"^(\w+)\s+([\w()]+)\s*(\[.*\])?\s*$", line)
        if not cm:
            continue
        col, ctype, attrs = cm.group(1), cm.group(2), cm.group(3) or ""
        ref = re.search(r"ref:\s*>\s*(\w+)\.(\w+)", attrs)
        cols.append({
            "name": col,
            "type": ctype,
            "pk": "pk" in attrs,
            "unique": "unique" in attrs,
            "required": "not null" in attrs,
            "ref": (ref.group(1), ref.group(2)) if ref else None,
            "note": (re.search(r"note:\s*'([^']*)'", attrs) or [None, ""])[1],
        })
    tables[name] = cols

groups = []
for m in re.finditer(r'^TableGroup "([^"]+)" \{(.*?)^\}', src, re.S | re.M):
    members = [t.strip() for t in m.group(2).splitlines() if t.strip()]
    groups.append((m.group(1), members))

assert tables and groups, "DBML 파싱 실패"

ROLE = {
    "organizations": "조직. 사용자 소속과 관리 단위",
    "users": "사내 계정. 이름·직급·시스템 역할·활성 여부",
    "projects": "프로젝트. 수행사·고객사·기간·수행 상태",
    "project_members": "프로젝트별 역할 배정. 접근 통제의 단위",
    "source_connections": "연동 설정. 채널·소유자·연결 상태·마지막 수집 결과",
    "record_sources": "수집된 개별 문서·메시지·파일의 원문 위치",
    "personal_notes": "빠른 메모 원본. 작성자 전용이며 공유해도 공개되지 않음",
    "work_records": "프로젝트 기록. 유형·요약·원문 발췌·신뢰 상태를 가진 중심 개체",
    "record_revisions": "기록 수정 이력. 무엇을 왜 고쳤는지",
    "record_relations": "기록 사이의 대체·상충·참조 관계",
    "handovers": "담당자 교체와 인수인계 진행 상태",
    "handover_scope_types": "인수인계에 포함할 기록 유형 다중 선택",
    "handover_items": "패키지 항목. 근거 기록·충돌 해소·후속 작업",
    "qa_messages": "질의응답. 답변 상태와 근거 없을 때의 확인 대상",
    "qa_citations": "답변이 인용한 기록과 인용 시점의 신뢰 상태",
    "assessments": "인수인계별 업무 준비도 점검",
    "assessment_questions": "점검 문항과 오답 시 확인할 근거 기록",
    "assessment_attempts": "응시 회차와 점수",
    "assessment_answers": "문항별 답안과 정오 판정",
    "credential_tokens": "관리자가 발급한 일회용 비밀번호 설정 코드. 해시만 저장",
    "handover_generations": "패키지 생성 회차. 다시 생성해도 이전 회차가 남는다",
    "handover_snapshot_records": "회차 시점에 고정한 근거 기록의 내용과 신뢰 상태",
    "handover_item_conflicts": "충돌 항목이 가리키는 관계와 양쪽 기록의 스냅숏",
    "handover_item_acknowledgements": "후임자가 항목을 읽고 확인한 기록",
    "record_embeddings": "확정 기록의 검색용 임베딩(256차원)과 모델 ID",
    "jobs": "비동기 작업 큐. 패키지 생성·색인·재시도",
    "idempotency_requests": "같은 요청의 재전송을 한 번만 처리하기 위한 키와 결과",
    "audit_events": "감사 기록. 추가만 허용하고 수정·삭제는 막는다",
    "spring_session": "로그인 세션(Spring Session JDBC)",
    "spring_session_attributes": "세션 속성",
}

ungrouped = set(tables) - {t for _, m in groups for t in m}
assert not ungrouped, f"TableGroup에 없는 테이블: {sorted(ungrouped)}"
assert not set(tables) - set(ROLE), f"역할 설명이 없는 테이블: {sorted(set(tables) - set(ROLE))}"

# Appendix용 전체 ERD (Mermaid)
# 컬럼을 모두 그리면 테이블이 한 장에 들어가지 않는다.
# 식별자와 외래키, 그리고 화면이 직접 읽는 컬럼만 남긴다.
KEEP_ALWAYS = {"code", "status", "trust_status", "record_type", "relation_type",
               "channel", "health", "visibility", "is_active", "project_role",
               "system_role", "item_kind", "answer_status", "question_type",
               "is_correct", "is_primary", "is_required", "score", "total"}

def short(t):
    return re.sub(r"\(.*\)", "", t)

lines = ["erDiagram"]
for name, cols in tables.items():
    lines.append(f"  {name} {{")
    for c in cols:
        keep = c["pk"] or c["ref"] or c["name"] in KEEP_ALWAYS
        if not keep:
            continue
        key = "PK" if c["pk"] else ("FK" if c["ref"] else "")
        lines.append(f"    {short(c['type'])} {c['name']} {key}".rstrip())
    lines.append("  }")

seen = set()
for name, cols in tables.items():
    for c in cols:
        if not c["ref"]:
            continue
        parent = c["ref"][0]
        key = (parent, name, c["name"])
        if key in seen:
            continue
        seen.add(key)
        # 필수 외래키는 실선, 선택 외래키는 점선으로 구분한다.
        rel = "||--o{" if c["required"] else "||..o{"
        lines.append(f'  {parent} {rel} {name} : "{c["name"]}"')

mmd = "\n".join(lines) + "\n"
open(os.path.join(OUT, "full.mmd"), "w", encoding="utf-8").write(mmd)
# file://에서는 fetch가 CORS로 막힌다. 스크립트로 주입해 정적 파일만으로 열리게 한다.
open(os.path.join(OUT, "erd-data.js"), "w", encoding="utf-8").write(
    "window.__MMD = " + json.dumps(mmd, ensure_ascii=False) + ";\n"
    "window.__GROUPS = " + json.dumps(
        [{"name": g, "tables": [{"name": t, "role": ROLE.get(t, ""),
          "cols": [c["name"] for c in tables[t] if c["pk"] or c["ref"] or c["name"] in KEEP_ALWAYS][:6]}
          for t in m]} for g, m in groups], ensure_ascii=False) + ";\n"
    "window.__STATS = " + json.dumps({"tables": len(tables), "enums": len(enums), "groups": len(groups)}) + ";\n"
)

# Appendix용 도메인별 테이블 목록
md = ["# Appendix — 도메인별 데이터베이스 목록", "",
      f"> `ProjectBaton-DB.dbml`에서 생성 / 테이블 {len(tables)}개 · enum {len(enums)}개", ""]
for gname, members in groups:
    md.append(f"## {gname}")
    md.append("")
    md.append("| 테이블 | 역할 | 주요 컬럼 |")
    md.append("|---|---|---|")
    for t in members:
        cols = tables[t]
        keys = [c["name"] for c in cols if c["pk"] or c["ref"] or c["name"] in KEEP_ALWAYS]
        md.append(f"| `{t}` | {ROLE.get(t, '')} | {' · '.join(f'`{k}`' for k in keys[:6])} |")
    md.append("")

md.append("## 상태 enum")
md.append("")
md.append("| enum | 값 |")
md.append("|---|---|")
for name in ("record_type", "record_trust_status", "record_relation_type",
             "source_channel", "connection_status", "handover_status", "handover_item_kind"):
    md.append(f"| `{name}` | {' · '.join(f'`{v}`' for v in enums[name])} |")
md.append("")
open(os.path.join(OUT, "db-list.md"), "w", encoding="utf-8").write("\n".join(md))

print(json.dumps({
    "tables": len(tables),
    "enums": len(enums),
    "groups": [(g, len(m)) for g, m in groups],
    "relations": len(seen),
}, ensure_ascii=False))


# 데이터가 바뀌면 그림도 같이 바뀌어야 한다(oas_export.py와 같다). 전체 ERD는 Mermaid가
# 불러온 뒤에 그리므로 그 SVG가 생길 때까지 기다린다.
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from html_png import render

html = os.path.join(OUT, "erd.html")
code = render([
    (html, "#summary", os.path.join(OUT, "ERD-본문-약식.png"), "#dbtables table"),
    (html, "#full", os.path.join(OUT, "ERD-Appendix-전체테이블.png"), "#mmd svg"),
    (html, "#dblist", os.path.join(OUT, "ERD-Appendix-DB목록.png"), "#dbtables table"),
])
if code:
    raise SystemExit(code)
