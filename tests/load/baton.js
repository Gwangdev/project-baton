// 부하 시나리오(README §8): 예열 5분 -> 30 RPS 15분 -> 60 RPS 15분 -> 30 RPS soak 60분, open arrival. 일반 트래픽은
// 조회 70%, 기록 검토와 메모 20%, 관리와 인수인계 작업 10%이고, 요청마다 그 요청을 할 수 있는 사용자가 보내도록 각각
// 따로 된 시나리오다. AI 작업은 따로 된 producer라 일반 기준에 넣지 않는다.
//
// 통과 기준: 실패율 < 1%(의도한 4xx 제외), 일반 p95 500ms, 타임라인 p95 1s, 권한 누출 0, 버려진 iteration 0. 데이터
// 손실과 중복은 이 스크립트가 알리는 계수로 실행 뒤 DB와 대조한다(tests/load/verify.sql).
//
// BATON_LOAD_PLAN=short는 60 RPS 단계 뒤에 멈추고 30 RPS 단계를 5분으로 줄인다(모두 25분). 한계에 걸려 있던 단계를
// 되풀이해 돌릴 때 쓴다. 속도, 트래픽 구성, VU 상한은 전체 계획과 같다.
//
//  docker compose -f compose.yml -f tests/load/compose.load.yml run --rm k6 run /scripts/baton.js
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { sessionOf, managerOf, baseProjectOf, pick } from './lib.js';
import { adminActorOf, leadOf, questionerOf, readerOf, rollOf, startClock } from './traffic.js';

const SCALE = Number(__ENV.BATON_LOAD_SCALE || 1); // 1 = README 계획. 더 작은 값은 예행 연습을 줄인다
const minutes = (m) => `${Math.max(1, Math.round(m * 60 * SCALE))}s`;
// 단계마다 `until`의 분까지 속도를 유지하고, 속도가 바뀌면 처음 30초 동안 올리거나 내린다. 계획은 plans.json에 있고,
// run.sh가 statement snapshot의 단계 경계를 거기서 읽는다.
const PLANS = JSON.parse(open('./plans.json'));
const PHASES = PLANS[__ENV.BATON_LOAD_PLAN || 'full'];
if (!PHASES) throw new Error(`BATON_LOAD_PLAN must be one of ${Object.keys(PLANS).join(', ')}`);
const TOTAL_MINUTES = PHASES[PHASES.length - 1].until;
// AI producer는 주기가 계획 길이를 나누는 고정 속도로 돌아서 도착 하나가 정확히 끝에 걸린다. k6는 그 도착을 어떤
// 실행에서는 시작하고 어떤 실행에서는 시작하지 않았다(같은 실행 세 번에서 패키지 재생성 5 / 6 / 6. 오프라인으로 보니
// 10초 동안 주기 2초는 5나 6, 9.5초 동안은 늘 5였다). 1초 일찍 멈추면 그 도착이 빠진다.
const PRODUCER_DURATION = `${Math.max(1, Math.round(TOTAL_MINUTES * 60 * SCALE) - 1)}s`;

function stages(share) {
  const out = [];
  PHASES.forEach((phase, i) => {
    const length = phase.until - (i === 0 ? 0 : PHASES[i - 1].until);
    const target = phase.rate * share;
    if (i === 0 || phase.rate === PHASES[i - 1].rate) {
      out.push({ target, duration: minutes(length) });
    } else {
      out.push({ target, duration: minutes(0.5) }, { target, duration: minutes(length - 0.5) });
    }
  });
  return out;
}

const general = (share, vus, fn) => ({
  executor: 'ramping-arrival-rate', exec: fn, startRate: 1, timeUnit: '1s', stages: stages(share),
  preAllocatedVUs: vus, maxVUs: vus,
});

