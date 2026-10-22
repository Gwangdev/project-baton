# UI 흐름도 — 화면 전이

> 작성: Claude / 2026-09-09
> 원본: `flow-1-auth.mmd` · `flow-2-record.mmd` · `flow-3-handover-request.mmd` · `flow-4-handover-learn.mmd`
> 생성: `.venv/bin/python tools/uiflow_export.py` — 손으로 고치지 말 것
> Mermaid 11에서 파싱·렌더 확인. 모든 전이를 실제 코드(`router`, 각 화면의 `router.push`)에서 뽑았다.

## UI 흐름도가 무엇인가

**화면 사이의 이동 경로를 그린 도면**이다. 세 가지를 답한다.

1. 어떤 화면이 있는가
2. 어느 화면에서 어느 화면으로 갈 수 있는가
3. 그 이동이 무슨 조건에서 일어나는가 — 성공·실패·권한 부족

**와이어프레임과 다르다.** 와이어프레임은 화면 하나의 내부 배치(버튼·입력·표)를 그리고,
흐름도는 화면들 사이의 관계를 그린다. 과제 §5 목차 3번이 둘을 함께 요구한다.

과제 세부 평가에서 이 도면이 담당하는 항목은 셋이다.

- 서비스 요구사항의 **모든** 기능이 UI 흐름에 반영되었는가
- 주요 화면 목록과 사용자 흐름이 정의되었는가
- **예외·오류 시나리오가 포함되었는가** (가점 항목)

## 1. 인증과 진입

![인증과 진입](UI흐름도-1-인증과진입.png)

```mermaid
flowchart LR
  START(("진입 /")) --> GUARD
  GUARD{"인증 확인<br/>router.beforeEach"}
  GUARD -->|"미인증"| S01
  GUARD -->|"인증됨"| S02

  S01["S01 로그인<br/>사내 계정 · 비밀번호"]
  S01 -->|"자격증명 일치"| S02
  S01 -->|"없는 계정"| E1["등록되지 않은<br/>사내 계정"]
  S01 -->|"비밀번호 불일치"| E2["비밀번호가<br/>일치하지 않음"]
  S01 -->|"비활성 계정"| E3["관리자 문의 안내"]
  E1 --> S01
  E2 --> S01
  E3 --> S01

  S02["S02 프로젝트 대시보드<br/>모든 Actor의 공통 시작점"]
  S02 -->|"로그아웃"| S01
  S02 -.->|"신규 담당자 · 배정 전"| EMPTY["배정된<br/>인수인계 없음"]

  classDef screen fill:#ffffff,stroke:#0b1b33,stroke-width:1.5px,color:#0b1b33
  classDef gate fill:#fff8f4,stroke:#ff6b2c,stroke-width:2px,color:#ff6b2c
  classDef err fill:#fff3ec,stroke:#ff6b2c,color:#5b6b80
  classDef side fill:#f4f6f9,stroke:#d6dde6,color:#5b6b80
  classDef term fill:#0b1b33,stroke:#0b1b33,color:#ffffff
  class S01,S02 screen
  class GUARD gate
  class E1,E2,E3 err
  class EMPTY side
  class START term
```

## 2. 평상시 기록 축적

![평상시 기록 축적](UI흐름도-2-평상시기록축적.png)

