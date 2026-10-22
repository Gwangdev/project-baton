package com.projectbaton.project;

/**
 * 업무 기록의 공개 범위 규칙은 하나뿐이다(README §4.2). 타임라인 목록과 합계, 기록 상세, 대시보드의 확인 대기 수가
 * 모두 이 조건을 넣어서 개수가 그 개수로 요약한 목록과 어긋나는 일이 없다.
 *
 * <p>예전에는 모든 기록에서 원문과 연동으로 left join하는 식으로 규칙을 써서, 타임라인 합계가 프로젝트 전체를
 * join했다. 기록 45,000건이면 요청마다 원문 300,000건을 훑었고, 60 RPS에서는 그것만으로 DB가
 * 포화됐다(evidence/performance.md). 여기서는 확정 기록에 join이 전혀 없고, 행위자가 검토할 수 있는 초안은
 * PostgreSQL이 요청마다 한 번 hash로 평가하는 비상관 subquery 하나다. subquery는 원문과 연동을 scope의 프로젝트로
 * 좁힌다. 기록→원문, 원문→연동의 복합 외래키가 이미 그것을 보장해서 결과는 바뀌지 않고, planner가 그 조건을 쓸 수
 * 있게 될 뿐이다.
 *
 * <p>조건이 파라미터를 받지 않고 scope 행에서 행위자와 프로젝트를 읽어서, 같은 문장이 bind 값(타임라인)과 바깥 쿼리의
 * 열(프로젝트별 대시보드 개수) 양쪽에서 동작한다.
 */
public final class RecordVisibility {
    /** bind 파라미터 두 개로 된 scope 행. 행위자 id가 먼저, 프로젝트 id가 다음이다. */
    public static final String BOUND_SCOPE = "(select cast(? as bigint) as actor_id, cast(? as bigint) as project_id)";

    private RecordVisibility() { }

    /**
     * scope의 행위자가 scope의 프로젝트에서 볼 수 있는 기록(별칭 {@code record}). 행위자가 활성 구성원인 동안의 모든
     * 확인·수정 기록, 그리고 행위자 자신의 개인 폴더나(기존 담당자와 PM이라면) 프로젝트 공유 연동의 초안과 제외
     * 기록이다. 별칭은 코드 상수다.
     */
    public static String visible(String record, String scope) {
        return """
                %1$s.project_id = %2$s.project_id
                and exists (select 1 from projects visible_project
                            join project_members visible_member
                              on visible_member.project_id = visible_project.id and visible_member.user_id = %2$s.actor_id
                            join users visible_user on visible_user.id = visible_member.user_id
                            join organizations visible_organization on visible_organization.id = visible_user.organization_id
                            where visible_project.id = %2$s.project_id
                              and visible_project.is_active and visible_user.is_active and visible_organization.is_active
                              and visible_member.joined_on <= current_date
                              and (visible_member.left_on is null or current_date < visible_member.left_on))
                and (%1$s.trust_status in ('CONFIRMED', 'CORRECTED')
                     or %1$s.record_source_id in (
                            select reviewable_source.id from record_sources reviewable_source
                            join source_connections reviewable_connection
                              on reviewable_connection.id = reviewable_source.source_connection_id
                            join project_members reviewer
                              on reviewer.project_id = reviewable_connection.project_id and reviewer.user_id = %2$s.actor_id
                            where reviewable_source.project_id = %2$s.project_id
                              and reviewable_connection.project_id = %2$s.project_id
                              and (reviewable_connection.owner_user_id = %2$s.actor_id
                                   or (reviewable_connection.owner_user_id is null
                                       and reviewer.project_role in ('OUTGOING_OWNER', 'PROJECT_MANAGER')))))
                """.formatted(record, scope);
    }
}
