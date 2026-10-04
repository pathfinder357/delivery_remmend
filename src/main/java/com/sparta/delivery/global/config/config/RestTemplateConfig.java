package com.sparta.delivery.global.config.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

	// 외부 AI(Gemini) 호출에 사용하는 RestTemplate.
	// 기본 생성자(new RestTemplate())는 타임아웃이 "무제한"이다.
	// 외부 API가 응답하지 않으면 톰캣 워커 스레드가 그대로 묶여서
	// AI와 무관한 일반 API까지 함께 느려지므로 반드시 타임아웃을 지정한다.
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

	@Bean
	public RestTemplate restTemplate() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(CONNECT_TIMEOUT); // TCP 연결 수립 대기 한도
		factory.setReadTimeout(READ_TIMEOUT);       // 응답 바디 수신 대기 한도
		return new RestTemplate(factory);
	}
}
