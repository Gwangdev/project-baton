import { describe, expect, it } from 'vitest'
import type { BatonRepository } from '@/api/batonRepository'
import { createDemoRepository } from '@/demo/demoRepository'
import { DEMO_PROJECT_ID } from '@/demo/scenario'
import { DEMO_PASSWORDS } from './support'

/**
 * 데모 빌드에서는 데모 저장소가 서버 역할을 한다. 그래서 화면이 실제 백엔드에서 다루는 것과 같은 형태와 Problem
 * code로 답해야 한다. 이 테스트는 1단계 장면을 그 계약 위에 고정한다.
 */

const KIM = 'A-10421'
const PARK = 'A-10188'
const LEE = 'A-10733'
const CHOI = 'A-10002'

async function as(repository: BatonRepository, employeeNo: string) {
  await repository.signOut()
  await repository.signIn(employeeNo, DEMO_PASSWORDS[employeeNo])
  return repository
}

function clock() {
  let now = 0
  return { now: () => now, advance: (ms: number) => { now += ms } }
}

describe('데모 세션', () => {
  it('세션이 없으면 401, 틀린 자격증명에는 계정과 무관하게 같은 401로 답한다', async () => {
    const repo = createDemoRepository({ delayMs: 0 })
    await expect(repo.getCurrentUser()).rejects.toMatchObject({ status: 401 })
    await expect(repo.signIn(KIM, 'wrong')).rejects.toMatchObject({ status: 401, code: 'INVALID_CREDENTIALS' })
    await expect(repo.signIn('A-00000', 'wrong')).rejects.toMatchObject({ status: 401, code: 'INVALID_CREDENTIALS' })
  })

  it('숫자 ID와 프로젝트 배정을 담은 사용자를 돌려준다', async () => {
    const repo = await as(createDemoRepository({ delayMs: 0 }), PARK)
    const me = await repo.getCurrentUser()
    expect(me.user.id).toBe(2)
    expect(me.memberships).toEqual([expect.objectContaining({ projectId: DEMO_PROJECT_ID, projectRole: 'PROJECT_MANAGER' })])
  })
})

describe('데모 설정 코드', () => {
  it('관리자가 만든 계정은 발급받은 코드로 비밀번호를 정한 뒤에야 로그인한다', async () => {
    const repo = createDemoRepository({ delayMs: 0, generationMs: 0 })
    await as(repo, CHOI)
    const created = await repo.createUser({ organizationId: 1, employeeNo: 'N-1', name: '신규', title: '사원', email: 'n1@example.test' })
    const { token } = await repo.issueCredentialToken(created.id)
    await repo.signOut()
    const password = `chosen-${crypto.randomUUID()}`

    await expect(repo.signIn('N-1', password)).rejects.toMatchObject({ status: 401 })
    await expect(repo.setCredential({ employeeNo: 'N-1', token: 'wrong', newPassword: password })).rejects.toMatchObject({ status: 400, code: 'INVALID_CREDENTIAL_TOKEN' })
    await expect(repo.setCredential({ employeeNo: 'N-1', token, newPassword: 'short' })).rejects.toMatchObject({ status: 400, code: 'INVALID_INPUT' })
    await repo.setCredential({ employeeNo: 'N-1', token, newPassword: password })

    expect((await repo.signIn('N-1', password)).user.employeeNo).toBe('N-1')
    await expect(repo.setCredential({ employeeNo: 'N-1', token, newPassword: password })).rejects.toMatchObject({ code: 'INVALID_CREDENTIAL_TOKEN' })
  })

  it('서버처럼, 로그인한 적이 있는 계정은 감사 행의 행위자라 지우지 않고 쓰인 적 없는 계정은 지운다', async () => {
    const repo = createDemoRepository({ delayMs: 0, generationMs: 0 })
    await as(repo, CHOI)
    const used = await repo.createUser({ organizationId: 1, employeeNo: 'N-2', name: '사용', title: '사원', email: 'n2@example.test' })
    const unused = await repo.createUser({ organizationId: 1, employeeNo: 'N-3', name: '미사용', title: '사원', email: 'n3@example.test' })
    const { token } = await repo.issueCredentialToken(used.id)
    await repo.signOut()
    const password = `chosen-${crypto.randomUUID()}`
    await repo.setCredential({ employeeNo: 'N-2', token, newPassword: password })
    await repo.signIn('N-2', password)

    await as(repo, CHOI)
    const current = (await repo.listUsers()).items.find((u) => u.id === used.id)!
    await expect(repo.deleteUser(used.id, current.version)).rejects.toMatchObject({
      status: 409, code: 'REFERENCE_CONFLICT', references: [expect.objectContaining({ resource: 'audit_events', count: 1 })],
    })
    await repo.deleteUser(unused.id, unused.version)
  })

  it('발급은 시스템 관리자만 한다', async () => {
    const repo = createDemoRepository({ delayMs: 0, generationMs: 0 })
    await as(repo, KIM)
    await expect(repo.issueCredentialToken(1)).rejects.toMatchObject({ status: 403 })
  })
})

