package com.sbshop.agent.core.application.product;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
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
	private final ProductDeleteTxService productDeleteTxService;
	private final ActionLogService actionLogService;
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
			.orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다: " + productId));

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

		// D-049(결정②): 자사 DB/R2 갱신 후, 연동된 각 마켓에 이미지/HTML 자동 재게시.
		return republishToMarkets(product, productId, hostedImages, newHtml);
	}

	/**
	 * D-049(결정②): 상품의 연동 마켓 목록을 순회하며 각 {@link MarketClient#syncImagesAndHtml}을 호출한다.
	 * <ul>
	 *   <li>GMARKET/AUCTION(D-044, MarketClient 구현체 없음)은 router.hasClient=false로 스킵.</li>
	 *   <li>라이브 마켓 쓰기이므로 마켓별 try로 감싸 부분 실패를 수집하고 전체 트랜잭션을 깨지 않는다
	 *       (한 마켓 실패가 나머지 마켓·자사 DB 갱신을 롤백하지 않도록).</li>
	 * </ul>
	 */
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
				// 재게시(이미지/HTML)는 삭제와 동일하게 상품 리소스 레벨 API(쿠팡 seller-products)를 호출한다.
				// 쿠팡은 sellerProductId가 필요한데 extractMarketCode()는 가격/재고용 vendorItemId를 우선하므로,
				// 재게시에 vendorItemId를 넘기면 "Product(...) data not found"가 난다 → extractDeleteCode(=sellerProductId) 사용.
				// (SMART_STORE/11번가/Cafe24는 extractDeleteCode==extractMarketCode라 동작 동일)
				String marketItemId = reg.extractDeleteCode();
				if (marketItemId == null || marketItemId.isEmpty()) {
					throw new IllegalStateException("마켓 상품코드 없음(연동정보에 코드 키 부재)");
				}
				Map<String, Object> currentRawData = parseRawData(reg.getMarketDetailedInfo());

				MarketClient client = marketClientRouter.getClient(marketType);
				Map<String, Object> updated = client.syncImagesAndHtml(product, marketItemId, currentRawData, hostedImages, newHtml);

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
			.orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다: " + productId));
		product.update(command);
		productWriter.save(product);
		log.info("상품 전체 업데이트 완료: id={}", productId);
	}

	/**
	 * F-PROD-27/28: 완전 상품 삭제. 연동된 각 외부 마켓 리스팅을 삭제한 뒤 등록행·Product를 삭제한다.
	 *
	 * <p>흐름: (1) 상품 조회(없으면 404) → (2) 등록행 조회 → (3) <b>[트랜잭션 밖]</b> 마켓별
	 * {@code deleteFromMarket} best-effort 호출(성공/스킵/실패 수집) → (4) <b>[짧은 @Transactional]</b>
	 * 등록행·Product 삭제 → (5) ActionLog 기록.
	 *
	 * <p>파괴적·장시간 외부 마켓 삭제 API는 반드시 트랜잭션 밖에서 호출한다(F-PSRC-8 패턴). DB 삭제만
	 * {@link ProductDeleteTxService}의 짧은 트랜잭션으로 커밋한다. best-effort(C)라 마켓 삭제 실패는
	 * DB 삭제를 막지 않으며, 실패 마켓은 응답·ActionLog에 남겨 수동 정리 근거로 삼는다(등록행은 지워지므로
	 * 이것이 유일한 추적 흔적).
	 *
	 * <p>이 메서드는 <b>비-{@code @Transactional}</b>이다 — (3)의 외부 I/O가 DB 트랜잭션을 물지 않도록.
	 */
	public ProductDeleteResult deleteProduct(Long productId) {
		Product product = productReader.findById(productId)
			.orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다: " + productId));

		List<MarketRegistration> registrations = marketRegistrationRepository.findByProductId(productId);
		List<MarketType> deleted = new ArrayList<>();
		List<MarketType> skipped = new ArrayList<>();
		Map<MarketType, String> failed = new LinkedHashMap<>();
		Map<MarketType, String> marketItemIds = new LinkedHashMap<>();

		// (3) [트랜잭션 밖] 마켓별 리스팅 삭제 — best-effort 수집(ProductMarketSyncService.syncInternal 동형).
		for (MarketRegistration reg : registrations) {
			MarketType marketType = reg.getMarketType();
			// 삭제용 식별자(쿠팡은 sellerProductId — extractMarketCode의 vendorItemId는 삭제경로에 부적합).
			String marketItemId = reg.extractDeleteCode();
			if (marketItemId != null && !marketItemId.isEmpty()) {
				marketItemIds.put(marketType, marketItemId);
			}
			if (!marketClientRouter.hasClient(marketType)) {
				skipped.add(marketType);
				log.info("[완전삭제] 마켓 클라이언트 없음 — 스킵: productId={}, market={}", productId, marketType);
				continue;
			}
			try {
				marketClientRouter.getClient(marketType).deleteFromMarket(marketItemId);
				deleted.add(marketType);
				log.info("[완전삭제] 마켓 리스팅 삭제 성공: productId={}, market={}, marketItemId={}",
					productId, marketType, marketItemId);
			} catch (Exception e) {
				failed.put(marketType, e.getMessage());
				log.error("[완전삭제] 마켓 리스팅 삭제 실패(best-effort로 DB 삭제는 진행): productId={}, market={}, error={}",
					productId, marketType, e.getMessage(), e);
			}
		}

		// (4) [짧은 @Transactional] 등록행 전부 + Product 삭제(외부 삭제 결과와 무관하게 진행 — C best-effort).
		productDeleteTxService.deleteWithRegistrations(product, registrations);

		// (5) 관측성: 삭제/스킵/실패 마켓 + marketItemId를 ActionLog(PRODUCT_DELETE)에 기록(수동 정리 근거).
		recordDeleteActionLog(productId, deleted, skipped, failed, marketItemIds);

		log.info("[완전삭제] 완료: productId={}, deleted={}, skipped={}, failed={}",
			productId, deleted, skipped, failed.keySet());
		return new ProductDeleteResult(deleted, skipped, failed);
	}

	/**
	 * 완전 삭제 결과를 ActionLog(PRODUCT_DELETE)에 기록한다. 실패 마켓이 있으면 FAILED, 없으면 SUCCESS.
	 * 각 마켓 라벨에 marketItemId를 붙여 실패 마켓의 수동 정리 근거를 남긴다.
	 */
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
