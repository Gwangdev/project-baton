<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import AppShell from '@/components/AppShell.vue'
import ListPager from '@/components/ListPager.vue'
import { CHANNEL_LABEL, CONNECTION_STATUS_LABEL, HEALTH_LABEL, PROJECT_ROLE_LABEL, PROJECT_STATUS_LABEL, formatTimestamp, seoulToday } from '@/components/labels'
import { ApiProblem } from '@/api/http'
import { errorLine } from '@/api/problemText'
import { useRepository } from '@/api/repositoryProvider'
import { useSessionStore } from '@/stores/session'
import type {
  ConnectionStatus, Organization, Project, ProjectMember, ProjectRole, ProjectStatus, SourceConnection, User,
} from '@/api/types'

/**
 * S12 시스템 관리.
 *
 * 지우는 것과 감추는 것은 다르다(D11). 영구 삭제는 아무도 참조하지 않는 오생성 행에만 있고, 참조가 있으면 서버가
 * 막는 참조를 담아 409 REFERENCE_CONFLICT로 답한다. 그러면 화면은 비활성화를 안내한다. 관리 권한도 서버가
 * 정한다. 시스템 관리자가 아니면 조직 목록 호출이 403으로 돌아오고, 관리 탭은 그 사실을 알린다. 연동 설정 탭은
 * 서버가 이 사용자에게 돌려준 선택 프로젝트의 연동을 보여준다 (FR-22).
 *
 * 조직·사용자·프로젝트 표는 한 번에 PAGE_SIZE 행씩 보여주고 이전·다음으로 넘긴다. 선택 상자(사용자의 조직, 배정할
 * 사용자, 배정 프로젝트)는 끝까지 읽은 목록을 쓴다. 예전에는 첫 페이지(최대 100행)만 받아 그 뒤의 사용자와 프로젝트를
 * 관리하거나 배정할 수 없었다(FR-18, 사용자 5,000명 기준).
 */
const PAGE_SIZE = 20
type Tab = 'organization' | 'user' | 'project' | 'member' | 'connection'
const TABS: { id: Tab; label: string }[] = [
  { id: 'organization', label: '조직' },
  { id: 'user', label: '사용자' },
  { id: 'project', label: '프로젝트' },
  { id: 'member', label: '구성원 배정' },
  { id: 'connection', label: '연동 설정' },
]
const ROLES: ProjectRole[] = ['OUTGOING_OWNER', 'INCOMING_OWNER', 'PROJECT_MANAGER', 'MEMBER']
const STATUSES: ProjectStatus[] = ['PLANNED', 'ON_TRACK', 'AT_RISK', 'CLOSED']

const session = useSessionStore()
const repository = useRepository()

const active = ref<Tab>('user')
const denied = ref(false)
const organizations = ref<Organization[]>([])
const users = ref<User[]>([])
const projects = ref<Project[]>([])
const offsets = ref({ organization: 0, user: 0, project: 0 })
const totals = ref({ organization: 0, user: 0, project: 0 })
const organizationChoices = ref<Organization[]>([])
const userChoices = ref<User[]>([])
const projectChoices = ref<Project[]>([])
const members = ref<ProjectMember[]>([])
const memberProjectId = ref<number | null>(null)
const connections = ref<SourceConnection[]>([])
const busy = ref(false)
const error = ref('')

const orgForm = ref({ name: '' })
const userForm = ref({ organizationId: null as number | null, employeeNo: '', name: '', title: '', email: '' })
const projectForm = ref({ code: '', name: '', deliveringCompany: '', clientCompany: '', startedOn: '', endsOn: '', phase: '', status: 'PLANNED' as ProjectStatus })
const memberForm = ref({ userId: null as number | null, projectRole: 'MEMBER' as ProjectRole, joinedOn: seoulToday() })

