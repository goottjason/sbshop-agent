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
 * <p>운영 {@code application.yml}로 스프링 컨텍스트가 실제 Postgres에서 정상 로드되는지 검증한다.
 * 스키마는 수동 관리(별도 마이그레이션 도구 없음)로 전환됐으므로 이 스모크는 마이그레이션 이력에
 * 의존하지 않는다. 대신 <b>테스트 한정</b>으로 {@code hibernate.ddl-auto=create-drop}을 주입해 엔티티
 * 매핑에서 스키마를 생성한 뒤 컨텍스트를 기동한다(운영은 {@code ddl-auto: none} 불변).
 *
 * <p>검증 요지: (1) 운영 yml + 엔티티 스캔이 실 Postgres에서 컨텍스트를 완주하는지, (2)
 * {@code Cafe24TokenManager} {@code @PostConstruct}가 <b>비어 있는</b> {@code sb_market_credential}
 * 조회를 예외 없이 견디는지(빈 DB 기동 내성). 외부 클라이언트 빈(R2 {@code S3Client} 등)은 더미
 * 자격증명으로 즉시생성만 통과시킨다(실 호출 없음).
 */
@Testcontainers
@SpringBootTest
class ApiContextLoadSmokeTest {

	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
		registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
		// 테스트 전용: 엔티티 매핑으로 스키마 생성(운영은 수동 스키마). 운영 ddl-auto: none 불변.
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
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
	void contextLoadsOnRealPostgresWithEntityGeneratedSchema() {
		// 컨텍스트가 여기까지 로드됐다는 것 자체가 성공: 운영 yml로 실 Postgres 기동 +
		// Cafe24TokenManager @PostConstruct가 빈 sb_market_credential 조회를 견딤.
		// 엔티티 기반 스키마가 실제로 생성됐는지 대표 테이블로 확인한다.
		Integer orderTable = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'sb_order'",
			Integer.class);
		assertThat(orderTable).isEqualTo(1);

		// D-005 정합성 흡수: market_specific_data가 엔티티 매핑(Order.java columnDefinition="TEXT")
		// 파생으로 생성되고 타입이 text로 수렴하는지 확인(구 V5가 보장하던 불변식을 스키마 생성이 유지).
		String mktDataType = jdbcTemplate.queryForObject(
			"SELECT data_type FROM information_schema.columns "
				+ "WHERE table_name = 'sb_order' AND column_name = 'market_specific_data'",
			String.class);
		assertThat(mktDataType).isEqualTo("text");

		// Cafe24TokenManager가 조회하는 테이블도 엔티티 매핑에서 생성되어 존재해야 한다(빈 조회 내성 근거).
		Integer credentialTable = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'sb_market_credential'",
			Integer.class);
		assertThat(credentialTable).isEqualTo(1);
		Integer credentialRows = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM sb_market_credential", Integer.class);
		assertThat(credentialRows).isZero();
	}
}
