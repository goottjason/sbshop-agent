package com.sbshop.agent.infrastructure.client.sourcing.content;

import com.sbshop.agent.core.application.product.content.*;
import com.sbshop.agent.infrastructure.client.sourcing.IherbScraperClient;
import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import net.coobird.thumbnailator.Thumbnails;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/** Existing IHB catalog contract, with a bounded image transport dedicated to reviewed refreshes. */
@Component
public class IherbProductContentSource implements ProductContentSource {
	private static final int MAX_IMAGE_BYTES = 10_000_000;
	private final IherbScraperClient crawler;
	private final ImageStorageClient storage;
	private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
		.connectTimeout(Duration.ofSeconds(10)).build();

	public IherbProductContentSource(IherbScraperClient crawler, ImageStorageClient storage) {
		this.crawler = crawler;
		this.storage = storage;
	}

	@Override
	public Fetch fetch(String sourceUrl) {
		var dto = crawler.crawlProductContentAsDto(ProductContentUrls.source(sourceUrl));
		if (dto == null || dto.vendor() != com.sbshop.agent.core.domain.product.enums.VendorType.IHB
			|| !Objects.equals(sourceUrl, dto.sourceUrl()) || dto.baseName() == null || dto.baseName().isBlank())
			throw new IllegalArgumentException("소싱처와 상품정보를 검증할 수 없습니다.");
		List<String> notices = new ArrayList<>();
		List<String> images = dto.sourceImages() == null ? List.of() : dto.sourceImages();
		List<String> hosted = List.of();
		boolean imagesComplete = false;
		if (images.isEmpty())
			notices.add("최신 이미지 목록이 비어 있어 기존 이미지를 유지합니다.");
		else if (images.size() > 8 || new HashSet<>(images).size() != images.size())
			notices.add("이미지 개수 또는 중복을 확인할 수 없어 이미지 적용을 제외했습니다.");
		else {
			List<ImageUploadFile> files = new ArrayList<>();
			try {
				for (int i = 0; i < images.size(); i++)
					files.add(download(ProductContentUrls.sourceImage(images.get(i)), i));
				Map<String, String> uploaded = storage.uploadImages(files);
				List<String> ordered = new ArrayList<>();
				for (ImageUploadFile file : files)
					ordered.add(ProductContentUrls.hostedImage(uploaded.get(file.originalFilename())));
				hosted = List.copyOf(ordered);
				imagesComplete = true;
			} catch (ProductContentThrottledException e) {
				throw e;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("이미지 수집 중단", e);
			} catch (Exception e) {
				notices.add("일부 이미지의 다운로드·변환·호스팅에 실패했습니다. 이미지 전체 항목의 적용을 제외했습니다.");
			} finally {
				for (ImageUploadFile file : files)
					try {
						file.inputStream().close();
					} catch (IOException ignored) {}
			}
		}
		String html = sanitize(dto.rawSourceHtml());
		boolean detailComplete = html != null && !html.isBlank() && !Jsoup.parseBodyFragment(html).text().isBlank();
		if (!detailComplete)
			notices.add("소싱 상세 설명이 비어 있거나 허용 크기를 초과하여 상세 HTML 적용을 제외했습니다.");
		notices.add("소싱 상세 설명의 실행 코드·외부 이미지·링크·스타일은 제거하고 본문·표 내용으로 상세를 생성합니다.");
		return new Fetch(imagesComplete ? List.copyOf(images) : List.of(), hosted, detailComplete ? html : null,
			imagesComplete, detailComplete, notices);
	}

	static String sanitize(String raw) {
		if (raw == null || raw.isBlank() || raw.length() > 200_000)
			return null;
		// Keep the product description, never the complete source page or executable content.
		return Jsoup.clean(raw, Safelist.relaxed().removeTags("img", "a"));
	}

	private ImageUploadFile download(String url, int index) throws Exception {
		var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25))
			.header("User-Agent", "Mozilla/5.0").header("Accept", "image/*").GET().build();
		var response = http.send(request, boundedImageBody());
		if (response.statusCode() == 429)
			throw new ProductContentThrottledException(
				com.sbshop.agent.infrastructure.client.smartstore.client.InspectionRetryAfter.parse(
					response.headers().firstValue("Retry-After").orElse(null), java.time.Instant.now()));
		if (response.statusCode() != 200
			|| !response.headers().firstValue("Content-Type").orElse("").startsWith("image/"))
			throw new IOException("이미지 응답이 확인되지 않았습니다.");
		byte[] raw = response.body();
		try (var imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
			var readers = ImageIO.getImageReaders(imageInput);
			if (!readers.hasNext())
				throw new IOException("지원되는 이미지 형식이 아닙니다.");
			var reader = readers.next();
			try {
				reader.setInput(imageInput);
				if ((long)reader.getWidth(0) * reader.getHeight(0) > 40_000_000L)
					throw new IOException("이미지 해상도가 허용 범위를 초과했습니다.");
			} finally {
				reader.dispose();
			}
		}
		var output = new ByteArrayOutputStream();
		Thumbnails.of(new ByteArrayInputStream(raw)).size(1000, 1000).outputFormat("jpg").outputQuality(0.8)
			.toOutputStream(output);
		byte[] converted = output.toByteArray();
		return new ImageUploadFile("content-" + index + ".jpg", "image/jpeg", new ByteArrayInputStream(converted),
			converted.length);
	}

	private HttpResponse.BodyHandler<byte[]> boundedImageBody() {
		return info -> {
			if (info.statusCode() != 200)
				return HttpResponse.BodySubscribers.replacing(new byte[0]);
			return new LimitedBodySubscriber(MAX_IMAGE_BYTES);
		};
	}

	/** Cancels transport as soon as its byte limit is exceeded; does not buffer a large body first. */
	static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
		private final int limit;
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
		private java.util.concurrent.Flow.Subscription subscription;

		LimitedBodySubscriber(int limit) {
			this.limit = limit;
		}

		@Override
		public java.util.concurrent.CompletionStage<byte[]> getBody() {
			return result;
		}

		@Override
		public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
			this.subscription = subscription;
			subscription.request(1);
		}

		@Override
		public void onNext(List<java.nio.ByteBuffer> buffers) {
			for (var buffer : buffers) {
				if (buffer.remaining() > limit - bytes.size()) {
					subscription.cancel();
					result.completeExceptionally(new IOException("이미지가 10MB 제한을 초과했습니다."));
					return;
				}
				byte[] next = new byte[buffer.remaining()];
				buffer.get(next);
				bytes.writeBytes(next);
			}
			subscription.request(1);
		}

		@Override
		public void onError(Throwable throwable) {
			result.completeExceptionally(throwable);
		}

		@Override
		public void onComplete() {
			result.complete(bytes.toByteArray());
		}
	}
}
