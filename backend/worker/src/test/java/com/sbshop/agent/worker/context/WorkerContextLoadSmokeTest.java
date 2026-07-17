package com.sbshop.agent.worker.context;

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
 * worker {@code @SpringBootTest} 컨텍스트 로드 스모크 (실 PostgreSQL / testcontainers).
 *
 * <p>운영 {@code application.yml}이 공유 설정({@code sbshop-common.yml})을
 * {@code spring.config.import}로 불러와 datasource·jpa·이메일 계정이 정상 바인딩되고,
 * worker 컨텍스트({@code com.sbshop.agent} 전역 스캔 — 인프라·스케줄러 빈 포함)가 실 Postgres에서
 * 완주하는지 검증한다(설정 통합 리팩토링 안전망).
 *
 * <p>테스트 한정으로 {@code ddl-auto=create-drop}을 주입해 엔티티 매핑에서 스키마를 만든다(운영 불변).
 * 외부 클라이언트 빈(R2 등)은 더미 자격증명으로 즉시생성만 통과시킨다(실 호출 없음).
 */
@Testcontainers
@SpringBootTest
class WorkerContextLoadSmokeTest {

	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
		registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
		// 외부 클라이언트 빈 즉시생성 통과용 더미 (실 자격증명·실 호출 없음)
		registry.add("cloud.cloudflare.r2.endpoint", () -> "http://localhost:1");
		registry.add("cloud.cloudflare.r2.access-key", () -> "test-access-key");
		registry.add("cloud.cloudflare.r2.secret-key", () -> "test-secret-key");
		// 공유 설정(sbshop-common.yml)의 이메일 계정 플레이스홀더 해소용 더미
		registry.add("EMAIL_USERNAME_1", () -> "test1@example.com");
		registry.add("EMAIL_PASSWORD_1", () -> "test-pass-1");
		registry.add("EMAIL_USERNAME_2", () -> "test2@example.com");
		registry.add("EMAIL_PASSWORD_2", () -> "test-pass-2");
	}

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private com.sbshop.agent.core.config.EmailAccountProperties emailAccountProperties;

	@Test
	void contextLoadsAndSharedEmailConfigBinds() {
		// 컨텍스트가 여기까지 로드됐다는 것 자체가 성공: 공유 설정 임포트로 worker가 실 Postgres에서 기동.
		Integer orderTable = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'sb_order'",
			Integer.class);
		assertThat(orderTable).isEqualTo(1);

		// 공유 파일의 sbshop.email.accounts가 worker에서도 정상 바인딩(중복 제거 후에도 계정 유지)
		assertThat(emailAccountProperties.getAccounts()).isNotEmpty();
		assertThat(emailAccountProperties.getAccounts().get(0).getUsername()).isEqualTo("test1@example.com");
	}
}
