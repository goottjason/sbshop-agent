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

class R2ImageStorageClientBlankCredentialsTest {

	@Test
	void uploadImagesFailsLoudlyWhenCredentialsAreBlank() {
		R2Properties blankProps = new R2Properties();
		blankProps.setEndpoint("http://localhost:1");
		blankProps.setAccessKey("");
		blankProps.setSecretKey("");
		blankProps.setBucket("bucket");
		blankProps.setPublicUrl("http://localhost:1/public");

		R2Config r2Config = new R2Config(blankProps);
		ObjectProvider<S3Client> lazyProvider = new LazyS3ClientProvider(r2Config);

		R2ImageStorageClient client = new R2ImageStorageClient(blankProps, lazyProvider);

		ImageUploadFile image = new ImageUploadFile(
			"a.jpg", "image/jpeg", new ByteArrayInputStream(new byte[] {1, 2, 3}), 3);

		assertThatThrownBy(() -> client.uploadImages(List.of(image)))
			.isInstanceOf(RuntimeException.class);
	}

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
