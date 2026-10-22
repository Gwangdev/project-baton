# Project Baton 프론트엔드

Vue 3 + TypeScript + Vite 화면이다. 화면은 `src/api/batonRepository.ts`의 계약 하나로만 데이터에 닿고, 데이터 출처는 빌드할 때 정한다.

| 모드 | 설정 | 데이터 |
|---|---|---|
| `api` (기본) | `VITE_DATA_MODE` 미설정 또는 `api` | Spring 백엔드 `/api/v1`. 쿠키 세션, CSRF, Idempotency-Key, If-Match는 `src/api/http.ts`가 처리한다 |
| `demo` | `VITE_DATA_MODE=demo` | 1단계 발표용 가상 시나리오를 메모리에서 실행한다 (`src/demo`) |

`api` 빌드에는 `src/demo`의 코드와 가상 계정이 들어가지 않는다. API가 실패해도 demo로 넘어가지 않는다. 다른 값을 주면 앱이 시작하지 않는다.

## 실행

```bash
npm --prefix frontend ci
```

개발 서버는 `/api`를 `BATON_API_PROXY_TARGET`(기본 `http://localhost:8080`)으로 넘긴다.

```bash
BATON_API_PROXY_TARGET=http://localhost:8080 npm --prefix frontend run dev
```

데모 모드는 백엔드 없이 뜬다.

```bash
VITE_DATA_MODE=demo npm --prefix frontend run dev
```

## 테스트

| 명령 | 대상 |
|---|---|
| `npm --prefix frontend test -- --run` | 단위 테스트 (Vitest, jsdom) |
| `npm --prefix frontend run test:e2e` | 실제 백엔드 E2E. Docker가 필요하다 |
| `npm --prefix frontend run test:e2e:demo` | 데모 모드 E2E |

실제 백엔드 E2E의 global setup(`tests/e2e/api/global-setup.ts`)은 일회용 `pgvector/pgvector:pg17` 컨테이너를 띄우고 `./backend/gradlew -p backend bootRunE2e`로 백엔드를 실행한다. 백엔드는 `e2e` 프로필에서 합성 fixture 프로젝트 `PJT-2026-014`와 기록 REC-101~107을 넣는다. seed 계정 비밀번호는 환경변수 `BATON_SEED_PASSWORD`로만 받는다. 주지 않으면 setup이 실행마다 무작위 값을 만들어 백엔드와 테스트에 함께 넘긴다. 백엔드 로그는 `frontend/e2e-backend.log`에 남는다. 포트는 `BATON_E2E_API_PORT`(기본 18080), `BATON_E2E_WEB_PORT`(기본 5174)로 바꿀 수 있다.

## 알려진 한계

- 기록 확인 화면에서 담당자를 바꿀 수 없다. 담당자 교체 요청은 그 프로젝트의 현재 구성원 목록(`GET /projects/{projectId}/members`)에서 고르고, 목록을 받지 못할 때만 사용자 ID를 입력한다.
- Q&A delta는 서버가 답변 생성과 인용 검증을 마친 뒤 문장 단위로 보내므로 토큰 단위로 흐르지 않는다.
- 관계 등록 화면은 확정 기록을 최근 순으로 100건씩 불러온다. 오래된 기록과 관계를 맺으려면 「기록 더 불러오기」를 눌러야 한다.
