-- 복원한 baseline의 마이그레이션 checksum을 현재 파일에 맞춘다. baseline을 덤프한 뒤 V10, V13, V14, V15의 주석을 다시
-- 썼고, Flyway는 주석까지 파일 전체로 checksum을 내서 덤프의 바뀌지 않은 스키마가 기동에서 거절된다. 문장마다 옛
-- checksum과 맞을 때만 바꿔서, 이미 새 checksum을 가진 DB(이 변경 뒤의 덤프)나 그 버전을 돌린 적 없는 DB에서는 아무
-- 일도 하지 않는다.
update flyway_schema_history set checksum = 1903567627  where version = '10' and checksum = -1082016421;
update flyway_schema_history set checksum = -2038092834 where version = '13' and checksum = -1037945752;
update flyway_schema_history set checksum = -127726855  where version = '14' and checksum = -1513606219;
update flyway_schema_history set checksum = -1702633973 where version = '15' and checksum = -552580787;
