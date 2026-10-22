# Backend

Project Baton 2단계 백엔드다. Java 21, Spring Boot 4.1.1, Gradle Wrapper 9.7.1(Kotlin DSL), PostgreSQL 17 + pgvector, Flyway V1–V22로 되어 있다. 좌표와 버전 근거는 [`DEPENDENCIES.md`](DEPENDENCIES.md)에 있다.

설계 계약은 [`설계서`](../docs/backend-design/README.md), [`계약 변경 명세`](../docs/backend-design/contracts.md), [`작업 카드 W00–W15·D1–D7`](../docs/backend-design/work-orders.md)다. 카드별 구현·시험 증거는 [`evidence/`](../docs/backend-design/evidence/)에 있다.

- REST/OpenAPI 계약: [`../ProjectBaton-API.yml`](../ProjectBaton-API.yml) (공개 operation 56개, 모두 구현)
- 데이터 모델: [`../ProjectBaton-DB.dbml`](../ProjectBaton-DB.dbml) (업무·운영 테이블 30개)
- 공개 표면 명세(구현할 endpoint 목록): [`../SPEC.yaml`](../SPEC.yaml)
- 현재 결정과 제약: 개발 상태 기록(비공개)

## 패키지

기능별로 나눈다: `identity`(로그인·사용자·조직), `project`(프로젝트·구성원·대시보드), `record`(업무 기록·검토·관계), `note`(개인 메모), `connection`(수집 연결·원본 처리), `handover`(인수인계 요청·패키지·승인), `qa`(근거 기반 질의응답), `assessment`(인수 평가), `jobs`(비동기 job lease), `audit`, `config`, `shared`.

## 실행과 검증

```bash
./gradlew clean build
```

- 단위 테스트(`*Test`)와 통합 테스트(`*IT`)를 모두 돈다. 통합 테스트는 Testcontainers로 pgvector 컨테이너를 띄우므로 Docker가 필요하다.
- `bootRunE2e`는 합성 fixture를 넣는 `e2e` 프로필로 앱을 띄운다. 프런트엔드 E2E(`npm --prefix frontend run test:e2e`)가 이 태스크를 쓴다. `e2e` 소스 세트는 bootJar에 들어가지 않는다.
- AI는 기본이 오프라인 `stub`이고, OpenAI·Google GenAI·Ollama starter가 들어 있어 환경변수로 고른다(`BATON_AI_PROVIDER`, 모델 ID, 키는 `OPENAI_API_KEY`·`GOOGLE_API_KEY`). 공급자를 정하지 않으면 모델 bean은 만들어지지 않는다. 합성 평가는 `./gradlew aiEvaluation`이다([`model-profile.md`](../docs/backend-design/evidence/model-profile.md)).
- 컨테이너 실행과 백업·복원은 저장소 루트 [`README.md`](../README.md)의 「백엔드 실행과 검증」을 본다.

## 현재 판정

격리 검증(2026-10-21, e2103ae..96f50e2)에서 범위의 코드는 **조건부 완료**로 판정했다(56/56 operation, 단위 68·통합 311·응답 계약 위반 0, 높음 결함 없음). 남은 지적과 처리 상태는 [`evidence/`](../docs/backend-design/evidence/)와 개발 기록(비공개) 피드백 대장에 있다.

## 관리자 비밀번호 복구 (운영자)

시스템 관리자 계정에는 설정 코드를 발급하지 않으므로, 관리자가 비밀번호를 잃으면 배포 변수를 바꿀 수 있는 운영자가 복구한다.

1. 최초 관리자를 만들 때와 같은 다섯 변수(`BATON_BOOTSTRAP_ADMIN_EMPLOYEE_NO`·`_PASSWORD`·`_NAME`·`_EMAIL`·`_ORGANIZATION`)에 `BATON_BOOTSTRAP_ADMIN_RESET=true`를 더해 한 번 기동한다. `_EMPLOYEE_NO`는 복구할 활성 LOCAL 시스템 관리자의 사번이고 `_PASSWORD`는 새 비밀번호(12~128자, 사번과 다름)다.
2. 기동하면 그 관리자의 비밀번호가 바뀌고 세션이 모두 끊기며 `RESET_SYSTEM_ADMIN_CREDENTIAL`이 감사된다. 일치하는 관리자가 없거나 다른 변수 없이 플래그만 있으면 이유를 말하고 기동이 멈춘다.
3. 관리자가 로그인한 것을 확인한 뒤 `BATON_BOOTSTRAP_ADMIN_RESET`을 지운다. 켜진 채 기동하면 매번 경고하고 그때마다 비밀번호가 변수의 값으로 덮이며 세션이 끊긴다.

일반 계정은 관리자가 설정 코드를 발급해 복구한다(루트 README). 계정 주인이 자기 계정에 일어난 일을 보는 「내 계정 활동」은 로그인할 수 있는 사람에게만 보인다.
