package com.sbshop.agent.infrastructure.client.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 특성화(characterization) 테스트 — D-004 구조 통합(ImageDownloader/ImageDownloadService → 단일
 * ImageDownloadClient 구현) 전후의 다운로드 동작 불변을 고정한다. 이 구현체가 존재하는 이유는 크롤링 대상
 * 사이트의 Cloudflare 봇 차단을 우회하기 위한 브라우저 User-Agent 전송이므로, 그 동작을 회귀 방지 대상으로
 * 명시적으로 고정한다.
 */
class ImageDownloadServiceCharacterizationTest {

	private HttpServer server;
	private String baseUrl;
	private byte[] imageBytes;
	private volatile int responseStatus = 200;
	private final AtomicReference<String> capturedUserAgent = new AtomicReference<>();
	private final AtomicReference<String> capturedAccept = new AtomicReference<>();

	@BeforeEach
	void setUp() throws Exception {
		imageBytes = sampleJpeg();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			capturedUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
			capturedAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
			if (responseStatus != 200) {
				exchange.sendResponseHeaders(responseStatus, -1);
				exchange.close();
				return;
			}
			exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
			exchange.sendResponseHeaders(200, imageBytes.length);
			exchange.getResponseBody().write(imageBytes);
			exchange.close();
		});
		server.start();
		baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	@DisplayName("downloadAndConvert는 원격 이미지를 1000 규격 JPEG ImageUploadFile로 변환한다")
	void downloadAndConvert_returnsConvertedJpeg() {
		ImageDownloadService service = new ImageDownloadService();

		List<ImageUploadFile> result = service.downloadAndConvert(List.of(baseUrl + "/img.jpg"));

		assertThat(result).hasSize(1);
		ImageUploadFile file = result.get(0);
		assertThat(file.originalFilename()).isEqualTo("crawled-image-1.jpg");
		assertThat(file.contentType()).isEqualTo("image/jpeg");
		assertThat(file.size()).isPositive();
	}

	@Test
	@DisplayName("downloadAndConvert는 Cloudflare 차단 우회를 위해 브라우저 User-Agent 및 Accept 헤더를 전송한다")
	void downloadAndConvert_sendsBrowserUserAgent() {
		ImageDownloadService service = new ImageDownloadService();

		service.downloadAndConvert(List.of(baseUrl + "/img.jpg"));

		assertThat(capturedUserAgent.get()).startsWith("Mozilla/5.0");
		assertThat(capturedAccept.get()).isEqualTo("image/*");
	}

	@Test
	@DisplayName("모든 URL 다운로드 실패 시 RuntimeException을 던진다")
	void downloadAndConvert_allFail_throws() {
		responseStatus = 403;
		ImageDownloadService service = new ImageDownloadService();

		assertThatThrownBy(() -> service.downloadAndConvert(List.of(baseUrl + "/blocked.jpg")))
				.isInstanceOf(RuntimeException.class);
	}

	private static byte[] sampleJpeg() throws Exception {
		BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", os);
		return os.toByteArray();
	}
}
