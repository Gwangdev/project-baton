/**
 * 데이터 출처 선택.
 *
 * 기본값이자 운영의 유일한 모드는 `api`다. `demo`는 1단계 메모리 시나리오이며 빌드가 VITE_DATA_MODE=demo를 줄
 * 때만 존재한다. 그 밖의 값이면 앱을 시작하지 않는다. 오타를 조용히 demo로 받아들이면 실제 데이터를 기대한 자리에
 * 가상 데이터가 보이기 때문이다.
 */

export type DataMode = 'api' | 'demo'

export function resolveDataMode(raw: string | undefined): DataMode {
  if (raw === undefined || raw === '' || raw === 'api') return 'api'
  if (raw === 'demo') return 'demo'
  throw new Error(`Unsupported VITE_DATA_MODE: ${raw}`)
}
