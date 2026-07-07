package com.sbshop.agent.infrastructure.client.cloudflare;

import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import com.sbshop.agent.infrastructure.client.cloudflare.config.R2Properties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Slf4j
@Component
@RequiredArgsConstructor
public class R2ImageStorageClient implements ImageStorageClient {

	private final R2Properties r2Properties;
	// @Lazy S3Client를 사용 시점에 해석한다(D-020). 자격증명이 없으면 여기서 명확히 실패하고,
	// 업로드를 호출하지 않는 다른 기능은 기동·동작에 영향받지 않는다.
	private final ObjectProvider<S3Client> s3ClientProvider;

	@Override
	public Map<String, String> uploadImages(List<ImageUploadFile> images) {
		Map<String, String> uploadedUrlMap = new LinkedHashMap<>();
		S3Client s3Client = s3ClientProvider.getObject();

		for (ImageUploadFile file : images) {
			String originalFilename = file.originalFilename();
			String extension = originalFilename != null && originalFilename.contains(".")
				? originalFilename.substring(originalFilename.lastIndexOf("."))
				: "";
			String fileName = UUID.randomUUID().toString() + extension;

			try {
				PutObjectRequest putObjectRequest = PutObjectRequest.builder()
					.bucket(r2Properties.getBucket())
					.key(fileName)
					.contentType(file.contentType())
					.build();

				s3Client.putObject(
					putObjectRequest,
					RequestBody.fromInputStream(file.inputStream(), file.size()));

				String publicUrl = r2Properties.getPublicUrl() + "/" + fileName;
				uploadedUrlMap.put(originalFilename, publicUrl);
			} catch (Exception e) {
				log.error("R2 업로드 실패: {}", originalFilename, e);
				throw new RuntimeException("이미지 업로드 중 서버 오류가 발생했습니다.", e);
			}
		}
		return uploadedUrlMap;
	}
}
