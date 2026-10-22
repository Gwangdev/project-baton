package com.projectbaton;

/** DbmlConsistencyIT가 DBML 열이나 인덱스의 괄호 설정을 읽는다. */
final class DbmlSettings {
    private DbmlSettings() {
    }

    /**
     * note 글을 뺀 괄호의 설정 키워드. note가 다른 것을 두고 "not null", "pk", "unique"라고 쓸 수 있어서, 그걸
     * 설정으로 읽으면 실제 불일치가 가려진다.
     */
    static String keywords(String settings) {
        return settings == null ? "" : settings.replaceAll("note:\\s*'(?:[^'\\\\]|\\\\.)*'", "");
    }

    static boolean unique(String settings) {
        return settings.matches(".*\\b(unique|pk)\\b.*");
    }
}
