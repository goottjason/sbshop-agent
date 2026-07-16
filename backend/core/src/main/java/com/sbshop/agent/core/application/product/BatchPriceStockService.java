package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import com.sbshop.agent.core.application.product.port.ProductStockCrawlerPort;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import com.sbshop.agent.core.application.product.event.BatchCompletedEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class BatchPriceStockService {

	private final ProductReader productReader;
	private final ProductWriter productWriter;
	private final ProductRepository productRepository;
	private final ProductStockCrawlerPort productStockCrawlerPort;
	private final ProcessStatusService processStatusService;
	private final MarginCalculator marginCalculator;
	private final ApplicationEventPublisher eventPublisher;
	private final ProductMarketSyncService productMarketSyncService;

	/**
	 * 크롤 기반 배치에서 상품 간 딜레이(ms). 외부 소싱 사이트 rate-limit 완화용.
	 * 수동(manual) 경로는 외부 크롤이 없어 이 완충이 없다(의도된 비대칭, F-BATCH-M2).
	 */
	private static final long CRAWL_THROTTLE_MS = 500L;

	@Async("productBatchExecutor")
	public void crawlAndUpdatePriceStock(String batchId, List<Long> productIds,
		BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice, String actionType) {
		int failCount = 0;
		for (Long productId : productIds) {
			try {
				Product product = productReader.findById(productId)
					.orElseThrow(() -> new IllegalArgumentException("상품 없음: " + productId));

				String sourceUrl = product.getSourcingUrl();
				if (sourceUrl == null || sourceUrl.isEmpty()) {
					processStatusService.markFailed(batchId, String.valueOf(productId),
						product.getSbCode() + ": 소싱 URL 없음");
					failCount++;
					continue;
				}

				StockCheckResult result = productStockCrawlerPort.checkStockWithDetails(sourceUrl);
				BigDecimal buyPrice = result.costPrice() != null ? result.costPrice() : BigDecimal.ZERO;
				int bundleQty = product.getLogisticsInfo() != null
					&& product.getLogisticsInfo().getBundleQuantity() != null
						? product.getLogisticsInfo().getBundleQuantity() : 1;
				BigDecimal salePrice = marginCalculator.calculateSalePrice(buyPrice, bundleQty, marginRate,
					minMarginPrice);

				ProductUpdateCommand command = ProductUpdateCommand.builder()
					.costPrice(buyPrice)
					.marginRate(marginRate)
					.salePrice(salePrice)
					.stock(result.stock())
					.build();
				product.update(command);
				product.updateStockStatus(result.status());
				product.updateRestockDate(result.restockDate());
				productWriter.save(product);

				// D-060: 배치 갱신분도 연동 마켓에 반영(단건과 동일 경로). 부분 실패는 메시지로 표면화.
				MarketRepublishResult sync = productMarketSyncService.syncPriceStock(
					productId, salePrice != null ? salePrice.intValue() : null, result.status());
				processStatusService.markSuccess(batchId, String.valueOf(productId),
					String.format("[%s] 가격:%s, 재고:%d · 마켓반영 성공%d/스킵%d/실패%d%s",
						product.getSbCode(), salePrice, result.stock(), sync.synced().size(), sync.skipped().size(),
						sync.failed().size(),
						sync.failed().isEmpty() ? "" : " (" + sync.failed().keySet() + ")"));
				Thread.sleep(CRAWL_THROTTLE_MS);
			} catch (Exception e) {
				log.error("배치 업데이트 실패: productId={}", productId, e);
				processStatusService.markFailed(batchId, String.valueOf(productId), e.getMessage());
				failCount++;
			}
		}
		eventPublisher.publishEvent(new BatchCompletedEvent(this, batchId,
			actionType,
			failCount == 0, failCount == 0 ? "배치 완료" : "배치 완료(실패 " + failCount + "건)"));
	}

	@Async("productBatchExecutor")
	public void manualUpdatePriceStock(String batchId,
		List<com.sbshop.agent.core.application.product.dto.PriceStockItem> items) {
		int failCount = 0;
		for (com.sbshop.agent.core.application.product.dto.PriceStockItem item : items) {
			Long productId = item.productId();
			try {
				Product product = productReader.findById(productId)
					.orElseThrow(() -> new IllegalArgumentException("상품 없음: " + productId));

				BigDecimal price = item.price();
				Integer stock = item.stock();

				StockStatus oldStatus = product.getStockStatus();
				BigDecimal oldPrice = product.getSalePrice();
				StockStatus newStatus = (stock == null) ? oldStatus
					: ((stock <= 0) ? StockStatus.OUT_OF_STOCK : StockStatus.IN_STOCK);
				boolean priceChanged = price != null && !price.equals(oldPrice);
				boolean statusChanged = newStatus != oldStatus;

				if (!priceChanged && !statusChanged) {
					processStatusService.markSuccess(batchId, String.valueOf(productId),
						"[" + product.getSbCode() + "] 변경사항 없음");
					continue;
				}

				ProductUpdateCommand command = ProductUpdateCommand.builder()
					.salePrice(price)
					.build();
				product.update(command);
				product.updateStockStatus(newStatus);
				productWriter.save(product);

				// D-060: 배치(수동) 갱신분도 연동 마켓에 반영.
				MarketRepublishResult sync = productMarketSyncService.syncPriceStock(
					productId, price != null ? price.intValue() : null, newStatus);
				processStatusService.markSuccess(batchId, String.valueOf(productId),
					String.format("[%s] 가격:%s->%s, 판매상태:%s->%s · 마켓반영 성공%d/스킵%d/실패%d%s",
						product.getSbCode(), oldPrice, price, oldStatus, newStatus, sync.synced().size(),
						sync.skipped().size(),
						sync.failed().size(), sync.failed().isEmpty() ? "" : " (" + sync.failed().keySet() + ")"));
			} catch (Exception e) {
				log.error("수동 업데이트 실패: productId={}", productId, e);
				processStatusService.markFailed(batchId, String.valueOf(productId), e.getMessage());
				failCount++;
			}
		}
		eventPublisher.publishEvent(new BatchCompletedEvent(this, batchId,
			com.sbshop.agent.core.domain.actionlog.ActionLogConstants.BATCH_MANUAL_UPDATE,
			failCount == 0, failCount == 0 ? "수동 배치 완료" : "수동 배치 완료(실패 " + failCount + "건)"));
	}

	@Async("productBatchExecutor")
	public void manualUpdateAllFields(String batchId, List<Long> productIds,
		List<com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand> commands) {
		int failCount = 0;
		for (int i = 0; i < productIds.size(); i++) {
			try {
				Long productId = productIds.get(i);
				Product product = productReader.findById(productId)
					.orElseThrow(() -> new IllegalArgumentException("상품 없음: " + productId));

				com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand command = commands.get(i);
				product.update(command);
				productWriter.save(product);

				processStatusService.markSuccess(batchId, String.valueOf(productId),
					"[" + product.getSbCode() + "] 전체 필드 수정 완료");
			} catch (Exception e) {
				log.error("전체 필드 업데이트 실패: productId={}", productIds.get(i), e);
				processStatusService.markFailed(batchId, String.valueOf(productIds.get(i)), e.getMessage());
				failCount++;
			}
		}
		eventPublisher.publishEvent(new BatchCompletedEvent(this, batchId,
			com.sbshop.agent.core.domain.actionlog.ActionLogConstants.BATCH_MANUAL_UPDATE_ALL,
			failCount == 0, failCount == 0 ? "전체 필드 배치 완료" : "전체 필드 배치 완료(실패 " + failCount + "건)"));
	}

	public List<Long> getProductIdsByVendor(VendorType vendor) {
		return productRepository.findByVendor(vendor).stream()
			.map(Product::getId)
			.collect(Collectors.toList());
	}
}
