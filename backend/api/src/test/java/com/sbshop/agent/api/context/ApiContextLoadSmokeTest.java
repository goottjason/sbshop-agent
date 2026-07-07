package com.sbshop.agent.api.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * D-013: api {@code @SpringBootTest} 컨텍스트 로드 스모크 (실 PostgreSQL / testcontainers).
 *
 * <p>운영 {@code application.yml} + Flyway 마이그레이션(V1~V5)이 실제 Postgres에서 기동되고 스프링
 * 컨텍스트가 정상 로드되는지 검증한다. 외부 클라이언트 빈(R2 {@code S3Client} 등)은 더미 자격증명으로
 * 즉시생성만 통과시킨다(실 호출 없음).
 *
 * <p>Red 실측(사이클 4): 현재 설정의 {@code spring.jpa.defer-datasource-initialization: true} +
 * Flyway 조합에서 {@code flyway}↔{@code entityManagerFactory} 순환 depends-on으로 컨텍스트 로드
 * 실패 → 운영 기동 차단(P0) 확정. Green: defer/sql.init 제거 + after-migrate.sql의 V5 흡수.
 */
@Testcontainers
@SpringBootTest
class ApiContextLoadSmokeTest {

	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
		// [가정: "운영 유사 상태"] 이 seed는 진짜 빈 DB가 아니라 "운영은 Flyway 도입 이전 스키마를
		// 가진 비어있지-않은 DB였다"는 가정을 재현한다. 그래야 V1~V5가 운영과 동일하게 적용된다.
		// 이 가정이 필요한 이유 = 별개 결함: D-015(V1~V4가 빈 DB에서 비자족 — V2 shipping_fee),
		// D-016(sb_market_credential/sb_market_registration 마이그레이션 부재). 둘 다 이 배치 범위 밖.
		// 리더 승인된 정당한 우회(침묵 가정 아님).
		.withInitScript("legacy/pre_flyway_baseline.sql");

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
		registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
		// 테스트 전용: 위 baseline 스크립트로 스키마가 비어있지 않으므로 Flyway가 baseline을 요구.
		// baseline-version=0으로 두어 V1~V5가 모두 실행되게 한다(운영은 이미 history 테이블 존재 → 무관).
		registry.add("spring.flyway.baseline-on-migrate", () -> "true");
		registry.add("spring.flyway.baseline-version", () -> "0");
		// 외부 클라이언트 빈 즉시생성 통과용 더미 (실 자격증명·실 호출 없음)
		registry.add("cloud.cloudflare.r2.endpoint", () -> "http://localhost:1");
		registry.add("cloud.cloudflare.r2.access-key", () -> "test-access-key");
		registry.add("cloud.cloudflare.r2.secret-key", () -> "test-secret-key");
		// 이메일 계정 플레이스홀더(운영 yml에 기본값 없음) 해소용 더미
		registry.add("EMAIL_USERNAME_1", () -> "test1@example.com");
		registry.add("EMAIL_PASSWORD_1", () -> "test-pass-1");
		registry.add("EMAIL_USERNAME_2", () -> "test2@example.com");
		registry.add("EMAIL_PASSWORD_2", () -> "test-pass-2");
	}

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void contextLoadsAndMigrationsApplied() {
		// 컨텍스트가 여기까지 로드됐다는 것 자체가 성공(순환 depends-on 해소 확인).
		Integer applied = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM flyway_schema_history WHERE success = true", Integer.class);
		assertThat(applied).isGreaterThanOrEqualTo(5); // V1~V5 전부 적용

		// D-005 델타: after-migrate.sql에만 있던 market_specific_data가 V5로 흡수되어 존재해야 함
		Integer col = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM information_schema.columns "
				+ "WHERE table_name = 'sb_order' AND column_name = 'market_specific_data'",
			Integer.class);
		assertThat(col).isEqualTo(1);
	}
}
