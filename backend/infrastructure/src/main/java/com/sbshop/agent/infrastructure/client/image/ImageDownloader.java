package com.sbshop.agent.infrastructure.client.image;

import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class ImageDownloader implements ImageDownloadClient {

	private final RestTemplate restTemplate = new RestTemplate();

	@Override
	public List<ImageUploadFile> downloadAll(List<String> imageUrls) {
		return imageUrls.stream()
				.filter(url -> url != null && !url.isBlank())
				.map(this::download)
				.filter(Optional::isPresent)
				.map(Optional::get)
				.toList();
	}

	@Override
	public Optional<ImageUploadFile> download(String imageUrl) {
		try {
			log.info("원본 이미지 다운로드 중: {}", imageUrl);

			ResponseEntity<byte[]> response = restTemplate.exchange(
					URI.create(imageUrl),
					HttpMethod.GET,
					null,
					byte[].class);

			byte[] imageBytes = response.getBody();
			if (imageBytes == null || imageBytes.length == 0) {
				log.warn("다운로드 실패: 빈 이미지 데이터 ({})", imageUrl);
				return Optional.empty();
			}

			String contentType = Objects.requireNonNull(response.getHeaders().getContentType()).toString();
			String filename = extractFilenameFromUrl(imageUrl);

			ImageUploadFile uploadFile = new ImageUploadFile(
					filename,
					contentType,
					new ByteArrayInputStream(imageBytes),
					imageBytes.length);

			return Optional.of(uploadFile);
		} catch (Exception e) {
			log.error("이미지 다운로드 중 오류 발생: {}", imageUrl, e);
			return Optional.empty();
		}
	}

	private String extractFilenameFromUrl(String url) {
		String path = url;
		int queryIndex = path.indexOf('?');
		if (queryIndex != -1) {
			path = path.substring(0, queryIndex);
		}
		int lastSlashIndex = path.lastIndexOf('/');
		if (lastSlashIndex != -1) {
			return path.substring(lastSlashIndex + 1);
		}
		return "unknown_image.jpg";
	}
}
