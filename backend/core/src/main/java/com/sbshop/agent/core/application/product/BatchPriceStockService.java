package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.dto.PricingInputs;
import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import com.sbshop.agent.core.domain.order.enums.MarketType;
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
	// 벤더별 재고 크롤러 라우팅(IHB=iHerb 내부API, FTN=Fortnum&Mason Scrapling 서비스).
	private final StockCrawlerRouter stockCrawlerRouter;
	private final ProcessStatusService processStatusService;
	private final MarginCalculator marginCalculator;
	private final ApplicationEventPublisher eventPublisher;
	private final ProductMarketSyncService productMarketSyncService;
	// D-094: 기준가(sb_product.sale_price)를 쿠팡 실수수료로 산정하기 위해 마켓별 수수료를 조회.
	private final MarketFeeService marketFeeService;

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

				// 벤더별 크롤러로 재고/원가 조회(F&M은 Scrapling 서비스가 원가를 원화로 산출해 반환).
				StockCheckResult result = stockCrawlerRouter.checkStockWithDetails(product.getVendor(), sourceUrl);
				int bundleQty = product.getLogisticsInfo() != null
					&& product.getLogisticsInfo().getBundleQuantity() != null
						? product.getLogisticsInfo().getBundleQuantity() : 1;

				// 링크 소멸(404 등) → 가격 재산정 없이 재고만 품절 처리(오가격 방지). 기존 가격/원가 유지.
				if (result.sourceGone()) {
					boolean goneChanged = product.getStockStatus() != StockStatus.OUT_OF_STOCK;
					product.updateStockStatus(StockStatus.OUT_OF_STOCK);
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

				// buyPrice = 상품원가 + 배송비/묶음수량 (유효단가). 이후 계산이 ×묶음수량 하므로
				// 결과적으로 (원가×묶음) + 배송비 1회가 된다(배송비는 묶음수량과 무관하게 주문당 1회).
				// iHerb 등 shippingCost 없는 경로는 원가 그대로(동작 불변).
				BigDecimal goods = result.costPrice() != null ? result.costPrice() : BigDecimal.ZERO;
				BigDecimal buyPrice = goods;
				if (result.shippingCost() != null && result.shippingCost().signum() > 0 && bundleQty > 0) {
					buyPrice = goods.add(result.shippingCost()
						.divide(BigDecimal.valueOf(bundleQty), 4, java.math.RoundingMode.HALF_UP));
				}
				// F-BATCH-6: 쿠폰율을 실매입가에 반영(구매가 × (1-쿠폰%))한 뒤 판매가를 산정한다.
				// D-094: 기준가(sb_product.sale_price)는 쿠팡 실수수료 기준으로 산정한다(표시·단건용).
				// 각 마켓 전송가는 아래 syncPriceStockPerMarket에서 마켓별 실수수료로 따로 재산정한다.
				BigDecimal coupangFee = marketFeeService.feeRate(MarketType.COUPANG);
				BigDecimal salePrice = marginCalculator.calculateSalePrice(buyPrice, bundleQty, marginRate,
					couponRate, minMarginPrice, coupangFee);

				// 이전 DB값 대비 실제 변경 여부(가격·판매상태) — 변경 없으면 Cafe24 재전송 스킵 대상.
				BigDecimal oldSalePrice = product.getSalePrice();
				StockStatus oldStatus = product.getStockStatus();
				boolean priceChanged = (salePrice == null) != (oldSalePrice == null)
					|| (salePrice != null && oldSalePrice != null && salePrice.compareTo(oldSalePrice) != 0);
				boolean changed = priceChanged || result.status() != oldStatus;

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

				// D-094: 배치 갱신분은 마켓별 실수수료로 가격을 따로 산정해 각 마켓에 반영한다.
				// changed=false면 Cafe24(직전 성공분)는 재전송 스킵.
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
