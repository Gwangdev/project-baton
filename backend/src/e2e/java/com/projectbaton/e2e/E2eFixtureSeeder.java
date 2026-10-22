package com.projectbaton.e2e;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import com.projectbaton.jobs.EmbeddingQueue;
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
 * 실제 백엔드 브라우저 시험이 시작하는 합성 fixture 프로젝트를 넣는다.
 *
 * <p>승인된 계약에는 로그인할 수 있는 첫 계정을 만드는 공개 방법이 없고(관리자가 만든 사용자에게는 자격 증명이 없다,
 * C02) 표시 코드는 DB sequence에서 나와서, fixture 코드 REC-101..107을 API로 만들 수 없다. 그래서 따로 둔 source
 * set의 runner로 넣는다. 이 클래스는 {@code bootRunE2e} Gradle 작업만 classpath에 넣는 {@code src/e2e}에 있고,
 * 패키지한 앱에는 들어가지 않는다. {@code e2e} 프로필도 요구해서 classpath에 잘못 섞여도 그것만으로는 아무것도 넣지
 * 않는다.
 *
 * <p>1단계 데모를 다시 재생하지 않고 최신 확정 상태를 여기서 고정한다. REC-101..106은 확정(REC-103은 수정본과 함께
 * 수정 확정)이고, REC-101과 REC-102는 오픈일에서 충돌하고, REC-106은 REC-104를 참조하고, REC-107은 기존 담당자 개인
 * 폴더의 미확정 초안 하나라서 기록 검토와 비공개 초안 경계를 시험할 수 있다. 이름, 회사, 글은 모두 지어낸 것이다.
 *
 * <p>넣는 모든 계정의 비밀번호는 {@code BATON_SEED_PASSWORD}에서만 오고, 없으면 기동이 멈춘다. 연동 checkpoint를
 * fixture 커넥터의 문서 뒤로 두어서 예약 수집이 아무것도 더하지 않고 확인함이 결정적으로 유지된다.
 */
@Component
@Profile("e2e")
public class E2eFixtureSeeder implements ApplicationRunner {
    static final String PROJECT_CODE = "PJT-2026-014";
    private static final String FIXTURE_CONNECTOR_END = "2";
    private static final Logger log = LoggerFactory.getLogger(E2eFixtureSeeder.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwords;
    private final EmbeddingQueue embeddings;
    private final String seedPassword;

    public E2eFixtureSeeder(JdbcTemplate jdbc, TransactionTemplate transactions, PasswordEncoder passwords,
                            EmbeddingQueue embeddings, @Value("${baton.e2e.seed-password:}") String seedPassword) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.passwords = passwords;
        this.embeddings = embeddings;
        this.seedPassword = seedPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (seedPassword == null || seedPassword.isBlank()) {
            throw new IllegalStateException("BATON_SEED_PASSWORD must be set for the e2e profile");
        }
        Boolean seeded = jdbc.queryForObject("select exists(select 1 from projects where code=?)", Boolean.class, PROJECT_CODE);
        if (Boolean.TRUE.equals(seeded)) {
            log.info("E2E fixture already present; leaving data unchanged");
            return;
        }
        transactions.executeWithoutResult(status -> seed());
        log.info("E2E fixture seeded: project {}, records REC-101..REC-107", PROJECT_CODE);
    }

    private void seed() {
        long delivery = organization("A테크서비스 고객지원사업부");
        long systems = organization("A테크서비스 정보시스템실");
        String hash = passwords.encode(seedPassword);
        long kim = user(delivery, "A-10421", "김현우", "책임", "USER", hash);
        long park = user(delivery, "A-10188", "박지민", "PL", "USER", hash);
        long lee = user(delivery, "A-10733", "이서연", "매니저", "USER", hash);
        long choi = user(systems, "A-10002", "최도윤", "매니저", "SYSTEM_ADMIN", hash);

        long project = jdbc.queryForObject("""
                insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status)
                values(?,?,?,?,?,?,?,'AT_RISK') returning id
                """, Long.class, PROJECT_CODE, "B리테일 옴니채널 고객지원 플랫폼 구축", "A테크서비스", "B리테일",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 11, 30), "통합 테스트 준비");
        member(project, kim, "OUTGOING_OWNER");
        member(project, park, "PROJECT_MANAGER");
        member(project, lee, "INCOMING_OWNER");
        member(project, choi, "MEMBER");

        long email = connection(project, null, "EMAIL", "사내 메일 — 고객지원사업부", "mail://teams/cs-delivery");
        long approval = connection(project, null, "APPROVAL", "전자결재 — 변경관리", "approval://change-mgmt");
        long messenger = connection(project, null, "MESSENGER", "프로젝트 메신저 — PJT-2026-014", "chat://rooms/pjt-2026-014");
        long minutes = connection(project, null, "MEETING_MINUTES", "회의록 저장소 — 주간·리스크", "docs://minutes/pjt-2026-014");
        long folder = connection(project, kim, "CLOUD_PROJECT_FOLDER", "내 업무 폴더 — B리테일 통합테스트",
                "drive://approved/kim/pjt-2026-014");

