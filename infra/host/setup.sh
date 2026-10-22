#!/usr/bin/env bash
# 저장소를 /opt/baton에 clone한 뒤, 새 Amazon Linux 2023 arm64 호스트(t4g.small)를 공개 데모용으로
# 준비한다(work-orders D7). root로 돌린다:
#  sudo /opt/baton/infra/host/setup.sh
# Docker, 공개된 SHA-256으로 확인한 버전 고정 Compose plugin(compose.deploy.yml이 !reset과 !override를 써서 Compose
# 2.24 이상이 필요하다), nftables, tc를 설치하고, 스왑 2 GiB를 더하고(호스트 메모리가 2 GiB다), 전송 상한, 연결 한도,
# 전송 할당, 매일 초기화를 설치해 시작한다. 스택은 /etc/baton/deploy.env(소유자만)와 이미지가 있어야 해서 시작하지
# 않는다(docs/operations/deploy-aws.md 참고). 다시 돌려도 이미 된 것은 바꾸지 않는다.
set -euo pipefail
COMPOSE_VERSION="${BATON_COMPOSE_VERSION:-v2.29.7}"
[ "$(id -u)" -eq 0 ] || { echo "setup: run as root" >&2; exit 1; }
[ "$(uname -m)" = "aarch64" ] || { echo "setup: expects an arm64 host (t4g)" >&2; exit 1; }
cd /opt/baton

dnf install -y docker git nftables iproute-tc
systemctl enable --now docker

plugin=/usr/local/lib/docker/cli-plugins/docker-compose
if ! docker compose version 2>/dev/null | grep -q "$COMPOSE_VERSION"; then
  mkdir -p "$(dirname "$plugin")"
  base="https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-aarch64"
  tmp=$(mktemp -d)
  curl -fsSL "$base" -o "$tmp/docker-compose-linux-aarch64"
  curl -fsSL "$base.sha256" -o "$tmp/docker-compose-linux-aarch64.sha256"
  (cd "$tmp" && sha256sum -c docker-compose-linux-aarch64.sha256)
  install -m 0755 "$tmp/docker-compose-linux-aarch64" "$plugin"
  rm -rf "$tmp"
fi
docker compose version

if ! swapon --show | grep -q /swapfile; then
  [ -f /swapfile ] || { dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none; chmod 600 /swapfile; mkswap /swapfile; }
  swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

mkdir -p /etc/baton /var/lib/baton
chmod 700 /etc/baton
install -m 0644 infra/host/systemd/*.service infra/host/systemd/*.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now baton-egress-shape.service baton-firewall.service baton-egress-quota.timer baton-demo-reset.timer
echo "setup: done. Next: /etc/baton/deploy.env, the baseline dump and infra/host/deploy.sh (docs/operations/deploy-aws.md)"
