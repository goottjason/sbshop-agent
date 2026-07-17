package com.sbshop.agent.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 단일 백엔드 앱(2026-07-17 병합). 기존 api·worker 두 JVM을 하나로 합치면서, worker가 담당하던
 * 스케줄링을 이 앱이 맡는다(@EnableScheduling). worker의 스케줄러·이메일 수집·내부 트리거 빈은
 * worker 라이브러리 모듈에서 com.sbshop.agent 전역 스캔으로 로드된다.
 */
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.sbshop.agent")
@EnableJpaRepositories(basePackages = "com.sbshop.agent")
@EntityScan(basePackages = "com.sbshop.agent")
public class ApiApplication {
	public static void main(String[] args) {
		SpringApplication.run(ApiApplication.class, args);
	}
}