describe('데모 기록', () => {
  it('개인 폴더 초안과 메모 code는 소유자에게만 보인다', async () => {
    const repo = await as(createDemoRepository({ delayMs: 0 }), KIM)
    await repo.sharePersonalNoteSummary(1, { recordType: 'SCOPE_EXCEPTION', title: 't', summary: 's', occurredOn: '2026-09-10' })
    const own = (await repo.listRecords(DEMO_PROJECT_ID)).items
    expect(own.find((r) => r.id === 105)?.sourceNoteCode).toBe('NOTE-001')

    await as(repo, LEE)
    const other = (await repo.listRecords(DEMO_PROJECT_ID)).items
    expect(other.find((r) => r.id === 105)?.sourceNoteCode).toBeNull()
  })

  it('불투명 cursor로 페이지를 나눈다', async () => {
    const repo = await as(createDemoRepository({ delayMs: 0 }), KIM)
    const first = await repo.listRecords(DEMO_PROJECT_ID, { limit: 2 })
    const second = await repo.listRecords(DEMO_PROJECT_ID, { limit: 2, cursor: first.page.nextCursor! })
    expect(first.items.map((r) => r.id)).toEqual([106, 104])
    expect(second.items.map((r) => r.id)).toEqual([103, 102])
  })

  it('읽은 version으로 검토하고 두 번째 검토는 거부한다', async () => {
    const repo = await as(createDemoRepository({ delayMs: 0 }), KIM)
    const draft = await repo.getRecord(103)
    await expect(repo.reviewRecord(103, draft.version + 1, { action: 'CONFIRM' })).rejects.toMatchObject({ status: 412 })
    const confirmed = await repo.reviewRecord(103, draft.version, { action: 'CORRECT', summary: '고친 요약', changeReason: '정정' })
    expect(confirmed).toMatchObject({ trustStatus: 'CORRECTED', summary: '고친 요약', version: draft.version + 1 })
    expect(confirmed.revisions).toHaveLength(1)
    await expect(repo.reviewRecord(103, confirmed.version, { action: 'CONFIRM' })).rejects.toMatchObject({ status: 409 })
  })

  it('서버와 같이 한 쌍에는 대체와 상충 중 하나만 걸리고, 참조는 함께 걸 수 있다', async () => {
    const repo = await as(createDemoRepository({ delayMs: 0 }), PARK)
    // 시나리오에는 102가 101을 대체하는 관계가 이미 있다.
    await expect(repo.createRecordRelation(DEMO_PROJECT_ID, { fromRecordId: 101, toRecordId: 102, relationType: 'CONFLICTS_WITH' }))
      .rejects.toMatchObject({ status: 409, code: 'INVALID_STATE' })
    await repo.createRecordRelation(DEMO_PROJECT_ID, { fromRecordId: 102, toRecordId: 101, relationType: 'REFERENCES' })
    await repo.createRecordRelation(DEMO_PROJECT_ID, { fromRecordId: 104, toRecordId: 102, relationType: 'CONFLICTS_WITH' })
    await expect(repo.createRecordRelation(DEMO_PROJECT_ID, { fromRecordId: 102, toRecordId: 104, relationType: 'SUPERSEDES' }))
      .rejects.toMatchObject({ status: 409, code: 'INVALID_STATE' })

    const supersession = (await repo.getRecord(101)).relations.find((r) => r.relationType === 'SUPERSEDES')!
    await repo.withdrawRecordRelation(supersession.id, supersession.version, '대체가 아니라 서로 어긋난다')
    await repo.createRecordRelation(DEMO_PROJECT_ID, { fromRecordId: 101, toRecordId: 102, relationType: 'CONFLICTS_WITH' })
  })

  it('서버와 같이 역방향 대체(순환)와 한 기록의 두 번째 상충을 거절하고, 없는 기록·관계는 PROJECT_NOT_FOUND다', async () => {
    const repo = await as(createDemoRepository({ delayMs: 0 }), KIM)
    const draft = await repo.getRecord(103)
    await repo.reviewRecord(103, draft.version, { action: 'CONFIRM' })
    await as(repo, PARK)
    await expect(repo.createRecordRelation(DEMO_PROJECT_ID, { fromRecordId: 101, toRecordId: 102, relationType: 'SUPERSEDES' }))
      .rejects.toMatchObject({ status: 409, code: 'INVALID_STATE' })
    // 서버의 V20 고유 인덱스처럼, 정렬한 낮은 기록 하나에는 철회되지 않은 상충이 하나뿐이다.
    await repo.createRecordRelation(DEMO_PROJECT_ID, { fromRecordId: 104, toRecordId: 101, relationType: 'CONFLICTS_WITH' })
    await expect(repo.createRecordRelation(DEMO_PROJECT_ID, { fromRecordId: 103, toRecordId: 101, relationType: 'CONFLICTS_WITH' }))
      .rejects.toMatchObject({ status: 409, code: 'INVALID_STATE' })
    await expect(repo.getRecord(9999)).rejects.toMatchObject({ status: 404, code: 'PROJECT_NOT_FOUND' })
    await expect(repo.createRecordRelation(DEMO_PROJECT_ID, { fromRecordId: 9999, toRecordId: 101, relationType: 'REFERENCES' }))
      .rejects.toMatchObject({ status: 404, code: 'PROJECT_NOT_FOUND' })
    await expect(repo.withdrawRecordRelation(9999, 0, '없음')).rejects.toMatchObject({ status: 404, code: 'PROJECT_NOT_FOUND' })
  })
})

