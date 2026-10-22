plugins {
    java
    id("org.springframework.boot") version "4.1.1"
}

group = "com.projectbaton"
version = "0.0.1-SNAPSHOT"
description = "Project Baton backend"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.1"))
    // Spring Boot 4.1.1이 관리하는 tomcat-embed 11.0.24와 Jackson 3.1.5에 공개된 보안 권고가 있다(OSV, 2026-09-30
    // 스캔: tomcat GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6, GHSA-h3x4-894j-xpx5; jackson-databind
    // GHSA-q4xh-88c3-wmh7, GHSA-gx83-3vf8-gh7j, GHSA-wjgm-6hv5-3cvf). Gradle은 요청된 가장 높은 버전을 고르므로,
    // 여기서 둘 다 같은 minor 안에서 올린다. Boot BOM이 고친 버전을 관리하면 뺀다.
    implementation(platform("tools.jackson:jackson-bom:3.1.7"))
    // Spring AI starter 뒤의 OpenAI와 Google GenAI SDK가 Jackson 2(2.21.5)를 가져오는데, 그 jackson-core와
    // jackson-databind에 공개된 보안 권고가 있고(GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89, GHSA-cxp5-3px4-pw24,
    // GHSA-gx83-3vf8-gh7j, GHSA-q4xh-88c3-wmh7, GHSA-wjgm-6hv5-3cvf, GHSA-wv8q-qhhj-9h54) 모두 2.21.7에서 고쳐졌다.
    // 앱 자체는 Jackson 3을 쓰므로 이 설정은 SDK가 불러오는 것만 올린다. SDK가 2.21.7에 의존하게 되면 뺀다.
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.21.7"))
    constraints {
        implementation("org.apache.tomcat.embed:tomcat-embed-core:11.0.26")
        implementation("org.apache.tomcat.embed:tomcat-embed-el:11.0.26")
        implementation("org.apache.tomcat.embed:tomcat-embed-websocket:11.0.26")
    }

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.ai:spring-ai-model")
    // 배포가 속성으로 바꿔 쓸 수 있는 모델 공급자 셋. 각 starter의 자동 구성은 spring.ai.model.chat과
    // spring.ai.model.embedding(application.yml)이 고르고, 둘 다 기본값은 none이다.
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework.ai:spring-ai-starter-model-google-genai")
    implementation("org.springframework.ai:spring-ai-starter-model-google-genai-embedding")
    implementation("org.springframework.ai:spring-ai-starter-model-ollama")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 실제 백엔드 브라우저 시험용 합성 fixture를 넣는다. e2e source set은 bootJar에 들어가지 않고 bootRunE2e만
// classpath에 넣으며, seeder는 e2e 프로필도 요구한다.
val e2e = sourceSets.create("e2e") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[e2e.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[e2e.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

tasks.register<JavaExec>("bootRunE2e") {
    description = "Runs the application with the synthetic E2E fixture (requires BATON_SEED_PASSWORD and BATON_DB_*)."
    group = "application"
    classpath = e2e.runtimeClasspath
    mainClass = "com.projectbaton.BatonApplication"
    systemProperty("spring.profiles.active", "e2e")
}

tasks.test {
    useJUnitPlatform()
    include("**/*Test.class")
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include("**/*IT.class")
    systemProperty("spring.profiles.active", "test")
    // 모든 MockMvc 응답을 여기 기록하고, 아래 responseContract가 OAS와 비교한다.
    val observations = layout.buildDirectory.dir("response-observations")
    systemProperty("baton.response-observations", observations.get().asFile.absolutePath)
    doFirst { delete(observations) }
    maxParallelForks = 4
    shouldRunAfter(tasks.test)
}

// 합성 집합으로 실제 모델을 평가한다. 공급자를 부르므로 check에 넣지 않고 요청할 때만 돈다: BATON_AI_PROVIDER=ollama
// BATON_AI_CHAT_MODEL_ID=... BATON_AI_EMBEDDING_MODEL_ID=... ./gradlew aiEvaluation
tasks.register<Test>("aiEvaluation") {
    description = "Runs the synthetic evaluation against the models chosen by BATON_AI_* (keys or a local Ollama required)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("ai-evaluation") }
    include("**/AiEvaluationRun.class")
    systemProperty("spring.profiles.active", "test")
    listOf("baton.eval.chat-tokens", "baton.eval.embedding-tokens", "baton.eval.documents", "baton.eval.questions").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
}

// 독립 검토가 OAS에 선언되지 않은 응답, 파라미터, 필드를 찾았는데, 이름만 비교하는 계약 검사는 그것을 볼 수 없었다.
val responseContract = tasks.register<JavaExec>("responseContract") {
    description = "Checks every response recorded by the integration tests against ProjectBaton-API.yml."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(integrationTest)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.projectbaton.contract.ResponseContractCheck"
    args(rootDir.resolve("../ProjectBaton-API.yml").absolutePath,
        layout.buildDirectory.dir("response-observations").get().asFile.absolutePath)
}

tasks.check {
    dependsOn(integrationTest)
    dependsOn(responseContract)
    // 스키마에 묶인 fixture 코드가 모르는 사이에 낡지 않도록 빌드할 때마다 seeder를 컴파일한다.
    dependsOn(e2e.classesTaskName)
}
