#!/usr/bin/env python3
"""관측 overlay의 Grafana 대시보드 셋을 이 파일 옆 dashboards/에 쓴다.

「일반 API」와 「타임라인」을 정하는 PromQL 필터가 여기 한 곳에만 있도록 대시보드를 생성한다(infra/observe/prometheus/
alerts.yml의 경보 규칙도 같은 필터를 쓴다). 이 파일을 고치고 돌린다. `--check`는 디스크의 파일이 이 파일이 쓸 내용과
같은지 알린다(tests/observe/observe-check.sh가 돌린다).
"""
import json
import os
import sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dashboards")
DS = {"type": "prometheus", "uid": "prometheus"}
GEN = 'uri=~"/api/.*", uri!="/api/v1/projects/{projectId}/records", uri!~".*/questions.*"'
TL = 'uri="/api/v1/projects/{projectId}/records"'
pid = [0]
def nid():
    pid[0] += 1; return pid[0]
def ts(title, targets, unit="short", x=0, y=0, w=12, h=8, desc="", thresholds=None, minv=None, series_thresholds=None):
    p = {"id": nid(), "type": "timeseries", "title": title, "description": desc, "datasource": DS,
         "gridPos": {"x": x, "y": y, "w": w, "h": h},
         "fieldConfig": {"defaults": {"unit": unit, "custom": {"lineWidth": 2, "fillOpacity": 8, "showPoints": "never",
                                                              "spanNulls": False}}, "overrides": []},
         "options": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}},
         "targets": [{"refId": chr(65 + i), "expr": e, "legendFormat": l, "datasource": DS} for i, (e, l) in enumerate(targets)]}
    if minv is not None: p["fieldConfig"]["defaults"]["min"] = minv
    if thresholds:
        p["fieldConfig"]["defaults"]["thresholds"] = {"mode": "absolute", "steps": [{"color": "green", "value": None}, {"color": "red", "value": thresholds}]}
        p["fieldConfig"]["defaults"]["custom"]["thresholdsStyle"] = {"mode": "dashed"}
    # 자기 목표가 있는 계열(일반 API의 500ms 옆 타임라인의 1s)은 자기 기준선을 함께 그린다.
    for name, value in (series_thresholds or {}).items():
        p["fieldConfig"]["overrides"].append({"matcher": {"id": "byName", "options": name}, "properties": [
            {"id": "thresholds", "value": {"mode": "absolute", "steps": [{"color": "green", "value": None}, {"color": "red", "value": value}]}},
            {"id": "custom.thresholdsStyle", "value": {"mode": "dashed"}}]})
    return p
def stat(title, expr, unit, x, y, w=6, h=4, desc="", steps=None):
    return {"id": nid(), "type": "stat", "title": title, "description": desc, "datasource": DS,
            "gridPos": {"x": x, "y": y, "w": w, "h": h},
            "fieldConfig": {"defaults": {"unit": unit, "thresholds": {"mode": "absolute", "steps": steps or [{"color": "text", "value": None}]}}, "overrides": []},
            "options": {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "value", "graphMode": "area"},
            "targets": [{"refId": "A", "expr": expr, "datasource": DS}]}
def dash(uid, title, desc, panels, templating=None):
    return {"uid": uid, "title": title, "description": desc, "tags": ["baton"], "timezone": "browser", "schemaVersion": 39,
            "refresh": "10s", "time": {"from": "now-1h", "to": "now"}, "editable": False, "panels": panels,
            "templating": {"list": templating or []}, "annotations": {"list": []}}

