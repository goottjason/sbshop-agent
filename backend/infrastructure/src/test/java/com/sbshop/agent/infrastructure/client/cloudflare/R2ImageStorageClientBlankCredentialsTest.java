package com.sbshop.agent.infrastructure.client.cloudflare;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import com.sbshop.agent.infrastructure.client.cloudflare.config.R2Config;
import com.sbshop.agent.infrastructure.client.cloudflare.config.R2Properties;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * D-020: R2 자격증명이 없어도 부팅은 되지만(→ {@code ApiContextLoadWithBlankR2CredentialsTest}),
 * 이미지 업로드를 <b>실제로 호출</b>하면 조용한 no-op이 아니라 명확한 실패로 끝나야 한다는 계약을 고정한다.
 *
 * <p>{@link R2ImageStorageClient}는 {@link S3Client}를 {@link ObjectProvider}로 지연 해석한다. 업로드
 * 시점에 실 {@link R2Config#s3Client()} 생성을 시도하면 blank 자격증명에서 AWS SDK가
 * {@code AwsBasicCredentials} 검증으로 예외를 던지고, 이 예외가 호출자에게 전파된다.
 */
class R2ImageStorageClientBlankCredentialsTest {

	@Test
	void uploadImagesFailsLoudlyWhenCredentialsAreBlank() {
		R2Properties blankProps = new R2Properties();
		blankProps.setEndpoint("http://localhost:1");
		blankProps.setAccessKey("");
		blankProps.setSecretKey("");
		blankProps.setBucket("bucket");
		blankProps.setPublicUrl("http://localhost:1/public");

		// 실 R2Config 생성 경로를 사용 시점에 해석하는 ObjectProvider (스프링 @Lazy 결선과 동일 동작).
		R2Config r2Config = new R2Config(blankProps);
		ObjectProvider<S3Client> lazyProvider = new LazyS3ClientProvider(r2Config);

		R2ImageStorageClient client = new R2ImageStorageClient(blankProps, lazyProvider);

		ImageUploadFile image = new ImageUploadFile(
			"a.jpg", "image/jpeg", new ByteArrayInputStream(new byte[] {1, 2, 3}), 3);

		// 조용한 no-op이 아니라 예외로 실패해야 한다(자격증명 blank → S3Client 생성 시 검증 실패).
		assertThatThrownBy(() -> client.uploadImages(List.of(image)))
			.isInstanceOf(RuntimeException.class);
	}

	/** 사용 시점(getObject)에만 실 S3Client 생성을 시도하는 최소 ObjectProvider 구현. */
	private record LazyS3ClientProvider(R2Config r2Config) implements ObjectProvider<S3Client> {
		@Override
		public S3Client getObject() {
			return r2Config.s3Client();
		}

		@Override
		public S3Client getObject(Object... args) {
			return getObject();
		}

		@Override
		public S3Client getIfAvailable() {
			return getObject();
		}

		@Override
		public S3Client getIfUnique() {
			return getObject();
		}
	}
}
