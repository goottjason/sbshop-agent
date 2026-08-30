package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.dto.PriceStockItem;
import com.sbshop.agent.core.application.product.dto.PricingInputs;
import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import com.sbshop.agent.core.application.product.event.BatchCompletedEvent;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import com.sbshop.agent.core.domain.product.enums.SourceGoneReason;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

	private final StockCrawlerRouter stockCrawlerRouter;
	private final ProcessStatusService processStatusService;
	private final MarginCalculator marginCalculator;
	private final ApplicationEventPublisher eventPublisher;
	private final ProductMarketSyncService productMarketSyncService;

	private final MarketFeeService marketFeeService;

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

				StockCheckResult result = stockCrawlerRouter.checkStockWithDetails(product.getVendor(), sourceUrl);
				int bundleQty = product.getLogisticsInfo() != null
					&& product.getLogisticsInfo().getBundleQuantity() != null
						? product.getLogisticsInfo().getBundleQuantity() : 1;

				if (result.sourceGone()) {
					boolean goneChanged = product.getStockStatus() != StockStatus.OUT_OF_STOCK;
					product.updateStockStatus(StockStatus.OUT_OF_STOCK);
					product.markSourceGone(result.sourceGoneReason() != null
						? result.sourceGoneReason() : SourceGoneReason.LINK_DEAD);
					productWriter.save(product);
					BigDecimal existingCost = product.getPriceInfo() != null
						? product.getPriceInfo().getCostPrice() : null;
					MarketRepublishResult goneSync = productMarketSyncService.syncPriceStockPerMarket(
						productId,
						new PricingInputs(existingCost != null ? existingCost : BigDecimal.ZERO,
							bundleQty, marginRate, couponRate, minMarginPrice),
						StockStatus.OUT_OF_STOCK, goneChanged);
					processStatusService.markSuccess(batchId, String.valueOf(productId),
						String.format("[%s] 소스 링크 없음 → 품절 처리(가격 미변경) · 마켓반영 성공%d/스킵%d/실패%d",
							product.getSbCode(), goneSync.synced().size(), goneSync.skipped().size(),
							goneSync.failed().size()));
					Thread.sleep(CRAWL_THROTTLE_MS);
					continue;
				}

				BigDecimal goods = result.costPrice() != null ? result.costPrice() : BigDecimal.ZERO;
				BigDecimal buyPrice = goods;
				if (result.shippingCost() != null && result.shippingCost().signum() > 0 && bundleQty > 0) {
					buyPrice = goods.add(result.shippingCost()
						.divide(BigDecimal.valueOf(bundleQty), 4, RoundingMode.HALF_UP));
				}

				BigDecimal coupangFee = marketFeeService.feeRate(MarketType.COUPANG);
				BigDecimal salePrice = marginCalculator.calculateSalePrice(buyPrice, bundleQty, marginRate,
					couponRate, minMarginPrice, coupangFee);

				BigDecimal oldSalePrice = product.getSalePrice();
				StockStatus oldStatus = product.getStockStatus();
				boolean priceChanged = (salePrice == null) != (oldSalePrice == null)
					|| (salePrice != null && oldSalePrice != null && salePrice.compareTo(oldSalePrice) != 0);
				boolean changed = priceChanged || result.status() != oldStatus;

				ProductUpdateCommand command = ProductUpdateCommand.builder()
					.costPrice(buyPrice)
					.marginRate(marginRate)
					.couponRate(couponRate)
					.minMarginPrice(minMarginPrice)
					.salePrice(salePrice)
					.stock(result.stock())
					.build();
				product.update(command);
				product.clearSourceGone();
				product.updateStockStatus(result.status());
				product.updateRestockDate(result.restockDate());
				productWriter.save(product);

				MarketRepublishResult sync = productMarketSyncService.syncPriceStockPerMarket(
					productId,
					new PricingInputs(buyPrice, bundleQty, marginRate, couponRate, minMarginPrice),
					result.status(), changed);
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
		List<PriceStockItem> items) {
		int failCount = 0;
		for (PriceStockItem item : items) {
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
			ActionLogConstants.BATCH_MANUAL_UPDATE,
			failCount == 0, failCount == 0 ? "수동 배치 완료" : "수동 배치 완료(실패 " + failCount + "건)"));
	}

	@Async("productBatchExecutor")
	public void manualUpdateAllFields(String batchId, List<Long> productIds,
		List<ProductUpdateCommand> commands) {
		int failCount = 0;
		for (int i = 0; i < productIds.size(); i++) {
			try {
				Long productId = productIds.get(i);
				Product product = productReader.findById(productId)
					.orElseThrow(() -> new IllegalArgumentException("상품 없음: " + productId));

				ProductUpdateCommand command = commands.get(i);
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
			ActionLogConstants.BATCH_MANUAL_UPDATE_ALL,
			failCount == 0, failCount == 0 ? "전체 필드 배치 완료" : "전체 필드 배치 완료(실패 " + failCount + "건)"));
	}

	public List<Long> getProductIdsByVendor(VendorType vendor) {
		return productRepository.findByVendor(vendor).stream()
			.map(Product::getId)
			.collect(Collectors.toList());
	}
}