```mermaid
flowchart TD
  S02["S02 프로젝트 대시보드"]
  S02 --> S06["S06 내 기록 수집"]
  S02 --> S04
  S02 --> S03

  S06 --> TAB1["프로젝트 폴더 탭<br/>연결 · 재연결 · 해제 · 지금 수집"]
  S06 --> TAB2["빠른 메모 탭<br/>원본은 항상 비공개"]

  EXT["외부 업무 시스템<br/>신규 · 변경 기록"] --> S04
  TAB1 -->|"자동 후보화<br/>AUTO_DRAFT"| S04

  S04["S04 확인 대기 기록함<br/>일괄 승인 · 일괄 제외"]
  S04 -->|"원문 대조"| S05["S05 기록 상세 · AI 초안 확인<br/>좌 원문 · 우 AI 추출"]

  S05 -->|"승인<br/>CONFIRMED"| S03
  S05 -->|"수정 후 승인<br/>CORRECTED"| S03
  S05 -->|"제외 · DISMISSED"| S04
  S05 -->|"처리 불가"| ERR["이미 확정된 기록<br/>수정 사유 없음"]
  ERR --> S05

  TAB2 -->|"선택 요약 공유<br/>CONFIRMED 즉시 생성"| S03
  TAB2 -.-> NOTE["원본 메모는<br/>공유해도 공개되지 않음"]

  S03["S03 프로젝트 타임라인<br/>유형 6종 · 신뢰 상태 4종 · 기간 필터"]

  classDef screen fill:#ffffff,stroke:#0b1b33,stroke-width:1.5px,color:#0b1b33
  classDef err fill:#fff3ec,stroke:#ff6b2c,color:#5b6b80
  classDef side fill:#f4f6f9,stroke:#d6dde6,color:#5b6b80
  classDef ext fill:#f4f6f9,stroke:#5b6b80,color:#0b1b33
  class S02,S03,S04,S05,S06,TAB1,TAB2 screen
  class ERR err
  class NOTE side
  class EXT ext
```

## 3. 담당자 교체와 패키지 생성

프로젝트 관리자의 구간이다.

![교체 요청과 패키지 생성](UI흐름도-3-교체요청과패키지생성.png)

```mermaid
flowchart TD
  %% 3a — 프로젝트 관리자의 교체 요청과 패키지 생성.
  %% S08 에서 3b 로 이어진다. 이음매 간선은 양쪽 도면에 겹쳐 그린다.
  S02["S02 프로젝트 대시보드"] --> S07["S07 담당자 교체 요청<br/>담당자 · 기간 · 유형 · 공개 범위"]
  S07 -->|"패키지 생성 요청"| S08["S08 패키지 생성 상태<br/>REQUESTED → GENERATING → READY"]

  S07 -->|"제출 불가"| B1["제출 차단 사유 표시<br/>관리자 아님<br/>신규 담당자 미지정<br/>유형 미선택"]
  B1 --> S07
  S08 -->|"충돌 미해소"| B2["최종 근거 선택 요구"]
  B2 --> S08
  S08 -->|"생성 실패"| B3["재시도"]
  B3 --> S08

  S02 --> S12["S12 시스템 관리<br/>사용자 · 조직 · 프로젝트 · 연동"]
  S12 -->|"일반 사용자"| B6["연동 상태만 조회"]

  S08 -->|"생성 완료"| S09(["3b 로 이어짐<br/>S09 인수인계 패키지"])

  classDef screen fill:#ffffff,stroke:#0b1b33,stroke-width:1.5px,color:#0b1b33
  classDef err fill:#fff3ec,stroke:#ff6b2c,color:#5b6b80
  classDef seam fill:#f4f6f9,stroke:#5b6b80,stroke-dasharray:4 3,color:#0b1b33
  class S02,S07,S08,S12 screen
  class B1,B2,B3,B6 err
  class S09 seam
```

## 4. 학습과 완료 승인

신규 담당자가 배우고, 관리자가 닫는 구간이다.

![학습과 완료 승인](UI흐름도-4-학습과완료승인.png)

