package com.sparta.delivery.global.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	// 400 Bad Request (유효성 검증 실패 및 파라미터 누락)
	// @Valid 유효성 검증 실패 시
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
		String errorMessage = ex.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
		return ResponseEntity
			.status(HttpStatus.BAD_REQUEST)
			.body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), errorMessage));
	}

	// 지원하지 않는 데이터 형식, JSON 파싱 에러 등
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
		return ResponseEntity
			.status(HttpStatus.BAD_REQUEST)
			.body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "잘못된 JSON 형식의 요청입니다."));
	}

	// 401 Unauthorized
	@ExceptionHandler(InsufficientAuthenticationException.class)
	public ResponseEntity<ErrorResponse> handleAccessDenied(InsufficientAuthenticationException ex) {
		return ResponseEntity
				.status(HttpStatus.UNAUTHORIZED)
				.body(new ErrorResponse(HttpStatus.UNAUTHORIZED.value(), "인증 정보가 없거나 유효하지 않습니다. 다시 로그인해 주세요."));
	}

	// 잘못된 값 입력 시 예외 처리
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
		return ResponseEntity
			.status(HttpStatus.BAD_REQUEST)
			.body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
	}

	// 403 Forbidden (권한 부족)
	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
		String message = (ex.getMessage() == null || ex.getMessage().isBlank())
			? "해당 요청에 대한 접근 권한이 없습니다."
			: ex.getMessage();
		return ResponseEntity
			.status(HttpStatus.FORBIDDEN)
			.body(new ErrorResponse(HttpStatus.FORBIDDEN.value(), message));
	}


	// 특정 커스텀 예외 처리 (404 Not Found)
	@ExceptionHandler(ResourceNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
		ErrorResponse errorResponse = new ErrorResponse(
			HttpStatus.NOT_FOUND.value(),
			ex.getMessage()
		);
		return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
	}

	// 5. 405 Method Not Allowed (지원하지 않는 HTTP 메서드로 요청했을 때, 예: POST인데 GET으로 보냄)
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class) // Spring 내장 예외
	public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
		return ResponseEntity
			.status(HttpStatus.METHOD_NOT_ALLOWED) // 405
			.body(new ErrorResponse(HttpStatus.METHOD_NOT_ALLOWED.value(), "지원하지 않는 HTTP 메서드입니다."));
	}

	//409 Conflict (비즈니스 규칙 위반 - 상태 전이 불가, 시간 초과 등)
	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<ErrorResponse> handleConflict(IllegalStateException ex) {
		return ResponseEntity
				.status(HttpStatus.CONFLICT) // 409
				.body(new ErrorResponse(HttpStatus.CONFLICT.value(), ex.getMessage()));
	}

	// 409 Conflict (동시 수정 발생 - 낙관적 락 충돌)
	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(ObjectOptimisticLockingFailureException ex) {
		log.warn("Optimistic Lock Conflict: ", ex);
		return ResponseEntity
				.status(HttpStatus.CONFLICT) // 409
				.body(new ErrorResponse(
						HttpStatus.CONFLICT.value(),
						"다른 사용자가 그 사이에 정보를 수정했습니다. 데이터를 새로고침한 후 다시 시도해 주세요."
				));
	}

	// 409 Conflict (중복 생성 - 애플리케이션 사전 검증 또는 DB UNIQUE 제약 위반)
	@ExceptionHandler(DuplicateResourceException.class)
	public ResponseEntity<ErrorResponse> handleDuplicateResource(DuplicateResourceException ex) {
		return ResponseEntity
			.status(HttpStatus.CONFLICT) // 409
			.body(new ErrorResponse(HttpStatus.CONFLICT.value(), ex.getMessage()));
	}

	// 409 Conflict (서비스에서 잡지 못하고 올라온 DB 제약 위반)
	// 사전 조회를 통과한 동시 요청이 DB UNIQUE 제약에서 걸러지는 경우가 대표적이다.
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
		log.warn("Data Integrity Violation: ", ex);
		return ResponseEntity
			.status(HttpStatus.CONFLICT) // 409
			.body(new ErrorResponse(HttpStatus.CONFLICT.value(), "이미 처리된 요청이거나 데이터 제약 조건을 위반했습니다."));
	}

	// 502 Bad Gateway (외부 API 연동 실패 - 우리 서버의 버그가 아니라 외부 의존 시스템 문제)
	@ExceptionHandler(ExternalApiException.class)
	public ResponseEntity<ErrorResponse> handleExternalApi(ExternalApiException ex) {
		log.error("External API Error: ", ex);
		return ResponseEntity
			.status(HttpStatus.BAD_GATEWAY) // 502
			.body(new ErrorResponse(HttpStatus.BAD_GATEWAY.value(), ex.getMessage()));
	}

	@ExceptionHandler(Exception.class) // 위에서 걸러지지 않은 모든 예외 처리
	public ResponseEntity<ErrorResponse> handleAllException(Exception ex) {
		// 로깅을 남겨서 서버 콘솔에서 개발자가 확인할 수 있게 함
		log.error("Unhandled Exception: ", ex);

		return ResponseEntity
			.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "서버 내부 오류가 발생했습니다. 관리자에게 문의하세요."));
	}

}
