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
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
		registry.add("cloud.cloudflare.r2.endpoint", () -> "http://localhost:1");
		registry.add("cloud.cloudflare.r2.access-key", () -> "test-access-key");
		registry.add("cloud.cloudflare.r2.secret-key", () -> "test-secret-key");
		registry.add("EMAIL_USERNAME_1", () -> "test1@example.com");
		registry.add("EMAIL_PASSWORD_1", () -> "test-pass-1");
		registry.add("EMAIL_USERNAME_2", () -> "test2@example.com");
		registry.add("EMAIL_PASSWORD_2", () -> "test-pass-2");
	}

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void contextLoadsOnRealPostgresWithEntityGeneratedSchema() {
		Integer orderTable = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'sb_order'",
			Integer.class);
		assertThat(orderTable).isEqualTo(1);

		String mktDataType = jdbcTemplate.queryForObject(
			"SELECT data_type FROM information_schema.columns "
				+ "WHERE table_name = 'sb_order' AND column_name = 'market_specific_data'",
			String.class);
		assertThat(mktDataType).isEqualTo("text");

		Integer credentialTable = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'sb_market_credential'",
			Integer.class);
		assertThat(credentialTable).isEqualTo(1);
		Integer credentialRows = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM sb_market_credential", Integer.class);
		assertThat(credentialRows).isZero();
	}
}
