# W13 Ollama 평가 보고서(원본)

실행: 2026-10-05 KST, `./gradlew aiEvaluation`, Ollama(`localhost:11434`)의 채팅 `qwen3:4b`와 임베딩 `embeddinggemma:latest`, 합성 평가 집합(문서 50·질문 30), 외부 전송 없음. 아래 본문은 평가 도구가 낸 보고서를 그대로 옮긴 것이다(영어 표는 도구의 출력). 해석과 한계는 [`model-profile.md`](model-profile.md)의 「W13 연결과 평가」와 「한계와 가정」에 있다. 이 문서가 없던 때에는 원본이 작업 세션의 임시 폴더에만 있었다(검증 지적 99).

### ollama — chat `qwen3:4b`, embeddings `embeddinggemma:latest`

| Measure | Result |
|---|---|
| Structuring: documents parsed | 92% (46/50) |
| Structuring: failures by code | {MODEL_OUTPUT_INVALID=4} |
| Structuring: expected records found | 78% (42/54) |
| Structuring: records invented from noise documents | 4 |
| Structuring latency | avg 5466 ms, p95 8670 ms, max 14250 ms |
| Q&A: correct | 90% (27/30) |
| Q&A ANSWERABLE | 100% (15/15) |
| Q&A CONFLICT | 60% (3/5) |
| Q&A INJECTION | 100% (5/5) |
| Q&A NO_EVIDENCE | 80% (4/5) |
| Q&A: failures by code | none |
| Q&A: citations naming no evidence | 0 |
| Q&A latency | avg 1480 ms, p95 2539 ms, max 4230 ms |
| Retrieval recall@3 | 100% of 15 questions |
| Tokens used (reported or estimated) | chat 47498, embeddings 2669 |

First failing outputs:

- structuring D009 MODEL_OUTPUT_INVALID: { "records": [ { "recordType": "SCHEDULE", "title": "통합 로그인 시험 시작일", "summary": "통합 로그인 시험은 11월 20일에 시작한다.", "originalExcerpt": "통합 로그인 시험은 11월 20일에 시작한다.", "occurredOn": "2026-11-20", "ownerUserId": null, "dueOn": null }, { "recordType": "COMMITMENT", "title": "데이터 이행 계획서 제출", "summary": "정하늘이(가) 1…
- structuring D021 MODEL_OUTPUT_INVALID: { "records": [ { "recordType": "SCHEDULE", "title": "통합 로그인 시험 시작일", "summary": "통합 로그인 시험은 10월 3일에 시작한다.", "originalExcerpt": "통합 로그인 시험은 10월 3일에 시작한다.", "occurredOn": "2026-10-03", "ownerUserId": 5, "dueOn": null }, { "recordType": "COMMITMENT", "title": "인터페이스 정의서 제출", "summary": "최도윤이(가) 10월 6일까…
- structuring D028 MODEL_OUTPUT_INVALID: { "records": [ { "recordType": "CHANGE_DECISION", "title": "알림 발송 서비스의 동시 접속 한도 변경", "summary": "운영위원회 결과 공유. 알림 발송 서비스의 동시 접속 한도를 기존 20에서 80으로 변경하기로 결정했습니다. 변경은 다음 배포부터 적용됩니다.", "originalExcerpt": "운영위원회 결과 공유. 알림 발송 서비스의 동시 접속 한도를 기존 20에서 80으로 변경하기로 결정했습니다. 변경은 다음 배포부터 적용됩니다. - 이서연", "occurredOn":…
