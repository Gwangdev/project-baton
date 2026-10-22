package com.projectbaton;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ProjectBaton-DB.dbml은 계약이 그린 스키마인데, Flyway 마이그레이션이 만드는 것과 비교하는 곳이 없어서 V3–V10에서
 * 더한 열과 인덱스가 반영되지 않았다. 이 시험은 DBML과 마이그레이션한 DB를 읽어서, 테이블마다 같은 열, 같은 NOT NULL
 * 열, 같은 char·varchar 길이, 모든 인덱스의 같은 키 열 목록과 고유 여부(primary key와 unique 제약 포함)를 요구한다.
 *
 * <p>char·varchar 길이 밖의 열 타입, 기본값, 외래키, CHECK 제약이나 enum 값, 부분 인덱스 조건, INCLUDE 열, note는
 * 비교하지 않고 읽어서 확인한다. 식 인덱스는 양쪽에서 뺀다. 차이가 나면 마이그레이션 쪽을 기준으로 정해서, DBML을
 * DB가 가진 대로 고친다.
 */
@SpringBootTest
@Testcontainers
class DbmlConsistencyIT {
    private static final Path DBML = Path.of("..", "ProjectBaton-DB.dbml");
    private static final String UNIQUE = " unique";
    private static final Set<String> FRAMEWORK_TABLES = Set.of("flyway_schema_history");
    private static final Pattern TABLE = Pattern.compile("^Table (\\w+) \\{");
    private static final Pattern COLUMN = Pattern.compile("^  (\\w+)\\s+[\\w()\\[\\],]+.*?(\\[(.*)])?\\s*$");
    private static final Pattern LENGTH = Pattern.compile("^  \\w+\\s+(?:varchar|char)\\((\\d+)\\)");
    private static final Pattern ONE_LINE_INDEXES = Pattern.compile("^\\s*Indexes \\{(.*)}\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INDEX = Pattern.compile("^\\s{4}(\\([^)]*\\)|\\w+)\\s*(\\[(.*)])?\\s*$");

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void theDbmlShowsTheTablesColumnsAndIndexesTheMigrationsBuild() throws IOException {
        Schema documented = readDbml();
        Schema migrated = readDatabase();

        assertEquals("", difference("column", migrated.columns(), documented.columns())
                + difference("NOT NULL column", migrated.notNull(), documented.notNull())
                + difference("character length", migrated.lengths(), documented.lengths())
                + difference("index", migrated.indexes(), documented.indexes()));
    }

    /** 다른 테이블마다 한 줄. 마이그레이션은 만드는데 DBML에 없는 것과, DBML에만 있는 것. */
    private String difference(String kind, Map<String, Set<String>> migrated, Map<String, Set<String>> documented) {
        StringBuilder report = new StringBuilder();
        Set<String> tables = new TreeSet<>(migrated.keySet());
        tables.addAll(documented.keySet());
        for (String table : tables) {
            Set<String> missing = new TreeSet<>(migrated.getOrDefault(table, Set.of()));
            missing.removeAll(documented.getOrDefault(table, Set.of()));
            Set<String> extra = new TreeSet<>(documented.getOrDefault(table, Set.of()));
            extra.removeAll(migrated.getOrDefault(table, Set.of()));
            if (!migrated.containsKey(table) || !documented.containsKey(table) || !missing.isEmpty() || !extra.isEmpty()) {
                report.append(kind).append(' ').append(table)
                        .append(migrated.containsKey(table) ? "" : " (table only in DBML)")
                        .append(documented.containsKey(table) ? "" : " (table missing from DBML)")
                        .append(": missing from DBML ").append(missing).append(", only in DBML ").append(extra).append('\n');
            }
        }
        return report.toString();
    }

