#!/usr/bin/env bash
# 야간 백업의 보존 규칙(infra/backup/rotate.awk)을 확인한다. 백업이 있는 최근 날마다 가장 새 백업과, 최근 달마다 첫
# 백업을 남기고, 그 밖의 백업은 지울 목록에 올리며, 백업이 아닌 파일은 목록에 올리지 않는다. awk만 있으면 된다.
set -euo pipefail
cd "$(dirname "$0")/../.."
RULE=infra/backup/rotate.awk
failures=0

expect() { # name, daily, monthly, input file names, expected removals
  local name="$1" daily="$2" monthly="$3" input="$4" expected="$5" actual
  actual=$(printf '%s\n' $input | awk -v daily="$daily" -v monthly="$monthly" -f "$RULE" | sort | tr '\n' ' ')
  expected=$(printf '%s\n' $expected | sed '/^$/d' | sort | tr '\n' ' ')
  if [ "$actual" = "$expected" ]; then echo "ok   $name"; else echo "FAIL $name"; echo "     expected: $expected"; echo "     actual:   $actual"; failures=$((failures + 1)); fi
}

# 3일과 2개월: 가장 최근 날의 이전 백업이 지워지고, 네 번째 날도, 세 날에도 들지 않고 가장 최근 두 달의 첫 백업도 아닌
# 것도 지워진다.
expect "days, months and a second backup on one day" 3 2 "
baton-20261012-020000.dump.age baton-20261012-100000.dump.age baton-20261011-020000.dump.age
baton-20261010-020000.dump.age baton-20261009-020000.dump.age baton-20261001-020000.dump.age
baton-20260915-020000.dump.age baton-20260901-020000.dump.age baton-20260801-020000.dump.age
notes.txt baton-manual.dump.age baton-20261008-020000.dump.age.partial" "
baton-20261012-020000.dump.age baton-20261009-020000.dump.age baton-20260915-020000.dump.age baton-20260801-020000.dump.age"

# 기본 30일: 어떤 달도 남기지 않으면 31일째는 지운다.
days=""; for d in $(seq 1 31); do days="$days baton-202608$(printf '%02d' $((d % 31 + 1)))-020000.dump.age"; done
days=$(printf '%s\n' $days | sort -u | tr '\n' ' ')
expect "the thirty-first day" 30 0 "$days" "baton-20260801-020000.dump.age"

# 달의 첫 백업은 30일보다 훨씬 오래돼도 남는다.
expect "a month's first backup outside the days" 1 2 "
baton-20261012-020000.dump.age baton-20261003-020000.dump.age baton-20260705-020000.dump.age baton-20260702-020000.dump.age" "
baton-20260705-020000.dump.age"

# 남길 것이 없어도 백업이 아닌 것은 지우지 않는다.
expect "only foreign files" 30 12 "readme.txt lost+found" ""

[ "$failures" -eq 0 ] && echo "rotation: all passed" || { echo "rotation: $failures failed"; exit 1; }
