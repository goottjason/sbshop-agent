package com.sbshop.agent.core.application.product;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import com.sbshop.agent.core.domain.market.MarketFailureClassifier;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.UnsyncReason;
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
	private final ProductDeleteTxService productDeleteTxService;
	private final ActionLogService actionLogService;
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Transactional
	public MarketRepublishResult updatePriceStock(Long productId, BigDecimal price, Boolean soldOut) {
		Product product = productReader.findById(productId)
			.orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다: " + productId));

		ProductUpdateCommand command = ProductUpdateCommand.builder()
			.salePrice(price)
			.build();
		product.update(command);

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
			.orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다: " + productId));

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

		return republishToMarkets(product, productId, hostedImages, newHtml);
	}

	@Transactional
	public void updateProduct(Long productId, ProductUpdateCommand command) {
		Product product = productReader.findById(productId)
			.orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다: " + productId));
		product.update(command);
		productWriter.save(product);
		log.info("상품 전체 업데이트 완료: id={}", productId);
	}

	public ProductDeleteResult deleteProduct(Long productId) {
		Product product = productReader.findById(productId)
			.orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다: " + productId));

		List<MarketRegistration> registrations = marketRegistrationRepository.findByProductId(productId);
		List<MarketType> deleted = new ArrayList<>();
		List<MarketType> skipped = new ArrayList<>();
		Map<MarketType, String> failed = new LinkedHashMap<>();
		Map<MarketType, String> manual = new LinkedHashMap<>();
		Map<MarketType, String> marketItemIds = new LinkedHashMap<>();

		for (MarketRegistration reg : registrations) {
			MarketType marketType = reg.getMarketType();

			String marketItemId = reg.extractDeleteCode();
			if (marketItemId != null && !marketItemId.isEmpty()) {
				marketItemIds.put(marketType, marketItemId);
			}
			if (Boolean.FALSE.equals(reg.getIsSynced())
				&& reg.getUnsyncReason() == UnsyncReason.DELETED_ON_MARKET) {
				deleted.add(marketType);
				log.info("[완전삭제] 이미 마켓에서 삭제 확인된 등록 — 건너뜀: productId={}, market={}",
					productId, marketType);
				continue;
			}
			if (marketItemId == null || marketItemId.isEmpty()) {
				manual.put(marketType, "마켓 상품코드를 몰라 자동 삭제할 수 없습니다 — 마켓에서 직접 지워야 합니다");
				log.warn("[완전삭제] 마켓 상품코드 없음 — 수동 처리 필요: productId={}, market={}",
					productId, marketType);
				continue;
			}
			if (!marketClientRouter.hasClient(marketType)) {
				manual.put(marketType, "마켓 클라이언트가 없어 자동 삭제할 수 없습니다 — 마켓에서 직접 지워야 합니다");
				log.warn("[완전삭제] 마켓 클라이언트 없음 — 수동 처리 필요: productId={}, market={}",
					productId, marketType);
				continue;
			}
			try {
				marketClientRouter.getClient(marketType).deleteFromMarket(marketItemId);
				reg.markAbsentFromMarket(UnsyncReason.DELETED_ON_MARKET);
				marketRegistrationRepository.save(reg);
				deleted.add(marketType);
				log.info("[완전삭제] 마켓 리스팅 삭제 성공: productId={}, market={}, marketItemId={}",
					productId, marketType, marketItemId);
			} catch (UnsupportedOperationException unsupported) {
				manual.put(marketType, unsupported.getMessage());
				log.warn("[완전삭제] 삭제 API 미지원 — 수동 처리 필요: productId={}, market={}",
					productId, marketType);
			} catch (Exception e) {
				failed.put(marketType, e.getMessage());
				reg.recordSyncError(MarketFailureClassifier.classifyError(e), e.getMessage());
				marketRegistrationRepository.save(reg);
				log.error("[완전삭제] 마켓 리스팅 삭제 실패 — 상품을 폐기하지 않는다: productId={}, market={}, error={}",
					productId, marketType, e.getMessage(), e);
			}
		}

		boolean disposed = failed.isEmpty() && manual.isEmpty();
		if (disposed) {
			productDeleteTxService.deleteWithRegistrations(product, registrations);
		} else {
			log.warn("[완전삭제] 폐기 보류 — 마켓에 리스팅이 남아 있다: productId={}, 실패={}, 수동={}",
				productId, failed.keySet(), manual.keySet());
		}

		recordDeleteActionLog(productId, deleted, skipped, failed, marketItemIds);

		log.info("[완전삭제] 완료: productId={}, 폐기={}, deleted={}, failed={}, manual={}",
			productId, disposed, deleted, failed.keySet(), manual.keySet());
		return new ProductDeleteResult(deleted, skipped, failed, manual, disposed);
	}

	private MarketRepublishResult republishToMarkets(Product product, Long productId,
		List<String> hostedImages, String newHtml) {
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
				String marketItemId = reg.extractDeleteCode();
				if (marketItemId == null || marketItemId.isEmpty()) {
					throw new IllegalStateException("마켓 상품코드 없음(연동정보에 코드 키 부재)");
				}
				Map<String, Object> currentRawData = parseRawData(reg.getMarketDetailedInfo());

				MarketClient client = marketClientRouter.getClient(marketType);
				Map<String, Object> updated = client.syncImagesAndHtml(product, marketItemId, currentRawData,
					hostedImages, newHtml);

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

	private void recordDeleteActionLog(Long productId, List<MarketType> deleted, List<MarketType> skipped,
		Map<MarketType, String> failed, Map<MarketType, String> marketItemIds) {
		List<String> parts = new ArrayList<>();
		for (MarketType m : deleted) {
			parts.add(marketLabelWithId(m, marketItemIds) + " 삭제");
		}
		for (Map.Entry<MarketType, String> e : failed.entrySet()) {
			parts.add(marketLabelWithId(e.getKey(), marketItemIds) + " 실패(" + e.getValue() + ")");
		}
		for (MarketType m : skipped) {
			parts.add(marketLabelWithId(m, marketItemIds) + " 스킵");
		}
		String detail = parts.isEmpty() ? "연동 마켓 없음" : String.join(", ", parts);
		String message = "상품 삭제 (상품 " + productId + ") | " + detail;
		ActionStatus status = failed.isEmpty() ? ActionStatus.SUCCESS : ActionStatus.FAILED;
		actionLogService.record(ActionLogConstants.PRODUCT_DELETE, null, status, message);
	}

	private String marketLabelWithId(MarketType market, Map<MarketType, String> marketItemIds) {
		String id = marketItemIds.get(market);
		return (id == null || id.isEmpty()) ? market.getLabel() : market.getLabel() + " " + id;
	}
}
