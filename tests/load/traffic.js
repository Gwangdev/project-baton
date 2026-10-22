// 각 요청을 어느 사용자가 보내고 어떤 operation을 하는지: tests/load/baton.js의 트래픽 계획. lib.js의 HTTP client와
// 떼어 놓아서 한쪽을 바꿔도 다른 쪽은 건드리지 않는다. 여기의 선택은 모두 시나리오의 iteration 번호(가상 사용자가 쥔
// 두 조회자는 가상 사용자)의 함수라서, 같은 계획의 두 실행이 시나리오, 역할, operation마다 같은 요청을 보내고 그래서
// 두 실행을 비교할 수 있다.
import { sleep } from 'k6';
import { managerOf, outgoingOf, incomingOf } from './lib.js';

// iteration에서 누가 행동하는지. 이전 버전은 모든 시나리오에 걸쳐 가상 사용자에 번호를 매기는 exec.vu.idInTest로
// 역할을 골랐다. k6는 실행마다 그 번호를 다른 순서로 시나리오에 나눠 줘서(오프라인 확인: 관리 시나리오의 가상 사용자
// 40명이 4로 나누어지는 번호를 한 번은 7개, 다음엔 11개 가졌다), 시스템 관리자, 메모 작성자, 질문자의 몫이 실행마다
// 바뀌었다(사용자 수정 597건 대 129건). 시나리오 자신의 iteration 번호는 실행마다 같아서 이 함수들은 그 번호를
// 받는다.

// 관리: 네 번째 iteration마다 시스템 관리자(1-3 차례로), 나머지는 인수인계 열 개 가운데 하나의 관리자, 기존 담당자,
// 신규 담당자. 역할과 프로젝트를 차례로 돈다.
export function adminActorOf(iteration) {
  if (iteration % 4 === 0) return { role: 'sysadmin', userId: 1 + (Math.floor(iteration / 4) % 3), project: null };
  const turn = iteration - Math.floor(iteration / 4) - 1;
  const project = 1 + (turn % 10);
  const role = ['manager', 'outgoing', 'incoming'][Math.floor(turn / 10) % 3];
  return { role, userId: role === 'manager' ? managerOf(project) : role === 'outgoing' ? outgoingOf(project) : incomingOf(project), project };
}

// 큰 프로젝트 열 개 가운데 하나의 관리자나 기존 담당자를 같은 차례로: 검토 시나리오에서 메모를 쓰거나 초안 목록을
// 다시 읽는 사람.
export function leadOf(turn) {
  const project = 1 + (turn % 10);
  const role = Math.floor(turn / 10) % 2 === 0 ? 'manager' : 'outgoing';
  return { role, userId: role === 'manager' ? managerOf(project) : outgoingOf(project), project };
}

// 질문: 신규 담당자를 차례로. producer의 분당 30회에서 각자 분당 셋이라 한도 여섯 아래다.
export function questionerOf(iteration) {
  const project = 1 + (iteration % 10);
  return { role: 'incoming', userId: incomingOf(project), project };
}

// 조회자: 가상 사용자마다 큰 프로젝트 구성원 한 명과 작은 프로젝트 구성원 한 명을 쥐고 iteration마다 번갈아서,
// 실행마다 조회의 절반이 큰 프로젝트 열 개에서 나온다. 첫 수정은 가상 사용자마다 exec.vu.idInTest의 홀짝으로 고른
// 조회자 하나를 두었고, 큰 프로젝트의 몫이 46%와 52% 사이에서 움직였다. 작은 프로젝트 조회자는 기본 프로젝트가 큰
// 프로젝트인 계정을 건너뛴다.
export function readerOf(vu, iteration) {
  if (iteration % 2 === 0) {
    const project = 1 + (vu % 10);
    // 프로젝트 p의 구성원은 4 + (p - 1) + 300k다. p <= 10이면 k = 3..15가 모두 있다(k >= 3은 일반 MEMBER).
    return { role: 'member-large', userId: 4 + (project - 1) + 300 * (3 + (Math.floor(vu / 10) % 13)) };
  }
  let offset = (vu * 137) % 4097;
  while (offset % 300 < 10) offset = (offset + 10) % 4097;
  return { role: 'member-small', userId: 904 + offset };
}

// operation 선택: Math.random 대신 Weyl 수열(n 곱하기 무리수의 소수 부분). 값이 [0, 1)에 고르게 퍼져서 몫이 p인
// operation은 몇 번의 호출 오차 안에서 iteration의 p를 받고, 같은 iteration은 늘 같은 선택을 한다. Math.random을 쓰면
// 실행마다 몇백 번 도는 operation이 표본 오차만으로 실행 사이에 5-10% 움직였다. iteration 안의 독립된 선택은 각자
// 자기 stream을 쓰고, 곱하는 수가 유리수적으로 독립이라 stream이 함께 움직이지 않는다.
const STREAMS = [(Math.sqrt(5) - 1) / 2, Math.SQRT2 - 1, Math.sqrt(3) - 1, Math.sqrt(7) - 2, Math.sqrt(11) - 3];
export function rollOf(iteration, stream) {
  const value = (iteration + 1) * STREAMS[stream];
  return value - Math.floor(value);
}

// 실행의 단계는 run.sh가 고르고 statement snapshot에도 주는 시작 시각부터 센다(tests/load/statements.sh). setup()이
// 먼저 로그인한 뒤 그 시각까지 기다려서, snapshot의 단계 경계와 k6의 경계가 같은 순간이 된다. 예전에는 컨테이너
// 기동과 setup만큼, 몇 초 어긋났다. 시작 시각이 없거나 이미 지났으면 지금부터 센다.
export function startClock(startAtMs) {
  const wait = startAtMs - Date.now();
  if (startAtMs && wait > 0) sleep(wait / 1000);
  else if (startAtMs && wait < 0) console.warn(`setup finished ${-wait} ms after the start time; phases start late`);
  return Math.max(Date.now(), startAtMs || 0);
}
