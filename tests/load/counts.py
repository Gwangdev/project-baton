#!/usr/bin/env python3
"""부하 실행의 시나리오, 단계, 역할, operation별 요청 수.

    tests/load/counts.py <results-dir>                 requests.csv.gz를 읽고 counts.csv를 쓴다
    tests/load/counts.py --compare <dir> <dir> ...     키마다 실행별 수와 가장 큰 편차를 출력한다

같은 계획을 두 번 다시 돌렸을 때 보낸 트래픽이 달랐는데(사용자 수정 597건 대 129건) DB를 세기 전까지는 결과 어디에도
드러나지 않았다. baton.js가 요청마다 역할 tag를 붙여서 k6 출력을 시나리오, 역할, operation별로 셀 수 있으므로, 실행의
지연을 비교하기 전에 실제로 보낸 트래픽부터 비교한다. 비교는 실행마다 모든 실행 평균에서의 편차를 퍼센트로 알린다.
"""
import csv
import gzip
import sys
from collections import Counter
from pathlib import Path
from urllib.parse import parse_qs

KEY = ("scenario", "phase", "role", "name")


def count(results: Path) -> None:
    requests, failed = Counter(), Counter()
    with gzip.open(results / "requests.csv.gz", "rt", newline="") as source:
        for row in csv.DictReader(source):
            if row["metric_name"] != "http_reqs":
                continue
            tags = {k: v[0] for k, v in parse_qs(row.get("extra_tags", "")).items()}
            key = (row["scenario"], tags.get("phase", ""), tags.get("role", ""), row["name"])
            requests[key] += 1
            if row.get("expected_response") == "false":
                failed[key] += 1
    with open(results / "counts.csv", "w", newline="") as target:
        writer = csv.writer(target)
        writer.writerow([*KEY, "requests", "unexpected"])
        for key in sorted(requests):
            writer.writerow([*key, requests[key], failed[key]])


def compare(runs: list) -> int:
    tables = []
    for run in runs:
        with open(Path(run) / "counts.csv", newline="") as source:
            tables.append({tuple(r[k] for k in KEY): int(r["requests"]) for r in csv.DictReader(source)})
    keys = sorted(set().union(*tables))
    print(",".join([*KEY, *[Path(r).name for r in runs], "max_deviation_pct"]))
    worst = 0.0
    for key in keys:
        values = [table.get(key, 0) for table in tables]
        mean = sum(values) / len(values)
        deviation = max(abs(v - mean) for v in values) / mean * 100 if mean else 0.0
        worst = max(worst, deviation)
        print(",".join([*key, *map(str, values), f"{deviation:.1f}"]))
    print(f"# largest deviation {worst:.1f}%", file=sys.stderr)
    return 0


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "--compare":
        sys.exit(compare(sys.argv[2:]))
    if len(sys.argv) == 2:
        count(Path(sys.argv[1]))
        sys.exit(0)
    print(__doc__, file=sys.stderr)
    sys.exit(2)
