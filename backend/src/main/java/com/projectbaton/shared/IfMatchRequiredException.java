package com.projectbaton.shared;

/**
 * 버전이 있는 자원을 바꾸는 명령에 If-Match 헤더가 없을 때 던진다. contracts.md C01에 따라 428 Precondition
 * Required로 나간다.
 */
public class IfMatchRequiredException extends RuntimeException {
}