describe('데모 인수인계', () => {
  async function readyHandover() {
    const time = clock()
    const repo = await as(createDemoRepository({ delayMs: 0, generationMs: 3000, now: time.now }), KIM)
    for (const id of [103, 106]) {
      const r = await repo.getRecord(id)
      await repo.reviewRecord(id, r.version, { action: 'CONFIRM' })
    }
    await repo.sharePersonalNoteSummary(1, { recordType: 'SCOPE_EXCEPTION', title: '대량 상담이력 다운로드 1차 오픈 제외', summary: 's', occurredOn: '2026-09-10' })
    await as(repo, PARK)
    const accepted = await repo.requestHandover(DEMO_PROJECT_ID, {
      outgoingOwnerId: 1, incomingOwnerId: 3, effectiveOn: '2026-09-12', scopeFrom: '2026-06-14', scopeTo: '2026-09-11',
      scopeRecordTypes: ['SCHEDULE', 'CHANGE_DECISION', 'COMMITMENT', 'BLOCKER', 'SCOPE_EXCEPTION', 'OPEN_ISSUE'],
      includeNoteDerivedRecords: true,
    })
    return { repo, time, id: accepted.id }
  }

  it('요청은 관리자만 할 수 있고, 조회가 아니라 경과 시간으로 진행한다', async () => {
    const repo = await as(createDemoRepository({ delayMs: 0 }), KIM)
    await expect(repo.requestHandover(DEMO_PROJECT_ID, {
      outgoingOwnerId: 1, incomingOwnerId: 3, effectiveOn: '2026-09-12', scopeFrom: '2026-06-01', scopeTo: '2026-09-30',
      scopeRecordTypes: ['SCHEDULE'], includeNoteDerivedRecords: true,
    })).rejects.toMatchObject({ status: 403 })

    const { repo: managed, time, id } = await readyHandover()
    expect((await managed.getHandover(id)).status).toBe('REQUESTED')
    expect((await managed.getHandover(id)).status).toBe('REQUESTED')
    time.advance(1000)
    expect((await managed.getHandover(id)).status).toBe('GENERATING')
    time.advance(2000)
    const ready = await managed.getHandover(id)
    expect(ready).toMatchObject({ status: 'READY', includedCount: 6 })
    expect(ready.approvalBlockers).toEqual(['REQUIRED_ITEMS_UNACKNOWLEDGED', 'CONFLICTS_UNRESOLVED', 'ASSESSMENT_NOT_SUBMITTED'])
  })

  it('충돌 해소·명시 확인·통과 응시가 모두 끝나야 완료된다', async () => {
    const { repo, time, id } = await readyHandover()
    time.advance(3000)
    const conflict = (await repo.listHandoverItems(id)).items.find((i) => i.conflictId)!
    expect(conflict.candidateRecordIds).toEqual([101, 102])
    // 서버와 같이, 대체 쌍은 대체한 기록(102)으로만 해소한다. 후보가 아닌 기록도 같은 409다.
    await expect(repo.resolveHandoverItem(id, conflict.id, conflict.version, 101, '옛 일정 유지')).rejects.toMatchObject({ status: 409, code: 'INVALID_STATE' })
    await expect(repo.resolveHandoverItem(id, conflict.id, conflict.version, 999, '엉뚱한 기록')).rejects.toMatchObject({ status: 409, code: 'INVALID_STATE' })
    await repo.resolveHandoverItem(id, conflict.id, conflict.version, 102, '변경 결재가 최종')

    await as(repo, LEE)
    const items = (await repo.listHandoverItems(id)).items
    for (const item of items.filter((i) => i.isRequired)) await repo.acknowledgeHandoverItem(id, item.id, item.version)
    const answers = ['10월 22일', '9월 18일 통합 시연 버전 제공', 'CRM 테스트 계정 발급 지연', '대량 상담이력 다운로드', '대체 테스트 데이터 사용 여부']
    const attempt = await repo.submitAssessmentAttempt(id, answers.map((answerText, i) => ({ questionId: i + 1, answerText })))
    expect(attempt.score).toBe(5)

    await as(repo, PARK)
    const before = await repo.getHandover(id)
    expect(before.approvalBlockers).toEqual([])
    await expect(repo.decideHandoverCompletion(id, before.version - 1, 'APPROVE')).rejects.toMatchObject({ status: 412 })
    // 서버와 같이, 의견은 보완 요청의 사유라 승인에 붙이면 거절한다.
    await expect(repo.decideHandoverCompletion(id, before.version, 'APPROVE', '좋습니다')).rejects.toMatchObject({ status: 400, code: 'INVALID_INPUT' })
    const done = await repo.decideHandoverCompletion(id, before.version, 'APPROVE')
    expect(done.status).toBe('COMPLETED')
  })

  it('서버와 같이 잘못된 관계를 철회하면 그 세대의 항목은 해소할 수 없고, 다시 만든 패키지에는 그 쌍이 없다', async () => {
    const { repo, time, id } = await readyHandover()
    time.advance(3000)
    const relation = (await repo.getHandover(id)).conflicts[0]
    const conflict = (await repo.listHandoverItems(id)).items.find((i) => i.conflictId)!
    // 충돌 항목의 ID는 서버처럼 관계의 ID와 다른 번호다.
    expect(conflict.conflictId).not.toBe(relation.id)

    await as(repo, KIM)
    await expect(repo.withdrawRecordRelation(relation.id, relation.version, '합의되지 않음')).rejects.toMatchObject({ status: 403 })
    await as(repo, PARK)
    await expect(repo.withdrawRecordRelation(relation.id, relation.version, '   ')).rejects.toMatchObject({ status: 400 })
    await expect(repo.withdrawRecordRelation(relation.id, relation.version + 1, '합의되지 않음')).rejects.toMatchObject({ status: 412 })
    const withdrawn = await repo.withdrawRecordRelation(relation.id, relation.version, '합의되지 않음')
    expect(withdrawn).toMatchObject({ version: relation.version + 1, withdrawalReason: '합의되지 않음' })
    expect(withdrawn.withdrawnAt).toBeTruthy()
    await expect(repo.withdrawRecordRelation(relation.id, withdrawn.version, '다시')).rejects.toMatchObject({ status: 409, code: 'INVALID_STATE' })

    const blocked = await repo.getHandover(id)
    expect(blocked.approvalBlockers).toContain('RELATIONS_WITHDRAWN')
    expect(blocked.approvalBlockers).not.toContain('CONFLICTS_UNRESOLVED')
    expect(blocked.conflicts[0].withdrawnAt).toBeTruthy()
    await expect(repo.resolveHandoverItem(id, conflict.id, conflict.version, 102, '변경 결재가 최종')).rejects.toMatchObject({ status: 409 })
    expect((await repo.getRecord(101)).supersededByCode).toBeNull()

    const { scopeFrom, scopeTo, scopeRecordTypes, includeNoteDerivedRecords } = blocked
    await repo.regenerateHandover(id, blocked.version, { scopeFrom, scopeTo, scopeRecordTypes, includeNoteDerivedRecords })
    time.advance(3000)
    const rebuilt = await repo.getHandover(id)
    expect(rebuilt.conflicts).toEqual([])
    expect(rebuilt.approvalBlockers).not.toContain('RELATIONS_WITHDRAWN')
    expect((await repo.listHandoverItems(id)).items.some((i) => i.conflictId)).toBe(false)
  })

  it('서버와 같이 대체 쌍은 새 기록의 항목에 붙고, 대체를 상충으로 바꿔 다시 만들면 상충이 충돌 항목이 된다', async () => {
    const { repo, time, id } = await readyHandover()
    time.advance(3000)
    const superseding = (await repo.listHandoverItems(id)).items.find((i) => i.conflictId)!
    expect(superseding.title).toBe('최종 오픈일 10월 22일로 변경')
    const relation = (await repo.getHandover(id)).conflicts[0]
    await repo.withdrawRecordRelation(relation.id, relation.version, '대체가 아니라 서로 어긋난다')
    await repo.createRecordRelation(DEMO_PROJECT_ID, { fromRecordId: 102, toRecordId: 101, relationType: 'CONFLICTS_WITH' })

    const blocked = await repo.getHandover(id)
    const { scopeFrom, scopeTo, scopeRecordTypes, includeNoteDerivedRecords } = blocked
    await repo.regenerateHandover(id, blocked.version, { scopeFrom, scopeTo, scopeRecordTypes, includeNoteDerivedRecords })
    time.advance(3000)
    const rebuilt = await repo.getHandover(id)
    expect(rebuilt.conflicts.map((c) => c.relationType)).toEqual(['CONFLICTS_WITH'])
    expect(rebuilt.approvalBlockers).toContain('CONFLICTS_UNRESOLVED')
    const conflicting = (await repo.listHandoverItems(id)).items.find((i) => i.conflictId)!
    // 상충은 정렬한 낮은 기록(101)이 출처라 그 항목에 붙고, 두 후보 중 어느 쪽으로든 해소한다.
    expect(conflicting.title).toBe('1차 오픈 목표일 10월 15일')
    await repo.resolveHandoverItem(id, conflicting.id, conflicting.version, 101, '옛 일정이 맞다')
    expect((await repo.getHandover(id)).approvalBlockers).not.toContain('CONFLICTS_UNRESOLVED')
  })

  it('서버와 같이 한 기록이 두 관계의 출처면 쌍마다 충돌 항목을 따로 만들고, 둘 다 해소해야 차단이 풀린다', async () => {
    const { repo, time, id } = await readyHandover()
    // 102는 101을 대체하고, 정렬한 낮은 기록이라 104와의 상충에서도 출처다.
    await repo.createRecordRelation(DEMO_PROJECT_ID, { fromRecordId: 104, toRecordId: 102, relationType: 'CONFLICTS_WITH' })
    time.advance(3000)
    const conflicting = (await repo.listHandoverItems(id)).items.filter((i) => i.conflictId)
    expect(conflicting.map((i) => i.candidateRecordIds)).toEqual([[101, 102], [102, 104]])
    expect(new Set(conflicting.map((i) => i.title))).toEqual(new Set(['최종 오픈일 10월 22일로 변경']))
    await repo.resolveHandoverItem(id, conflicting[0].id, conflicting[0].version, 102, '변경 결재가 최종')
    expect((await repo.getHandover(id)).approvalBlockers).toContain('CONFLICTS_UNRESOLVED')
    await repo.resolveHandoverItem(id, conflicting[1].id, conflicting[1].version, 104, '계정 발급 지연이 일정을 막는다')
    expect((await repo.getHandover(id)).approvalBlockers).not.toContain('CONFLICTS_UNRESOLVED')
  })

  it('차단 사유가 있으면 승인을 거부하고, 다른 참여자는 관리자 명령을 쓰지 못한다', async () => {
    const { repo, time, id } = await readyHandover()
    time.advance(3000)
    const h = await repo.getHandover(id)
    await expect(repo.decideHandoverCompletion(id, h.version, 'APPROVE')).rejects.toMatchObject({ status: 409, code: 'INVALID_STATE' })
    await as(repo, LEE)
    await expect(repo.decideHandoverCompletion(id, h.version, 'APPROVE')).rejects.toMatchObject({ status: 403 })
    await as(repo, CHOI)
    await expect(repo.getHandover(id)).rejects.toMatchObject({ status: 403 })
  })

  it('저장된 답변 전에 meta·evidence·delta를 흘리고, 근거가 없으면 그렇다고 답한다', async () => {
    const { repo, time, id } = await readyHandover()
    time.advance(3000)
    await as(repo, LEE)
    const events: string[] = []
    const result = await repo.askQuestion(id, '최종 오픈일은 언제인가요?', {
      onMeta: () => events.push('meta'), onEvidence: () => events.push('evidence'), onDelta: () => events.push('delta'),
    })
    expect(events).toEqual(['meta', 'evidence', 'delta', 'delta'])
    expect(result).toMatchObject({ kind: 'done', answer: { answerStatus: 'ANSWERED' } })
    const none = await repo.askQuestion(id, '2차 배포일은?', {})
    expect(none).toMatchObject({ kind: 'done', answer: { answerStatus: 'NO_EVIDENCE', citations: [] } })
    expect((await repo.listQuestions(id)).items.map((m) => m.role)).toEqual(['USER', 'ASSISTANT', 'USER', 'ASSISTANT'])
  })
})

