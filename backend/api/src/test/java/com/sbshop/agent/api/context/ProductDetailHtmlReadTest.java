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

/**
 * D-021: {@code Product.detailHtml}의 {@code @Lob} 매핑이 실 PostgreSQL에서 조회를 파괴하는 결함 재현.
 *
 * <p>운영 실측(2026-07-07): {@code text} 타입 컬럼 detail_html에 HTML이 저장돼 있는데, {@code @Lob}
 * String을 Hibernate가 PostgreSQL Large Object(OID)로 취급해 {@code getLong()}으로 읽으려다
 * {@code Bad value for type long : <img ...>}로 실패 — 주문/상품 조회 API 전체 500. H2 테스트는 @Lob을
 * CLOB 텍스트로 처리해 이 결함을 탐지하지 못한다(H2 ≠ PostgreSQL 함정).
 *
 * <p>이 테스트는 운영과 동일 조건(text 컬럼 + HTML 데이터)을 실 Postgres에 구성하고 JPA 엔티티
 * 로드가 성공하는지 검증한다.
 */
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
		// 운영 데이터와 동일 조건: text 컬럼에 HTML 문자열이 저장된 상태를 JDBC로 구성
		jdbcTemplate.update(
			"INSERT INTO sb_product (id, sb_code, product_name, status, stock, detail_html) "
				+ "VALUES (999999, 'TEST-D021', '타트체리 재현 상품', 'ACTIVE', 0, ?)",
			html);

		Product loaded = entityManager.find(Product.class, 999999L);

		assertThat(loaded).isNotNull();
		assertThat(loaded.getDetailHtml()).isEqualTo(html);
	}
}
