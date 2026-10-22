/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** `api`(기본) 또는 `demo`. src/api/dataMode.ts 참고. */
  readonly VITE_DATA_MODE?: string
  /** `true`면 공개 데모 배포 빌드다. 로그인 화면이 합성 데이터·매일 초기화·개인정보 입력 금지를 알린다. */
  readonly VITE_PUBLIC_DEMO?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
