# 공개 데모 배포 절차 (AWS, `baton.gwang.dev`)

work-orders D7의 배포를 사람이 콘솔에서 하는 순서다. AWS 자원을 만드는 것은 운영자가 하고, 저장소의 스크립트가 호스트 안의 일을 한다. 모든 자원은 **서울 리전(ap-northeast-2)** 에 만든다. 리전에 묶인 자원은 옮길 수 없다.

## 0. 먼저 정할 것
- **데모 비밀번호.** 네 시연 계정이 같이 쓰는 공개값으로 README 「공개 데모」에 적혀 있다. 같은 값을 GitHub 저장소 비밀값 `BATON_DEMO_PASSWORD`에 넣는다(CI가 기준 덤프를 만들 때 쓴다). 바꾸려면 README와 비밀값을 함께 바꾸고(12자 이상, 사번과 다르게, 다른 곳에서 쓰지 않는 값) 이미지를 다시 만든다.
- **비용 확인.** 결제 화면에서 남은 크레딧을 보고, 요금 계산기로 `t4g.small`·gp3 20 GiB·공인 IPv4 한 개의 월 요금과 월 무료 전송량을 확인해 `evidence/D7.md`의 계산표에 적는다. 전송 할당(90 GB)이 무료량보다 작아야 한다.

## 1. ECR 저장소 (이미지 보관)
`ECR → 프라이빗 리포지토리 → 생성`으로 셋을 만든다: `project-baton/app`, `project-baton/proxy`, `project-baton/demo-baseline`. 공개 데모는 매일 기준 덤프로 되돌리므로 백업하지 않고, 백업 이미지도 올리지 않는다. 태그 변경 불가(immutable)를 켠다. 각 저장소의 `수명 주기 정책`에 [`infra/aws/ecr-lifecycle.json`](../../infra/aws/ecr-lifecycle.json)을 넣는다(최근 5개만 남긴다).

## 2. GitHub가 ECR에 올리는 권한 (OIDC, 저장된 키 없음)
1. `IAM → 자격 증명 공급자 → 공급자 추가`: OpenID Connect, URL `https://token.actions.githubusercontent.com`, 대상 `sts.amazonaws.com`.
2. `IAM → 역할 → 역할 생성 → 사용자 지정 신뢰 정책`: [`infra/aws/github-oidc-trust.json`](../../infra/aws/github-oidc-trust.json)의 `<ACCOUNT_ID>`와 `sub` 값을 채운다(main 브랜치의 workflow만 이 역할을 쓴다). 2026-07-15 이후에 만든 GitHub 저장소는 `sub`에 소유자와 저장소의 숫자 ID가 붙는 형식(`repo:<GITHUB_OWNER>@<OWNER_ID>/<REPOSITORY>@<REPOSITORY_ID>:ref:refs/heads/main`)을 쓰고, 이름만 넣으면 CI가 `Not authorized to perform sts:AssumeRoleWithWebIdentity`로 실패한다. `gh api repos/<GITHUB_OWNER>/<REPOSITORY>/actions/oidc/customization/sub`가 돌려주는 `sub_claim_prefix` 뒤에 `:ref:refs/heads/main`을 붙인 값을 넣는다(그보다 오래된 저장소면 `repo:<GITHUB_OWNER>/<REPOSITORY>:ref:refs/heads/main`). 이름 예: `baton-github-ecr-push`.
3. 그 역할에 인라인 정책으로 [`infra/aws/ecr-push-policy.json`](../../infra/aws/ecr-push-policy.json)을 붙인다(`project-baton/*` 저장소에 올리기만).
4. GitHub 저장소 `Settings → Secrets and variables → Actions`: 변수 `AWS_ECR_PUSH_ROLE_ARN`에 역할 ARN, 비밀값 `BATON_DEMO_PASSWORD`에 데모 비밀번호. 변수가 없으면 CI의 이미지 작업은 건너뛴다.

## 3. 호스트의 역할
`IAM → 역할 → 역할 생성 → AWS 서비스 EC2`, 관리형 정책 `AmazonSSMManagedInstanceCore`(SSH 없이 접속)와 `AmazonEC2ContainerRegistryReadOnly`(이미지 받기). 이름 예: `baton-demo-host`.

## 4. EC2
`EC2 → 인스턴스 시작`
| 항목 | 값 |
|---|---|
| AMI | Amazon Linux 2023, 64비트(Arm) |
| 유형 | `t4g.small` |
| 키 페어 | 없음(SSM Session Manager로 접속) |
| 보안 그룹 | 인바운드 TCP 80, 443만(0.0.0.0/0). 22는 열지 않는다 |
| 스토리지 | gp3 20 GiB |
| 고급 → IAM 인스턴스 프로파일 | `baton-demo-host` |
| 고급 → 크레딧 사양 | **표준(standard)** — 무제한이면 CPU를 오래 쓸 때 추가 요금이 붙는다 |
| 고급 → 메타데이터 버전 | V2만 |

그다음 `탄력적 IP → 할당 → 연결`. 연결하지 않은 탄력적 IP는 더 비싸므로 할당하면 바로 연결한다.

## 5. DNS (Cloudflare)
`gwang.dev → DNS → 레코드 추가`: 유형 A, 이름 `baton`, IPv4 탄력적 IP, **프록시 상태 DNS only(회색 구름)**. 주황색이면 Cloudflare가 TLS를 끝내 인증서 발급이 실패하고 브라우저부터 호스트까지의 TLS가 끊긴다. `dig +short baton.gwang.dev`가 탄력적 IP를 돌려줄 때까지 기다린다.

