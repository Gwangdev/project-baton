package com.projectbaton.e2e;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 복구 경로의 브라우저 시험(frontend/tests/e2e/api/recovery-flow.spec.ts)이 쓰는 두 번째 합성 프로젝트를 넣는다.
 * 근거를 확정한 뒤 다시 시도하는 실패한 패키지, 재생성, 다시 제출로 답하는 보완 요청, 다시 처리하는 실패한 개인 폴더
 * 파일, 해제했다 다시 연결하는 폴더다. 흐름마다 자기 데이터를 갖고 서로 실행 순서에 기대지 않도록
 * {@link E2eFixtureSeeder}와 나눴다.
 *
 * <p>REC-201..205는 공유 메일 연동에서 온 검토하지 않은 초안으로, 준비 확인이 묻는 기록 유형마다 하나씩이고 시험이
 * 요청하는 인수인계 기간 안의 날짜다. 누군가 확정하기 전까지 그 기간에는 확정 근거가 없어서 첫 패키지가
 * INSUFFICIENT_EVIDENCE로 실패한다. 기존 담당자의 폴더에는 구조화에 실패한 파일 하나가 있다. 이름, 회사, 글은 모두
 * 지어낸 것이고, 비밀번호는 여기서도 {@code BATON_SEED_PASSWORD}에서만 온다.
 */
@Component
@Profile("e2e")
public class E2eRecoveryFixtureSeeder implements ApplicationRunner {
    static final String PROJECT_CODE = "PJT-2026-021";
    private static final String FIXTURE_CONNECTOR_END = "2";
    private static final Logger log = LoggerFactory.getLogger(E2eRecoveryFixtureSeeder.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwords;
    private final String seedPassword;

    public E2eRecoveryFixtureSeeder(JdbcTemplate jdbc, TransactionTemplate transactions, PasswordEncoder passwords,
                                    @Value("${baton.e2e.seed-password:}") String seedPassword) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.passwords = passwords;
        this.seedPassword = seedPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (seedPassword == null || seedPassword.isBlank()) {
            throw new IllegalStateException("BATON_SEED_PASSWORD must be set for the e2e profile");
        }
        Boolean seeded = jdbc.queryForObject("select exists(select 1 from projects where code=?)", Boolean.class, PROJECT_CODE);
        if (Boolean.TRUE.equals(seeded)) {
            log.info("E2E recovery fixture already present; leaving data unchanged");
            return;
        }
        transactions.executeWithoutResult(status -> seed());
        log.info("E2E recovery fixture seeded: project {}, drafts REC-201..REC-205", PROJECT_CODE);
    }

    private void seed() {
        long organization = jdbc.queryForObject("insert into organizations(name) values(?) returning id", Long.class,
                "C물류 배송시스템팀");
        String hash = passwords.encode(seedPassword);
        long manager = user(organization, "A-20101", "정하늘", "PM", hash);
        long outgoing = user(organization, "A-20102", "한도윤", "책임", hash);
        long incoming = user(organization, "A-20103", "오세린", "선임", hash);

        long project = jdbc.queryForObject("""
                insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status)
                values(?,?,?,?,?,?,?,'ON_TRACK') returning id
                """, Long.class, PROJECT_CODE, "C물류 배송관제 고도화", "A테크서비스", "C물류",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31), "구축");
        member(project, manager, "PROJECT_MANAGER");
        member(project, outgoing, "OUTGOING_OWNER");
        member(project, incoming, "INCOMING_OWNER");

        long mail = connection(project, null, "EMAIL", "사내 메일 — 배송관제", "mail://teams/dispatch");
        long folder = connection(project, outgoing, "CLOUD_PROJECT_FOLDER", "내 업무 폴더 — 배송관제",
                "drive://approved/han/pjt-2026-021");

