package com.projectbaton.shared;

/**
 * If-Match 버전이 현재 행 버전과 더는 맞지 않을 때 던진다. contracts.md C01에 따라 412 Precondition Failed로 나간다.
 */
public class StaleVersionException extends RuntimeException {
}
