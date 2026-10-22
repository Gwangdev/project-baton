# 일회용 Compose 스택을 띄우는 확인(tests/deploy/compose-start-check.sh, tests/observe/observe-check.sh)이 함께 쓴다.
# 실행하지 않고 source한다.

# 스택이 확인이 정한 것만 보도록, 호출한 셸의 Baton·공급자·Compose 변수를 모두 지운다. 확인 자체의 설정(포트)을 읽은
# 뒤에 부른다. 확인은 자기 프로세스에서 돌아서 호출한 셸에는 변수가 그대로 남는다. BATON_GRADLE_RO_CACHE는 남긴다.
# 스택이 아니라 이미지 빌드에 쓰이고, 없으면 Maven Central이 거절하는 기계는 확인이 띄울 이미지를 빌드할 수 없다.
scrub_stack_env() {
  local name
  for name in $(compgen -e | grep -E '^(BATON_|OPENAI_|GOOGLE_|SPRING_|COMPOSE_)' | grep -v '^BATON_GRADLE_RO_CACHE$'); do unset "$name"; done
}

# 프록시용 하루짜리 자체 서명 인증서(cert.pem, key.pem)를 root가 아닌 사용자가 읽을 수 있게 $1에 쓴다.
throwaway_cert() {
  openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=localhost \
    -keyout "$1/key.pem" -out "$1/cert.pem" >/dev/null 2>&1
  chmod 755 "$1"
  chmod 644 "$1/key.pem" "$1/cert.pem"
}