const thresholds = {
  dropped_iterations: ['count==0'],
  permission_leaks: ['count==0'],
  'http_req_failed{kind:general}': ['rate<0.01'],
  'http_req_failed{kind:timeline}': ['rate<0.01'],
  'http_req_duration{kind:general}': ['p(95)<500'],
  'http_req_duration{kind:timeline}': ['p(95)<1000'],
};
for (const phase of PHASES) {
  // 60 RPS 단계와 soak가 평균에 묻히지 않고 따로 판정되도록 단계별 하위 metric을 둔다.
  thresholds[`http_req_duration{kind:general,phase:${phase.name}}`] = ['p(95)<500'];
  thresholds[`http_req_duration{kind:timeline,phase:${phase.name}}`] = ['p(95)<1000'];
  thresholds[`http_req_failed{kind:general,phase:${phase.name}}`] = ['rate<0.01'];
  thresholds[`http_req_failed{kind:timeline,phase:${phase.name}}`] = ['rate<0.01'];
  thresholds[`http_req_duration{kind:ai,phase:${phase.name}}`] = ['max>=0'];
}

export const options = {
  discardResponseBodies: false,
  // setup()은 로그인한 뒤 실행 시작 시각까지 기다린다(run.sh가 약 30초 앞으로 잡는다).
  setupTimeout: '120s',
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    reads: general(0.7, 180, 'reads'),
    reviews: general(0.2, 60, 'reviews'),
    admin: general(0.1, 40, 'admin'),
    // AI producer: 신규 담당자 열 명의 근거 기반 질문(각자 분당 여섯 번이 한도)과, 기록 수천 건의 snapshot을 다시
    // 만드는 5분마다의 패키지 재생성.
    ai_questions: { executor: 'constant-arrival-rate', exec: 'aiQuestions', rate: 30, timeUnit: '1m', duration: PRODUCER_DURATION, preAllocatedVUs: 10, maxVUs: 10 },
    ai_packages: { executor: 'constant-arrival-rate', exec: 'aiPackages', rate: 1, timeUnit: '5m', duration: PRODUCER_DURATION, preAllocatedVUs: 2, maxVUs: 2 },
  },
  thresholds,
};

const leaks = new Counter('permission_leaks');
const notesCreated = new Counter('notes_created');
const reviewsDone = new Counter('reviews_done');
const reviewConflicts = new Counter('review_conflicts');
const summariesShared = new Counter('summaries_shared');
const draftsExhausted = new Counter('review_drafts_exhausted');
const usersUpdated = new Counter('users_updated');

// 실행 중 프로젝트마다 관리자가 검토하는 초안. 첫 버전은 검토자마다의 몫을 모든 시나리오에 걸쳐 가상 사용자에 번호를
// 매기는 exec.vu.idInTest로 정해서, 몫이 겹치고 검토의 16%가 412를 만났다. 이제 검토 iteration마다 이 목록에서 자기
// 위치의 초안을 가져가서 두 iteration이 같은 초안을 검토하지 않는다.
// 95분 동안 검토 iteration 약 19,400번이 목록에 닿아서, run.sh는 prime 뒤 큰 프로젝트마다 초안 2,500개를
// 내보낸다(타임라인이 돌려주는 순서대로 최신순, 프로젝트를 번갈아). setup()에서 돌려주면 목록이 가상 사용자 약
// 300명에게 하나씩 복사되어 항목 25,000개에서 3분 만에 k6 컨테이너의 1 GiB를 넘었으므로, SharedArray로 모든 가상
// 사용자가 사본 하나를 함께 쓴다.
const DRAFTS = new SharedArray('review drafts', () => JSON.parse(open('/results/drafts.json')));

export function setup() {
  // 인수인계는 prime.js가 만들었고, 큰 프로젝트마다 하나씩 있다.
  const handovers = {};
  for (let project = 1; project <= 10; project++) {
    const list = sessionOf(managerOf(project)).get(`/api/v1/projects/${project}/handovers`, { name: 'listHandovers', kind: 'setup' });
    handovers[project] = list.json('items.0.id');
  }
  return { handovers, startedAt: startClock(Number(__ENV.BATON_LOAD_START_AT || 0) * 1000) };
}

function phase(data) {
  const elapsed = (Date.now() - data.startedAt) / 60000 / SCALE;
  return PHASES.find((p) => elapsed < p.until)?.name || PHASES[PHASES.length - 1].name;
}

// 요청마다 보낸 사용자의 역할을 tag로 붙여서, run.sh가 시나리오, 역할, operation별로 요청을 세고 실제로 보낸
// 트래픽으로 실행을 비교할 수 있게 한다.
const tagsFor = (data, role) => (name, kind = 'general') => ({ name, kind, role, phase: phase(data) });
const vu = () => exec.vu.idInTest;
// 가상 사용자마다: 조회자별 마지막 타임라인 페이지(기록 id는 그 목록을 받은 조회자가 읽는다)와, 여기서 쓴 뒤 작성자가
// 공유하기를 기다리는 메모.
const local = { readers: {}, notes: [] };

