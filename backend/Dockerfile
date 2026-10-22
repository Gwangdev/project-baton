# API 이미지. 빌드 단계는 저장소에 들어 있는 Gradle Wrapper를 돌리고, wrapper가 배포본을
# gradle/wrapper/gradle-wrapper.properties의 SHA-256으로 검증한다. 실행 단계에는 build/libs의 boot jar만 두고 권한
# 없는 사용자로 돌린다. 읽기 전용 루트 파일 시스템과 /tmp tmpfs는 compose.yml이 더한다.
# 빌드에 줄 수 있는 읽기 전용 Gradle 의존성 캐시(compose.yml이 BATON_GRADLE_RO_CACHE로 넘기고, 보통 그 기계의
# ~/.gradle/caches). Gradle이 Maven Central보다 먼저 읽어서(GRADLE_RO_DEP_CACHE), 이 프로젝트를 한 번 빌드한 기계는
# Maven Central이 요청을 거절해도(반복 빌드 뒤 하루 넘게 429로 답했다) 네트워크 없이 이미지를 빌드한다. 캐시를 주지
# 않으면 이 빈 단계가 대신하고 Gradle은 전처럼 내려받는다.
FROM scratch AS gradle-ro-cache

FROM eclipse-temurin:21-jdk-noble@sha256:d0aa6704a67ac080591815bb7734dbe56171dd7a8e9b767e9989b95dd37b3085 AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# Gradle 캐시는 layer보다 오래 남는 BuildKit cache mount에 둔다. 예전 의존성 layer는 `dependencies` 보고서를 돌렸는데,
# 이 보고서는 runtime 그래프만 풀고 jar도 compile 전용 메타데이터도 내려받지 않아서 소스를 바꿀 때마다 Maven
# Central에서 artifact 약 270개를 다시 받았다. 그래서 한 아침에 반복한 빌드가 429 Too Many Requests로 거절됐다.
# mount가 있으면 소스를 바꿔도 이전 빌드가 이미 받은 것은 내려받지 않는다. cache id의 기본값은 mount 대상, 즉
# BuildKit이 이름 없는 cache mount에 주는 id라서 기존 캐시가 그대로 이어지고, 오프라인 빌드 확인은 빈 캐시에서
# 시작하도록 자기 id를 넘긴다.
ARG GRADLE_CACHE_ID=/root/.gradle
COPY src/main src/main
RUN --mount=type=cache,id=${GRADLE_CACHE_ID},target=/root/.gradle \
    --mount=type=bind,from=gradle-ro-cache,target=/gradle-ro-cache \
    GRADLE_RO_DEP_CACHE=/gradle-ro-cache ./gradlew --no-daemon --quiet bootJar

FROM eclipse-temurin:21-jre-noble@sha256:7edbe8532195c8222735caca437e56780e1fc7db08a4b4b4af77fe188cccc15f
RUN groupadd --system --gid 10001 baton \
    && useradd --system --uid 10001 --gid baton --no-create-home --shell /usr/sbin/nologin baton
WORKDIR /app
COPY --from=build /workspace/build/libs/project-baton-backend-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
# 8080은 프록시에 API를 내주고, 8081은 probe와 metric용 관리 포트라 바깥에 공개하지 않는다.
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
