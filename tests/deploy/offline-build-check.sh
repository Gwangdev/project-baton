#!/usr/bin/env bash
# Maven Central 없는 빌드 확인: Maven Central의 두 주소를 막고 빌드 자체의 Gradle 캐시도 빈 상태로 API 이미지를
# 빌드해서, Maven Central에서 오던 의존성이 빌드에 주는 읽기 전용 캐시(BATON_GRADLE_RO_CACHE, 기본 ~/.gradle/caches)에서
# 오는지 본다. 그래도 Maven Central을 찾는 빌드는 Maven Central이 이 기계를 거절하는 날(반복 이미지 빌드 뒤 하루 넘게
# 모든 요청에 429로 답했다)이 아니라 여기서 실패한다. plugins.gradle.org와 services.gradle.org는 막지 않아서, 플러그인
# 포털에서만 받는 의존성이 생기면 이 확인은 잡지 못한다.
#
#  tests/deploy/offline-build-check.sh
#
# 이미지는 남기지 않는다(캐시만 출력). 확인은 자기 cache id를 써서 평소 빌드가 쓰는 캐시를 읽지도 건드리지도 않는다.
# 그 일회용 캐시에는 Maven Central이 아니라 services.gradle.org에서 오는 Gradle 배포본만 남고, 실행마다 하나씩 쌓이므로
# 가끔 `docker buildx prune`으로 지운다.
set -euo pipefail
cd "$(dirname "$0")/../.."
ro_cache="${BATON_GRADLE_RO_CACHE:-$HOME/.gradle/caches}"
[ -d "$ro_cache/modules-2" ] || { echo "offline build check: $ro_cache has no modules-2; run a Gradle build on this machine first" >&2; exit 2; }

if docker buildx build --progress=plain --output type=cacheonly \
     --build-arg "GRADLE_CACHE_ID=offline-build-check-$$" \
     --build-context "gradle-ro-cache=$ro_cache" \
     --add-host repo.maven.apache.org:127.0.0.1 --add-host repo1.maven.org:127.0.0.1 \
     backend > "${TMPDIR:-/tmp}/offline-build-check-$$.log" 2>&1; then
  echo "offline build check: the API image builds from the read-only cache alone"
  rm -f "${TMPDIR:-/tmp}/offline-build-check-$$.log"
else
  echo "offline build check: the build needed Maven Central" >&2
  grep -E "Could not (resolve|GET)|What went wrong|ERROR" "${TMPDIR:-/tmp}/offline-build-check-$$.log" | head -5 >&2
  rm -f "${TMPDIR:-/tmp}/offline-build-check-$$.log"
  exit 1
fi