// seed.sql의 계획에 따른 프로젝트의 첫 기록 id. 프로젝트 1-10에는 기록 45,000건씩, 그 뒤 프로젝트 11-220에는 1,552건,
// 221-300에는 1,551건이다.
function firstRecordOf(project) {
  if (project <= 10) return (project - 1) * 45000 + 1;
  const before = project - 11;
  return 450000 + Math.min(before, 210) * 1552 + Math.max(0, before - 210) * 1551 + 1;
}

// 기록의 절반이 있는 큰 프로젝트 열 개의 구성원이 조회의 절반을 보낸다(lib.js readerOf). operation과 필터는 iteration
// 번호에서 뽑는다(lib.js rollOf, stream 0-3).
export function reads(data) {
  const iteration = exec.scenario.iterationInTest;
  const reader = readerOf(vu(), iteration);
  const s = sessionOf(reader.userId);
  const project = baseProjectOf(s.userId);
  const tags = tagsFor(data, reader.role);
  const seen = local.readers[reader.userId] || (local.readers[reader.userId] = { records: [], cursor: null });
  const roll = rollOf(iteration, 0);
  if (roll < 0.05) {
    // 권한 확인: 다른 프로젝트의 타임라인과 기록은 숨겨져야 한다(README §4.2, 404). 대상은 기본 배정만 있는 프로젝트
    // 11-300 가운데 하나라서 조회자가 속한 프로젝트일 수 없다.
    let foreign = 11 + ((project + 149) % 290);
    if (foreign === project) foreign = foreign === 300 ? 11 : foreign + 1;
    const records = s.get(`/api/v1/projects/${foreign}/records?limit=5`, tags('probeForeignRecords', 'probe'), [404]);
    const record = s.get(`/api/v1/records/${firstRecordOf(foreign)}`, tags('probeForeignRecord', 'probe'), [404]);
    // 누출은 성공한 응답이다. 그 밖의 404가 아닌 응답(과부하에서의 500)은 이미 실패한 요청이라 여기가 아니라
    // http_req_failed가 센다.
    if (records.status >= 200 && records.status < 300) leaks.add(1, { probe: 'records' });
    if (record.status >= 200 && record.status < 300) leaks.add(1, { probe: 'record' });
  } else if (roll < 0.35) {
    const params = ['limit=20', 'trustStatus=CONFIRMED', 'trustStatus=CORRECTED'];
    if (rollOf(iteration, 1) < 0.3) params.push(`recordType=${pick(['SCHEDULE', 'CHANGE_DECISION', 'COMMITMENT', 'BLOCKER', 'SCOPE_EXCEPTION', 'OPEN_ISSUE'], iteration)}`);
    if (rollOf(iteration, 2) < 0.3) params.push('occurredFrom=2026-09-01', 'occurredTo=2026-09-27');
    if (seen.cursor && rollOf(iteration, 3) < 0.3) params.push(`cursor=${encodeURIComponent(seen.cursor)}`);
    const page = s.get(`/api/v1/projects/${project}/records?${params.join('&')}`, tags('listRecords', 'timeline'));
    if (check(page, { 'timeline 200': (r) => r.status === 200 })) {
      seen.cursor = page.json('page.nextCursor');
      seen.records = page.json('items').map((item) => item.id);
    }
  } else if (roll < 0.55) {
    s.get('/api/v1/dashboard', tags('getDashboard'));
  } else if (roll < 0.75) {
    if (seen.records.length === 0) return;
    s.get(`/api/v1/records/${pick(seen.records, iteration)}`, tags('getRecord'));
  } else if (roll < 0.85) {
    s.get(`/api/v1/projects/${project}`, tags('getProject'));
  } else if (roll < 0.95) {
    s.get(`/api/v1/projects/${project}/notes?limit=20`, tags('listPersonalNotes'));
  } else {
    s.get(`/api/v1/projects/${project}/connections`, tags('listSourceConnections'));
  }
}

