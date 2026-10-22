package com.projectbaton.config;

import org.flywaydb.core.Flyway;

/**
 * 배포의 마이그레이션 단계로, 앱이 뜨기 전에 일회용 컨테이너에서 돈다(work-orders D7, 결정 11). 앱은 아무것도
 * 소유하지 않는 계정으로 접속해서 감사 트리거(V21)를 끌 수 없다. 앱이 마이그레이션까지 하면 소유자 비밀번호를 들고
 * 있어야 하고, 앱을 장악한 쪽도 그 비밀번호를 쓸 수 있게 된다. 그 비밀번호는 이 진입점만 받는다. Spring Boot가 하던
 * 것과 같은 마이그레이션(classpath:db/migration, 앱 계정에 권한을 주는 afterMigrate.sql 포함)을 앱의 세션 시간대로
 * 돌리고 끝난다. 앱 이미지에서
 * {@code java -Dloader.main=com.projectbaton.config.DatabaseMigration -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher}로
 * 시작한다.
 */
public final class DatabaseMigration {
    private DatabaseMigration() { }

    public static void main(String[] args) {
        migrate(required("BATON_DB_URL"), required("BATON_DB_MIGRATION_USERNAME"), required("BATON_DB_MIGRATION_PASSWORD"));
    }

    public static void migrate(String url, String username, String password) {
        Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .initSql(DatabasePools.CONNECTION_INIT_SQL)
                .load()
                .migrate();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