```mermaid
flowchart TD
  %% 3b — 신규 담당자의 학습과 관리자의 완료 승인.
  %% 3a 의 S08 에서 이어진다. 되돌아가는 경로는 맨 뒤에 두어야 주 흐름이 곧게 선다.
  S08(["3a 에서 이어짐<br/>S08 패키지 생성 완료"]) -->|"생성 완료"| S09["S09 인수인계 패키지 · Q&A"]
  S09 -->|"준비도 점검"| S10["S10 업무 준비도 확인"]
  S10 -->|"5/5 · 검토 요청"| S11["S11 관리자 인수인계 검토"]
  S11 -->|"완료 승인"| END(["인수인계 완료<br/>미해결 위험은 후속 작업으로"])

  S09 -->|"근거 없음 · AI 실패"| B4["확인 대상 안내<br/>기록 조회는 계속 가능"]
  B4 --> S09
  S11 -->|"승인 불가"| B5["승인 차단 사유 표시<br/>준비도 미제출<br/>미확인 기록<br/>미해소 충돌"]
  B5 --> S11

  S09 -->|"근거 원문 열람"| S05["S05 기록 상세"]
  S10 -->|"오답 근거 열람"| S05

  S10 -->|"기준 미달 · 재응시"| S10
  S11 -->|"보완 요청"| S09

  classDef screen fill:#ffffff,stroke:#0b1b33,stroke-width:1.5px,color:#0b1b33
  classDef err fill:#fff3ec,stroke:#ff6b2c,color:#5b6b80
  classDef seam fill:#f4f6f9,stroke:#5b6b80,stroke-dasharray:4 3,color:#0b1b33
  classDef term fill:#0b1b33,stroke:#0b1b33,color:#ffffff
  class S05,S09,S10,S11 screen
  class B4,B5 err
  class S08 seam
  class END term
```

## 왜 넷으로 나눴나

12개 화면과 예외 분기를 한 장에 넣으면 선이 엉키고 라벨이 겹쳐 읽을 수 없다.
기획서 §3의 구분(축적 / 인수인계)을 따르되 두 번 더 쪼갰다.

**인증을 뺀 이유**는 로그인이 실제 인증으로 바뀌면서 분기가 넷으로 늘었기 때문이다.

**담당자 교체를 둘로 나눈 이유는 슬라이드 글자 크기다.** 이 구간은 순차 단계가
일곱이라 한 장으로 그리면 세로로 깊어진다. 16:9 슬라이드에 높이를 맞춰 넣으면
상자 글자가 5.4pt로 떨어져 이 프로젝트가 정한 최소 8pt 아래가 된다. 액터 경계에서
둘로 나누니 10.3pt와 8.1pt가 됐다.

**나누면서 잃은 관계는 없다.** 경계를 넘는 간선은 `S08 → S09` 하나뿐이고 그것은
양쪽 도면에 점선 상자로 겹쳐 그렸다. 분할 전후의 간선 22개와 노드 15개를 대조해
빠짐과 늘어남이 각각 0임을 확인했다.

## 기획서 §3과의 차이 — Codex 동기화 필요

`DAY1_서비스_기획_확정안.md` §3.2·§3.3의 흐름도는 로그인이 인증으로 바뀌기 전에
작성되어 아래 네 가지가 빠져 있다.

| # | 빠진 것 | 근거 |
|---|---|---|
| 1 | **인증 가드** — 미인증이면 12개 경로 어디도 열리지 않고 `/login`으로 돌아온다 | `src/router/index.ts`의 `beforeEach` |
| 2 | **로그인 실패 분기 3종** — 없는 계정 · 비밀번호 불일치 · 비활성 계정 | `SignInError`의 `UNKNOWN_ID` · `WRONG_CREDENTIAL` · `INACTIVE` |
| 3 | **로그아웃** — S02에서 S01로 돌아가는 경로 | `ActorSwitcher.onSignOut()` |
| 4 | **S12 진입 경로** — §3.2에도 §3.3에도 S12로 가는 화살표가 없다 | `AppShell`의 레일 `/admin` |

§3.3의 `M9 승인` 분기도 실제로는 조건 셋(준비도 미제출·미확인 기록·미해소 충돌)이
걸려 있는데 단선으로 그려져 있다.

## 이 도면이 보증하는 것과 아닌 것

모든 전이를 코드에서 뽑았다 — `router/routes.ts`의 12개 경로, 각 화면의 `router.push`
목적지, `beforeEach` 가드, 실패 분기의 오류 타입이다. **화면에 없는 이동을 그리지 않았다.**

다만 반대 방향은 보증하지 않는다. 코드에 있는 이동을 이 도면이 빠뜨렸는지는
기계로 확인하지 않았다. 필요하면 `router.push` 목적지 목록과 도면의 간선을 대조하는
검사를 붙일 수 있다.
