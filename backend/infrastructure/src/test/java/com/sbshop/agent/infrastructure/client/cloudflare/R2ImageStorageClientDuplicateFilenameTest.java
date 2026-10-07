package com.sbshop.agent.infrastructure.client.cloudflare;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import com.sbshop.agent.infrastructure.client.cloudflare.config.R2Properties;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class R2ImageStorageClientDuplicateFilenameTest {

	@Test
	void duplicateOriginalFilenamesKeepEveryUploadedImageInInputOrder() {
		S3Client s3 = mock(S3Client.class);
		@SuppressWarnings("unchecked") ObjectProvider<S3Client> provider = mock(ObjectProvider.class);
		when(provider.getObject()).thenReturn(s3);
		R2Properties properties = new R2Properties();
		properties.setBucket("test-bucket");
		properties.setPublicUrl("https://images.example");
		var storage = new R2ImageStorageClient(properties, provider);

		var result = storage.uploadImages(List.of(image("photo.jpg"), image("photo.jpg"), image("other.jpg")));

		var requests = ArgumentCaptor.forClass(PutObjectRequest.class);
		verify(s3, times(3)).putObject(requests.capture(), any(RequestBody.class));
		assertThat(result.values()).containsExactlyElementsOf(requests.getAllValues().stream()
			.map(request -> "https://images.example/" + request.key()).toList());
	}

	private ImageUploadFile image(String filename) {
		return new ImageUploadFile(filename, "image/jpeg", new ByteArrayInputStream(new byte[] {1}), 1);
	}
}