/** 표 세 개의 현재 페이지. 마지막 행을 지워 페이지가 비면 남은 마지막 페이지로 물러난다. */
async function loadPages() {
  const fetchers = {
    organization: (offset: number) => repository.listOrganizations({ limit: PAGE_SIZE, offset }),
    user: (offset: number) => repository.listUsers({ limit: PAGE_SIZE, offset }),
    project: (offset: number) => repository.listAllProjects({ limit: PAGE_SIZE, offset }),
  }
  const read = async <T,>(table: keyof typeof fetchers) => {
    let page = await fetchers[table](offsets.value[table])
    if (page.items.length === 0 && offsets.value[table] > 0) {
      offsets.value = { ...offsets.value, [table]: Math.max(0, Math.floor((page.page.total - 1) / PAGE_SIZE) * PAGE_SIZE) }
      page = await fetchers[table](offsets.value[table])
    }
    totals.value = { ...totals.value, [table]: page.page.total }
    return page.items as T[]
  }
  const [organizationRows, userRows, projectRows] = await Promise.all([
    read<Organization>('organization'), read<User>('user'), read<Project>('project'),
  ])
  organizations.value = organizationRows
  users.value = userRows
  projects.value = projectRows
}

/** 선택 상자의 전체 목록. 행이 생기거나 사라지는 명령 뒤에만 다시 읽는다(페이지를 넘길 때는 읽지 않는다). */
async function loadChoices() {
  const [allOrganizations, allUsers, allProjects] = await Promise.all([
    repository.listOrganizations(), repository.listUsers(), repository.listAllProjects(),
  ])
  organizationChoices.value = allOrganizations.items
  userChoices.value = allUsers.items
  projectChoices.value = allProjects.items
}

async function loadAdmin() {
  try {
    await loadPages()
    await loadChoices()
    memberProjectId.value ??= projectChoices.value[0]?.id ?? null
    await loadMembers()
    denied.value = false
  } catch (e) {
    if (e instanceof ApiProblem && e.status === 403) {
      denied.value = true
      active.value = 'connection'
    } else {
      error.value = errorLine(e)
    }
  }
}

async function loadMembers() {
  members.value = memberProjectId.value ? (await repository.listProjectMembers(memberProjectId.value)).items : []
}

async function loadConnections() {
  const projectId = session.currentProjectId
  connections.value = projectId ? (await repository.listSourceConnections(projectId)).items : []
}

onMounted(async () => {
  await loadAdmin()
  await loadConnections().catch((e) => { error.value = errorLine(e) })
})
/** 방금 발급한 설정 코드. 서버가 평문으로 한 번만 주므로 화면도 한 번만 보여 주고 닫거나 탭을 옮기면 버린다. */
const issued = ref<{ name: string; token: string; expiresAt: string } | null>(null)
watch(active, () => { issued.value = null })

async function issueCode(user: User) {
  busy.value = true
  error.value = ''
  try {
    const result = await repository.issueCredentialToken(user.id)
    issued.value = { name: user.name, token: result.token, expiresAt: result.expiresAt }
  } catch (e) {
    error.value = errorLine(e)
  } finally {
    busy.value = false
  }
}

async function copyCode() {
  if (issued.value) await navigator.clipboard?.writeText(issued.value.token).catch(() => undefined)
}

watch(memberProjectId, () => { void loadMembers().catch((e) => { error.value = errorLine(e) }) })

function turn(table: keyof typeof offsets.value, offset: number) {
  offsets.value = { ...offsets.value, [table]: offset }
  void loadPages().catch((e) => { error.value = errorLine(e) })
}

async function run(fn: () => Promise<unknown>) {
  busy.value = true
  error.value = ''
  try {
    await fn()
  } catch (e) {
    error.value = errorLine(e)
  } finally {
    // 목록을 다시 받은 뒤에 버튼을 푼다. 그 전에 누르면 방금 바뀐 행의 옛 version을 보내 412로 거절된다.
    if (!denied.value) await loadAdmin()
    await loadConnections().catch(() => undefined)
    busy.value = false
  }
}

