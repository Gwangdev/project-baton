# 야간 암호화 백업. pg_dump가 서버의 major 버전과 맞도록 DB 이미지 자체에 공개키 암호화용 age를 더한다. 이미지에는
# 개인키가 들어가지 않고, 실행할 때 수신자 파일(공개키)만 mount한다.
FROM pgvector/pgvector:pg17@sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f
RUN apt-get update \
    && apt-get install -y --no-install-recommends age=1.1.1-1+b3 \
    && rm -rf /var/lib/apt/lists/*
COPY baton-backup /usr/local/bin/baton-backup
COPY rotate.awk /usr/local/lib/baton/rotate.awk
USER 999:999
ENTRYPOINT ["/usr/local/bin/baton-backup"]
CMD ["schedule"]
