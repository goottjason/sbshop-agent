package com.sbshop.agent.infrastructure.client.image;

import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
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
					URI.create(imageUrl), HttpMethod.GET, null, byte[].class);
			byte[] imageBytes = response.getBody();
			if (imageBytes == null || imageBytes.length == 0) {
				log.warn("다운로드 실패: 빈 이미지 데이터 ({})", imageUrl);
				return Optional.empty();
			}
			String contentType = Objects.requireNonNull(response.getHeaders().getContentType()).toString();
			String filename = extractFilenameFromUrl(imageUrl);
			ImageUploadFile uploadFile = new ImageUploadFile(
					filename, contentType, new ByteArrayInputStream(imageBytes), imageBytes.length);
			return Optional.of(uploadFile);
		} catch (Exception e) {
			log.error("이미지 다운로드 중 오류 발생: {}", imageUrl, e);
			return Optional.empty();
		}
	}

	@Override
	public List<ImageUploadFile> downloadAndConvert(List<String> imageUrls) {
		List<ImageUploadFile> results = new ArrayList<>();
		for (int i = 0; i < imageUrls.size(); i++) {
			String url = imageUrls.get(i);
			try {
				Optional<ImageUploadFile> rawFile = download(url);
				if (rawFile.isEmpty()) continue;

				ByteArrayOutputStream os = new ByteArrayOutputStream();
				Thumbnails.of(rawFile.get().inputStream())
						.size(1000, 1000)
						.outputFormat("jpg")
						.outputQuality(0.8)
						.toOutputStream(os);

				byte[] optimizedBytes = os.toByteArray();
				InputStream optimizedStream = new ByteArrayInputStream(optimizedBytes);
				results.add(new ImageUploadFile(
						"crawled-image-" + (i + 1) + ".jpg",
						"image/jpeg",
						optimizedStream,
						optimizedBytes.length));
				log.info("이미지 다운로드 및 최적화 완료 [{}/{}]: {}", i + 1, imageUrls.size(), url);
			} catch (Exception e) {
				log.error("이미지 다운로드/변환 실패 [{}/{}]: {}", i + 1, imageUrls.size(), url, e.getMessage());
			}
		}
		if (results.isEmpty()) {
			throw new RuntimeException("모든 이미지 다운로드에 실패했습니다.");
		}
		return results;
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
