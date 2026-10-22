# 같은 출처 진입점(README §3): 운영용 Vue 빌드를 정적 파일로 내주고, /api는 API 컨테이너로 넘긴다. 빌드 context는
# 저장소 루트이고, Dockerfile.dockerignore가 frontend/와 이 폴더로 제한한다. compose.yml이 이미지를 읽기 전용 루트
# 파일 시스템과 권한 없는 사용자로 돌린다.
FROM node:24-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
# 공개 데모 빌드(work-orders D7)는 로그인 화면의 안내를 보이고, 기본 빌드는 보이지 않는다.
ARG VITE_PUBLIC_DEMO=
RUN VITE_DATA_MODE=api VITE_PUBLIC_DEMO=$VITE_PUBLIC_DEMO npm run build

FROM caddy:2-alpine@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648
COPY infra/proxy/Caddyfile infra/proxy/Caddyfile.dev infra/proxy/Caddyfile.prod infra/proxy/routes.caddy infra/proxy/health.caddy /etc/caddy/
# 배포는 인증서를 /data의 named volume에 두는데, 새 volume은 이 폴더의 소유자를 받아서 권한 없는 프록시가 쓸 수 있다.
# 로컬 스택은 대신 tmpfs를 mount한다.
RUN mkdir -p /data /config && chown 10002:10002 /data /config
COPY --from=web /web/dist /srv
USER 10002:10002
EXPOSE 8443
