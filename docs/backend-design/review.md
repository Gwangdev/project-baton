# 설계 검토 기록

검토일: 2026-09-14. 기준: `bc39306`. 이 기록은 설계 문서의 자체 대조 결과이며 독립 코드 리뷰·서버 테스트·성능 측정 결과가 아니다.

## 검토한 근거

| 자료 | 설계에 반영한 내용 |
|---|---|
| 하네스 문서(비공개), 하네스 문서(비공개), 하네스 문서(비공개) | 계약 우선, 작업 단위, 완료 증거, 기존 변경 보존 |
| 상태, 로그, 인사이트 | 1단계 데모와 2단계 실제 서비스 구분, 과거 API 집계의 불일치 |
| 기획서 | FR-01~22, NFR의 부하·지연·AI 격리 목표 |
| 추적표, 데모 시나리오, 데이터·API 해설 | 사용자 흐름, 엔터티, 화면·API 대응 |
| [OpenAPI](../../ProjectBaton-API.yml), [DBML](../../ProjectBaton-DB.dbml), [SPEC](../../SPEC.yaml) | 실제 operation·schema·table 및 공개 표면 |
| [데모 저장소](../../frontend/src/demo/demoRepository.ts) | 조회 중 상태 변경, 전역 초안 차단, 부분 문자열 채점 등 서버로 복사하면 안 되는 동작 |
| 설계 점검표, 완료 기준 | 권한·실패·검증·운영 누락 점검 |
| 백엔드 하드닝, 도입 후보 | 표준 라이브러리 우선, 재시도 중첩 금지, 실제 DB 검증 |

공식 기술 근거는 [설계서 §10](README.md)에 링크했다. 버전 호환 문서 확인은 실제 dependency resolve 성공과 다르며 후자는 W01에서 수행한다. 최종 발표·ERD의 설명 맥락은 함께 보존된 Markdown 기획·해설을 사용했고, PDF/PPTX의 시각적 재검수는 이번 작업 범위가 아니다.

## 발견한 불일치와 처분

| 문제 | 설계 처분 | 구현 검증 위치 |
|---|---|---|
| bearer 인증 선언과 token 없는 로그인 응답, SHA-256 길이의 credential | 세션·CSRF·적응형 password encoder로 계약 변경 제안 | C01/C02, W00/W02 |
| 필수 항목을 실제로 읽었는지 저장할 계약 없음 | 세대별 명시적 acknowledgement 추가 | C06, W06 |
| 객관식 enum은 있지만 선택지·서버 정답 계약 없음 | 선택지와 비공개 정답 분리, 결정적 채점 | C08, W06 |
| Q&A 스트리밍 목표와 JSON 전용 계약 충돌 | Accept 기반 JSON/SSE, 완료 이벤트·실패·재전송 정의 | C07, W08 |
| 영속 작업·중복 수집·실패 복구 저장 구조 없음 | jobs/lease/fencing과 source version·dedup | C04/C09, W05 |
| 자료 변경 뒤 이전 이해도 점수로 승인 가능 | generation snapshot, 구세대 결과 무효 | C05, W06 |
| 관리자·프로젝트 권한이 개인 원문 노출로 이어질 위험 | 원문 소유권과 프로젝트 권한 별도 검사·반례 | README §4, W02/W04/W08 |
| 표기의 39/41 API와 원본 46 API 불일치 | 현행 목록을 원본에서 추출, W00에서 현재 문서 동기화 | api-inventory, W00 |
| 초당 처리량 목표만으로 AI 수집 목표 달성 가정 | 슬롯·평균 처리시간·부하 분포로 용량 계산, 실측 요구 | README §8, W10 |

자체 대조 중 재생성의 FAILED 허용 여부와 문항 검수 방식의 문서 간 차이를 수정했다. 비동기 접수 202와 Q&A 종결을 구분하여 LLM 호출 동안 트랜잭션을 유지하지 않도록 명문화했다.

## 검증 범위와 결과