const createOrganization = () => run(async () => {
  await repository.createOrganization(orgForm.value.name.trim())
  orgForm.value.name = ''
})
const createUser = () => run(async () => {
  const f = userForm.value
  await repository.createUser({ organizationId: f.organizationId!, employeeNo: f.employeeNo.trim(), name: f.name.trim(), title: f.title.trim(), email: f.email.trim() })
  userForm.value = { organizationId: f.organizationId, employeeNo: '', name: '', title: '', email: '' }
})
const createProject = () => run(async () => {
  await repository.createProject({ ...projectForm.value })
  projectForm.value = { code: '', name: '', deliveringCompany: '', clientCompany: '', startedOn: '', endsOn: '', phase: '', status: 'PLANNED' }
})
const addMember = () => run(async () => {
  await repository.addProjectMember(memberProjectId.value!, { userId: memberForm.value.userId!, projectRole: memberForm.value.projectRole, joinedOn: memberForm.value.joinedOn })
  memberForm.value.userId = null
})

const orgName = (id: number) => organizationChoices.value.find((o) => o.id === id)?.name ?? `조직 ${id}`
const setConnection = (c: SourceConnection, status: ConnectionStatus) => run(() => repository.updateSourceConnection(c.id, c.version, { status }))

const canCreateUser = computed(() => {
  const f = userForm.value
  return !!f.organizationId && !!f.employeeNo.trim() && !!f.name.trim() && !!f.title.trim() && !!f.email.trim()
})
const canCreateProject = computed(() => Object.values(projectForm.value).every((v) => String(v).trim() !== ''))
</script>