        Map<Long, Long> confirmedVersions = new LinkedHashMap<>();
        confirmed(confirmedVersions, sourced(101, project, email, "고객 이메일 2026-08-28", "SCHEDULE",
                "1차 오픈 목표일 10월 15일", "1차 오픈 목표일은 10월 15일이다.",
                "[고객 이메일 2026-08-28] 협의드린 대로 1차 오픈 목표일은 10월 15일로 잡고 진행 부탁드립니다.",
                LocalDate.of(2026, 8, 28), "B리테일 정과장", null, LocalDate.of(2026, 10, 15), kim, "CONFIRMED"));
        confirmed(confirmedVersions, sourced(102, project, approval, "변경승인 결재문서 2026-09-05", "CHANGE_DECISION",
                "최종 오픈일 10월 22일로 변경", "보안성 검토 기간을 반영해 최종 오픈일을 10월 22일로 변경한다.",
                "[변경승인 결재문서 2026-09-05] 변경 사유: 보안성 검토 기간 2주 반영. 변경 후 오픈일: 2026-10-22.",
                LocalDate.of(2026, 9, 5), "박지민", park, LocalDate.of(2026, 10, 22), kim, "CONFIRMED"));
        confirmed(confirmedVersions, sourced(103, project, minutes, "주간회의록 2026-09-08", "COMMITMENT",
                "통합 시연 버전 9월 18일 제공", "통합 시연 버전을 9월 18일까지 고객에게 제공한다. 담당은 김현우 책임이다.",
                "[주간회의록 2026-09-08] 고객사에서 통합 시연 버전 요청. 9월 18일까지 제공하기로 함. 담당 협의 필요.",
                LocalDate.of(2026, 9, 8), "김현우", kim, LocalDate.of(2026, 9, 18), kim, "CORRECTED"));
        revision(103, kim, "담당자와 기한을 회의 결과에 맞춰 보정", "통합 시연 버전 9월 18일 제공",
                "통합 시연 버전을 9월 18일까지 고객에게 제공한다.",
                "통합 시연 버전을 9월 18일까지 고객에게 제공한다. 담당은 김현우 책임이다.",
                "COMMITMENT", kim, LocalDate.of(2026, 9, 18));
        confirmed(confirmedVersions, sourced(104, project, messenger, "프로젝트 메신저 2026-09-09", "BLOCKER",
                "CRM 테스트 계정 발급 지연", "B리테일 CRM 테스트 계정 발급이 지연 중이며 고객 인프라팀 확인이 필요하다.",
                "[프로젝트 메신저 2026-09-09] CRM 테스트 계정 아직입니다. 인프라팀 회신 없음. 통합테스트 일정에 영향 있습니다.",
                LocalDate.of(2026, 9, 9), "김현우", kim, null, kim, "CONFIRMED"));
        confirmed(confirmedVersions, noteShared(105, project, kim, "SCOPE_EXCEPTION",
                "대량 상담이력 다운로드 1차 오픈 제외",
                "대량 상담이력 다운로드 기능은 1차 오픈 범위에서 제외하고 2차 개선으로 이관한다.",
                LocalDate.of(2026, 9, 10)));
        confirmed(confirmedVersions, sourced(106, project, minutes, "리스크 회의록 2026-09-11", "OPEN_ISSUE",
                "대체 테스트 데이터 사용 여부 결정 필요",
                "CRM 테스트 계정이 9월 16일까지 발급되지 않으면 대체 테스트 데이터를 사용할지 결정해야 한다.",
                "[리스크 회의록 2026-09-11] 리스크 R-03. 계정 미발급 지속 시 대체 데이터 사용 검토. 판단 기한 9/16.",
                LocalDate.of(2026, 9, 11), "박지민", park, LocalDate.of(2026, 9, 16), park, "CONFIRMED"));
        sourced(107, project, folder, "통합테스트_시나리오_v3.xlsx", "OPEN_ISSUE",
                "통합테스트 시나리오 v3 검토 필요", "통합테스트 시나리오 v3의 결제 예외 흐름을 고객과 다시 확인해야 한다.",
                "[통합테스트_시나리오_v3.xlsx] 결제 예외 흐름 3건 고객 확인 필요.",
                LocalDate.of(2026, 9, 11), "김현우", null, null, null, "AUTO_DRAFT");

