import type { HttpClient } from '../http'
import type { Page, PersonalNote, ShareNoteSummaryInput, WorkRecord } from '../types'
import { readAll } from './listLimit'

/** 개인 메모와 선택 요약 공유 (FR-07·08). 원본은 공유해도 공개되지 않는다. */
export interface NoteRepository {
  listPersonalNotes(projectId: number): Promise<Page<PersonalNote>>
  createPersonalNote(projectId: number, body: string): Promise<PersonalNote>
  sharePersonalNoteSummary(noteId: number, input: ShareNoteSummaryInput): Promise<WorkRecord>
}

export function apiNoteRepository(http: HttpClient): NoteRepository {
  return {
    listPersonalNotes: (projectId) =>
      readAll((query) => http.get<Page<PersonalNote>>(`/projects/${projectId}/notes`, { ...query })),
    createPersonalNote: (projectId, body) =>
      http.command<PersonalNote>('POST', `/projects/${projectId}/notes`, { body: { body } }),
    sharePersonalNoteSummary: (noteId, input) =>
      http.command<WorkRecord>('POST', `/notes/${noteId}/share`, { body: input }),
  }
}