describe('데모 시스템 관리', () => {
  it('아무도 참조하지 않는 것만 지우고, 아니면 참조를 알린다', async () => {
    const repo = await as(createDemoRepository({ delayMs: 0 }), CHOI)
    const orgs = (await repo.listOrganizations()).items
    const used = orgs.find((o) => o.id === 1)!
    await expect(repo.deleteOrganization(used.id, used.version)).rejects.toMatchObject({
      status: 409, code: 'REFERENCE_CONFLICT', references: [expect.objectContaining({ resource: 'users' })],
    })
    const typo = orgs.find((o) => o.id === 3)!
    await repo.deleteOrganization(typo.id, typo.version)
    expect((await repo.listOrganizations()).items.map((o) => o.id)).toEqual([1, 2])
  })

  it('마지막 활성 시스템 관리자를 지키고, 관리자가 아니면 거부한다', async () => {
    const repo = await as(createDemoRepository({ delayMs: 0 }), CHOI)
    const admin = (await repo.listUsers()).items.find((u) => u.systemRole === 'SYSTEM_ADMIN')!
    await expect(repo.updateUser(admin.id, admin.version, { isActive: false })).rejects.toMatchObject({ code: 'LAST_SYSTEM_ADMIN' })
    await as(repo, KIM)
    await expect(repo.listOrganizations()).rejects.toMatchObject({ status: 403 })
  })
})

describe('데모 구성원 목록', () => {
  it('서버처럼 현재 구성원만 담고, 비활성화된 사용자는 목록에서도 권한에서도 빠진다', async () => {
    const repo = await as(createDemoRepository({ delayMs: 0 }), PARK)
    const before = (await repo.listCurrentProjectMembers(DEMO_PROJECT_ID)).items.map((m) => m.userId)
    expect(before).toContain(1)

    await as(repo, CHOI)
    const kim = (await repo.listUsers()).items.find((u) => u.id === 1)!
    await repo.updateUser(1, kim.version, { isActive: false })

    await as(repo, PARK)
    expect((await repo.listCurrentProjectMembers(DEMO_PROJECT_ID)).items.map((m) => m.userId)).not.toContain(1)
  })
})

