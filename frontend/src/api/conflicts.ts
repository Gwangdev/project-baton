import type { HandoverItem, RecordRelation } from './types'

/**
 * 충돌 항목의 관계. 서버의 `conflictId`는 충돌 행의 ID이고 인수인계 상세의 `conflicts[].id`는 관계의 ID라서, 두 값을
 * 같다고 보고 짝지으면 실제 서버에서는 엉뚱한 관계를 찾거나 못 찾는다(데모만 두 값이 같았다). 항목의 두 후보 기록과
 * 관계의 두 기록이 같은 쌍인지로 찾는다. 관계 등록이 한 쌍에 대체와 상충을 함께 걸지 못하게 하고 같은 종류의 중복도
 * 막으므로, 한 세대에서 같은 두 기록 사이의 충돌 항목은 하나뿐이다.
 */
export function relationOfItem(relations: RecordRelation[] | undefined, item: HandoverItem): RecordRelation | undefined {
  const [first, second] = item.candidateRecordIds ?? []
  if (first === undefined || second === undefined) return undefined
  return relations?.find((r) =>
    (r.fromRecordId === first && r.toRecordId === second) || (r.fromRecordId === second && r.toRecordId === first))
}
