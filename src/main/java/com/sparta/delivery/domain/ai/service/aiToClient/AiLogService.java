package com.sparta.delivery.domain.ai.service.aiToClient;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sparta.delivery.domain.ai.entity.AiDescriptionLog;
import com.sparta.delivery.domain.ai.repository.AiDescriptRepository;
import com.sparta.delivery.domain.menu.entity.Menu;
import com.sparta.delivery.domain.restaurant.entity.Restaurant;
import com.sparta.delivery.domain.user.entity.User;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AiLogService {

	// 프롬프트와 응답에는 사용자 입력이 그대로 섞여 들어올 수 있다.
	// 무제한으로 저장하면 저장 비용도 커지고, 민감 정보가 장기 보관될 위험도 커진다.
	private static final int MAX_TEXT_LENGTH = 2000;
	private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

	private final AiDescriptRepository logRepository;

	// REQUIRES_NEW: 호출한 쪽 트랜잭션이 롤백돼도 AI 요청 이력은 독립적으로 남는다.
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void saveLog(User owner, Restaurant restaurant, Menu menu, String prompt, String responseTxt,
		boolean isSuccess, String errorMessage) {

		String safePrompt = truncate(prompt, MAX_TEXT_LENGTH);

		AiDescriptionLog log = AiDescriptionLog.builder()
			.owner(owner)
			.restaurant(restaurant)
			.menu(menu)
			.prompt(safePrompt)
			.requestText(safePrompt)
			.responseText(truncate(responseTxt, MAX_TEXT_LENGTH))
			.isSuccess(isSuccess)
			.errorMessage(truncate(errorMessage, MAX_ERROR_MESSAGE_LENGTH))
			.build();

		logRepository.save(log);
	}

	private String truncate(String value, int maxLength) {
		if (value == null) {
			return null;
		}
		return value.length() <= maxLength ? value : value.substring(0, maxLength);
	}
}
