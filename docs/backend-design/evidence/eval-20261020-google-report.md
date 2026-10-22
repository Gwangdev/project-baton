# Google 축소 평가 보고서(원본)

실행: 2026-10-20 KST, `./gradlew aiEvaluation -Dbaton.eval.chat-tokens=150000 -Dbaton.eval.embedding-tokens=20000 -Dbaton.eval.documents=8 -Dbaton.eval.questions=8`, 채팅 `gemini-3.5-flash-lite`와 임베딩 `gemini-embedding-001`, 합성 평가 집합의 앞부분(문서 8·질문 8), 사용자가 승인한 외부 전송. 무료 등급의 하루 호출 한도 때문에 규모를 줄였다. 아래 본문은 평가 도구가 낸 보고서를 그대로 옮긴 것이다(영어 표는 도구의 출력). 해석과 한계는 [`model-profile.md`](model-profile.md)의 「공급자 평가 (2026-10-20)」에 있다.

### google-genai — chat `gemini-3.5-flash-lite`, embeddings `gemini-embedding-001`

| Measure | Result |
|---|---|
| Structuring: documents parsed | 100% (8/8) |
| Structuring: failures by code | none |
| Structuring: expected records found | 100% (8/8) |
| Structuring: records invented from noise documents | 0 |
| Structuring latency | avg 1289 ms, p95 1891 ms, max 1891 ms |
| Q&A: correct | 100% (8/8) |
| Q&A ANSWERABLE | 100% (2/2) |
| Q&A CONFLICT | 100% (2/2) |
| Q&A INJECTION | 100% (2/2) |
| Q&A NO_EVIDENCE | 100% (2/2) |
| Q&A: failures by code | none |
| Q&A: citations naming no evidence | 0 |
| Q&A latency | avg 1043 ms, p95 2292 ms, max 2292 ms |
| Retrieval recall@3 | 100% of 2 questions |
| Tokens used (reported or estimated) | chat 7976, embeddings 2604 |
