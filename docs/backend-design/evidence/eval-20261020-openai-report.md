# OpenAI 평가 보고서(원본)

실행: 2026-10-20 KST, `./gradlew aiEvaluation -Dbaton.eval.chat-tokens=150000 -Dbaton.eval.embedding-tokens=20000`, 채팅 `gpt-5.4-mini`와 임베딩 `text-embedding-3-small`, 합성 평가 집합(문서 50·질문 30), 사용자가 승인한 외부 전송. 아래 본문은 평가 도구가 낸 보고서를 그대로 옮긴 것이다(영어 표는 도구의 출력). 해석과 한계는 [`model-profile.md`](model-profile.md)의 「공급자 평가 (2026-10-20)」와 「한계와 가정」에 있다.

### openai — chat `gpt-5.4-mini`, embeddings `text-embedding-3-small`

| Measure | Result |
|---|---|
| Structuring: documents parsed | 100% (50/50) |
| Structuring: failures by code | none |
| Structuring: expected records found | 100% (54/54) |
| Structuring: records invented from noise documents | 2 |
| Structuring latency | avg 1279 ms, p95 1829 ms, max 1927 ms |
| Q&A: correct | 100% (30/30) |
| Q&A ANSWERABLE | 100% (15/15) |
| Q&A CONFLICT | 100% (5/5) |
| Q&A INJECTION | 100% (5/5) |
| Q&A NO_EVIDENCE | 100% (5/5) |
| Q&A: failures by code | none |
| Q&A: citations naming no evidence | 0 |
| Q&A latency | avg 932 ms, p95 1499 ms, max 1927 ms |
| Retrieval recall@3 | 100% of 15 questions |
| Tokens used (reported or estimated) | chat 39342, embeddings 3936 |
