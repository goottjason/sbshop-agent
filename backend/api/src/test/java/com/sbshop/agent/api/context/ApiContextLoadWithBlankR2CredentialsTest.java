package com.sbshop.agent.api.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * D-020: R2 자격증명이 <b>비어 있어도</b> api 컨텍스트가 기동되는지 검증하는 스모크.
 *
 * <p>운영 서버 {@code .env}에 {@code CLOUDFLARE_R2_*}가 미설정이면 운영 {@code application.yml}의
 * 기본값이 빈 문자열({@code ${CLOUDFLARE_R2_ACCESS_KEY:}})로 바인딩된다. 결함 상태에서는
 * {@code R2Config.s3Client()}가 기동 시 즉시 {@code AwsBasicCredentials.create(blank)}를 호출해
 * {@code NullPointerException("Access key ID cannot be blank")}로 컨텍스트 로드가 실패했다
 * (api·worker 부팅 차단). 이 테스트는 blank 자격증명에서 컨텍스트가 완주함을 고정한다.
 *
 * <p>{@link ApiContextLoadSmokeTest}와의 차이: 그쪽은 더미 자격증명을 주입해 S3Client 즉시생성을
 * 통과시키므로 blank 케이스를 잡지 못한다. 두 스모크는 공존한다(더미-creds 정상 경로 + blank-creds
 * 내성).
 */
@Testcontainers
@SpringBootTest
class ApiContextLoadWithBlankR2CredentialsTest {

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
		// 핵심: R2 자격증명을 운영 미설정 상태(빈 문자열)로 재현. 결함이 있으면 여기서 기동 실패.
		registry.add("cloud.cloudflare.r2.endpoint", () -> "");
		registry.add("cloud.cloudflare.r2.access-key", () -> "");
		registry.add("cloud.cloudflare.r2.secret-key", () -> "");
		// 이메일 계정 플레이스홀더(운영 yml에 기본값 없음) 해소용 더미
		registry.add("EMAIL_USERNAME_1", () -> "test1@example.com");
		registry.add("EMAIL_PASSWORD_1", () -> "test-pass-1");
		registry.add("EMAIL_USERNAME_2", () -> "test2@example.com");
		registry.add("EMAIL_PASSWORD_2", () -> "test-pass-2");
	}

	@Autowired
	private ImageStorageClient imageStorageClient;

	@Test
	void contextLoadsEvenWhenR2CredentialsAreBlank() {
		// 컨텍스트가 여기까지 로드됐다는 것 자체가 성공: blank R2 자격증명에서도 api 부팅 완주.
		// 업로드 클라이언트 빈은 생성되어야 하나, 내부 S3Client는 아직 초기화되지 않은 상태여야 한다
		// (자격증명은 실제 업로드 호출 시점에만 필요).
		assertThat(imageStorageClient).isNotNull();
	}
}
