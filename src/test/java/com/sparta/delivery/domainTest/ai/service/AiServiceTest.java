package com.sparta.delivery.domainTest.ai.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.net.SocketTimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.sparta.delivery.domain.ai.dto.Gemini.GeminiResponseDto;
import com.sparta.delivery.domain.ai.service.aiToClient.AiService;
import com.sparta.delivery.global.exception.ExternalApiException;

@ExtendWith(MockitoExtension.class)
class AiServiceTest {

	@InjectMocks
	private AiService aiService;

	@Mock
	private RestTemplate restTemplate;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(aiService, "geminiApiKey", "test-key");
	}

	@Test
	@DisplayName("타임아웃(ResourceAccessException)은 ExternalApiException 으로 변환된다")
	void requestToGemini_Timeout() {
		given(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(GeminiResponseDto.class)))
			.willThrow(new ResourceAccessException("read timed out", new SocketTimeoutException()));

		assertThatThrownBy(() -> aiService.requestToGemini("prompt"))
			.isInstanceOf(ExternalApiException.class)
			.hasMessageContaining("응답이 지연");
	}

	@Test
	@DisplayName("외부 API 5xx 응답도 ExternalApiException 으로 변환된다")
	void requestToGemini_ServerError() {
		given(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(GeminiResponseDto.class)))
			.willThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThatThrownBy(() -> aiService.requestToGemini("prompt"))
			.isInstanceOf(ExternalApiException.class);
	}

	@Test
	@DisplayName("응답 본문이 비어 있으면 ExternalApiException 을 던진다")
	void requestToGemini_EmptyBody() {
		given(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(GeminiResponseDto.class)))
			.willReturn(ResponseEntity.ok((GeminiResponseDto)null));

		assertThatThrownBy(() -> aiService.requestToGemini("prompt"))
			.isInstanceOf(ExternalApiException.class);
	}
}
