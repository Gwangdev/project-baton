# No read-only Gradle cache

`compose.yml` hands this folder to the API image build when `BATON_GRADLE_RO_CACHE` is not set. It holds no cache, so
Gradle downloads the dependencies as usual. Set `BATON_GRADLE_RO_CACHE` to a Gradle cache folder (normally
`$HOME/.gradle/caches`) to let the build read the dependencies a machine already has, without Maven Central
(`backend/Dockerfile`, `tests/deploy/offline-build-check.sh`).
