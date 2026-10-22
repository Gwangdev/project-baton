#!/usr/bin/env bash
# CI가 올린 이미지로 호스트의 공개 데모를 시작하거나 갱신한다(work-orders D7, 결정 10). root로 돌린다:
#  sudo BATON_IMAGE_TAG=<commit> /opt/baton/infra/host/deploy.sh
# 호스트는 인스턴스 역할로 ECR에 로그인해서 여기에 AWS 키를 두지 않는다. registry와 그 밖의 설정은
# /etc/baton/deploy.env에 있고(BATON_IMAGE_PREFIX=<account>.dkr.ecr.ap-northeast-2.amazonaws.com/project-baton과
# 비밀번호들), 여기서 준 tag가 파일의 tag보다 우선한다.
set -euo pipefail
cd /opt/baton
env_file=/etc/baton/deploy.env
[ "$(stat -c %a "$env_file")" = "600" ] || { echo "deploy: $env_file must be readable by its owner only (chmod 600)" >&2; exit 1; }
prefix=$(grep -E '^BATON_IMAGE_PREFIX=' "$env_file" | cut -d= -f2-)
registry=${prefix%%/*}
aws ecr get-login-password --region ap-northeast-2 | docker login --username AWS --password-stdin "$registry"
compose() { docker compose --env-file "$env_file" -f compose.yml -f compose.deploy.yml "$@"; }

# 매일 초기화가 복원하는 기준 덤프는 같은 tag로 온다(CI가 만든다, infra/demo/Dockerfile). 무엇을 바꾸기 전에 먼저
# 받아서, 기준 덤프가 없는 tag면 돌고 있는 스택을 건드리지 않고 여기서 멈춘다.
tag="${BATON_IMAGE_TAG:-$(grep -E '^BATON_IMAGE_TAG=' "$env_file" | cut -d= -f2-)}"
docker pull -q "$prefix/demo-baseline:$tag" >/dev/null
docker run --rm "$prefix/demo-baseline:$tag" > /var/lib/baton/demo-baseline.dump.new
compose pull db migrate app proxy
mv /var/lib/baton/demo-baseline.dump.new /var/lib/baton/demo-baseline.dump
chmod 600 /var/lib/baton/demo-baseline.dump
compose up -d --no-build --wait --wait-timeout 300

# 새 DB에는 스키마만 있고 데모 데이터는 없어서 기준 덤프를 한 번 넣는다.
projects=$(compose exec -T db psql -At -U baton -d baton -c 'select count(*) from projects')
if [ "$projects" = "0" ]; then
  BATON_DEPLOY_ENV="$env_file" BATON_DEMO_BASELINE=/var/lib/baton/demo-baseline.dump infra/demo/reset.sh
  compose up -d --no-build --wait --wait-timeout 300
fi
# compose up은 전송 할당을 다 쓴 달에도 프록시를 띄우므로, 다음 5분 실행을 기다리지 않고 할당 확인이 지금 다시 멈춘다.
systemctl start baton-egress-quota.service
compose ps
