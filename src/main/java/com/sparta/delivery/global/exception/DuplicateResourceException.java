package com.sparta.delivery.global.exception;

/**
 * 이미 존재하는 리소스를 다시 생성하려고 할 때 발생시키는 예외.
 * (예: 하나의 주문에 리뷰를 두 번 작성)
 * DB UNIQUE 제약 위반(DataIntegrityViolationException)도 이 예외로 변환해서
 * 사용자에게는 항상 동일한 409 Conflict 응답을 준다.
 */
public class DuplicateResourceException extends RuntimeException {
	public DuplicateResourceException(String message) {
		super(message);
	}

	public DuplicateResourceException(String message, Throwable cause) {
		super(message, cause);
	}
}