    private Schema readDbml() throws IOException {
        Map<String, Set<String>> columns = new TreeMap<>();
        Map<String, Set<String>> indexes = new TreeMap<>();
        Map<String, Set<String>> notNull = new TreeMap<>();
        Map<String, Set<String>> lengths = new TreeMap<>();
        String table = null;
        boolean inIndexes = false;
        List<String> primaryKey = new ArrayList<>();
        for (String line : Files.readAllLines(DBML)) {
            Matcher start = TABLE.matcher(line);
            if (start.find()) {
                table = start.group(1);
                columns.put(table, new TreeSet<>());
                indexes.put(table, new TreeSet<>());
                notNull.put(table, new TreeSet<>());
                lengths.put(table, new TreeSet<>());
                primaryKey.clear();
                inIndexes = false;
                continue;
            }
            if (table == null) {
                continue;
            }
            if (line.equals("}")) {
                if (!primaryKey.isEmpty()) {
                    indexes.get(table).add(String.join(",", primaryKey) + UNIQUE);
                }
                table = null;
                continue;
            }
            if (line.trim().equalsIgnoreCase("indexes {")) {
                inIndexes = true;
                continue;
            }
            Matcher oneLine = ONE_LINE_INDEXES.matcher(line);
            if (oneLine.find()) {
                Matcher index = INDEX.matcher("    " + oneLine.group(1).trim());
                if (index.find()) {
                    String settings = DbmlSettings.keywords(index.group(3));
                    indexes.get(table).add(index.group(1).replace("(", "").replace(")", "").replace(" ", "") + uniqueness(settings));
                    if (settings.contains("pk")) {
                        primaryKey.clear();
                    }
                }
                continue;
            }
            if (inIndexes) {
                if (line.trim().equals("}")) {
                    inIndexes = false;
                    continue;
                }
                Matcher index = INDEX.matcher(line);
                if (index.find() && !line.contains("`")) {
                    String key = index.group(1).replace("(", "").replace(")", "").replace(" ", "");
                    String settings = DbmlSettings.keywords(index.group(3));
                    if (settings.contains("pk")) {
                        primaryKey.clear();
                    }
                    indexes.get(table).add(key + uniqueness(settings));
                }
                continue;
            }
            if (line.startsWith("  ") && !line.startsWith("   ") && !line.trim().startsWith("Note") && !line.trim().startsWith("checks")) {
                Matcher column = COLUMN.matcher(line);
                if (column.find()) {
                    columns.get(table).add(column.group(1));
                    String settings = DbmlSettings.keywords(column.group(3));
                    if (settings.matches(".*\\bpk\\b.*")) {
                        primaryKey.add(column.group(1));
                    }
                    if (settings.matches(".*\\bunique\\b.*")) {
                        indexes.get(table).add(column.group(1) + UNIQUE);
                    }
                    // PostgreSQL에서 primary key 열은 DBML이 pk라고만 써도 NOT NULL이다.
                    if (settings.matches(".*\\b(not null|pk)\\b.*")) {
                        notNull.get(table).add(column.group(1));
                    }
                    Matcher length = LENGTH.matcher(line);
                    if (length.find()) {
                        lengths.get(table).add(column.group(1) + "(" + length.group(1) + ")");
                    }
                }
            }
        }
        return new Schema(columns, indexes, notNull, lengths);
    }

    private static String uniqueness(String settings) {
        return DbmlSettings.unique(settings) ? UNIQUE : "";
    }

    private Schema readDatabase() {
        Map<String, Set<String>> columns = new TreeMap<>();
        Map<String, Set<String>> notNull = new TreeMap<>();
        Map<String, Set<String>> lengths = new TreeMap<>();
        jdbc.query("""
                select table_name, column_name, is_nullable, character_maximum_length from information_schema.columns
                where table_schema = 'public' order by table_name, ordinal_position
                """, rs -> {
            if (!FRAMEWORK_TABLES.contains(rs.getString(1))) {
                columns.computeIfAbsent(rs.getString(1), name -> new TreeSet<>()).add(rs.getString(2));
                notNull.computeIfAbsent(rs.getString(1), name -> new TreeSet<>());
                lengths.computeIfAbsent(rs.getString(1), name -> new TreeSet<>());
                if ("NO".equals(rs.getString(3))) {
                    notNull.get(rs.getString(1)).add(rs.getString(2));
                }
                if (rs.getObject(4) != null) {
                    // character_maximum_length는 char(n)과 varchar(n) 열에만 있다.
                    lengths.get(rs.getString(1)).add(rs.getString(2) + "(" + rs.getInt(4) + ")");
                }
            }
        });
        Map<String, Set<String>> indexes = new TreeMap<>();
        columns.keySet().forEach(table -> indexes.put(table, new TreeSet<>()));
        jdbc.query("""
                select t.relname, string_agg(a.attname, ',' order by k.ordinality), i.indisunique
                from pg_index i
                join pg_class t on t.oid = i.indrelid
                join pg_namespace n on n.oid = t.relnamespace and n.nspname = 'public'
                cross join lateral unnest(i.indkey[0:i.indnkeyatts - 1]) with ordinality k(attnum, ordinality)
                join pg_attribute a on a.attrelid = t.oid and a.attnum = k.attnum
                where not exists (select 1 from unnest(i.indkey[0:i.indnkeyatts - 1]) key(attnum) where key.attnum = 0)
                group by i.indexrelid, t.relname, i.indisunique
                """, rs -> {
            if (indexes.containsKey(rs.getString(1))) {
                indexes.get(rs.getString(1)).add(rs.getString(2) + (rs.getBoolean(3) ? UNIQUE : ""));
            }
        });
        return new Schema(columns, indexes, notNull, lengths);
    }

    private record Schema(Map<String, Set<String>> columns, Map<String, Set<String>> indexes, Map<String, Set<String>> notNull,
                          Map<String, Set<String>> lengths) { }
}
