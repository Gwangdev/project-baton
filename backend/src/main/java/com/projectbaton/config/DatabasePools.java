package com.projectbaton.config;

import java.time.Duration;
import java.util.Map;
import javax.sql.DataSource;

import com.projectbaton.jobs.DatabaseLane;
import com.projectbaton.shared.BusinessCalendar;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * JPA, JdbcTemplate, Flyway, Spring Session, 트랜잭션 관리자가 이미 쓰는 DataSource 하나 뒤에 연결 pool 셋을
 * 둔다(README §6: API 20, worker 8, control 4). 호출한 스레드의 {@link DatabaseLane}이 pool을 고른다. 요청 처리는 API
 * pool, 작업 실행과 근거 검색은 worker pool, 작업을 살려 두는 예약 작업(heartbeat, sweep, Q&A 복구, 보존, 수집
 * 일정)은 control pool을 쓴다. 스레드로 나누면 트랜잭션 관리자가 하나로 남아서, 작업 lease 서비스처럼 여러 lane이
 * 함께 쓰는 서비스를 따로 둘 필요가 없다.
 *
 * <p>control pool의 연결 수는 scheduler 스레드 수(spring.task.scheduling.pool.size)와 같고 예약 작업은 연결을 한 번에
 * 하나만 쥐므로, 예약 작업이 연결을 기다리는 일은 없다. 그래서 작업이 자기 lease를 갱신하는 heartbeat를 굶길 수 없다.
 *
 * <p>pool을 나누면 AI 작업이 API의 연결은 빼앗지 못해도 DB의 CPU는 쓸 수 있어서, worker와 control pool의 문장도 lease
 * 길이에서 끝낸다. API pool에 문장 시간 제한을 두면 느린 요청이 500으로 바뀔 뿐 부하는 줄지 않으므로 두지 않는다.
 */
@Configuration
public class DatabasePools {
    static final String CONNECTION_INIT_SQL = "SET TIME ZONE 'Asia/Seoul'";

    @Bean
    DataSource dataSource(DataSourceProperties properties, MeterRegistry meters,
                          @Value("${baton.database.api-pool-size:20}") int apiPoolSize,
                          @Value("${baton.database.worker-pool-size:8}") int workerPoolSize,
                          @Value("${baton.database.control-pool-size:4}") int controlPoolSize,
                          @Value("${baton.database.connection-timeout:5s}") Duration connectionTimeout,
                          @Value("${baton.database.worker-statement-timeout:30s}") Duration workerStatementTimeout) {
        HikariDataSource api = pool(properties, meters, "baton-api", apiPoolSize, connectionTimeout);
        HikariDataSource worker = pool(properties, meters, "baton-worker", workerPoolSize, connectionTimeout);
        HikariDataSource control = pool(properties, meters, "baton-control", controlPoolSize, connectionTimeout);
        // 드라이버 연결 옵션으로 주어서 모든 연결이 시간 제한을 가진 채 시작하고 SQL 문자열을 조립하지 않는다.
        for (HikariDataSource timed : new HikariDataSource[] {worker, control}) {
            timed.addDataSourceProperty("options", "-c statement_timeout=" + workerStatementTimeout.toMillis());
        }
        Routing routing = new Routing(api, worker, control);
        routing.setTargetDataSources(Map.of(DatabaseLane.API, api, DatabaseLane.WORKER, worker, DatabaseLane.CONTROL, control));
        routing.setDefaultTargetDataSource(api);
        routing.afterPropertiesSet();
        return routing;
    }

    /**
     * readiness(README §8)는 API 요청이 DB에 닿을 수 있는지 묻는다. Spring Boot의 기본 indicator는 router 뒤의 pool을
     * 모두 확인해서, 느린 AI 작업으로 worker pool이 바닥나면 서비스 전체가 빠진다. 그래서 이 indicator는 API pool만
     * 확인하고, bean 이름으로 Boot의 indicator를 대신한다.
     */
    @Bean
    DataSourceHealthIndicator dbHealthIndicator(DataSource dataSource) {
        return new DataSourceHealthIndicator(((Routing) dataSource).api);
    }

    private static HikariDataSource pool(DataSourceProperties properties, MeterRegistry meters, String name, int size,
                                         Duration connectionTimeout) {
        HikariDataSource pool = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        pool.setPoolName(name);
        pool.setMaximumPoolSize(size);
        pool.setConnectionTimeout(connectionTimeout.toMillis());
        // 업무일은 Asia/Seoul 기준이다(shared/BusinessCalendar). 드라이버는 세션을 JVM 시간대로 열어서, UTC로 띄운
        // 서비스는 current_date를 서울보다 9시간 늦게 읽었다. 드라이버는 JVM 시간대를 기동 인자로도 보내고 그 값이
        // 옵션보다 우선해서(측정함) 드라이버 옵션으로는 시간대를 정할 수 없다. 그래서 새 연결마다 도는 문장으로
        // 시간대를 정한다. 문장은 상수이고, 시험이 BusinessCalendar.ZONE과 맞는지 지킨다.
        pool.setConnectionInitSql(CONNECTION_INIT_SQL);
        pool.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(meters));
        return pool;
    }

    /** 새 연결을 호출한 스레드의 lane에 따라 보내고, context와 함께 모든 pool을 닫는다. */
    static final class Routing extends AbstractRoutingDataSource implements AutoCloseable {
        private final HikariDataSource api;
        private final HikariDataSource worker;
        private final HikariDataSource control;

        Routing(HikariDataSource api, HikariDataSource worker, HikariDataSource control) {
            this.api = api;
            this.worker = worker;
            this.control = control;
        }

        @Override
        protected Object determineCurrentLookupKey() {
            return DatabaseLane.current();
        }

        @Override
        public void close() {
            control.close();
            worker.close();
            api.close();
        }
    }
}