- 원본 OAS: 32 paths, 46 operations, 77 schemas. 제안은 6 operations/6 paths 추가로 38 paths, 52 operations다. 원본을 52개로 변경한 것은 아니다.
- 원본 DBML: 업무 19 tables. 제안은 신규 8개와 Spring Session 2개를 더한 29개이며 Flyway 이력은 제외한다. 실제 DDL·migration은 아직 없다.
- `git diff --check`: 통과. 별도 읽기 전용 검사에서 설계 문서 5개의 로컬 Markdown 링크가 모두 존재하고 미해결 placeholder가 없음을 확인했다. OAS 파싱으로 현행 46개 경로·operationId의 목록 포함 여부와 32 paths/77 schemas를 확인했고 DBML의 Table 선언은 19개였다. 의미적 구현 적합성까지 증명하는 검사는 아니다.
- `python3 tools/gate.py docs/backend-design`: exit 0, BLOCK 0 / WARN 1. 이 하위 디렉터리가 별도 Git 저장소가 아니라 이력 검사가 없다는 경고다. **루트 SPEC·전체 코드가 검사된 성공으로 해석하지 않는다.**
- `python3 tools/gate.py .`: 루트 검사 BLOCK 8 / WARN 9. 미구현 backend operation(S3), 기존 스크립트·하네스 문서 규칙, PDF 대응 Markdown 누락 등이 남아 있다. 해당 파일을 이번 설계 작성에서 변경하지 않았으며 게이트 통과를 위해 SPEC을 축소하지 않았다.
- 2026-09-14 최초 검토 당시 서버 빌드, PostgreSQL 통합 테스트, 실모델 호출, 60 RPS 부하, 백업 복구는 **미실행**이었다. 이후 상태는 아래 날짜별 추가 검토와 카드 증거가 우선하며, 문서 검사는 실행 증거를 대체하지 않는다.

원본 계약 확인용 SHA-256:

```text
ProjectBaton-API.yml 48b0021049cb631891538aa240619d24612eb08fbd7a4a1d5511008c44cf6039
ProjectBaton-DB.dbml 8c98cc85bbcde60bf4ce9e32a0bfe40972a748d270f2c964594dc650eeb5dba2
SPEC.yaml feacb5e5e332cdd01349f9f8955042adb6047cfb8d96ccc8abfddfa1f68795a0
```

## 남은 결정과 착수 기준

P0는 제안한 합성 데이터·stub 프로필로 착수할 수 있다. 실제 제공자·모델·embedding 차원은 W07 진입 전에 고정한다. 실기업 SSO, 클라우드 커넥터 공급자, 보존·삭제 정책, 운영 비용·복구 목표 승인은 운영 출시 전에 별도로 필요하다. 계정·자료가 없다고 성공한 연결로 보고하지 않는다.

2026-09-14 최초 검토의 다음 실행은 [W00](work-orders.md)의 OAS/DBML/SPEC/상태 동기화였다. 현재 다음 실행은 아래 최신 추가 검토가 대체한다. 설계와 계약이 다른 상태에서 임의의 쪽을 택해 구현하지 않으며, 실측 없이 “성능 충족”이나 “운영 준비 완료”로 판정하지 않는다.

## 2026-09-21 추가 검토: Maven에서 Gradle로 전환

W05.2 커밋 `2e4a7b9` 기준으로 Maven `verify`를 새로 실행해 77 tests, failure/error/skip 0을 확인했다. main Java 48개와 IT class 16개는 표준 `src/main/java`, `src/test/java`에 있어 소스 배치 변경은 필요 없다. 직접 결합은 `pom.xml`/Wrapper, Failsafe 수명주기, SPEC test command, 산출물 경로와 문서다.

승인한 처분은 [W05.G](work-orders.md)였고, 기본 Gradle `test`에 IT를 섞지 않고 별도 `integrationTest`를 `check`에 연결해 실행했다. Maven 기준선과 Gradle 모두 16 suites/77 tests, failure/error/skip 0을 확인했고 bootJar도 검증했다. 이에 따라 Maven 파일을 제거하고 `SPEC.yaml`의 활성 명령을 Gradle로 전환했다.