service = dash("baton-service", "Baton 서비스",
  "사용자가 느끼는 증상(RED): 요청 수, 오류, 응답 시간. 목표는 README §8의 p95 500 ms(일반 API)와 1초(타임라인)다. Q&A 스트림은 모델 시간이라 일반 API에서 뺀다.", [
  stat("요청 수 (5분)", 'sum(rate(http_server_requests_seconds_count{uri=~"/api/.*"}[5m]))', "reqps", 0, 0),
  stat("500 ms 안에 끝난 일반 요청 (5분)", 'baton:general_within_target:ratio_rate5m', "percentunit", 6, 0,
       desc="95% 이상이면 p95 목표 안이다.", steps=[{"color": "red", "value": None}, {"color": "green", "value": 0.95}]),
  stat("5xx 비율 (5분)", 'sum(rate(http_server_requests_seconds_count{uri=~"/api/.*", status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count{uri=~"/api/.*"}[5m]))', "percentunit", 12, 0,
       steps=[{"color": "green", "value": None}, {"color": "red", "value": 0.01}]),
  stat("발생 중인 경보", 'count(ALERTS{alertstate="firing"}) or vector(0)', "short", 18, 0,
       steps=[{"color": "green", "value": None}, {"color": "red", "value": 1}]),
  ts("일반 API p95·p99 (1분)", [
      (f'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{{{GEN}}}[1m])))', "p95"),
      (f'histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{{{GEN}}}[1m])))', "p99")],
     unit="s", x=0, y=4, thresholds=0.5, desc="점선이 목표 500 ms. 1분 창이라 짧은 정체가 보인다."),
  ts("타임라인 p95 (1분)", [(f'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{{{TL}}}[1m])))', "p95")],
     unit="s", x=12, y=4, thresholds=1, desc="점선이 목표 1초."),
  ts("상태별 요청 수", [('sum by (status) (rate(http_server_requests_seconds_count{uri=~"/api/.*"}[1m]))', "{{status}}")],
     unit="reqps", x=0, y=12),
  ts("느린 경로 상위 5 (p95, 5분)", [(f'topk(5, histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket{{uri=~"/api/.*"}}[5m]))))', "{{uri}}")],
     unit="s", x=12, y=12),
])

proj = [{"name": "project", "label": "Compose 프로젝트", "type": "query", "datasource": DS, "refresh": 2,
         "query": {"query": "label_values(container_cpu_usage_seconds_total, container_label_com_docker_compose_project)", "refId": "P"},
         "definition": "label_values(container_cpu_usage_seconds_total, container_label_com_docker_compose_project)",
         "includeAll": False, "multi": False}]
SVC = 'container_label_com_docker_compose_project="$project", container_label_com_docker_compose_service!=""'
resources = dash("baton-resources", "Baton 자원",
  "원인을 좁히는 자원 화면(USE): 앱 CPU, DB 연결 pool, AI slot, JVM, DB. 경보가 울린 뒤 같은 시간축에서 본다.", [
  ts("앱 CPU (상한 대비 %)", [('process_cpu_usage{job="app"} * 100', "앱 프로세스")],
     unit="percent", x=0, y=0, desc="JVM이 보는 CPU(컨테이너 상한 2 CPU) 대비 사용률. DB 컨테이너 CPU는 이 구성에 없다(cAdvisor가 Docker Desktop에서 동작하지 않음). 부하 시험 중에는 tests/load/sample.sh가 docker stats로 남긴다.", minv=0),
  ts("DB 연결 수", [('sum(pg_stat_database_numbackends{datname="baton"})', "연결"), ('pg_settings_max_connections', "최대")],
     unit="short", x=12, y=0, minv=0),
  ts("DB 연결 pool: 기다린 요청", [('hikaricp_connections_pending', "{{pool}}")], unit="short", x=0, y=8,
     desc="0이 아니면 요청이 DB 연결을 기다렸다. 부하 시험의 정체마다 먼저 올랐다.", minv=0),
  ts("DB 연결 pool: 사용 중", [('hikaricp_connections_active', "{{pool}} 사용 중"), ('hikaricp_connections_max', "{{pool}} 최대")], unit="short", x=12, y=8, minv=0),
  ts("AI job slot 여유", [('baton_job_slots_available', "{{pool}}")], unit="short", x=0, y=16, desc="0이 오래 가면 job이 줄을 선다.", minv=0),
  ts("JVM heap", [('sum(jvm_memory_used_bytes{area="heap"})', "사용"), ('sum(jvm_memory_max_bytes{area="heap"})', "최대")], unit="bytes", x=12, y=16, minv=0),
  ts("DB 블록당 읽기 지연 (µs)", [('1000 * rate(pg_stat_database_blk_read_time{datname="baton"}[1m]) / rate(pg_stat_database_blks_read{datname="baton"}[1m])', "µs/블록")],
     unit="µs", x=0, y=24, desc="버퍼 밖 블록 하나를 읽는 평균 시간. 부하 시험에서 9 µs 이하면 통과, 14 µs 이상이면 흔들렸다. DB에 track_io_timing이 켜져 있어야 값이 나온다(부하 overlay는 켠다).", minv=0),
  ts("DB 버퍼 적중률", [('rate(pg_stat_database_blks_hit{datname="baton"}[1m]) / (rate(pg_stat_database_blks_hit{datname="baton"}[1m]) + rate(pg_stat_database_blks_read{datname="baton"}[1m]))', "적중률")],
     unit="percentunit", x=12, y=24),
  ts("DB 세션 상태", [('sum by (state) (pg_stat_activity_count{datname="baton"})', "{{state}}")], unit="short", x=0, y=32, minv=0),
  ts("DB 트랜잭션·잠금", [('rate(pg_stat_database_xact_commit{datname="baton"}[1m])', "commit/s"), ('sum(pg_locks_count{datname="baton"})', "잠금 수")], unit="short", x=12, y=32, minv=0),
])

