import type { NoteRepository } from '@/api/repositories/notes'
import { requireMember, requireUser, userName } from '../access'
import { publicNote, workRecord } from '../views'
import { fail, type DemoRecord, type DemoState } from '../state'

/** 데모 개인 메모와 선택 요약 공유. 공유해도 메모 본문은 기록에 복사하지 않는다 (D10). */
export function demoNoteRepository(state: DemoState): NoteRepository {
  return {
    listPersonalNotes: async (projectId) => {
      await state.pause()
      const userId = requireMember(state, projectId)
      const items = state.notes.filter((n) => n.authorId === userId && n.projectId === projectId).map(publicNote)
      return { items, page: { total: items.length, limit: items.length, offset: 0, nextCursor: null } }
    },
    createPersonalNote: async (projectId, body) => {
      await state.pause()
      const userId = requireMember(state, projectId)
      if (!body.trim()) fail(400, 'INVALID_INPUT')
      const id = state.nextId()
      const note = { id, code: `NOTE-${id}`, projectId, authorId: userId, body, createdAt: new Date().toISOString(), sharedRecordCodes: [] }
      state.notes.push(note)
      return publicNote(note)
    },
    sharePersonalNoteSummary: async (noteId, input) => {
      await state.pause()
      const userId = requireUser(state)
      const note = state.notes.find((n) => n.id === noteId && n.authorId === userId) ?? fail(404, 'NOTE_NOT_FOUND')
      // 장면 2의 첫 공유는 시나리오 문서대로 REC-105가 된다.
      const id = state.records.some((r) => r.id === 105) ? state.nextId() : 105
      const actor = userName(state, userId)
      const record: DemoRecord = {
        id, recordType: input.recordType, title: input.title, summary: input.summary,
        // 작성자가 고른 요약만 공개한다. 비공개 본문은 복사하지 않는다.
        originalExcerpt: input.summary, occurredOn: input.occurredOn, authorName: actor, ownerName: null,
        dueOn: input.dueOn ?? null, trustStatus: 'CONFIRMED', connectionId: null, confirmedByName: actor,
        confirmedAt: new Date().toISOString(), version: 0, noteId: note.id, noteCode: note.code, revisions: [],
      }
      state.records.push(record)
      note.sharedRecordCodes = [...(note.sharedRecordCodes ?? []), `REC-${id}`]
      return workRecord(state, record, userId)
    },
  }
}
