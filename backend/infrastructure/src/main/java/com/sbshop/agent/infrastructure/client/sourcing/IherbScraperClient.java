package com.sbshop.agent.infrastructure.client.sourcing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import com.sbshop.agent.core.application.product.port.ProductInfoCrawlerPort;
import com.sbshop.agent.core.application.product.port.VendorAwareStockCrawler;
import com.sbshop.agent.core.application.sourcing.dto.ScrapedProductDto;
import com.sbshop.agent.core.application.sourcing.dto.SourcingCrawlResult;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.infrastructure.client.sourcing.dto.IherbProductInfo;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class IherbScraperClient implements VendorAwareStockCrawler, ProductInfoCrawlerPort {

	private final HttpClient httpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();
	private final ObjectMapper objectMapper;

	@Override
	public VendorType vendor() {
		return VendorType.IHB;
	}

	@Override
	public StockStatus checkStockStatus(String sourceUrl) {
		StockCheckResult result = checkStockWithDetails(sourceUrl);
		return result != null ? result.status() : StockStatus.OUT_OF_STOCK;
	}

	@Override
	public StockCheckResult checkStockWithDetails(String sourceUrl) {
		String productId = extractProductId(sourceUrl);
		if (productId == null) {
			throw new IllegalStateException("아이허브 재고 판정 불가 — 상품 ID 를 뽑을 수 없는 URL 이다: "
				+ sourceUrl + " (아이허브 상품이 맞는지 확인 필요. 품절로 단정하지 않는다)");
		}

		String apiUrl = "https://catalog.app.iherb.com/product/" + productId;
		int maxRetries = 3;

		for (int i = 0; i <= maxRetries; i++) {
			try {
				HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(apiUrl))
					.header("User-Agent",
						"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
					.header("Accept", "application/json")
					.header("Accept-Language", "en-US,en;q=0.9")
					.header("Referer", "https://www.iherb.com/")
					.GET()
					.build();

				HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

				if (response.statusCode() == 200) {
					return parseResponse(response.body());
				} else if (response.statusCode() == 403) {
					log.warn("아이허브 403 차단. 재시도 중... ({}/{})", i + 1, maxRetries);
					Thread.sleep(2000L * (i + 1));
				} else if (response.statusCode() == 404) {
					log.warn("아이허브 상품 없음 (404) — 원본 소멸로 기록: {}", productId);
					return new StockCheckResult(StockStatus.OUT_OF_STOCK, null, 0, null, true, null,
						com.sbshop.agent.core.domain.product.enums.SourceGoneReason.LINK_DEAD);
				} else {
					if (i == maxRetries) {
						throw new RuntimeException("HTTP 상태 코드 에러: " + response.statusCode());
					}
				}
			} catch (Exception e) {
				if (i == maxRetries) {
					throw new IllegalStateException("아이허브 재고 판정 불가 — 조회가 끝내 실패했다: "
						+ productId + " (차단·순단일 수 있다. 품절로 단정하지 않는다)", e);
				}
			}
		}
		throw new IllegalStateException("아이허브 재고 판정 불가 — 재시도를 모두 소진했다: " + productId
			+ " (품절로 단정하지 않는다)");
	}

	public IherbProductInfo crawlProductInfo(String url) {
		String productId = extractProductId(url);
		if (productId == null) {
			log.error("아이허브 상품 ID 추출 실패. url={}", url);
			return null;
		}

		String apiUrl = "https://catalog.app.iherb.com/product/" + productId;
		for (int i = 0; i <= 3; i++) {
			try {
				HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(apiUrl))
					.header("User-Agent",
						"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
					.header("Accept", "application/json")
					.header("Accept-Language", "en-US,en;q=0.9")
					.header("Referer", "https://www.iherb.com/")
					.GET()
					.build();

				HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() == 200) {
					return parseProductInfo(response.body(), url);
				} else if (response.statusCode() == 403) {
					log.warn("아이허브 403 차단. 재시도 중... ({}/3)", i + 1);
					Thread.sleep(2000L * (i + 1));
				}
			} catch (Exception e) {
				if (i == 3)
					log.error("아이허브 상품 정보 크롤링 실패: {}", url, e);
			}
		}
		return null;
	}

	@Override
	public ScrapedProductDto crawlProductInfoAsDto(String url) {
		IherbProductInfo info = crawlProductInfo(url);
		return info != null ? toScrapedDto(info) : null;
	}

	@Override
	public SourcingCrawlResult crawlProducts(List<String> urls) {
		List<ScrapedProductDto> succeeded = new ArrayList<>();
		List<SourcingCrawlResult.SourcingFailure> failed = new ArrayList<>();
		for (String url : urls) {
			try {
				ScrapedProductDto dto = crawlProductInfoAsDto(url);
				if (dto != null) {
					succeeded.add(dto);
				} else {
					failed.add(new SourcingCrawlResult.SourcingFailure(url, "크롤 결과를 가져오지 못했습니다"));
				}
				Thread.sleep(500 + (long)(Math.random() * 500));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				log.error("소싱 실패: {}", url, e);
				failed.add(new SourcingCrawlResult.SourcingFailure(url, e.getMessage()));
			}
		}
		return new SourcingCrawlResult(succeeded, failed);
	}

	IherbProductInfo parseProductInfo(String body, String sourceUrl) {
		try {
			JsonNode root = objectMapper.readTree(body);

			String productName = root.path("productName").asText("");
			String brandName = root.path("brandName").asText("");
			String englishName = root.path("englishName").asText(productName);

			BigDecimal listPrice = BigDecimal.valueOf(root.path("listPriceAmount").asDouble(0));
			BigDecimal discountPrice = BigDecimal.valueOf(root.path("discountPriceAmount").asDouble(0));
			int discountType = root.path("discountType").asInt(0);
			int couponRate = root.path("couponDiscountPercentage").asInt(0);
			int salesDiscount = root.path("salesDiscountPercentage").asInt(0);

			boolean isAvailable = root.path("isAvailableToPurchase").asBoolean(false);

			List<String> imageLinks = new ArrayList<>();
			String partNumber = root.path("partNumber").asText(null);
			JsonNode imageIndices = root.path("imageIndices");
			if (partNumber != null && imageIndices.isArray()) {
				String brandLike = partNumber.split("-")[0].toLowerCase();
				String part = partNumber.toLowerCase().replace("-", "");
				int count = 0;
				for (JsonNode idxNode : imageIndices) {
					if (count >= 5)
						break;
					imageLinks.add(String.format(
						"https://cloudinary.images-iherb.com/image/upload/f_auto,q_auto:eco/images/%s/%s/l/%d.jpg",
						brandLike, part, idxNode.asInt()));
					count++;
				}
			}

			String categoryPath = root.path("userCategoryPath").asText("");
			String htmlDescription = root.path("htmlDescription").asText("");

			BigDecimal capacity = BigDecimal.ZERO;
			String unit = root.path("unit").asText("");
			try {
				String servingSize = root.path("servingSize").asText("");
				if (!servingSize.isEmpty()) {
					capacity = new BigDecimal(servingSize.replaceAll("[^0-9.]", ""));
				}
			} catch (Exception ignored) {}

			return new IherbProductInfo(
				productName, englishName, brandName,
				listPrice, discountPrice, discountType, couponRate, salesDiscount,
				isAvailable, imageLinks, categoryPath, htmlDescription,
				capacity, unit, sourceUrl);
		} catch (Exception e) {
			log.error("아이허브 상품 정보 파싱 실패", e);
			return null;
		}
	}

	private StockCheckResult parseResponse(String body) {
		try {
			JsonNode root = objectMapper.readTree(body);

			boolean isAvailable = root.path("isAvailableToPurchase").asBoolean(false);
			StockStatus status = isAvailable ? StockStatus.IN_STOCK : StockStatus.OUT_OF_STOCK;

			BigDecimal costPrice = null;
			JsonNode discountAmountNode = root.path("discountPriceAmount");
			if (!discountAmountNode.isMissingNode() && discountAmountNode.asDouble(0) > 0) {
				costPrice = BigDecimal.valueOf(discountAmountNode.asDouble(0));
			} else {
				JsonNode listAmountNode = root.path("listPriceAmount");
				if (!listAmountNode.isMissingNode() && listAmountNode.asDouble(0) > 0) {
					costPrice = BigDecimal.valueOf(listAmountNode.asDouble(0));
				}
			}
			if (costPrice == null) {
				JsonNode priceNode = root.path("price");
				if (!priceNode.isMissingNode()) {
					JsonNode discountNode = priceNode.path("discount");
					if (!discountNode.isMissingNode() && discountNode.has("amount")) {
						costPrice = new BigDecimal(discountNode.path("amount").asText("0"));
					} else if (priceNode.has("listPrice")) {
						costPrice = new BigDecimal(priceNode.path("listPrice").asText("0"));
					}
				}
			}

			int stock = 0;
			JsonNode stockNode = root.path("stockQuantity");
			if (!stockNode.isMissingNode()) {
				stock = stockNode.asInt(0);
			}
			if (stock == 0 && isAvailable) {
				stock = 100;
			}

			LocalDate restockDate = null;
			JsonNode availabilityNode = root.path("expectedAvailability");
			if (!availabilityNode.isMissingNode() && !availabilityNode.isNull()) {
				try {
					restockDate = LocalDate.parse(availabilityNode.asText().substring(0, 10),
						DateTimeFormatter.ISO_LOCAL_DATE);
				} catch (Exception e) {
					log.debug("입고예정일 파싱 실패: {}", availabilityNode.asText());
				}
			}
			if (restockDate == null) {
				JsonNode backOrderNode = root.path("backOrderDate");
				if (!backOrderNode.isMissingNode() && !backOrderNode.isNull()) {
					try {
						restockDate = LocalDate.parse(backOrderNode.asText().substring(0, 10),
							DateTimeFormatter.ISO_LOCAL_DATE);
					} catch (Exception e) {
						log.debug("backOrderDate 파싱 실패: {}", backOrderNode.asText());
					}
				}
			}

			log.info("아이허브 크롤링 결과 - status: {}, costPrice: {}, stock: {}, restockDate: {}",
				status, costPrice, stock, restockDate);

			return new StockCheckResult(status, costPrice, stock, restockDate);
		} catch (Exception e) {
			log.error("아이허브 응답 파싱 실패", e);
			return new StockCheckResult(StockStatus.OUT_OF_STOCK, null, 0, null);
		}
	}

	private String extractProductId(String url) {
		if (url == null)
			return null;
		Pattern pattern = Pattern.compile("/product/(\\d+)");
		Matcher matcher = pattern.matcher(url);
		if (matcher.find())
			return matcher.group(1);

		pattern = Pattern.compile("/pr/[^/]+/(\\d+)");
		matcher = pattern.matcher(url);
		return matcher.find() ? matcher.group(1) : null;
	}

	private ScrapedProductDto toScrapedDto(IherbProductInfo info) {
		return ScrapedProductDto.builder()
			.sourceUrl(info.sourceUrl())
			.baseName(info.productName())
			.originalName(info.englishName())
			.brand(info.brandName())
			.costPrice(info.discountPrice() != null && info.discountPrice().compareTo(BigDecimal.ZERO) > 0
				? info.discountPrice() : info.listPrice())
			.listPrice(info.listPrice())
			.discountPrice(info.discountPrice())
			.discountType(info.discountType())
			.couponRate(info.couponRate())
			.salesDiscount(info.salesDiscount())
			.isAvailable(info.isAvailable())
			.sourceImages(info.imageLinks())
			.rawSourceHtml(info.htmlDescription())
			.rawCategory(info.categoryPath())
			.capacity(info.capacity())
			.unit(info.unit())
			.vendor(VendorType.IHB)
			.build();
	}
}