<template>
  <AppShell>
    <template #title>
      <h1 class="title">시스템 관리</h1>
      <p class="sub">{{ denied ? '참여 중인 프로젝트의 연동 상태만 조회합니다.' : '사용자·조직·프로젝트·연동을 관리합니다.' }}</p>
    </template>

    <nav class="tabs" aria-label="관리 대상">
      <button
        v-for="t in TABS"
        :key="t.id"
        class="tabs__tab"
        :class="{ 'tabs__tab--on': active === t.id }"
        type="button"
        :disabled="denied && t.id !== 'connection'"
        @click="active = t.id"
      >{{ t.label }}</button>
    </nav>

    <p v-if="denied && active !== 'connection'" class="state state--denied">
      이 항목은 시스템 관리자만 관리할 수 있습니다.
    </p>

    <template v-else-if="active === 'organization'">
      <table class="table">
        <thead><tr><th>이름</th><th>사용자</th><th>상태</th><th>동작</th></tr></thead>
        <tbody>
          <tr v-for="o in organizations" :key="o.id" :class="{ 'table__row--off': !o.isActive }">
            <td class="table__name">{{ o.name }}</td>
            <td>{{ o.userCount ?? '--' }}명</td>
            <td :class="{ 'is-off': !o.isActive }">{{ o.isActive ? '활성' : '비활성' }}</td>
            <td class="table__actions">
              <button class="btn btn--sm" type="button" :disabled="busy" :aria-label="`${o.name} ${o.isActive ? '비활성화' : '활성화'}`"
                      @click="run(() => repository.updateOrganization(o.id, o.version, { isActive: !o.isActive }))">{{ o.isActive ? '비활성화' : '활성화' }}</button>
              <button class="btn btn--sm btn--danger" type="button" :disabled="busy" :aria-label="`${o.name} 삭제`"
                      @click="run(() => repository.deleteOrganization(o.id, o.version))">삭제</button>
            </td>
          </tr>
        </tbody>
      </table>
      <ListPager label="조직" :total="totals.organization" :offset="offsets.organization" :limit="PAGE_SIZE"
                 @update:offset="turn('organization', $event)" />
      <form class="create" @submit.prevent="createOrganization">
        <input v-model="orgForm.name" type="text" aria-label="새 조직 이름" placeholder="새 조직 이름" />
        <button class="btn btn--primary" type="submit" :disabled="busy || !orgForm.name.trim()">등록</button>
      </form>
    </template>

    <template v-else-if="active === 'user'">
      <section v-if="issued" class="credential" role="status">
        <p class="credential__text">
          <strong>{{ issued.name }}</strong>의 설정 코드입니다. 이 화면을 닫으면 다시 볼 수 없습니다.
          본인에게 직접 전하십시오. 만료 {{ formatTimestamp(issued.expiresAt) }}.
        </p>
        <code class="credential__code" data-testid="credential-code">{{ issued.token }}</code>
        <button class="btn btn--sm" type="button" @click="copyCode">복사</button>
        <button class="btn btn--sm" type="button" @click="issued = null">닫기</button>
      </section>
      <table class="table">
        <thead><tr><th>사내 계정</th><th>이름</th><th>조직</th><th>권한</th><th>로그인</th><th>상태</th><th>동작</th></tr></thead>
        <tbody>
          <tr v-for="u in users" :key="u.id" :class="{ 'table__row--off': !u.isActive }">
            <td>{{ u.employeeNo }}</td>
            <td class="table__name">{{ u.name }} {{ u.title }}</td>
            <td>{{ u.organizationName ?? orgName(u.organizationId) }}</td>
            <td>{{ u.systemRole === 'SYSTEM_ADMIN' ? '시스템 관리자' : '일반' }}</td>
            <td>{{ u.loginEnabled ? '가능' : '자격증명 준비 전' }}</td>
            <td :class="{ 'is-off': !u.isActive }">{{ u.isActive ? '활성' : '비활성' }}</td>
            <td class="table__actions">
              <button class="btn btn--sm" type="button" :disabled="busy" :aria-label="`${u.name} ${u.isActive ? '비활성화' : '활성화'}`"
                      @click="run(() => repository.updateUser(u.id, u.version, { isActive: !u.isActive }))">{{ u.isActive ? '비활성화' : '활성화' }}</button>
              <button v-if="u.isActive && u.authProvider === 'LOCAL' && u.systemRole !== 'SYSTEM_ADMIN'" class="btn btn--sm" type="button" :disabled="busy"
                      :aria-label="`${u.name} 설정 코드 발급`" @click="issueCode(u)">설정 코드 발급</button>
              <button class="btn btn--sm btn--danger" type="button" :disabled="busy" :aria-label="`${u.name} 삭제`"
                      @click="run(() => repository.deleteUser(u.id, u.version))">삭제</button>
            </td>
          </tr>
        </tbody>
      </table>
      <ListPager label="사용자" :total="totals.user" :offset="offsets.user" :limit="PAGE_SIZE"
                 @update:offset="turn('user', $event)" />
      <form class="create" @submit.prevent="createUser">
        <select v-model="userForm.organizationId" aria-label="새 사용자 조직">
          <option :value="null">조직 선택</option>
          <option v-for="o in organizationChoices" :key="o.id" :value="o.id">{{ o.name }}</option>
        </select>
        <input v-model="userForm.employeeNo" type="text" aria-label="새 사용자 사내 계정" placeholder="사내 계정" />
        <input v-model="userForm.name" type="text" aria-label="새 사용자 이름" placeholder="이름" />
        <input v-model="userForm.title" type="text" aria-label="새 사용자 직함" placeholder="직함" />
        <input v-model="userForm.email" type="email" aria-label="새 사용자 이메일" placeholder="이메일" />
        <button class="btn btn--primary" type="submit" :disabled="busy || !canCreateUser">등록</button>
      </form>
      <p class="hint">새 계정은 초기 자격증명이 준비되기 전까지 로그인할 수 없습니다.</p>
    </template>

    <template v-else-if="active === 'project'">
      <table class="table">
        <thead><tr><th>코드</th><th>이름</th><th>기간</th><th>상태</th><th>사용</th><th>동작</th></tr></thead>
        <tbody>
          <tr v-for="p in projects" :key="p.id" :class="{ 'table__row--off': !p.isActive }">
            <td>{{ p.code }}</td>
            <td class="table__name">{{ p.name }}</td>
            <td>{{ p.startedOn }} ~ {{ p.endsOn }}</td>
            <td>{{ PROJECT_STATUS_LABEL[p.status] }}</td>
            <td :class="{ 'is-off': !p.isActive }">{{ p.isActive ? '활성' : '비활성' }}</td>
            <td class="table__actions">
              <button class="btn btn--sm" type="button" :disabled="busy" :aria-label="`${p.name} ${p.isActive ? '비활성화' : '활성화'}`"
                      @click="run(() => repository.updateProject(p.id, p.version, { isActive: !p.isActive }))">{{ p.isActive ? '비활성화' : '활성화' }}</button>
              <button class="btn btn--sm btn--danger" type="button" :disabled="busy" :aria-label="`${p.name} 삭제`"
                      @click="run(() => repository.deleteProject(p.id, p.version))">삭제</button>
            </td>
          </tr>
        </tbody>
      </table>
      <ListPager label="프로젝트" :total="totals.project" :offset="offsets.project" :limit="PAGE_SIZE"
                 @update:offset="turn('project', $event)" />
      <form class="create create--wrap" @submit.prevent="createProject">
        <input v-model="projectForm.code" type="text" aria-label="새 프로젝트 코드" placeholder="코드" />
        <input v-model="projectForm.name" type="text" aria-label="새 프로젝트 이름" placeholder="이름" />
        <input v-model="projectForm.deliveringCompany" type="text" aria-label="수행사" placeholder="수행사" />
        <input v-model="projectForm.clientCompany" type="text" aria-label="고객사" placeholder="고객사" />
        <input v-model="projectForm.startedOn" type="date" aria-label="시작일" />
        <input v-model="projectForm.endsOn" type="date" aria-label="종료일" />
        <input v-model="projectForm.phase" type="text" aria-label="단계" placeholder="단계" />
        <select v-model="projectForm.status" aria-label="상태">
          <option v-for="st in STATUSES" :key="st" :value="st">{{ PROJECT_STATUS_LABEL[st] }}</option>
        </select>
        <button class="btn btn--primary" type="submit" :disabled="busy || !canCreateProject">등록</button>
      </form>
    </template>

    <template v-else-if="active === 'member'">
      <label class="picker">
        <span>프로젝트</span>
        <select v-model="memberProjectId" aria-label="배정 프로젝트">
          <option v-for="p in projectChoices" :key="p.id" :value="p.id">{{ p.name }}</option>
        </select>
      </label>
      <table class="table">
        <thead><tr><th>구성원</th><th>역할</th><th>배정일</th><th>종료일</th><th>동작</th></tr></thead>
        <tbody>
          <tr v-for="m in members" :key="m.id" :class="{ 'table__row--off': !!m.leftOn }">
            <td class="table__name">{{ m.userName }} {{ m.userTitle }}</td>
            <td>{{ PROJECT_ROLE_LABEL[m.projectRole] }}</td>
            <td>{{ m.joinedOn }}</td>
            <td>{{ m.leftOn ?? '--' }}</td>
            <td class="table__actions">
              <button v-if="!m.leftOn" class="btn btn--sm" type="button" :disabled="busy" :aria-label="`${m.userName} 배정 종료`"
                      @click="run(() => repository.updateProjectMember(memberProjectId!, m.id, m.version, { leftOn: seoulToday() }))">배정 종료</button>
              <button class="btn btn--sm btn--danger" type="button" :disabled="busy" :aria-label="`${m.userName} 배정 삭제`"
                      @click="run(() => repository.deleteProjectMember(memberProjectId!, m.id, m.version))">삭제</button>
            </td>
          </tr>
        </tbody>
      </table>
      <form class="create" @submit.prevent="addMember">
        <select v-model="memberForm.userId" aria-label="배정할 사용자">
          <option :value="null">사용자 선택</option>
          <option v-for="u in userChoices" :key="u.id" :value="u.id">{{ u.name }} {{ u.title }} ({{ u.employeeNo }})</option>
        </select>
        <select v-model="memberForm.projectRole" aria-label="배정 역할">
          <option v-for="r in ROLES" :key="r" :value="r">{{ PROJECT_ROLE_LABEL[r] }}</option>
        </select>
        <input v-model="memberForm.joinedOn" type="date" aria-label="배정일" />
        <button class="btn btn--primary" type="submit" :disabled="busy || !memberForm.userId || !memberProjectId">배정</button>
      </form>
    </template>

    <template v-else>
      <p class="state state--empty connections__note">
        연동을 해제해도 이미 수집된 기록은 지우지 않습니다. 수집한 문서가 있는 연동은 삭제되지 않습니다.
      </p>
      <table class="table">
        <thead>
          <tr><th>연동</th><th>채널</th><th>소유</th><th>연결 상태</th><th>수집 상태</th><th>마지막 수집</th><th>동작</th></tr>
        </thead>
        <tbody>
          <tr v-for="c in connections" :key="c.id">
            <td class="table__name">{{ c.displayName }}</td>
            <td>{{ CHANNEL_LABEL[c.channel] }}</td>
            <td>{{ c.ownerUserId ? '개인 연결' : '프로젝트 공용' }}</td>
            <td :class="{ 'is-off': c.status !== 'ACTIVE' }">{{ CONNECTION_STATUS_LABEL[c.status] }}</td>
            <td :class="{ 'is-warn': c.health !== 'NORMAL' }">{{ HEALTH_LABEL[c.health] }}</td>
            <td>{{ formatTimestamp(c.lastCollectedAt) }}</td>
            <td class="table__actions">
              <button v-if="c.status === 'ACTIVE'" class="btn btn--sm" type="button" :disabled="busy" @click="setConnection(c, 'PAUSED')">일시 중지</button>
              <button v-if="c.status === 'PAUSED'" class="btn btn--sm" type="button" :disabled="busy" @click="setConnection(c, 'ACTIVE')">재개</button>
              <button v-if="c.status === 'DISCONNECTED'" class="btn btn--sm" type="button" :disabled="busy" @click="run(() => repository.reconnectSourceConnection(c.id))">다시 연결</button>
              <button v-else class="btn btn--sm" type="button" :disabled="busy" @click="setConnection(c, 'DISCONNECTED')">해제</button>
              <button class="btn btn--sm btn--danger" type="button" :disabled="busy" :aria-label="`${c.displayName} 삭제`"
                      @click="run(() => repository.deleteSourceConnection(c.id, c.version))">삭제</button>
            </td>
          </tr>
        </tbody>
      </table>
    </template>

    <p v-if="error" class="state state--failed">{{ error }}</p>
  </AppShell>
