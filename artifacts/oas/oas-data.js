window.__OAS = {
 "groups": [
  {
   "tag": "auth",
   "desc": "S01 사내 계정 로그인과 로그아웃",
   "count": 4
  },
  {
   "tag": "dashboard",
   "desc": "S02 프로젝트 대시보드의 요약 정보",
   "count": 2
  },
  {
   "tag": "projects",
   "desc": "S02·S03 프로젝트 목록과 상세",
   "count": 3
  },
  {
   "tag": "records",
   "desc": "S03·S04·S05 프로젝트 기록의 조회와 확인",
   "count": 5
  },
  {
   "tag": "notes",
   "desc": "S06 빠른 메모의 비공개 저장과 선택 요약 공유",
   "count": 3
  },
  {
   "tag": "connections",
   "desc": "S06·S12 기록 수집 연동의 연결과 상태",
   "count": 9
  },
  {
   "tag": "handovers",
   "desc": "S07·S08·S11 담당자 교체와 인수인계 패키지",
   "count": 9
  },
  {
   "tag": "qa",
   "desc": "S09 근거 기반 질의응답",
   "count": 2
  },
  {
   "tag": "assessments",
   "desc": "S10 업무 준비도 확인",
   "count": 2
  },
  {
   "tag": "admin",
   "desc": "S12 사용자·조직·프로젝트·역할 관리",
   "count": 17
  }
 ],
 "rows": [
  {
   "method": "POST",
   "path": "/auth/sign-in",
   "tag": "auth",
   "operationId": "signIn",
   "summary": "사내 계정 로그인",
   "request": "SignInRequest",
   "successCode": "200",
   "response": "CurrentUser",
   "errors": [
    "400",
    "401",
    "403",
    "413",
    "415",
    "429",
    "500"
   ],
   "screen": "S01",
   "action": "사내 계정으로 로그인"
  },
  {
   "method": "POST",
   "path": "/records/{recordId}/review",
   "tag": "records",
   "operationId": "reviewRecord",
   "summary": "기록 승인·수정 후 승인·제외",
   "request": "ReviewRecordRequest",
   "successCode": "200",
   "response": "RecordDetail",
   "errors": [
    "400",
    "401",
    "403",
    "404",
    "409",
    "412",
    "413",
    "415",
    "428",
    "500",
    "503"
   ],
   "screen": "S05",
   "action": "AI 초안을 수정 후 승인"
  },
  {
   "method": "POST",
   "path": "/notes/{noteId}/share",
   "tag": "notes",
   "operationId": "sharePersonalNoteSummary",
   "summary": "메모의 선택 요약을 프로젝트 기록으로 공유",
   "request": "ShareNoteSummaryRequest",
   "successCode": "201",
   "response": "RecordDetail",
   "errors": [
    "400",
    "401",
    "403",
    "404",
    "409",
    "413",
    "415",
    "500",
    "503"
   ],
   "screen": "S06",
   "action": "메모의 선택 요약만 공유"
  },
  {
   "method": "PATCH",
   "path": "/connections/{connectionId}",
   "tag": "connections",
   "operationId": "updateSourceConnection",
   "summary": "연동 표시명·대상 변경과 일시 중지·재개·해제",
   "request": "UpdateSourceConnectionRequest",
   "successCode": "200",
   "response": "SourceConnection",
   "errors": [
    "400",
    "401",
    "403",
    "404",
    "409",
    "412",
    "413",
    "415",
    "428",
    "500"
   ],
   "screen": "S06",
   "action": "승인 폴더 연결·일시 중지·해제"
  },
  {
   "method": "POST",
   "path": "/projects/{projectId}/handovers",
   "tag": "handovers",
   "operationId": "requestHandover",
   "summary": "담당자 교체 요청과 패키지 생성 접수",
   "request": "CreateHandoverRequest",
   "successCode": "202",
   "response": "Handover",
   "errors": [
    "400",
    "401",
    "403",
    "404",
    "409",
    "413",
    "415",
    "500",
    "503"
   ],
   "screen": "S07",
   "action": "담당자 교체 요청"
  },
  {
   "method": "PATCH",
   "path": "/handovers/{handoverId}/items/{itemId}",
   "tag": "handovers",
   "operationId": "resolveHandoverItem",
   "summary": "충돌 항목의 최종 근거 선택",
   "request": "ResolveHandoverItemRequest",
   "successCode": "200",
   "response": "HandoverItem",
   "errors": [
    "400",
    "401",
    "403",
    "404",
    "409",
    "412",
    "413",
    "415",
    "428",
    "500"
   ],
   "screen": "S08",
   "action": "충돌의 최종 근거 선택"
  },
  {
   "method": "POST",
   "path": "/handovers/{handoverId}/questions",
   "tag": "qa",
   "operationId": "askQuestion",
   "summary": "프로젝트 질문과 근거 기반 답변",
   "request": "AskQuestionRequest",
   "successCode": "201",
   "response": "QaAnswer",
   "errors": [
    "400",
    "401",
    "403",
    "404",
    "406",
    "409",
    "413",
    "415",
    "429",
    "500",
    "503"
   ],
   "screen": "S09",
   "action": "근거 기반 질문"
  },
  {
   "method": "POST",
   "path": "/handovers/{handoverId}/assessment/attempts",
   "tag": "assessments",
   "operationId": "submitAssessmentAttempt",
   "summary": "준비도 점검 답안 제출",
   "request": "SubmitAttemptRequest",
   "successCode": "201",
   "response": "AssessmentAttempt",
   "errors": [
    "400",
    "401",
    "403",
    "404",
    "409",
    "413",
    "415",
    "500"
   ],
   "screen": "S10",
   "action": "준비도 답안 제출"
  },
  {
   "method": "POST",
   "path": "/handovers/{handoverId}/approval",
   "tag": "handovers",
   "operationId": "decideHandoverCompletion",
   "summary": "인수인계 완료 승인 또는 보완 요청",
   "request": "HandoverApprovalRequest",
   "successCode": "200",
   "response": "HandoverDetail",
   "errors": [
    "400",
    "401",
    "403",
    "404",
    "409",
    "412",
    "413",
    "415",
    "428",
    "500"
   ],
   "screen": "S11",
   "action": "완료 승인 또는 보완 요청"
  },
  {
   "method": "PATCH",
   "path": "/admin/users/{userId}",
   "tag": "admin",
   "operationId": "updateUser",
   "summary": "사용자 수정 또는 비활성화",
   "request": "UserUpdateRequest",
   "successCode": "200",
   "response": "User",
   "errors": [
    "400",
    "401",
    "403",
    "404",
    "409",
    "412",
    "413",
    "415",
    "428",
    "500"
   ],
   "screen": "S12",
   "action": "사용자 비활성화"
  }
 ],
 "full": [
  {
   "tag": "auth",
   "desc": "S01 사내 계정 로그인과 로그아웃",
   "ops": [
    {
     "method": "POST",
     "path": "/auth/sign-in",
     "summary": "사내 계정 로그인",
     "successCode": "200",
     "request": "SignInRequest",
     "response": "CurrentUser",
     "errors": [
      "400",
      "401",
      "403",
      "413",
      "415",
      "429",
      "500"
     ]
    },
    {
     "method": "POST",
     "path": "/auth/sign-out",
     "summary": "로그아웃",
     "successCode": "204",
     "request": "",
     "response": "",
     "errors": [
      "401",
      "403",
      "500"
     ]
    },
    {
     "method": "POST",
     "path": "/auth/credentials",
     "summary": "설정 코드로 비밀번호 정하기",
     "successCode": "204",
     "request": "SetCredentialRequest",
     "response": "",
     "errors": [
      "400",
      "403",
      "413",
      "415",
      "429",
      "500"
     ]
    },
    {
     "method": "GET",
     "path": "/auth/csrf",
     "summary": "상태 변경용 CSRF 토큰 조회",
     "successCode": "200",
     "request": "",
     "response": "CsrfToken",
     "errors": [
      "403",
      "500"
     ]
    }
   ]
  },
  {
   "tag": "dashboard",
   "desc": "S02 프로젝트 대시보드의 요약 정보",
   "ops": [
    {
     "method": "GET",
     "path": "/me",
     "summary": "현재 사용자와 참여 프로젝트 역할 조회",
     "successCode": "200",
     "request": "",
     "response": "CurrentUser",
     "errors": [
      "401",
      "403",
      "500"
     ]
    },
    {
     "method": "GET",
     "path": "/dashboard",
     "summary": "대시보드 요약 조회",
     "successCode": "200",
     "request": "",
     "response": "DashboardSummary",
     "errors": [
      "401",
      "403",
      "500"
     ]
    }
   ]
  },
  {
   "tag": "projects",
   "desc": "S02·S03 프로젝트 목록과 상세",
   "ops": [
    {
     "method": "GET",
     "path": "/projects",
     "summary": "접근 가능한 프로젝트 목록 조회",
     "successCode": "200",
     "request": "",
     "response": "ProjectList",
     "errors": [
      "400",
      "401",
      "403",
      "500"
     ]
    },
    {
     "method": "GET",
     "path": "/projects/{projectId}",
     "summary": "프로젝트 상세 조회",
     "successCode": "200",
     "request": "",
     "response": "ProjectDetail",
     "errors": [
      "401",
      "403",
      "404",
      "500"
     ]
    },
    {
     "method": "GET",
     "path": "/projects/{projectId}/members",
     "summary": "프로젝트 현재 구성원 조회",
     "successCode": "200",
     "request": "",
     "response": "ProjectMemberList",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "500"
     ]
    }
   ]
  },
  {
   "tag": "records",
   "desc": "S03·S04·S05 프로젝트 기록의 조회와 확인",
   "ops": [
    {
     "method": "GET",
     "path": "/projects/{projectId}/records",
     "summary": "프로젝트 기록 목록 조회",
     "successCode": "200",
     "request": "",
     "response": "RecordList",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "500"
     ]
    },
    {
     "method": "GET",
     "path": "/records/{recordId}",
     "summary": "기록 상세 조회",
     "successCode": "200",
     "request": "",
     "response": "RecordDetail",
     "errors": [
      "401",
      "403",
      "404",
      "500"
     ]
    },
    {
     "method": "POST",
     "path": "/records/{recordId}/review",
     "summary": "기록 승인·수정 후 승인·제외",
     "successCode": "200",
     "request": "ReviewRecordRequest",
     "response": "RecordDetail",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "412",
      "413",
      "415",
      "428",
      "500",
      "503"
     ]
    },
    {
     "method": "POST",
     "path": "/projects/{projectId}/record-relations",
     "summary": "확정 기록 관계 생성",
     "successCode": "201",
     "request": "CreateRecordRelationRequest",
     "response": "RecordRelation",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "413",
      "415",
      "500"
     ]
    },
    {
     "method": "POST",
     "path": "/record-relations/{relationId}/withdrawal",
     "summary": "잘못 등록한 기록 관계 철회",
     "successCode": "200",
     "request": "WithdrawRecordRelationRequest",
     "response": "RecordRelation",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "412",
      "413",
      "415",
      "428",
      "500"
     ]
    }
   ]
  },
  {
   "tag": "notes",
   "desc": "S06 빠른 메모의 비공개 저장과 선택 요약 공유",
   "ops": [
    {
     "method": "GET",
     "path": "/projects/{projectId}/notes",
     "summary": "내 빠른 메모 목록 조회",
     "successCode": "200",
     "request": "",
     "response": "PersonalNoteList",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "500"
     ]
    },
    {
     "method": "POST",
     "path": "/projects/{projectId}/notes",
     "summary": "빠른 메모 비공개 저장",
     "successCode": "201",
     "request": "CreatePersonalNoteRequest",
     "response": "PersonalNote",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "413",
      "415",
      "500"
     ]
    },
    {
     "method": "POST",
     "path": "/notes/{noteId}/share",
     "summary": "메모의 선택 요약을 프로젝트 기록으로 공유",
     "successCode": "201",
     "request": "ShareNoteSummaryRequest",
     "response": "RecordDetail",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "413",
      "415",
      "500",
      "503"
     ]
    }
   ]
  },
  {
   "tag": "connections",
   "desc": "S06·S12 기록 수집 연동의 연결과 상태",
   "ops": [
    {
     "method": "GET",
     "path": "/projects/{projectId}/connections",
     "summary": "프로젝트 연동 목록과 상태 조회",
     "successCode": "200",
     "request": "",
     "response": "SourceConnectionList",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "500"
     ]
    },
    {
     "method": "POST",
     "path": "/projects/{projectId}/connections",
     "summary": "승인된 클라우드 업무 폴더 연결",
     "successCode": "201",
     "request": "CreateSourceConnectionRequest",
     "response": "SourceConnection",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "413",
      "415",
      "500"
     ]
    },
    {
     "method": "GET",
     "path": "/connections/{connectionId}",
     "summary": "연동 상세와 마지막 수집 결과 조회",
     "successCode": "200",
     "request": "",
     "response": "SourceConnectionDetail",
     "errors": [
      "401",
      "403",
      "404",
      "500"
     ]
    },
    {
     "method": "PATCH",
     "path": "/connections/{connectionId}",
     "summary": "연동 표시명·대상 변경과 일시 중지·재개·해제",
     "successCode": "200",
     "request": "UpdateSourceConnectionRequest",
     "response": "SourceConnection",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "412",
      "413",
      "415",
      "428",
      "500"
     ]
    },
    {
     "method": "DELETE",
     "path": "/connections/{connectionId}",
     "summary": "연동 설정 삭제",
     "successCode": "204",
     "request": "",
     "response": "",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "412",
      "428",
      "500"
     ]
    },
    {
     "method": "POST",
     "path": "/connections/{connectionId}/reconnect",
     "summary": "연동 재연결",
     "successCode": "200",
     "request": "",
     "response": "SourceConnection",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "500"
     ]
    },
    {
     "method": "GET",
     "path": "/connections/{connectionId}/files",
     "summary": "수집된 파일·문서 목록 조회",
     "successCode": "200",
     "request": "",
     "response": "CollectedFileList",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "500"
     ]
    },
    {
     "method": "POST",
     "path": "/connections/{connectionId}/files/{sourceId}/records",
     "summary": "실패한 원문에서 수동 초안 생성",
     "successCode": "201",
     "request": "CreateManualDraftRequest",
     "response": "RecordDetail",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "413",
      "415",
      "500"
     ]
    },
    {
     "method": "POST",
     "path": "/connections/{connectionId}/files/{sourceId}/processing-attempts",
     "summary": "실패한 원문 처리 재시도 접수",
     "successCode": "202",
     "request": "",
     "response": "ProcessingAttempt",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "500",
      "503"
     ]
    }
   ]
  },
  {
   "tag": "handovers",
   "desc": "S07·S08·S11 담당자 교체와 인수인계 패키지",
   "ops": [
    {
     "method": "GET",
     "path": "/projects/{projectId}/handovers",
     "summary": "프로젝트의 인수인계 목록 조회",
     "successCode": "200",
     "request": "",
     "response": "HandoverList",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "500"
     ]
    },
    {
     "method": "POST",
     "path": "/projects/{projectId}/handovers",
     "summary": "담당자 교체 요청과 패키지 생성 접수",
     "successCode": "202",
     "request": "CreateHandoverRequest",
     "response": "Handover",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "413",
      "415",
      "500",
      "503"
     ]
    },
    {
     "method": "GET",
     "path": "/handovers/{handoverId}",
     "summary": "인수인계 상태와 패키지 요약 조회",
     "successCode": "200",
     "request": "",
     "response": "HandoverDetail",
     "errors": [
      "401",
      "403",
      "404",
      "500"
     ]
    },
    {
     "method": "POST",
     "path": "/handovers/{handoverId}/retry",
     "summary": "패키지 생성 재시도",
     "successCode": "202",
     "request": "",
     "response": "Handover",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "412",
      "428",
      "500",
      "503"
     ]
    },
    {
     "method": "GET",
     "path": "/handovers/{handoverId}/items",
     "summary": "인수인계 항목 목록 조회",
     "successCode": "200",
     "request": "",
     "response": "HandoverItemList",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "500"
     ]
    },
    {
     "method": "PATCH",
     "path": "/handovers/{handoverId}/items/{itemId}",
     "summary": "충돌 항목의 최종 근거 선택",
     "successCode": "200",
     "request": "ResolveHandoverItemRequest",
     "response": "HandoverItem",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "412",
      "413",
      "415",
      "428",
      "500"
     ]
    },
    {
     "method": "POST",
     "path": "/handovers/{handoverId}/approval",
     "summary": "인수인계 완료 승인 또는 보완 요청",
     "successCode": "200",
     "request": "HandoverApprovalRequest",
     "response": "HandoverDetail",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "412",
      "413",
      "415",
      "428",
      "500"
     ]
    },
    {
     "method": "POST",
     "path": "/handovers/{handoverId}/generations",
     "summary": "최신 확정 근거로 새 인수인계 세대 생성",
     "successCode": "202",
     "request": "RegenerateHandoverRequest",
     "response": "Handover",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "412",
      "413",
      "415",
      "428",
      "500",
      "503"
     ]
    },
    {
     "method": "POST",
     "path": "/handovers/{handoverId}/items/{itemId}/acknowledgements",
     "summary": "현재 세대의 필수 항목을 명시적으로 확인",
     "successCode": "201",
     "request": "",
     "response": "HandoverItemAcknowledgement",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "412",
      "428",
      "500"
     ]
    }
   ]
  },
  {
   "tag": "qa",
   "desc": "S09 근거 기반 질의응답",
   "ops": [
    {
     "method": "GET",
     "path": "/handovers/{handoverId}/questions",
     "summary": "질의응답 이력 조회",
     "successCode": "200",
     "request": "",
     "response": "QaMessageList",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "500"
     ]
    },
    {
     "method": "POST",
     "path": "/handovers/{handoverId}/questions",
     "summary": "프로젝트 질문과 근거 기반 답변",
     "successCode": "201",
     "request": "AskQuestionRequest",
     "response": "QaAnswer",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "406",
      "409",
      "413",
      "415",
      "429",
      "500",
      "503"
     ]
    }
   ]
  },
  {
   "tag": "assessments",
   "desc": "S10 업무 준비도 확인",
   "ops": [
    {
     "method": "GET",
     "path": "/handovers/{handoverId}/assessment",
     "summary": "업무 준비도 점검 문항과 응시 결과 조회",
     "successCode": "200",
     "request": "",
     "response": "AssessmentDetail",
     "errors": [
      "401",
      "403",
      "404",
      "409",
      "500"
     ]
    },
    {
     "method": "POST",
     "path": "/handovers/{handoverId}/assessment/attempts",
     "summary": "준비도 점검 답안 제출",
     "successCode": "201",
     "request": "SubmitAttemptRequest",
     "response": "AssessmentAttempt",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "413",
      "415",
      "500"
     ]
    }
   ]
  },
  {
   "tag": "admin",
   "desc": "S12 사용자·조직·프로젝트·역할 관리",
   "ops": [
    {
     "method": "GET",
     "path": "/admin/organizations",
     "summary": "조직 목록 조회",
     "successCode": "200",
     "request": "",
     "response": "OrganizationList",
     "errors": [
      "400",
      "401",
      "403",
      "500"
     ]
    },
    {
     "method": "POST",
     "path": "/admin/organizations",
     "summary": "조직 생성",
     "successCode": "201",
     "request": "OrganizationUpsertRequest",
     "response": "Organization",
     "errors": [
      "400",
      "401",
      "403",
      "409",
      "413",
      "415",
      "500"
     ]
    },
    {
     "method": "PATCH",
     "path": "/admin/organizations/{organizationId}",
     "summary": "조직 수정 또는 비활성화",
     "successCode": "200",
     "request": "OrganizationUpdateRequest",
     "response": "Organization",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "412",
      "413",
      "415",
      "428",
      "500"
     ]
    },
    {
     "method": "DELETE",
     "path": "/admin/organizations/{organizationId}",
     "summary": "조직 삭제",
     "successCode": "204",
     "request": "",
     "response": "",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "412",
      "428",
      "500"
     ]
    },
    {
     "method": "GET",
     "path": "/admin/users",
     "summary": "사용자 목록 조회",
     "successCode": "200",
     "request": "",
     "response": "UserList",
     "errors": [
      "400",
      "401",
      "403",
      "500"
     ]
    },
    {
     "method": "POST",
     "path": "/admin/users",
     "summary": "사용자 생성",
     "successCode": "201",
     "request": "UserCreateRequest",
     "response": "User",
     "errors": [
      "400",
      "401",
      "403",
      "409",
      "413",
      "415",
      "500"
     ]
    },
    {
     "method": "PATCH",
     "path": "/admin/users/{userId}",
     "summary": "사용자 수정 또는 비활성화",
     "successCode": "200",
     "request": "UserUpdateRequest",
     "response": "User",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "412",
      "413",
      "415",
      "428",
      "500"
     ]
    },
    {
     "method": "DELETE",
     "path": "/admin/users/{userId}",
     "summary": "사용자 삭제",
     "successCode": "204",
     "request": "",
     "response": "",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "412",
      "428",
      "500"
     ]
    },
    {
     "method": "POST",
     "path": "/admin/users/{userId}/credential-tokens",
     "summary": "초기 설정 코드 발급",
     "successCode": "201",
     "request": "",
     "response": "CredentialToken",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "500"
     ]
    },
    {
     "method": "GET",
     "path": "/admin/projects",
     "summary": "전체 프로젝트 목록 조회",
     "successCode": "200",
     "request": "",
     "response": "ProjectList",
     "errors": [
      "400",
      "401",
      "403",
      "500"
     ]
    },
    {
     "method": "POST",
     "path": "/admin/projects",
     "summary": "프로젝트 생성",
     "successCode": "201",
     "request": "ProjectCreateRequest",
     "response": "Project",
     "errors": [
      "400",
      "401",
      "403",
      "409",
      "413",
      "415",
      "500"
     ]
    },
    {
     "method": "PATCH",
     "path": "/admin/projects/{projectId}",
     "summary": "프로젝트 수정 또는 비활성화",
     "successCode": "200",
     "request": "ProjectUpdateRequest",
     "response": "Project",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "412",
      "413",
      "415",
      "428",
      "500"
     ]
    },
    {
     "method": "DELETE",
     "path": "/admin/projects/{projectId}",
     "summary": "프로젝트 삭제",
     "successCode": "204",
     "request": "",
     "response": "",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "412",
      "428",
      "500"
     ]
    },
    {
     "method": "GET",
     "path": "/admin/projects/{projectId}/members",
     "summary": "프로젝트 구성원과 역할 조회",
     "successCode": "200",
     "request": "",
     "response": "ProjectMemberList",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "500"
     ]
    },
    {
     "method": "POST",
     "path": "/admin/projects/{projectId}/members",
     "summary": "프로젝트 구성원 배정",
     "successCode": "201",
     "request": "ProjectMemberUpsertRequest",
     "response": "ProjectMember",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "413",
      "415",
      "500"
     ]
    },
    {
     "method": "PATCH",
     "path": "/admin/projects/{projectId}/members/{memberId}",
     "summary": "구성원 역할 변경 또는 배정 종료",
     "successCode": "200",
     "request": "ProjectMemberUpdateRequest",
     "response": "ProjectMember",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "412",
      "413",
      "415",
      "428",
      "500"
     ]
    },
    {
     "method": "DELETE",
     "path": "/admin/projects/{projectId}/members/{memberId}",
     "summary": "구성원 배정 삭제",
     "successCode": "204",
     "request": "",
     "response": "",
     "errors": [
      "400",
      "401",
      "403",
      "404",
      "409",
      "412",
      "428",
      "500"
     ]
    }
   ]
  }
 ],
 "stats": {
  "operations": 56,
  "paths": 42,
  "tags": 10,
  "schemas": 86,
  "refs": 715,
  "async": 4,
  "deletes": 5,
  "errorCodes": [
   "400",
   "401",
   "403",
   "404",
   "406",
   "409",
   "412",
   "413",
   "415",
   "428",
   "429",
   "500",
   "503"
  ]
 }
};
