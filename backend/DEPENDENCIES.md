# Build dependencies

W05.G fixes the active build baseline to Java 21, Spring Boot 4.1.1, Gradle
Wrapper 9.7.1, Kotlin DSL, and Spring AI BOM 2.0.1. W13 adds the three Spring AI
provider starters below. None of them builds a model unless a provider is chosen
(`spring.ai.model.chat` and `spring.ai.model.embedding` default to `none`), and
none calls a provider while the application starts, so the stub profile and the
test profile cannot initiate an external call.

## Verified coordinates

| Purpose | Gradle coordinate | Version source |
|---|---|---|
| Spring Boot platform | `platform("org.springframework.boot:spring-boot-dependencies:4.1.1")` | Gradle native BOM |
| MVC | `org.springframework.boot:spring-boot-starter-webmvc` | Spring Boot BOM |
| JPA | `org.springframework.boot:spring-boot-starter-data-jpa` | Spring Boot BOM |
| Validation | `org.springframework.boot:spring-boot-starter-validation` | Spring Boot BOM |
| Security | `org.springframework.boot:spring-boot-starter-security` | Spring Boot BOM |
| Actuator | `org.springframework.boot:spring-boot-starter-actuator` | Spring Boot BOM |
| JDBC session | `org.springframework.boot:spring-boot-starter-session-jdbc` | Spring Boot BOM |
| Flyway | `org.springframework.boot:spring-boot-starter-flyway`, `org.flywaydb:flyway-database-postgresql` | Spring Boot BOM |
| PostgreSQL JDBC | `org.postgresql:postgresql` | Spring Boot BOM |
| Prometheus metrics (W10) | `io.micrometer:micrometer-registry-prometheus` (runtime, resolved 1.17.1) | Spring Boot BOM |
| MockMvc | `org.springframework.boot:spring-boot-starter-webmvc-test` | Spring Boot 4 MVC test starter |
| Testcontainers | `org.testcontainers:testcontainers-junit-jupiter`, `org.testcontainers:testcontainers-postgresql` | Spring Boot BOM (Testcontainers 2.0.5 BOM) |
| Spring AI platform | `platform("org.springframework.ai:spring-ai-bom:2.0.1")` | Gradle native BOM |
| Spring AI model API (W07) | `org.springframework.ai:spring-ai-model` | Spring AI BOM 2.0.1 |
| OpenAI chat and embeddings (W13) | `org.springframework.ai:spring-ai-starter-model-openai` | Spring AI BOM 2.0.1 |
| Google GenAI (Gemini) chat (W13) | `org.springframework.ai:spring-ai-starter-model-google-genai` | Spring AI BOM 2.0.1 |
| Google GenAI text embeddings (W13) | `org.springframework.ai:spring-ai-starter-model-google-genai-embedding` | Spring AI BOM 2.0.1 (a separate artifact: the chat starter has no embedding classes) |
| Ollama (local) chat and embeddings (W13) | `org.springframework.ai:spring-ai-starter-model-ollama` | Spring AI BOM 2.0.1 |
| Jackson 2 line raised for the SDKs (W13) | `platform("com.fasterxml.jackson:jackson-bom:2.21.7")` | Gradle native BOM; the SDKs behind the starters load Jackson 2.21.5, which has seven published advisories fixed in 2.21.7 |

Spring AI 2.0.x supports Spring Boot 4.0.x and 4.1.x according to the official
Spring AI reference. W01 imports its BOM only. W07 adds `spring-ai-model`, which
holds the `ChatModel` and `EmbeddingModel` interfaces the `local` and `provider`
profiles call, and no provider module: this machine had no local model runtime
or provider credentials, and a provider call would be an external transmission
that needs approval (see `docs/backend-design/evidence/model-profile.md`).
Choosing a provider starter was part of pinning a real model profile; W13 did it (2026-10-05, download approved by the user in the W13 card).

W13 scan of the resolved runtime and test classpaths (269 coordinates, 223 runtime and 46 test-only) against OSV on
2026-10-05 found 2 coordinates with advisories (jackson-core and jackson-databind 2.21.5, pulled in by the provider SDKs). The
`jackson-bom` constraint above removed them; the rescan found none.

## Gradle Wrapper provenance

- Distribution: `https://services.gradle.org/distributions/gradle-9.7.1-bin.zip`
- Distribution SHA-256: `acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a`
- Checked-in `gradle-wrapper.jar` SHA-256: `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`

`gradle/wrapper/gradle-wrapper.properties` enforces the distribution checksum.

## Container images (W10)

`compose.yml`, `backend/Dockerfile`, `infra/proxy/Dockerfile` and `tests/load/compose.load.yml` pin every image by
digest. The digests were read from the local image store on 2026-09-28 after the approved pull.

| Purpose | Image | Digest |
|---|---|---|
| API build stage (runs the Gradle Wrapper) | `eclipse-temurin:21-jdk-noble` | `sha256:d0aa6704a67ac080591815bb7734dbe56171dd7a8e9b767e9989b95dd37b3085` |
| API runtime | `eclipse-temurin:21-jre-noble` | `sha256:7edbe8532195c8222735caca437e56780e1fc7db08a4b4b4af77fe188cccc15f` |
| Proxy build stage (Vue build) | `node:24-alpine` | `sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1` |
| Proxy runtime | `caddy:2-alpine` (Caddy v2.11.4) | `sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648` |
| Database | `pgvector/pgvector:pg17` | `sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f` |
| Load generator | `grafana/k6:2.3.0` (k6 v2.3.0) | `sha256:9c2dee7f8ed74d317e4027c06a10f169b625638189de8d4555d0b3486a5aeb34` |

### Observability overlay (D3)

`compose.observe.yml` pins these by multi-platform index digest, read with `docker buildx imagetools inspect` on
2026-10-13 after the approved pull. Each was the newest stable release published at least two weeks earlier. cAdvisor
(`gcr.io/cadvisor/cadvisor:v0.55.1`) was pulled as approved and left out: on Docker Desktop it does not find the Docker
daemon through read-only host mounts, and the Docker socket would hand it the whole Docker API.

| Purpose | Image | Digest |
|---|---|---|
| Metrics store and alert rules | `prom/prometheus:v3.15.0` | `sha256:efd719c99d83b060d9daefdcf00360461adf279f45ef5391f8d111892118753e` |
| Dashboards | `grafana/grafana:13.2.3` | `sha256:b28bae15e219c998fb0e0424ed724930cc61b1f61fb404d47c862f9a23f9e572` |
| Database statistics | `quay.io/prometheuscommunity/postgres-exporter:v0.20.1` | `sha256:ac5ec343104fae0e2d84a27bb8d69b38430a11910c5382cad85d478d2bab713e` |
