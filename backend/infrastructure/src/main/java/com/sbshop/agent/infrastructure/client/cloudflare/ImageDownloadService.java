package com.sbshop.agent.infrastructure.client.cloudflare;

import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

/**
 * {@link ImageDownloadClient}의 단일 구현체. 크롤링 대상 사이트의 Cloudflare 봇 차단을 우회해야 하므로
 * 브라우저 User-Agent 헤더를 실어 OkHttp로 이미지를 내려받는다.
 */
@Slf4j
@Component
public class ImageDownloadService implements ImageDownloadClient {

	private final OkHttpClient httpClient = new OkHttpClient();

	@Override
	public List<ImageUploadFile> downloadAndConvert(List<String> imageUrls) {
		List<ImageUploadFile> results = new ArrayList<>();

		for (int i = 0; i < imageUrls.size(); i++) {
			String url = imageUrls.get(i);
			try {
				byte[] rawBytes = downloadImage(url);

				ByteArrayOutputStream os = new ByteArrayOutputStream();
				Thumbnails.of(new ByteArrayInputStream(rawBytes))
					.size(1000, 1000)
					.outputFormat("jpg")
					.outputQuality(0.8)
					.toOutputStream(os);

				byte[] optimizedBytes = os.toByteArray();
				InputStream optimizedStream = new ByteArrayInputStream(optimizedBytes);
				String filename = "crawled-image-" + (i + 1) + ".jpg";

				results.add(new ImageUploadFile(
					filename,
					"image/jpeg",
					optimizedStream,
					optimizedBytes.length));

				log.info("이미지 다운로드 및 최적화 완료 [{}/{}]: {}", i + 1, imageUrls.size(), url);
			} catch (Exception e) {
				log.error("이미지 다운로드 실패 [{}/{}]: {} - {}", i + 1, imageUrls.size(), url, e.getMessage());
			}
		}

		if (results.isEmpty()) {
			throw new RuntimeException("모든 이미지 다운로드에 실패했습니다.");
		}

		return results;
	}

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
			byte[] rawBytes = downloadImage(imageUrl);
			String filename = extractFilenameFromUrl(imageUrl);
			ImageUploadFile uploadFile = new ImageUploadFile(
				filename, "image/jpeg", new ByteArrayInputStream(rawBytes), rawBytes.length);
			return Optional.of(uploadFile);
		} catch (Exception e) {
			log.error("이미지 다운로드 중 오류 발생: {}", imageUrl, e);
			return Optional.empty();
		}
	}

	private byte[] downloadImage(String url) throws Exception {
		Request request = new Request.Builder()
			.url(url)
			.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
			.header("Accept", "image/*")
			.build();

		try (Response response = httpClient.newCall(request).execute()) {
			if (!response.isSuccessful() || response.body() == null) {
				throw new RuntimeException("이미지 다운로드 실패 (HTTP " + response.code() + "): " + url);
			}
			return response.body().bytes();
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
