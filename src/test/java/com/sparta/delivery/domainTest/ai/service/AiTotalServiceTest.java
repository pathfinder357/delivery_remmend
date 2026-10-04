package com.sparta.delivery.domainTest.ai.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import com.sparta.delivery.domain.ai.service.aiToClient.AiLogService;
import com.sparta.delivery.domain.ai.service.aiToClient.AiService;
import com.sparta.delivery.domain.ai.service.aiToClient.AiTotalService;
import com.sparta.delivery.domain.menu.entity.Menu;
import com.sparta.delivery.domain.restaurant.entity.Restaurant;
import com.sparta.delivery.domain.user.entity.User;
import com.sparta.delivery.global.exception.ExternalApiException;

@ExtendWith(MockitoExtension.class)
class AiTotalServiceTest {

	@InjectMocks
	private AiTotalService aiTotalService;

	@Mock
	private AiService aiService;

	@Mock
	private AiLogService aiLogService;

	@Mock
	private RedisTemplate<String, String> redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	@Mock
	private User owner;

	@Mock
	private Restaurant restaurant;

	@Mock
	private Menu menu;

	private void givenLockAcquired(UUID menuId) {
		given(menu.getId()).willReturn(menuId);
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);
	}

	@Test
	@DisplayName("AI 설명 생성 성공 - 응답을 정제해서 반환하고 성공 로그를 남긴다")
	void generateMenuDescript_Success() {
		UUID menuId = UUID.randomUUID();
		givenLockAcquired(menuId);
		given(menu.getName()).willReturn("후라이드 치킨");
		given(aiService.requestToGemini(anyString())).willReturn("  바삭한   후라이드\n치킨입니다.  ");
		given(redisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any())).willReturn(1L);

		String result = aiTotalService.generateMenuDescript(owner, restaurant, menu);

		// 공백/개행이 정리되어 한 줄로 저장된다
		assertThat(result).isEqualTo("바삭한 후라이드 치킨입니다.");
		then(aiLogService).should().saveLog(eq(owner), eq(restaurant), eq(menu), anyString(),
			eq("바삭한 후라이드 치킨입니다."), eq(true), isNull());
	}

	@Test
	@DisplayName("AI 응답이 지나치게 길면 최대 길이로 잘라서 반환한다")
	void generateMenuDescript_TruncatesTooLongResponse() {
		UUID menuId = UUID.randomUUID();
		givenLockAcquired(menuId);
		given(menu.getName()).willReturn("후라이드 치킨");
		given(aiService.requestToGemini(anyString())).willReturn("가".repeat(300));
		given(redisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any())).willReturn(1L);

		String result = aiTotalService.generateMenuDescript(owner, restaurant, menu);

		assertThat(result).hasSize(100);
	}

	@Test
	@DisplayName("AI 응답이 비어 있으면 ExternalApiException 을 던지고 실패 로그를 남긴다")
	void generateMenuDescript_Fail_BlankResponse() {
		UUID menuId = UUID.randomUUID();
		givenLockAcquired(menuId);
		given(menu.getName()).willReturn("후라이드 치킨");
		given(aiService.requestToGemini(anyString())).willReturn("   ");
		given(redisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any())).willReturn(1L);

		assertThatThrownBy(() -> aiTotalService.generateMenuDescript(owner, restaurant, menu))
			.isInstanceOf(ExternalApiException.class);

		then(aiLogService).should().saveLog(eq(owner), eq(restaurant), eq(menu), anyString(),
			isNull(), eq(false), anyString());
	}

	@Test
	@DisplayName("이미 같은 메뉴의 락이 잡혀 있으면 외부 API를 호출하지 않는다")
	void generateMenuDescript_Fail_LockNotAcquired() {
		given(menu.getId()).willReturn(UUID.randomUUID());
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(false);

		assertThatThrownBy(() -> aiTotalService.generateMenuDescript(owner, restaurant, menu))
			.isInstanceOf(IllegalStateException.class);

		then(aiService).shouldHaveNoInteractions();
		then(aiLogService).shouldHaveNoInteractions();
		// 락을 잡지 못했으므로 해제(삭제) 시도도 하면 안 된다
		then(redisTemplate).should(never())
			.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any());
	}

	@Test
	@DisplayName("락 해제는 자신이 저장한 토큰과 일치할 때만 삭제하는 Lua 스크립트로 수행된다")
	void releaseLock_UsesOwnershipCheckedScript() {
		UUID menuId = UUID.randomUUID();
		givenLockAcquired(menuId);
		given(menu.getName()).willReturn("후라이드 치킨");
		given(aiService.requestToGemini(anyString())).willReturn("맛있는 치킨");
		given(redisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any())).willReturn(1L);

		aiTotalService.generateMenuDescript(owner, restaurant, menu);

		ArgumentCaptor<String> tokenOnSet = ArgumentCaptor.forClass(String.class);
		then(valueOperations).should()
			.setIfAbsent(eq("ai:lock:menu:" + menuId), tokenOnSet.capture(), any(Duration.class));

		ArgumentCaptor<List> keysCaptor = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.forClass(Object.class);
		then(redisTemplate).should()
			.execute(ArgumentMatchers.<RedisScript<Long>>any(), keysCaptor.capture(), argsCaptor.capture());

		// 해제 시 넘기는 값이 "획득 시 저장한 토큰"과 동일해야 남의 락을 지우지 않는다
		assertThat(keysCaptor.getValue()).containsExactly("ai:lock:menu:" + menuId);
		assertThat(argsCaptor.getValue()).isEqualTo(tokenOnSet.getValue());
	}

	@Test
	@DisplayName("락이 이미 만료되어 스크립트가 0을 반환해도 정상 응답은 그대로 반환된다")
	void releaseLock_LeaseAlreadyExpired() {
		UUID menuId = UUID.randomUUID();
		givenLockAcquired(menuId);
		given(menu.getName()).willReturn("후라이드 치킨");
		given(aiService.requestToGemini(anyString())).willReturn("맛있는 치킨");
		// 0 = "내 토큰이 아니라서 아무것도 지우지 않았다"
		given(redisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any())).willReturn(0L);

		String result = aiTotalService.generateMenuDescript(owner, restaurant, menu);

		assertThat(result).isEqualTo("맛있는 치킨");
	}
}