        // 코드는 앱 자체의 'REC-' || id 규칙을 따르므로, sequence를 fixture id 뒤로 옮기지 않으면 나중 초안이
        // REC-101..107과 겹친다.
        jdbc.execute("select setval('work_records_id_seq', (select max(id) from work_records))");
        relation(project, 101, 102, "CONFLICTS_WITH", "1차 오픈일 10월 15일과 변경된 최종 오픈일 10월 22일이 서로 다르다");
        relation(project, 106, 104, "REFERENCES", "대체 테스트 데이터 사용 여부는 CRM 계정 발급 지연을 전제로 한다");
        confirmedVersions.forEach(embeddings::enqueue);
    }

    private long organization(String name) {
        return jdbc.queryForObject("insert into organizations(name) values(?) returning id", Long.class, name);
    }

    private long user(long organization, String employeeNo, String name, String title, String role, String hash) {
        return jdbc.queryForObject("""
                insert into users(organization_id,employee_no,name,title,email,system_role,credential_hash)
                values(?,?,?,?,?,cast(? as system_role),?) returning id
                """, Long.class, organization, employeeNo, name, title,
                employeeNo.toLowerCase() + "@example.test", role, hash);
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
                OffsetDateTime.of(2026, 9, 11, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    private long sourced(long id, long project, long connection, String documentName, String type, String title,
                         String summary, String excerpt, LocalDate occurredOn, String author, Long owner, LocalDate dueOn,
                         Long confirmedBy, String status) {
        long source = jdbc.queryForObject("""
                insert into record_sources(source_connection_id,display_name,collected_at,project_id,external_document_id,
                                           external_version,content_hash,source_body,processing_status,source_author_name,
                                           source_occurred_on)
                values(?,?,?,?,?,'v1',?,?,'SUCCEEDED',?,?) returning id
                """, Long.class, connection, documentName, occurredOn.atStartOfDay().atOffset(ZoneOffset.UTC), project,
                "e2e-" + id, sha256(excerpt), excerpt, author, occurredOn);
        return insertRecord(id, project, source, null, type, title, summary, excerpt, occurredOn, null, author, owner, dueOn,
                confirmedBy, status);
    }

    private long noteShared(long id, long project, long author, String type, String title, String summary,
                            LocalDate occurredOn) {
        long note = jdbc.queryForObject("""
                insert into personal_notes(code,project_id,author_id,body)
                values('NOTE-' || nextval('personal_notes_id_seq'),?,?,?) returning id
                """, Long.class, project, author, String.join("\n",
                "9/10 15:20 B리테일 정과장 통화.",
                "대량 상담이력 다운로드는 1차 오픈에서 빼기로 구두 합의. 성능 부담 우려.",
                "2차 개선으로 넘기되 시기는 아직 미정.",
                "(개인 메모) 이 건은 회의록에 안 남았음."));
        String name = jdbc.queryForObject("select name from users where id=?", String.class, author);
        return insertRecord(id, project, null, note, type, title, summary, summary, occurredOn, author, name, null, null,
                author, "CONFIRMED");
    }

    private long insertRecord(long id, long project, Long source, Long note, String type, String title, String summary,
                              String excerpt, LocalDate occurredOn, Long authorId, String authorName, Long owner,
                              LocalDate dueOn, Long confirmedBy, String status) {
        OffsetDateTime confirmedAt = confirmedBy == null ? null : occurredOn.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        jdbc.update("""
                insert into work_records(id,code,project_id,record_source_id,source_note_id,record_type,title,summary,
                                         original_excerpt,occurred_on,author_id,author_name,owner_user_id,due_on,trust_status,
                                         confirmed_by,confirmed_at,source_version,search_text)
                values(?,?,?,?,?,cast(? as record_type),?,?,?,?,?,?,?,?,cast(? as record_trust_status),?,?,?,?)
                """, id, "REC-" + id, project, source, note, type, title, summary, excerpt, occurredOn, authorId, authorName,
                owner, dueOn, status, confirmedBy, confirmedAt, source == null ? null : "v1", summary);
        return id;
    }

    private void confirmed(Map<Long, Long> versions, long recordId) {
        versions.put(recordId, jdbc.queryForObject("select version from work_records where id=?", Long.class, recordId));
    }

    private void revision(long recordId, long changedBy, String reason, String title, String before, String after,
                          String type, long owner, LocalDate dueOn) {
        // RecordReviewService와 같은 이전·이후 모양. 수정하기 전 초안에는 담당자와 기한이 없었다.
        jdbc.update("""
                insert into record_revisions(work_record_id,revision_no,changed_by,change_reason,before_summary,after_summary,
                                             before_data,after_data)
                values(?,1,?,?,?,?,
                       jsonb_build_object('title',?::text,'summary',?::text,'recordType',?::text,'ownerUserId',null,'dueOn',null),
                       jsonb_build_object('title',?::text,'summary',?::text,'recordType',?::text,'ownerUserId',?::bigint,'dueOn',?::text))
                """, recordId, changedBy, reason, before, after, title, before, type, title, after, type, owner, dueOn.toString());
    }

    private void relation(long project, long from, long to, String type, String note) {
        jdbc.update("""
                insert into record_relations(project_id,from_record_id,to_record_id,relation_type,note)
                values(?,?,?,cast(? as record_relation_type),?)
                """, project, from, to, type, note);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
