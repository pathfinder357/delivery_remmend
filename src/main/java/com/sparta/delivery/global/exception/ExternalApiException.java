package com.sparta.delivery.global.exception;

/**
 * 외부 API(Gemini 등) 연동 실패를 나타내는 예외.
 * 우리 서버의 버그(500)가 아니라 "외부 의존 시스템의 문제"라는 것을 구분하기 위해 별도로 둔다.
 */
public class ExternalApiException extends RuntimeException {
	public ExternalApiException(String message) {
		super(message);
	}

	public ExternalApiException(String message, Throwable cause) {
		super(message, cause);
	}
}
