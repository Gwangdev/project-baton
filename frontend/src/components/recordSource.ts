import type { WorkRecord } from '@/api/types'

/**
 * 서버가 이 사용자에게 허락한 범위에서 기록의 출처를 표시한다. 메모 공유 기록의 메모 code는 작성자에게만 오고
 * (다른 사람에게는 서버가 생략한다), 개인 폴더 원본 이름도 같은 이유로 빠질 수 있다.
 */
export function recordSourceName(record: WorkRecord): string {
  if (record.sourceNoteCode) return `${record.sourceNoteCode} 공유 요약`
  if (record.source) return record.source.displayName
  return '공유 메모'
}