// 첫 버전처럼 iteration의 절반은 초안을 검토하고, 40%는 메모를 쓰고, 10%는 메모를 공유한다. 검토는 내보낸 목록에서
// iteration 자기 위치의 초안으로 가고, 그 초안의 프로젝트 관리자가 검토한다. 첫 버전의 검토자는 검토 iteration 약
// 17번에 한 번 초안 한 페이지를 다시 읽었는데, 타임라인 트래픽이 비교 가능하도록 같은 비율로 남긴다. 메모와 초안 다시
// 읽기의 작성자는 iteration에서 정하고(lib.js leadOf), 공유는 그 메모의 작성자가 보낸다.
export function reviews(data) {
  const iteration = exec.scenario.iterationInTest;
  if (iteration % 2 === 0) {
    const k = iteration / 2;
    if (k % 17 === 16) {
      const reader = leadOf(Math.floor(k / 17));
      sessionOf(reader.userId).get(`/api/v1/projects/${reader.project}/records?trustStatus=AUTO_DRAFT&limit=100`,
        tagsFor(data, reader.role)('listDrafts', 'timeline'));
      return;
    }
    const entry = DRAFTS[k - Math.floor(k / 17)];
    if (!entry) {
      draftsExhausted.add(1);
      return;
    }
    const [id, version, draftProject] = entry;
    const action = rollOf(iteration, 0) < 0.9 ? 'CONFIRM' : 'DISMISS';
    // 다른 사람이 초안을 먼저 바꾸면 설계상 412로 답한다(C01 낙관적 잠금). 여기서는 초안마다 한 번만 검토하므로 이제
    // 412는 실제 동시 변경이고, 따로 알린다.
    const reviewed = sessionOf(managerOf(draftProject)).command('POST', `/api/v1/records/${id}/review`, { action }, tagsFor(data, 'manager')('reviewRecord'),
      { ifMatch: version, expected: [200, 412] });
    if (reviewed.status === 412) reviewConflicts.add(1);
    if (check(reviewed, { 'review 200 or 412': (r) => r.status === 200 || r.status === 412 }) && reviewed.status === 200) reviewsDone.add(1, { action });
  } else if (rollOf(iteration, 1) < 0.8) {
    const author = leadOf((iteration - 1) / 2);
    const created = sessionOf(author.userId).command('POST', `/api/v1/projects/${author.project}/notes`,
      { body: `합성 부하 메모 ${vu()}-${iteration}` }, tagsFor(data, author.role)('createPersonalNote'));
    if (check(created, { 'note 201': (r) => r.status === 201 })) {
      notesCreated.add(1);
      local.notes.push({ id: created.json('id'), author });
    }
  } else {
    if (local.notes.length === 0) return;
    const { id, author } = local.notes.shift();
    const shared = sessionOf(author.userId).command('POST', `/api/v1/notes/${id}/share`, {
      recordType: 'OPEN_ISSUE', title: `합성 공유 요약 ${vu()}`, summary: '부하 시험 중 공유한 합성 요약', occurredOn: '2026-09-27',
    }, tagsFor(data, author.role)('sharePersonalNoteSummary'));
    if (check(shared, { 'share 201': (r) => r.status === 201 })) summariesShared.add(1);
  }
}

