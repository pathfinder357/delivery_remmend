package com.sparta.delivery.domain.ai.service.aiToClient;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.sparta.delivery.domain.ai.dto.Gemini.GeminiRequestDto;
import com.sparta.delivery.domain.ai.dto.Gemini.GeminiResponseDto;
import com.sparta.delivery.global.exception.ExternalApiException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j(topic = "AI Connect Api")
@Service
@RequiredArgsConstructor
// 외부 API와의 통신만 담당하므로 @Transactional을 두지 않는다.
// (DB 트랜잭션 안에서 외부 호출을 기다리면 커넥션을 오래 점유하게 된다)
public class AiService {

	private static final String GEMINI_API_URL =
		"https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key=";

	// 프롬프트에 사용자 입력이 섞여 들어올 수 있으므로 로그는 길이를 제한해서 남긴다.
	private static final int MAX_PROMPT_LOG_LENGTH = 200;

	private final RestTemplate restTemplate;

	@Value("${google.gemini.api-key}")
	private String geminiApiKey;

	public String requestToGemini(String prompt) {

		// URL에 API 키가 포함되므로 요청 URL 자체는 절대 로그로 남기지 않는다.
		log.info("Gemini API 호출 - prompt={}", abbreviate(prompt));

		GeminiRequestDto.Part part = new GeminiRequestDto.Part(prompt);
		GeminiRequestDto.Content content = new GeminiRequestDto.Content(List.of(part));
		GeminiRequestDto requestDto = new GeminiRequestDto(List.of(content));

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

		HttpEntity<GeminiRequestDto> request = new HttpEntity<>(requestDto, headers);
		String requestUrl = GEMINI_API_URL + geminiApiKey;

		try {
			ResponseEntity<GeminiResponseDto> response =
				restTemplate.postForEntity(requestUrl, request, GeminiResponseDto.class);
			return extractTextFromResponse(response.getBody());

		} catch (ResourceAccessException e) {
			// 커넥션/리드 타임아웃, 네트워크 오류. RestClientException 의 하위 타입이므로 먼저 잡는다.
			log.error("Gemini API 응답 지연 또는 네트워크 오류", e);
			throw new ExternalApiException("AI 서버 응답이 지연되어 메뉴 설명 생성에 실패했습니다.", e);

		} catch (RestClientException e) {
			// 4xx/5xx 응답, 역직렬화 실패 등
			log.error("Gemini API 호출 실패", e);
			throw new ExternalApiException("AI 메뉴 설명 생성에 실패했습니다.", e);
		}
	}

	private String extractTextFromResponse(GeminiResponseDto body) {
		if (body == null || body.getCandidates() == null || body.getCandidates().isEmpty()) {
			throw new ExternalApiException("AI가 응답을 반환하지 않았습니다.");
		}

		GeminiResponseDto.Candidate candidate = body.getCandidates().get(0);
		if (candidate == null
			|| candidate.getContent() == null
			|| candidate.getContent().getParts() == null
			|| candidate.getContent().getParts().isEmpty()) {
			throw new ExternalApiException("AI 응답 형식이 올바르지 않습니다.");
		}

		String text = candidate.getContent().getParts().get(0).getText();
		if (text == null || text.isBlank()) {
			throw new ExternalApiException("AI 응답이 비어 있습니다.");
		}
		return text.trim();
	}

	private String abbreviate(String value) {
		if (value == null) {
			return null;
		}
		return value.length() <= MAX_PROMPT_LOG_LENGTH
			? value
			: value.substring(0, MAX_PROMPT_LOG_LENGTH) + "...(생략)";
	}
}
