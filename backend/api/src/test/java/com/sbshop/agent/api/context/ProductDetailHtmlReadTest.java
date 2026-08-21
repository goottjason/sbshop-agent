package com.sbshop.agent.api.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.product.Product;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class ProductDetailHtmlReadTest {

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

	@Autowired
	private EntityManager entityManager;

	@Test
	@Transactional
	void shouldReadProductWhoseDetailHtmlColumnContainsHtmlText() {
		String html = "<img src=\"http://example.com/top.png\"><br /><b>테스트 상세</b>";
		jdbcTemplate.update(
			"INSERT INTO sb_product (id, sb_code, product_name, status, stock, detail_html) "
				+ "VALUES (999999, 'TEST-D021', '타트체리 재현 상품', 'ACTIVE', 0, ?)",
			html);

		Product loaded = entityManager.find(Product.class, 999999L);

		assertThat(loaded).isNotNull();
		assertThat(loaded.getDetailHtml()).isEqualTo(html);
	}
}