</template>

<style scoped>
.credential { margin: 0 0 12px; padding: 12px 14px; border: 1px solid var(--line); border-left: 3px solid var(--orange); border-radius: 8px; display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.credential__text { flex-basis: 100%; margin: 0; font-size: 12px; color: var(--grey-dark); }
.credential__code { padding: 4px 8px; background: var(--paper); border-radius: 6px; font-size: 13px; word-break: break-all; }

.title { margin: 0; font-size: 22px; }
.sub { margin: 4px 0 0; font-size: 12px; color: var(--grey-dark); }

.tabs { display: flex; border-bottom: 1px solid var(--line); margin-bottom: 14px; }
.tabs__tab { border: none; background: none; padding: 8px 14px; font-family: var(--font); font-size: 13px; color: var(--grey-dark); cursor: pointer; border-bottom: 2px solid transparent; }
.tabs__tab--on { color: var(--navy); font-weight: 600; border-bottom-color: var(--orange); }
.tabs__tab:disabled { opacity: 0.4; cursor: not-allowed; }

.connections__note { border-style: solid; }
.table { width: 100%; border-collapse: collapse; background: var(--white); border: 1px solid var(--line); border-radius: var(--radius); overflow: hidden; font-size: 12px; }
.table th { text-align: left; padding: 9px 10px; background: var(--paper); color: var(--grey-dark); border-bottom: 1px solid var(--line); white-space: nowrap; }
.table td { padding: 9px 10px; border-bottom: 1px solid var(--line); }
.table__name { font-weight: 600; }
.table__row--off { opacity: 0.55; }
.table__actions { display: flex; gap: 6px; align-items: center; }
.is-off { color: var(--grey); }
.is-warn { color: var(--orange); }

.create { display: flex; gap: 8px; margin-top: 12px; }
.create--wrap { flex-wrap: wrap; }
.create select { border: 1px solid var(--line); border-radius: 6px; padding: 7px 10px; font-family: var(--font); font-size: 12px; }
.picker { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; font-size: 12px; color: var(--grey-dark); }
.hint { margin: 6px 0 0; font-size: 11px; color: var(--grey); }
.create input { flex: 1; border: 1px solid var(--line); border-radius: 6px; padding: 7px 10px; font-family: var(--font); font-size: 12px; }

.btn { height: 30px; padding: 0 12px; font-size: 12px; }
.btn--sm { height: 26px; padding: 0 10px; font-size: 11px; }
.btn--danger { border-color: var(--danger); color: var(--danger); }
</style>