## 6. 이미지
저장소의 main에 push하면 CI가 시험을 돌리고, 이미지 셋(app, proxy, demo-baseline)을 모두 만든 뒤에 커밋 해시 태그로 ECR에 올린다. `ECR`에서 세 저장소에 같은 태그가 있는지 본다.

## 7. 호스트 준비 (SSM 세션)
`EC2 → 인스턴스 → 연결 → Session Manager`
```bash
sudo -i
dnf install -y git && git clone https://github.com/<GITHUB_OWNER>/<REPOSITORY>.git /opt/baton
/opt/baton/infra/host/setup.sh
```
`setup.sh`는 Docker, 버전 고정·체크섬 확인한 Compose 플러그인, nftables·tc, 스왑 2 GiB를 설치하고 전송 상한·연결 한도·전송 할당·매일 초기화를 켠다. 다시 돌려도 이미 된 것은 바꾸지 않는다.

배포 설정 파일을 만든다(소유자만 읽기).
```bash
umask 077
cat > /etc/baton/deploy.env <<ENV
BATON_IMAGE_PREFIX=<ACCOUNT_ID>.dkr.ecr.ap-northeast-2.amazonaws.com/project-baton
BATON_IMAGE_TAG=<커밋 해시>
BATON_DB_PASSWORD=$(openssl rand -hex 24)
BATON_DB_APP_PASSWORD=$(openssl rand -hex 24)
BATON_DB_INSPECT_PASSWORD=$(openssl rand -hex 24)
BATON_SITE_ADDRESS=baton.gwang.dev
ENV
```

## 8. 띄우기
```bash
/opt/baton/infra/host/deploy.sh
```
ECR에 로그인하고 이미지를 받아 띄운 뒤, 기준 덤프를 꺼내 두고 빈 DB면 한 번 불러온다. 처음 띄울 때 Caddy가 Let's Encrypt 인증서를 받는다.

**확인:** `https://baton.gwang.dev`가 유효한 인증서로 열리고 로그인 화면에 공개 데모 안내가 보인다. 네 시연 계정으로 로그인된다. 호스트에서:
```bash
systemctl list-timers 'baton-*'
tc qdisc show
nft list table inet baton
free -h && docker stats --no-stream
```

## 9. 예산 (두 번째 방어)
`Billing → Budgets → 예산 생성 → 비용 예산`, 월 금액은 고정비 계산값 근처. **고급 옵션에서 크레딧을 빼고(실제 사용액 기준)** 50·80·100%에 이메일 알림. 같은 예산에 `작업(Actions)`으로 임계치에서 이 EC2 인스턴스를 멈추는 동작을 붙일 수 있으면 붙인다(설정 화면에서 지원 범위를 확인해 `evidence/D7.md`에 적는다). 비용 집계는 실시간이 아니므로 첫 번째 방어는 호스트의 전송 상한·할당이다.

## 운영
- **새 버전:** main에 push → CI가 이미지 → 호스트에서 `cd /opt/baton && git pull`, `deploy.env`의 `BATON_IMAGE_TAG`를 새 해시로 바꾸고 `infra/host/deploy.sh`. `infra/host/` 아래(systemd 단위·nftables·tc)가 바뀐 커밋이면 그 전에 `infra/host/setup.sh`를 다시 돌려 호스트에 설치된 사본을 맞춘다.
- **매일 04:00(KST):** `baton-demo-reset.timer`가 기준 덤프로 되돌린다. 즉시: `systemctl start baton-demo-reset.service`.
- **전송 할당:** `cat /var/lib/baton/egress/$(date -u +%Y-%m).bytes`. 할당을 넘으면 프록시가 멈추고 다음 달 1일에 다시 뜬다.
- **실패 확인(알림 없음):** 호스트에는 알림 채널이 없다. 매일 초기화가 실패하면 앱이 멈춘 채 503 안내가 남고, 전송 할당 스크립트가 실패하면 할당이 조용히 꺼진다(속도 상한은 남는다). 시연 전과 배포 뒤에 `systemctl list-units --failed 'baton-*'`가 비어 있는지, `systemctl list-timers 'baton-*'`의 마지막 실행 시각을 본다. 실패했으면 `journalctl -u baton-demo-reset -n 50`(또는 `-u baton-egress-quota`)로 원인을 보고 `systemctl start baton-demo-reset.service`로 다시 돌린다. 운영 배포는 회사 관측 체계에 붙인다(A-14).
- **접속기록 점검:** [`access-log-inspection.md`](access-log-inspection.md). 배포에서는 `-U baton_inspect`로 연결한다.
- **DB 비밀번호 바꾸기:** `baton_app`·`baton_inspect`는 DB를 처음 만들 때만 생기므로 `deploy.env`의 값만 바꾸면 앱이 로그인하지 못한다. 먼저 DB에서 바꾸고 파일을 맞춘다. 새 값은 `openssl rand -hex 24`로 만들고, `cd /opt/baton && docker compose --env-file /etc/baton/deploy.env -f compose.yml -f compose.deploy.yml exec db psql -U baton -d baton -c '\password baton_app'`의 입력 창에 넣는다(값이 명령줄과 기록에 남지 않는다). 그다음 `deploy.env`의 `BATON_DB_APP_PASSWORD`를 같은 값으로 바꾸고 `infra/host/deploy.sh`. `baton_inspect`도 같다. 소유자의 비밀번호는 `\password baton`으로 바꾸고 `BATON_DB_PASSWORD`를 맞춘다.
- **멈추기:** 면접이 끝나면 인스턴스를 중지한다(디스크와 탄력적 IP 요금만 남는다). 완전히 끝내면 탄력적 IP를 해제하고 인스턴스를 종료하고 ECR 저장소를 지운다.