load = dash("baton-load", "Baton 부하 시험",
  "k6가 보내는 클라이언트 쪽 지표와 서버 쪽 p95를 나란히 본다. tests/load/run.sh를 BATON_LOAD_PROMETHEUS=1로 돌리면 실시간으로 채워진다.", [
  ts("k6 요청 수", [('sum by (kind) (rate(k6_http_reqs_total[1m]))', "{{kind}}")], unit="reqps", x=0, y=0, minv=0),
  ts("k6 가상 사용자·dropped", [('sum(k6_vus)', "VU"),
      # k6는 버려진 것이 생겨야 dropped counter를 보내므로, k6가 도는 동안에는 데이터 없음 대신 0을 보인다.
      ('sum(increase(k6_dropped_iterations_total[1m])) or (sum(k6_vus) * 0)', "dropped/분")], unit="short", x=12, y=0, minv=0,
     desc="dropped는 보낼 사용자가 모자라 보내지 못한 요청이다. 0이어야 한다."),
  ts("k6 p95 (클라이언트, 1분)", [('histogram_quantile(0.95, sum by (kind) (rate(k6_http_req_duration_seconds{kind=~"general|timeline"}[1m])))', "{{kind}}")],
     unit="s", x=0, y=8, thresholds=0.5, series_thresholds={"timeline": 1}, desc="proxy와 TLS를 포함한 시간. 서버 쪽 p95보다 조금 길다. 점선은 일반 500 ms, 타임라인 1초."),
  ts("서버 p95 (1분)", [
      (f'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{{{GEN}}}[1m])))', "일반 API"),
      (f'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{{{TL}}}[1m])))', "타임라인")],
     unit="s", x=12, y=8, thresholds=0.5, series_thresholds={"타임라인": 1}),
  ts("k6 실패율", [('avg by (kind) (k6_http_req_failed_rate{kind=~"general|timeline"})', "{{kind}}")],
     unit="percentunit", x=0, y=16, minv=0),
  ts("API pool 대기와 DB 블록당 읽기 지연", [('hikaricp_connections_pending{pool="baton-api"}', "API pool 대기"),
      ('1000 * rate(pg_stat_database_blk_read_time{datname="baton"}[1m]) / rate(pg_stat_database_blks_read{datname="baton"}[1m])', "블록당 읽기 µs")],
     unit="short", x=12, y=16, minv=0, desc="부하 시험의 정체에서 함께 올랐던 두 값."),
])
stale = []
for name, d in [("service.json", service), ("resources.json", resources), ("load.json", load)]:
    text = json.dumps(d, ensure_ascii=False, indent=2) + "\n"
    path = os.path.join(OUT, name)
    if "--check" in sys.argv:
        with open(path, encoding="utf-8") as f:
            if f.read() != text:
                stale.append(name)
    else:
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
if stale:
    sys.exit("dashboards differ from build-dashboards.py: " + ", ".join(stale))