        draft(201, project, mail, "고객 이메일 2026-09-21", "SCHEDULE", "배송관제 2차 오픈 11월 12일",
                "배송관제 2차 오픈일은 11월 12일로 확정 요청이 왔다.", LocalDate.of(2026, 9, 21), LocalDate.of(2026, 11, 12));
        draft(202, project, mail, "변경 요청 메일 2026-09-22", "CHANGE_DECISION", "관제 지도 공급사 변경",
                "관제 지도 공급사를 기존 업체에서 신규 업체로 변경하기로 했다.", LocalDate.of(2026, 9, 22), null);
        draft(203, project, mail, "주간 보고 메일 2026-09-23", "COMMITMENT", "기사 앱 시범 배포 10월 20일",
                "기사 앱 시범 배포를 10월 20일까지 제공하기로 약속했다.", LocalDate.of(2026, 9, 23), LocalDate.of(2026, 10, 20));
        draft(204, project, mail, "장애 공유 메일 2026-09-24", "BLOCKER", "차량 단말 인증서 만료",
                "차량 단말 인증서가 만료되어 위치 수신이 막혀 있다.", LocalDate.of(2026, 9, 24), null);
        draft(205, project, mail, "리스크 메일 2026-09-25", "OPEN_ISSUE", "야간 배차 규칙 미확정",
                "야간 배차 규칙이 아직 정해지지 않아 10월 2일까지 결정이 필요하다.", LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 2));
        // 위의 id는 코드에 맞춰 고정되어 있어서, sequence를 가장 큰 id를 가진 fixture 뒤로 옮겨야 한다.
        jdbc.execute("select setval('work_records_id_seq', (select max(id) from work_records))");

        String body = "[배송관제_인터페이스_정의서.docx] 관제 서버와 기사 앱 사이의 위치 전송 주기 정의.";
        jdbc.update("""
                insert into record_sources(source_connection_id,display_name,collected_at,project_id,external_document_id,
                                           external_version,content_hash,source_body,processing_status,failure_code,
                                           source_author_name,source_occurred_on)
                values(?,?,?,?,?,'v1',?,?,'FAILED','MODEL_OUTPUT_INVALID',?,?)
                """, folder, "배송관제_인터페이스_정의서.docx", OffsetDateTime.of(2026, 9, 26, 0, 0, 0, 0, ZoneOffset.UTC),
                project, "e2e-folder-failed", sha256(body), body, "한도윤", LocalDate.of(2026, 9, 26));
    }

    private long user(long organization, String employeeNo, String name, String title, String hash) {
        return jdbc.queryForObject("""
                insert into users(organization_id,employee_no,name,title,email,system_role,credential_hash)
                values(?,?,?,?,?,'USER',?) returning id
                """, Long.class, organization, employeeNo, name, title, employeeNo.toLowerCase() + "@example.test", hash);
    }

    private void member(long project, long user, String role) {
        jdbc.update("insert into project_members(project_id,user_id,project_role,joined_on) values(?,?,cast(? as project_role),?)",
                project, user, role, LocalDate.of(2026, 6, 1));
    }

    private long connection(long project, Long owner, String channel, String name, String ref) {
        return jdbc.queryForObject("""
                insert into source_connections(project_id,owner_user_id,channel,display_name,external_ref,checkpoint,
                                               last_collected_at,last_status,last_collected_count)
                values(?,?,cast(? as source_channel),?,?,?,?,'SUCCEEDED',0) returning id
                """, Long.class, project, owner, channel, name, ref, FIXTURE_CONNECTOR_END,
                OffsetDateTime.of(2026, 9, 26, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    private void draft(long id, long project, long connection, String documentName, String type, String title,
                       String summary, LocalDate occurredOn, LocalDate dueOn) {
        String excerpt = "[" + documentName + "] " + summary;
        long source = jdbc.queryForObject("""
                insert into record_sources(source_connection_id,display_name,collected_at,project_id,external_document_id,
                                           external_version,content_hash,source_body,processing_status,source_author_name,
                                           source_occurred_on)
                values(?,?,?,?,?,'v1',?,?,'SUCCEEDED','C물류 담당자',?) returning id
                """, Long.class, connection, documentName, occurredOn.atStartOfDay().atOffset(ZoneOffset.UTC), project,
                "e2e-" + id, sha256(excerpt), excerpt, occurredOn);
        jdbc.update("""
                insert into work_records(id,code,project_id,record_source_id,record_type,title,summary,original_excerpt,
                                         occurred_on,author_name,due_on,trust_status,source_version,search_text)
                values(?,?,?,?,cast(? as record_type),?,?,?,?,'C물류 담당자',?,'AUTO_DRAFT','v1',?)
                """, id, "REC-" + id, project, source, type, title, summary, excerpt, occurredOn, dueOn, summary);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
