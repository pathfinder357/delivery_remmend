package com.sparta.delivery.domain.ai.service.aiToClient;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import com.sparta.delivery.domain.menu.entity.Menu;
import com.sparta.delivery.domain.restaurant.entity.Restaurant;
import com.sparta.delivery.domain.user.entity.User;
import com.sparta.delivery.global.exception.ExternalApiException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiTotalService { // facade 패턴

	private static final String LOCK_KEY_PREFIX = "ai:lock:menu:";

	// 락 유효시간(lease time)은 반드시 "외부 호출이 최대로 걸릴 수 있는 시간"보다 길어야 한다.
	// RestTemplate 타임아웃(connect 3s + read 10s)보다 넉넉하게 잡아
	// 작업이 끝나기 전에 락이 먼저 만료되는 상황 자체를 줄인다.
	private static final Duration LOCK_LEASE_TIME = Duration.ofSeconds(30);

	// AI가 생성한 설명의 최대 길이 (프롬프트로 50자를 요청하지만 모델이 지킨다는 보장은 없다)
	private static final int MAX_DESCRIPTION_LENGTH = 100;

	// 락 해제는 "값 비교 후 삭제"가 하나의 원자적 연산이어야 한다.
	// GET -> 비교 -> DEL 을 자바 코드로 나눠 쓰면 그 사이에 락이 만료되고
	// 다른 요청이 같은 키로 락을 새로 잡을 수 있어서, 남의 락을 지우게 된다.
	private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
		"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
		Long.class);

	private final AiService aiService;
	private final RedisTemplate<String, String> redisTemplate;
	private final AiLogService aiLogService;

	public String generateMenuDescript(User owner, Restaurant restaurant, Menu menu) {

		String lockKey = LOCK_KEY_PREFIX + menu.getId();
		// 요청마다 고유한 토큰을 락의 "값"으로 저장한다 -> 락의 소유자를 식별할 수 있다.
		String lockToken = UUID.randomUUID().toString();

		acquireLock(lockKey, lockToken);

		String prompt = createPrompt(menu.getName());
		try {
			String responseTxt = sanitizeDescription(aiService.requestToGemini(prompt));
			aiLogService.saveLog(owner, restaurant, menu, prompt, responseTxt, true, null);
			return responseTxt;

		} catch (Exception e) {
			// 실패 이력도 반드시 남긴다. (AiLogService 는 REQUIRES_NEW 라 이 흐름이 롤백돼도 로그는 남는다)
			aiLogService.saveLog(owner, restaurant, menu, prompt, null, false, e.getMessage());
			throw e;

		} finally {
			releaseLock(lockKey, lockToken);
		}
	}

	private void acquireLock(String lockKey, String lockToken) {
		Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, lockToken, LOCK_LEASE_TIME);
		if (!Boolean.TRUE.equals(acquired)) {
			// 409 Conflict 로 변환된다 (GlobalExceptionHandler)
			throw new IllegalStateException("현재 해당 메뉴의 AI 설명을 생성 중입니다. 잠시 후 다시 시도해 주세요.");
		}
	}

	private void releaseLock(String lockKey, String lockToken) {
		Long released = redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), lockToken);

		if (released == null || released == 0L) {
			// 내 락이 이미 만료됐거나 다른 요청이 소유 중이라는 뜻.
			// 이 경우 "아무것도 지우지 않는 것"이 정답이므로 삭제하지 않고 경고만 남긴다.
			log.warn("AI 락 해제 생략 - 이미 만료되었거나 다른 요청이 소유한 락입니다. key={}", lockKey);
		}
	}

	private String createPrompt(String menuName) {
		return String.format(
			"넌 지금부터 배달 앱이야. 메뉴 이름이 %s인 음식의 맛있어 보이는 설명을 50자 이내로 작성해. 설명 문장만 출력해.", menuName);
	}

	// 외부 AI의 응답은 "신뢰할 수 없는 입력"으로 취급한다.
	// 길이/형식을 검증하지 않으면 DB 컬럼이나 화면 레이아웃을 그대로 망가뜨릴 수 있다.
	private String sanitizeDescription(String rawText) {
		if (rawText == null || rawText.isBlank()) {
			throw new ExternalApiException("AI가 유효한 메뉴 설명을 생성하지 못했습니다.");
		}

		String text = rawText.replace("```", " ")
			.replaceAll("\\s+", " ")
			.trim();

		if (text.isEmpty()) {
			throw new ExternalApiException("AI가 유효한 메뉴 설명을 생성하지 못했습니다.");
		}

		return text.length() > MAX_DESCRIPTION_LENGTH
			? text.substring(0, MAX_DESCRIPTION_LENGTH)
			: text;
	}
}