// 관리 iteration의 4분의 1은 시스템 관리자의 것이고 나머지는 인수인계에 참여한다(lib.js adminActorOf). operation과
// 페이지 offset도 iteration에서 정해서, 사용자 수정이 어느 페이지에서 대상을 고르는지, 그래서 대상을 찾는지가
// 실행마다 같다.
export function admin(data) {
  const iteration = exec.scenario.iterationInTest;
  const actor = adminActorOf(iteration);
  const s = sessionOf(actor.userId);
  const tags = tagsFor(data, actor.role);
  const roll = rollOf(iteration, 0);
  if (actor.role === 'sysadmin') {
    const turn = Math.floor(iteration / 4);
    if (roll < 0.35) {
      const users = s.get(`/api/v1/admin/users?limit=20&offset=${(turn * 20) % 5000}`, tags('listUsers'));
      if (roll < 0.1 && users.status === 200) {
        const target = users.json('items').find((user) => user.id > 903);
        if (target) {
          const updated = s.command('PATCH', `/api/v1/admin/users/${target.id}`, { title: pick(['사원', '대리', '과장'], turn) },
            tags('updateUser'), { ifMatch: target.version });
          if (check(updated, { 'user update 200': (r) => r.status === 200 })) usersUpdated.add(1);
        }
      }
    } else if (roll < 0.7) {
      s.get(`/api/v1/admin/projects?limit=20&offset=${(turn * 20) % 300}`, tags('listAllProjects'));
    } else {
      s.get(`/api/v1/admin/projects/${1 + (turn % 300)}/members?limit=20`, tags('listProjectMembers'));
    }
    return;
  }
  const { project } = actor;
  const handover = data.handovers[project];
  if (roll < 0.4) s.get(`/api/v1/handovers/${handover}`, tags('getHandover'));
  else if (roll < 0.7) s.get(`/api/v1/handovers/${handover}/items?limit=20`, tags('listHandoverItems'));
  else if (roll < 0.85) s.get(`/api/v1/projects/${project}/handovers`, tags('listHandovers'));
  else if (actor.role === 'incoming') s.get(`/api/v1/handovers/${handover}/questions?limit=20`, tags('listQuestions'));
  // 409: 패키지 producer가 인수인계의 다음 회차를 아직 만드는 중이다(getAssessment, OAS).
  else s.get(`/api/v1/handovers/${handover}/assessment`, tags('getAssessment'), [200, 403, 409]);
}

const QUESTIONS = ['데이터 이관 계획의 후속 조치는?', '인터페이스 명세 변경은 누가 확인하는가?', '보안 점검 항목 중 남은 것은?',
  '통합 시험 시나리오 일정은?', '계약 변경 범위에 대한 합의 내용은?'];

export function aiQuestions(data) {
  const iteration = exec.scenario.iterationInTest;
  const asker = questionerOf(iteration);
  // 409: 패키지 producer가 질문자의 인수인계를 다시 만드는 중이라 물어볼 발행 패키지가 아직 없다(askQuestion, OAS).
  const asked = sessionOf(asker.userId).command('POST', `/api/v1/handovers/${data.handovers[asker.project]}/questions`,
    { question: pick(QUESTIONS, Math.floor(iteration / 10)) }, tagsFor(data, asker.role)('askQuestion', 'ai'), { expected: [201, 409, 429] });
  check(asked, { 'question answered as declared': (r) => r.status === 201 || r.status === 409 || r.status === 429 });
}

export function aiPackages(data) {
  const project = 1 + (exec.scenario.iterationInTest % 2);
  const s = sessionOf(managerOf(project));
  const tags = tagsFor(data, 'manager');
  const handover = s.get(`/api/v1/handovers/${data.handovers[project]}`, tags('getHandover', 'ai'));
  if (handover.status !== 200 || handover.json('status') !== 'READY') return;
  const regenerated = s.command('POST', `/api/v1/handovers/${data.handovers[project]}/generations`, {
    scopeFrom: '2026-09-21', scopeTo: '2026-09-27', includeNoteDerivedRecords: true,
    scopeRecordTypes: ['SCHEDULE', 'CHANGE_DECISION', 'COMMITMENT', 'BLOCKER', 'SCOPE_EXCEPTION', 'OPEN_ISSUE'],
  },
    tags('regenerateHandover', 'ai'), { ifMatch: handover.json('version'), expected: [202, 409] });
  check(regenerated, { 'regeneration accepted': (r) => r.status === 202 || r.status === 409 });
}

export function handleSummary(data) {
  const out = {};
  out['/results/summary.json'] = JSON.stringify(data, null, 2);
  const lines = [];
  for (const [name, metric] of Object.entries(data.metrics)) {
    if (!name.startsWith('http_req_duration{') && !name.startsWith('http_req_failed{') && !['dropped_iterations', 'permission_leaks',
      'notes_created', 'reviews_done', 'review_conflicts', 'review_drafts_exhausted', 'summaries_shared', 'users_updated', 'http_reqs', 'iterations'].includes(name)) continue;
    const verdict = metric.thresholds ? (Object.values(metric.thresholds).every((t) => t.ok) ? 'PASS' : 'FAIL') : '';
    lines.push(`${verdict.padEnd(4)} ${name} ${JSON.stringify(metric.values)}`);
  }
  out.stdout = lines.sort().join('\n') + '\n';
  return out;
}
