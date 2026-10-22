# 야간 백업의 보존. 한 줄에 하나씩 파일 이름을 읽고 지울 백업을 출력한다.
# 남기는 것: 백업이 있는 최근 `daily`일마다 가장 새 백업과, 백업이 있는 최근 `monthly`개월마다 첫 백업. 다른 디스크로
# 복사하면 바뀔 수 있는 파일 시각이 아니라 이름(baton-YYYYMMDD-HHMMSS.dump.age)에서 날과 달을 읽는다. 백업 이름이 아닌
# 것은 출력하지 않는다.
# 이미지와 작업 기계에서 같은 규칙이 돌도록 순수 POSIX awk만 쓴다(interval 표현식도 정렬 함수도 쓰지 않는다).
/^baton-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9][0-9][0-9]\.dump\.age$/ { names[++n] = $0 }
END {
  # 이름이 시간순으로 정렬되므로 이름에 삽입 정렬을 하면 오래된 것이 앞에 온다.
  for (i = 2; i <= n; i++) {
    v = names[i]
    for (j = i - 1; j >= 1 && names[j] > v; j--) names[j + 1] = names[j]
    names[j + 1] = v
  }
  days = 0
  for (i = n; i >= 1; i--) {
    day = substr(names[i], 7, 8)
    if (!(day in seenDay)) {
      seenDay[day] = 1
      if (days < daily) { keep[names[i]] = 1; days++ }
    }
  }
  months = 0
  for (i = 1; i <= n; i++) {
    month = substr(names[i], 7, 6)
    if (!(month in firstOf)) { firstOf[month] = names[i]; order[++months] = month }
  }
  for (k = months; k >= 1 && k > months - monthly; k--) keep[firstOf[order[k]]] = 1
  for (i = 1; i <= n; i++) if (!(names[i] in keep)) print names[i]
}
