package com.sbshop.agent.core.application.product;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import com.sbshop.agent.core.domain.product.component.HtmlImageReplacer;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductManageUseCase {

	private final ProductReader productReader;
	private final ProductWriter productWriter;
	private final ImageStorageClient imageStorageClient;
	private final HtmlImageReplacer htmlImageReplacer;
	private final MarketRegistrationRepository marketRegistrationRepository;
	private final MarketClientRouter marketClientRouter;
	private final ProductMarketSyncService productMarketSyncService;
	private final ObjectMapper objectMapper = new ObjectMapper();

	/**
	 * 가격/재고상태 수정.
	 *
	 * <p>F-PROD-7: {@code soldOut}은 nullable이다. null이면 재고상태를 변경하지 않고(가격만 수정하려는 요청),
	 * 상품의 현재 재고상태를 그대로 마켓에 전파한다. 과거엔 null이 조용히 false→IN_STOCK으로 붕괴돼
	 * 품절 상품이 가격 수정만으로 판매재개 상태가 마켓에 전파되는 결함이 있었다.
	 * true/false면 각각 품절/판매중으로 재고상태를 갱신하고 그 상태를 마켓에 전파한다.
	 */
	@Transactional
	public MarketRepublishResult updatePriceStock(Long productId, BigDecimal price, Boolean soldOut) {
		Product product = productReader.findById(productId)
			.orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다: " + productId));

		// 가격만 command로 갱신(수량은 판매중/품절 이분법으로 대체 — DB 수량은 건드리지 않음).
		ProductUpdateCommand command = ProductUpdateCommand.builder()
			.salePrice(price)
			.build();
		product.update(command);
		// F-PROD-7: soldOut=null이면 재고상태 미변경 — 현재 상태를 유지하고 마켓에도 현재 상태를 전파한다.
		StockStatus stockStatus;
		if (soldOut == null) {
			stockStatus = product.getStockStatus();
		} else {
			stockStatus = soldOut ? StockStatus.OUT_OF_STOCK : StockStatus.IN_STOCK;
			product.updateStockStatus(stockStatus);
		}
		productWriter.save(product);

		log.info("상품 가격/판매상태 업데이트: id={}, price={}, soldOut={}", productId, price, soldOut);

		Integer priceInt = price != null ? price.intValue() : null;
		return productMarketSyncService.syncPriceStock(productId, priceInt, stockStatus);
	}

	@Transactional
	public MarketRepublishResult updateImagesAndHtml(Long productId, List<ImageUploadFile> imageFiles) {
		Product product = productReader.findById(productId)
			.orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다: " + productId));

		Map<String, String> uploadedUrlMap = imageStorageClient.uploadImages(imageFiles);
		List<String> hostedImages = new ArrayList<>(uploadedUrlMap.values());

		String newHtml = htmlImageReplacer.replaceImagesBySku(
			product.getDetailHtml(), product.getSbCode(), hostedImages);

		ProductUpdateCommand command = ProductUpdateCommand.builder()
			.hostedImages(hostedImages)
			.detailHtml(newHtml)
			.build();
		product.update(command);
		productWriter.save(product);

		log.info("상품 이미지 업데이트 완료: id={}, images={}", productId, hostedImages.size());

		// D-049(결정②): 자사 DB/R2 갱신 후, 연동된 각 마켓에 이미지/HTML 자동 재게시.
		return republishToMarkets(productId, hostedImages, newHtml);
	}

	/**
	 * D-049(결정②): 상품의 연동 마켓 목록을 순회하며 각 {@link MarketClient#syncImagesAndHtml}을 호출한다.
	 * <ul>
	 *   <li>GMARKET/AUCTION(D-044, MarketClient 구현체 없음)은 router.hasClient=false로 스킵.</li>
	 *   <li>라이브 마켓 쓰기이므로 마켓별 try로 감싸 부분 실패를 수집하고 전체 트랜잭션을 깨지 않는다
	 *       (한 마켓 실패가 나머지 마켓·자사 DB 갱신을 롤백하지 않도록).</li>
	 * </ul>
	 */
	private MarketRepublishResult republishToMarkets(Long productId, List<String> hostedImages, String newHtml) {
		List<MarketRegistration> registrations = marketRegistrationRepository.findByProductId(productId);
		List<MarketType> synced = new ArrayList<>();
		List<MarketType> skipped = new ArrayList<>();
		Map<MarketType, String> failed = new LinkedHashMap<>();

		for (MarketRegistration reg : registrations) {
			MarketType marketType = reg.getMarketType();
			if (!marketClientRouter.hasClient(marketType)) {
				skipped.add(marketType);
				log.info("[재게시] 마켓 클라이언트 없음 — 스킵: productId={}, market={}", productId, marketType);
				continue;
			}
			try {
				String marketItemId = reg.extractMarketCode();
				if (marketItemId == null || marketItemId.isEmpty()) {
					throw new IllegalStateException("마켓 상품코드 없음(연동정보에 코드 키 부재)");
				}
				Map<String, Object> currentRawData = parseRawData(reg.getMarketDetailedInfo());

				MarketClient client = marketClientRouter.getClient(marketType);
				Map<String, Object> updated = client.syncImagesAndHtml(marketItemId, currentRawData, hostedImages, newHtml);

				if (updated != null) {
					reg.updateMarketDetailedInfo(objectMapper.writeValueAsString(updated));
				}
				reg.markSynced();
				marketRegistrationRepository.save(reg);
				synced.add(marketType);
				log.info("[재게시] 성공: productId={}, market={}, marketItemId={}", productId, marketType, marketItemId);
			} catch (Exception e) {
				failed.put(marketType, e.getMessage());
				log.error("[재게시] 실패(부분 실패로 수집, 롤백하지 않음): productId={}, market={}, error={}",
					productId, marketType, e.getMessage(), e);
			}
		}

		log.info("[재게시] 완료: productId={}, synced={}, skipped={}, failed={}",
			productId, synced, skipped, failed.keySet());
		return new MarketRepublishResult(synced, skipped, failed);
	}

	private Map<String, Object> parseRawData(String json) {
		if (json == null || json.isBlank()) {
			return new HashMap<>();
		}
		try {
			return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
		} catch (Exception e) {
			log.warn("[재게시] marketDetailedInfo 파싱 실패 — 빈 rawData로 진행: {}", e.getMessage());
			return new HashMap<>();
		}
	}

	@Transactional
	public void updateProduct(Long productId, ProductUpdateCommand command) {
		Product product = productReader.findById(productId)
			.orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다: " + productId));
		product.update(command);
		productWriter.save(product);
		log.info("상품 전체 업데이트 완료: id={}", productId);
	}

	@Transactional
	public void deleteProduct(Long productId) {
		Product product = productReader.findById(productId)
			.orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다: " + productId));
		productWriter.delete(product);
		log.info("상품 삭제 완료: id={}", productId);
	}
}
