// 부하 시험이 읽는 인수인계 데이터를 SQL이 아니라 실제 API로 준비한다. 큰 프로젝트 열 개마다 관리자가 최근 7일의
// 인수인계를 요청하고, worker가 패키지를 만들고, 신규 담당자가 근거 기반 질문 세 개(Q&A 인용)를 묻고 평가에 한 번
// 응시한다. tests/load/baton.js는 listHandovers로 그 인수인계를 다시 찾는다.
//
//  docker compose -f compose.yml -f tests/load/compose.load.yml run --rm k6 run /scripts/prime.js
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Session, managerOf, outgoingOf, incomingOf } from './lib.js';

export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'] } };

const PROJECTS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
const TYPES = ['SCHEDULE', 'CHANGE_DECISION', 'COMMITMENT', 'BLOCKER', 'SCOPE_EXCEPTION', 'OPEN_ISSUE'];
const QUESTIONS = ['데이터 이관 계획의 후속 조치는?', '성능 시험 결과와 관련된 쟁점은?', '배포 승인 절차는 어떻게 진행 중인가?'];

export default function () {
  for (const project of PROJECTS) {
    const manager = new Session(managerOf(project));
    manager.signIn();
    const requested = manager.command('POST', `/api/v1/projects/${project}/handovers`, {
      outgoingOwnerId: outgoingOf(project), incomingOwnerId: incomingOf(project), effectiveOn: '2026-09-28',
      scopeFrom: '2026-09-21', scopeTo: '2026-09-27', scopeRecordTypes: TYPES, includeNoteDerivedRecords: true,
    }, { name: 'requestHandover', kind: 'prime' });
    if (requested.status !== 202) exec.test.abort(`requestHandover ${project}: ${requested.status} ${requested.body}`);
    const id = requested.json('id');
    let status = requested.json('status');
    for (let i = 0; i < 300 && status !== 'READY'; i++) {
      sleep(2);
      status = manager.get(`/api/v1/handovers/${id}`, { name: 'getHandover', kind: 'prime' }).json('status');
      if (status === 'FAILED') exec.test.abort(`package ${id} failed`);
    }
    check(status, { 'package ready': (s) => s === 'READY' });

    const incoming = new Session(incomingOf(project));
    incoming.signIn();
    for (const question of QUESTIONS) {
      const answer = incoming.command('POST', `/api/v1/handovers/${id}/questions`, { question }, { name: 'askQuestion', kind: 'prime' });
      check(answer, { 'question answered': (r) => r.status === 201 });
      sleep(11); // 사용자당 분당 질문 여섯 개 한도(README §7) 아래에 머문다
    }
    const assessment = incoming.get(`/api/v1/handovers/${id}/assessment`, { name: 'getAssessment', kind: 'prime' });
    const answers = assessment.json('questions').map((q) => ({ questionId: q.id, answerText: q.choices ? q.choices[0].id : '합성 답안' }));
    const attempt = incoming.command('POST', `/api/v1/handovers/${id}/assessment/attempts`, { answers }, { name: 'submitAssessmentAttempt', kind: 'prime' });
    check(attempt, { 'assessment submitted': (r) => r.status === 201 });
  }
}

export function handleSummary(data) {
  return { '/results/prime-summary.json': JSON.stringify(data, null, 2) };
}
