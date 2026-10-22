import type { RouteRecordRaw } from 'vue-router'

/**
 * 화면 경로 S01~S12 (`DAY1_서비스_기획_확정안.md`).
 *
 * 경로 파라미터는 API의 숫자 엔티티 ID다. REC-101 같은 화면 code는 경로에 넣지 않는다. 모든 사용자는
 * /dashboard에서 시작하고(D5), 역할에 따라 달라지는 것은 경로가 아니라 그 안에서 서버가 돌려주는 내용이다.
 * `/records`와 `/handovers/new`는 셸에서 선택한 현재 프로젝트를 대상으로 한다.
 */
export const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { screen: 'S01' } },
  { path: '/credentials', name: 'credentials', component: () => import('@/views/CredentialSetupView.vue'), meta: { screen: 'S01' } },
  { path: '/dashboard', name: 'dashboard', component: () => import('@/views/DashboardView.vue'), meta: { screen: 'S02' } },
  { path: '/projects/:projectId', name: 'project-timeline', component: () => import('@/views/ProjectTimelineView.vue'), meta: { screen: 'S03' } },
  { path: '/projects/:projectId/collection', name: 'record-collection', component: () => import('@/views/RecordCollectionView.vue'), meta: { screen: 'S06' } },
  { path: '/records', name: 'record-inbox', component: () => import('@/views/RecordInboxView.vue'), meta: { screen: 'S04' } },
  { path: '/records/:recordId', name: 'record-review', component: () => import('@/views/RecordReviewView.vue'), meta: { screen: 'S05' } },
  { path: '/handovers/new', name: 'handover-request', component: () => import('@/views/HandoverRequestView.vue'), meta: { screen: 'S07' } },
  { path: '/handovers/:handoverId/progress', name: 'handover-progress', component: () => import('@/views/HandoverProgressView.vue'), meta: { screen: 'S08' } },
  { path: '/handovers/:handoverId', name: 'handover-package', component: () => import('@/views/HandoverPackageView.vue'), meta: { screen: 'S09' } },
  { path: '/handovers/:handoverId/readiness', name: 'readiness-check', component: () => import('@/views/ReadinessCheckView.vue'), meta: { screen: 'S10' } },
  { path: '/handovers/:handoverId/review', name: 'manager-review', component: () => import('@/views/ManagerReviewView.vue'), meta: { screen: 'S11' } },
  { path: '/admin', name: 'admin', component: () => import('@/views/AdminView.vue'), meta: { screen: 'S12' } },
]

/** 루트와 모르는 경로는 대시보드로 보낸다. 로그인하지 않았다면 가드가 먼저 로그인으로 보낸다. */
export const fallbackRoutes: RouteRecordRaw[] = [
  { path: '/', redirect: '/dashboard' },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
]
