// 부하 스크립트가 함께 쓰는 helper. client는 브라우저 앱처럼 동작한다. 쿠키 세션, 모든 상태 변경에 CSRF 헤더, PATCH와
// DELETE를 포함한 모든 명령에 Idempotency-Key(ProjectBaton-API.yml의 선언대로), 버전이 있는 변경에 If-Match(C01)를
// 쓴다. compose overlay가 내부 네트워크의 프록시로 가리키는 BATON_BASE_URL 말고 다른 호스트에는 닿지 않는다.
import http from 'k6/http';
import exec from 'k6/execution';

// setup에서의 로그인이나 CSRF 실패는 깨진 전제 조건이라 시험 전체를 0이 아닌 종료 코드로 멈춘다. 시나리오 중에는 그
// iteration만 실패한다. 이제 가상 사용자는 처음 그 사용자로 행동할 때 로그인하고, 그 가운데 일부는 60 RPS 단계가
// 서비스를 과부하로 몰 때 로그인해서, 로그인 거절 한 번이 측정 전체를 멈춘 적이 있다. 실패한 요청 자체는 이미
// http_req_failed가 센다.
function inScenario() {
  try {
    return Boolean(exec.scenario.name);
  } catch (ignored) {
    return false;
  }
}
const fail = (message) => {
  if (inScenario()) throw new Error(message);
  exec.test.abort(message);
};

export const BASE = __ENV.BATON_BASE_URL || 'https://proxy:8443';
const PASSWORD = __ENV.BATON_SEED_PASSWORD;

// 합성 신원은 tests/load/seed/seed.sql을 따른다. 계정 1-3은 시스템 관리자이고, 프로젝트 p의 관리자는 4 + (p - 1),
// 기존 담당자는 304 + (p - 1), 신규 담당자는 604 + (p - 1)이며, 904-5000은 구성원이다.
export const employeeNo = (userId) => 'LT' + String(userId).padStart(5, '0');
export const managerOf = (project) => 4 + (project - 1);
export const outgoingOf = (project) => 304 + (project - 1);
export const incomingOf = (project) => 604 + (project - 1);
export const baseProjectOf = (userId) => 1 + ((userId - 4) % 300);

// 가상 사용자는 자기가 대신하는 사용자마다 로그인한 세션 하나를 처음 쓸 때 만들어 쥔다.
const sessions = {};
export function sessionOf(userId) {
  if (!sessions[userId]) {
    const session = new Session(userId);
    session.signIn();
    sessions[userId] = session;
  }
  return sessions[userId];
}

// 서버는 30분 동안 쓰지 않은 세션을 끝낸다(README §4.1). 세션을 여럿 쥔 가상 사용자는 그중 하나를 그만큼 쓰지 않을 수
// 있고, 서버가 설계대로 동작했는데도 다음 요청이 401을 만나 실패로 세어졌다(soak 재실행 한 번에 90건). 브라우저
// 사용자가 로그인 화면으로 돌아가는 것처럼 25분 동안 쓰지 않은 세션은 다음 요청 전에 다시 로그인해서, 이런 도구
// 잡음이 실패율에 들어가지 않게 한다.
const REFRESH_AFTER_IDLE_MS = 25 * 60 * 1000;

export class Session {
  constructor(userId) {
    this.userId = userId;
    this.csrf = null;
    this.jar = new http.CookieJar();
    this.lastUsed = 0;
  }

  signIn() {
    if (!PASSWORD) fail('BATON_SEED_PASSWORD is required');
    this.jar.clear(BASE);
    this.refreshCsrf();
    const response = http.post(`${BASE}/api/v1/auth/sign-in`,
      JSON.stringify({ employeeNo: employeeNo(this.userId), password: PASSWORD }),
      this.params({ name: 'signIn', kind: 'auth' }, { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': this.csrf }));
    if (response.status !== 200) fail(`sign-in failed for ${employeeNo(this.userId)}: ${response.status}`);
    // 앱처럼 로그인 뒤 token을 다시 읽는다(README §4.1).
    this.refreshCsrf();
    this.lastUsed = Date.now();
  }

  refreshCsrf() {
    const response = http.get(`${BASE}/api/v1/auth/csrf`, this.params({ name: 'getCsrfToken', kind: 'auth' }));
    if (response.status !== 200) fail(`csrf failed: ${response.status}`);
    this.csrf = response.json('token');
  }

  params(tags, headers = {}, expected) {
    const params = { jar: this.jar, tags, headers: { Accept: 'application/json', ...headers } };
    if (expected) params.responseCallback = http.expectedStatuses(...expected);
    return params;
  }

  get(path, tags, expected) {
    return report(this.retryOnExpiredSession(() => http.get(`${BASE}${path}`, this.params(tags, {}, expected))), tags, expected);
  }

  command(method, path, body, tags, { ifMatch, expected } = {}) {
    return report(this.retryOnExpiredSession(() => {
      const headers = { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': this.csrf, 'Idempotency-Key': crypto.randomUUID() };
      if (ifMatch !== undefined) headers['If-Match'] = `"${ifMatch}"`;
      return http.request(method, `${BASE}${path}`, body === null ? null : JSON.stringify(body), this.params(tags, headers, expected));
    }), tags, expected);
  }

  // idle 한도에 가까운 세션은 먼저 로그인한다. 그래도 401이 오면 실패로 세고 로그인 뒤 요청을 한 번 더 보내서, 세션
  // 하나를 잃었다고 그 가상 사용자의 이후 iteration이 모두 실패하지는 않게 한다.
  retryOnExpiredSession(send) {
    if (Date.now() - this.lastUsed > REFRESH_AFTER_IDLE_MS) this.signIn();
    const response = send();
    this.lastUsed = Date.now();
    if (response.status !== 401) return response;
    this.signIn();
    return send();
  }
}

// 요약의 실패율을 그 실패를 만든 operation까지 따라갈 수 있게, operation과 상태마다 처음 몇 개의 예상하지 못한 응답을
// problem code와 함께 로그에 남긴다. problem 응답의 본문에는 개인정보가 없다.
const reported = {};
function report(response, tags, expected) {
  const ok = expected ? expected.includes(response.status) : response.status >= 200 && response.status < 400;
  if (!ok) {
    const key = `${tags.name}:${response.status}`;
    reported[key] = (reported[key] || 0) + 1;
    if (reported[key] <= 3) {
      let code = '';
      try { code = response.json('code') || ''; } catch (ignored) { code = ''; }
      console.warn(`UNEXPECTED ${tags.name} ${response.status} ${code} ${response.error || ''}`);
    }
  }
  return response;
}

export function pick(items, n) {
  return items[Math.abs(n) % items.length];
}
