package com.projectbaton.contract;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 통합 시험이 만든 응답을 ProjectBaton-API.yml과 비교한다. 각 응답은 선언된 operation과 상태에 속해야 하고, JSON
 * 본문은 선언된 스키마에 맞아야 한다(필수 필드가 있고, 스키마가 선언하지 않은 필드가 없고, null은 스키마가 허용한
 * 곳에만 있고, 선언된 JSON 타입이다). 계약 검사기는 이름만 비교해서, OAS가 선언하지 않은 응답, 파라미터, 필드를
 * 검토가 읽기 전까지 알아채지 못했다.
 *
 * <p>실행 전체도 의미가 있어야 한다. 아무것도 관찰하지 못했을 때, OAS의 operation이 한 번도 불리지 않았을 때(라우팅된
 * 응답은 filter chain의 401까지 모두 호출로 세서 세션 없이만 시험한 operation도 세어진다. 지금은 모든 operation에 2xx
 * 응답이 있다), 500이 나왔을 때 실패한다. 모든 operation이 500을 선언해서 상태 확인만으로는 결함과 선언된 결과를 가를
 * 수 없다. 시험이 일부러 내는 500(일반 Problem을 보려고 넣은 DB 장애)은 {@code contract/intended-server-errors.txt}에
 * 이유와 함께 적는다. 더는 어떤 시험도 내지 않는 항목도 실패라서, 목록이 자신이 변명하는 시험보다 오래 남지 않는다.
 * 항목은 시험 하나가 아니라 operation을 변명해서, 같은 operation의 다른 500도 통과한다.
 *
 * <p>OpenAPI 검증 라이브러리가 아니라 직접 만든 검사기다. 라이브러리는 내려받아 검토해야 하는 새 의존성이고(이
 * 프로젝트는 프레임워크가 주는 것을 쓰고 직접 만들면 이유를 남긴다), 이 계약이 쓰는 규칙은 몇 개뿐이다. OpenAPI 3.0의
 * {@code nullable}과 {@code allOf}는 {@link #merged}와 {@link #resolve}가 처리하고, 검사기 자체의 시험이 그 동작을
 * 고정한다.
 *
 * <p>확인하지 않는 것: 요청 파라미터와 헤더(계약 검사기가 이름으로 비교하고 여기서는 검증하지 않는다),
 * {@code maxLength}, {@code format} 같은 값 제약, MockMvc 밖의 응답(Docker 스택의 프록시와 실제 네트워크),
 * server-sent event의 본문. OAS에 없는 경로나 경로가 받지 않는 메서드의 요청은 프레임워크 거절(404/405)이고, 둘
 * 가운데 하나이기만 하면 된다.
 */
public final class ResponseContractCheck {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PREFIX = "/api/v1";
    private static final Set<Integer> UNROUTED = Set.of(404, 405);

    private static final String INTENDED_ERRORS = "contract/intended-server-errors.txt";
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete", "head", "options");

    private final Map<String, Object> spec;
    private final List<Route> routes = new ArrayList<>();
    private final Map<String, String> intendedServerErrors;
    private final Set<String> observedOperations = new TreeSet<>();
    private final Set<String> intendedErrorsSeen = new TreeSet<>();

    public ResponseContractCheck(Map<String, Object> spec) {
        this(spec, Map.of());
    }

    /**
     * {@code intendedServerErrors}는 "METHOD /path/{template}"을 시험이 그 operation에서 500을 내는 이유에
     * 대응시킨다.
     */
    @SuppressWarnings("unchecked")
    public ResponseContractCheck(Map<String, Object> spec, Map<String, String> intendedServerErrors) {
        this.spec = spec;
        this.intendedServerErrors = intendedServerErrors;
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        paths.forEach((template, operations) -> routes.add(new Route(template,
                Pattern.compile("^" + PREFIX + template.replaceAll("\\{[^/]+}", "[^/]+") + "$"), (Map<String, Object>) operations)));
    }

    public static void main(String[] arguments) throws IOException {
        Path specification = Path.of(arguments[0]);
        Path observations = Path.of(arguments[1]);
        ResponseContractCheck check = new ResponseContractCheck(load(specification), loadIntendedServerErrors());
        Set<String> violations = new TreeSet<>();
        int count = 0;
        if (Files.isDirectory(observations)) {
            try (Stream<Path> files = Files.list(observations)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".jsonl")).toList()) {
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        JsonNode observation = JSON.readTree(line);
                        violations.addAll(check.check(observation.get("method").asText(), observation.get("path").asText(),
                                observation.get("status").asInt(), observation.path("contentType").asText(null),
                                observation.path("body").asText("")));
                        count++;
                    }
                }
            }
        }
        violations.addAll(check.finish(count));
        System.out.println("response contract: " + count + " responses checked over " + check.observedOperations.size() + " of "
                + check.operations().size() + " operations, " + violations.size() + " violations");
        violations.forEach(violation -> System.out.println("  " + violation));
        if (!violations.isEmpty()) {
            System.exit(1);
        }
    }

    private static Map<String, String> loadIntendedServerErrors() throws IOException {
        try (java.io.InputStream stream = ResponseContractCheck.class.getClassLoader().getResourceAsStream(INTENDED_ERRORS)) {
            if (stream == null) {
                throw new IllegalStateException(INTENDED_ERRORS + " is missing from the test resources");
            }
            return intendedServerErrors(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }

    /**
     * 한 줄에 항목 하나, {@code METHOD /path/{template} | 이유}. 빈 줄과 {@code #}으로 시작하는 줄은 주석이다.
     * 변명하는 500은 이유를 말해야 하므로 이유 없는 항목은 거절한다.
     */
    public static Map<String, String> intendedServerErrors(Reader source) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String line : new java.io.BufferedReader(source).lines().toList()) {
            String entry = line.trim();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            int bar = entry.indexOf('|');
            String reason = bar < 0 ? "" : entry.substring(bar + 1).trim();
            if (reason.isEmpty()) {
                throw new IllegalArgumentException("intended server error without a reason: " + entry);
            }
            entries.put(entry.substring(0, bar).trim(), reason);
        }
        return entries;
    }

    public static Map<String, Object> load(Path specification) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(20 * 1024 * 1024);
        try (Reader reader = Files.newBufferedReader(specification, StandardCharsets.UTF_8)) {
            return new Yaml(options).load(reader);
        }
    }

    /** 응답 하나의 위반. 중복이 합쳐지도록 메서드, 경로 template, 상태로 문장을 만든다. */
    @SuppressWarnings("unchecked")
    public List<String> check(String method, String path, int status, String contentType, String body) {
        if (!path.startsWith(PREFIX + "/")) {
            return List.of();
        }
        Route route = routes.stream().filter(candidate -> candidate.pattern().matcher(path).matches()).findFirst().orElse(null);
        Map<String, Object> operation = route == null ? null : (Map<String, Object>) route.operations().get(method.toLowerCase());
        if (operation == null) {
            return UNROUTED.contains(status) ? List.of()
                    : List.of(method + " " + path + " " + status + ": no such operation in the OAS");
        }
        String operationKey = method.toUpperCase() + " " + route.template();
        observedOperations.add(operationKey);
        if (status == 500) {
            if (!intendedServerErrors.containsKey(operationKey)) {
                return List.of(operationKey + " 500: unexpected server error");
            }
            intendedErrorsSeen.add(operationKey);
        }
        String where = method + " " + route.template() + " " + status;
        Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
        Object declared = responses.containsKey(String.valueOf(status)) ? responses.get(String.valueOf(status)) : responses.get("default");
        if (declared == null) {
            return List.of(where + ": status not declared");
        }
        Map<String, Object> response = resolve((Map<String, Object>) declared);
        Map<String, Object> content = (Map<String, Object>) response.get("content");
        if (content == null || body == null || body.isBlank() || contentType == null || contentType.startsWith("text/event-stream")) {
            return List.of();
        }
        String mediaType = contentType.split(";")[0].trim();
        Map<String, Object> media = (Map<String, Object>) content.get(mediaType);
        if (media == null) {
            return List.of(where + ": content type " + mediaType + " not declared");
        }
        Map<String, Object> schema = (Map<String, Object>) media.get("schema");
        if (schema == null) {
            return List.of();
        }
        List<String> violations = new ArrayList<>();
        validate(JSON.readTree(body), schema, "$", where, violations);
        return violations;
    }

    /**
     * 모든 응답을 확인한 뒤의 실행 전체 위반: 관찰한 것이 없음, 어떤 시험도 부르지 않은 OAS operation, 어떤 시험도
     * 내지 않은 의도된 서버 오류.
     */
    public List<String> finish(int observed) {
        if (observed == 0) {
            return List.of("no responses were observed; the integration tests did not record anything");
        }
        List<String> violations = new ArrayList<>();
        for (String operation : operations()) {
            if (!observedOperations.contains(operation)) {
                violations.add(operation + ": no integration test called this operation");
            }
        }
        for (String intended : intendedServerErrors.keySet()) {
            if (!intendedErrorsSeen.contains(intended)) {
                violations.add(intended + ": listed as an intended server error but no test produced one");
            }
        }
        return violations;
    }

    /** OAS가 선언한 모든 "METHOD /template"을 고정된 순서로. */
    @SuppressWarnings("unchecked")
    private Set<String> operations() {
        Set<String> operations = new TreeSet<>();
        for (Route route : routes) {
            route.operations().keySet().stream().filter(METHODS::contains)
                    .forEach(method -> operations.add(method.toUpperCase() + " " + route.template()));
        }
        return operations;
    }

    @SuppressWarnings("unchecked")
    private void validate(JsonNode value, Map<String, Object> rawSchema, String at, String where, List<String> violations) {
        Map<String, Object> schema = merged(rawSchema);
        if (value == null || value.isNull()) {
            if (!Boolean.TRUE.equals(schema.get("nullable"))) {
                violations.add(where + ": " + at + " is null but not nullable");
            }
            return;
        }
        String type = (String) schema.get("type");
        if (type == null && schema.containsKey("properties")) {
            type = "object";
        }
        if (type == null) {
            return;
        }
        switch (type) {
            case "object" -> {
                if (!value.isObject()) {
                    violations.add(where + ": " + at + " should be an object");
                    return;
                }
                Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
                for (String required : (List<String>) schema.getOrDefault("required", List.of())) {
                    if (!value.has(required)) {
                        violations.add(where + ": " + at + "." + required + " is required but missing");
                    }
                }
                Object additional = schema.get("additionalProperties");
                for (Map.Entry<String, JsonNode> field : value.properties()) {
                    Object property = properties.get(field.getKey());
                    if (property == null) {
                        if (additional instanceof Map<?, ?> additionalSchema) {
                            validate(field.getValue(), (Map<String, Object>) additionalSchema, at + "." + field.getKey(), where, violations);
                        } else if (!Boolean.TRUE.equals(additional) && !properties.isEmpty()) {
                            violations.add(where + ": " + at + "." + field.getKey() + " is not declared");
                        }
                        continue;
                    }
                    validate(field.getValue(), (Map<String, Object>) property, at + "." + field.getKey(), where, violations);
                }
            }
            case "array" -> {
                if (!value.isArray()) {
                    violations.add(where + ": " + at + " should be an array");
                    return;
                }
                Map<String, Object> items = (Map<String, Object>) schema.get("items");
                if (items != null) {
                    for (int index = 0; index < value.size(); index++) {
                        validate(value.get(index), items, at + "[]", where, violations);
                    }
                }
            }
            case "string" -> {
                if (!value.isTextual()) {
                    violations.add(where + ": " + at + " should be a string");
                } else if (schema.get("enum") instanceof List<?> allowed && !allowed.contains(value.asText())) {
                    violations.add(where + ": " + at + " value " + value.asText() + " is not in the enum");
                }
            }
            case "integer" -> {
                if (!value.isIntegralNumber()) {
                    violations.add(where + ": " + at + " should be an integer");
                }
            }
            case "number" -> {
                if (!value.isNumber()) {
                    violations.add(where + ": " + at + " should be a number");
                }
            }
            case "boolean" -> {
                if (!value.isBoolean()) {
                    violations.add(where + ": " + at + " should be a boolean");
                }
            }
            default -> { }
        }
    }

    /** $ref를 풀고 allOf 부분을 스키마 하나로 합친다(properties와 required 목록을 합친다). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> merged(Map<String, Object> schema) {
        Map<String, Object> resolved = resolve(schema);
        if (!resolved.containsKey("allOf")) {
            return resolved;
        }
        Map<String, Object> result = new LinkedHashMap<>(resolved);
        result.remove("allOf");
        Map<String, Object> properties = new LinkedHashMap<>((Map<String, Object>) resolved.getOrDefault("properties", Map.of()));
        List<String> required = new ArrayList<>((List<String>) resolved.getOrDefault("required", List.of()));
        for (Map<String, Object> part : (List<Map<String, Object>>) resolved.get("allOf")) {
            Map<String, Object> partSchema = merged(part);
            properties.putAll((Map<String, Object>) partSchema.getOrDefault("properties", Map.of()));
            required.addAll((List<String>) partSchema.getOrDefault("required", List.of()));
            partSchema.forEach((key, value) -> {
                if (!key.equals("properties") && !key.equals("required")) {
                    result.putIfAbsent(key, value);
                }
            });
        }
        result.put("properties", properties);
        result.put("required", required);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolve(Map<String, Object> node) {
        Map<String, Object> current = node;
        while (current.containsKey("$ref")) {
            String reference = (String) current.get("$ref");
            Map<String, Object> target = spec;
            for (String part : reference.substring(2).split("/")) {
                target = (Map<String, Object>) target.get(part);
            }
            if (current.size() > 1) {
                Map<String, Object> combined = new LinkedHashMap<>(target);
                current.forEach((key, value) -> {
                    if (!key.equals("$ref")) {
                        combined.put(key, value);
                    }
                });
                current = combined;
            } else {
                current = target;
            }
        }
        return current;
    }

    private record Route(String template, Pattern pattern, Map<String, Object> operations) { }
}
