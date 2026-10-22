# 공개 데모의 기준 덤프를 호스트로 옮긴다(work-orders D7, 결정 12). CI가 infra/demo/make-baseline.sh로 덤프를 만들어
# 이 이미지를 비공개 registry에 올리고, infra/host/deploy.sh가 `docker run --rm <image> >
# /var/lib/baton/demo-baseline.dump`로 덤프를 꺼낸다. 덤프에 공개 데모 비밀번호의 해시가 들어 있어서 저장소에는 두지
# 않는다.
FROM busybox:1.36.1-musl@sha256:3c6ae8008e2c2eedd141725c30b20d9c36b026eb796688f88205845ef17aa213
COPY demo-baseline.dump /demo-baseline.dump
ENTRYPOINT ["cat", "/demo-baseline.dump"]
