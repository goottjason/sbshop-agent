package com.sbshop.agent.core.application.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.dto.PriceStockItem;
import com.sbshop.agent.core.application.pricing.VendorPricePolicyService;
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
import com.sbshop.agent.core.domain.pricing.LandedCostCalculator;
import com.sbshop.agent.core.domain.pricing.VendorPricePolicy;
import com.sbshop.agent.core.domain.product.enums.SourceGoneReason;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
	private final VendorPricePolicyService vendorPricePolicyService;

	private static final long CRAWL_THROTTLE_MS = 500L;

	private static final int MAX_REASON_LEN = 160;

	private static final ObjectMapper DETAILS_MAPPER = new ObjectMapper();

	/**
	 * 크롤 실패를 상품에 남긴다 — <b>재고·가격은 건드리지 않는다.</b> 일시 오류일 수 있기 때문이다.
	 * 기록 자체가 실패해도 배치는 계속한다 — 부수 기록 때문에 본 작업을 멈추지 않는다.
	 */
	private void recordCrawlFailure(Long productId, Exception cause) {
		try {
			productReader.findById(productId).ifPresent(p -> {
				String reason = cause.getMessage() != null ? cause.getMessage() : cause.toString();
				p.recordCrawlFailure(reason);
				productWriter.save(p);
			});
		} catch (Exception e) {
			log.warn("크롤 실패 기록 실패(무시하고 계속): productId={}, error={}", productId, e.getMessage());
		}
	}

	private static String renderMarketOutcome(MarketRepublishResult sync) {
		String base = String.format(" · 마켓반영 성공%d/스킵%d/실패%d",
			sync.synced().size(), sync.skipped().size(), sync.failed().size());
		if (sync.failed().isEmpty()) {
			return base;
		}
		StringBuilder sb = new StringBuilder(base).append(" (");
		boolean first = true;
		for (Map.Entry<MarketType, String> entry : sync.failed().entrySet()) {
			if (!first) {
				sb.append(" | ");
			}
			first = false;
			sb.append(entry.getKey()).append(": ").append(abbreviate(entry.getValue()));
		}
		return sb.append(")").toString();
	}

	private static String abbreviate(String reason) {
		if (reason == null || reason.isBlank()) {
			return "사유 없음";
		}
		String flat = reason.replaceAll("\\s+", " ").trim();
		return flat.length() <= MAX_REASON_LEN ? flat : flat.substring(0, MAX_REASON_LEN) + "…";
	}

	private static boolean isOutOfStockWithoutCost(StockCheckResult result) {
		return result.status() == StockStatus.OUT_OF_STOCK
			&& (result.costPrice() == null || result.costPrice().signum() <= 0);
	}

	private boolean recordOutcome(String batchId, Long productId, MarketRepublishResult sync, String message) {
		String details = renderMarketDetails(sync);
		if (sync.failed().isEmpty()) {
			processStatusService.markSuccess(batchId, String.valueOf(productId), message, details);
			return false;
		}
		processStatusService.markPartialFailed(batchId, String.valueOf(productId), message, details);
		return true;
	}

	private static String renderMarketDetails(MarketRepublishResult sync) {
		ObjectNode root = DETAILS_MAPPER.createObjectNode();
		ArrayNode synced = root.putArray("synced");
		sync.synced().forEach(m -> synced.add(m.name()));
		ArrayNode skipped = root.putArray("skipped");
		sync.skipped().forEach(m -> skipped.add(m.name()));
		ArrayNode failed = root.putArray("failed");
		for (Map.Entry<MarketType, String> entry : sync.failed().entrySet()) {
			ObjectNode one = failed.addObject();
			one.put("market", entry.getKey().name());
			one.put("reason", entry.getValue() == null ? "사유 없음" : entry.getValue());
		}
		return root.toString();
	}

	private static String batchMessage(String prefix, int failCount, int partialCount) {
		if (failCount == 0 && partialCount == 0) {
			return prefix;
		}
		StringBuilder sb = new StringBuilder(prefix).append("(");
		if (failCount > 0) {
			sb.append("실패 ").append(failCount).append("건");
		}
		if (partialCount > 0) {
			if (failCount > 0) {
				sb.append(", ");
			}
			sb.append("부분실패 ").append(partialCount).append("건");
		}
		return sb.append(")").toString();
	}

	@Async("productBatchExecutor")
	public void crawlAndUpdatePriceStock(String batchId, List<Long> productIds,
		BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice, String actionType) {
		int failCount = 0;
		int partialCount = 0;
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
					product.recordCrawlSuccess();
					productWriter.save(product);
					// 원본이 사라진 상품은 새 가격을 알 수 없다. 원가(null)를 그대로 넘겨
					// 가격은 건드리지 않고 재고만 0 으로 보낸다 — 0 으로 계산하면 쓰레기 값이 나간다(D-253).
					MarketRepublishResult goneSync = productMarketSyncService.syncPriceStockPerMarket(
						productId,
						new PricingInputs(null, bundleQty, marginRate, couponRate, minMarginPrice),
						StockStatus.OUT_OF_STOCK, goneChanged);
					if (recordOutcome(batchId, productId, goneSync,
						String.format("[%s] 소스 링크 없음 → 품절 처리(가격 미전송)%s",
							product.getSbCode(), renderMarketOutcome(goneSync)))) {
						partialCount++;
					}
					Thread.sleep(CRAWL_THROTTLE_MS);
					continue;
				}

				if (isOutOfStockWithoutCost(result)) {
					boolean soldOutChanged = product.getStockStatus() != StockStatus.OUT_OF_STOCK;
					product.updateStockStatus(StockStatus.OUT_OF_STOCK);
					product.updateRestockDate(result.restockDate());
					product.update(ProductUpdateCommand.builder().stock(result.stock()).build());
					product.recordCrawlSuccess();
					productWriter.save(product);
					MarketRepublishResult soldOutSync = productMarketSyncService.syncPriceStockPerMarket(
						productId,
						new PricingInputs(null, bundleQty, marginRate, couponRate, minMarginPrice),
						StockStatus.OUT_OF_STOCK, soldOutChanged);
					if (recordOutcome(batchId, productId, soldOutSync,
						String.format("[%s] 소싱처 품절 → 재고 0 전송(가격 미전송)%s",
							product.getSbCode(), renderMarketOutcome(soldOutSync)))) {
						partialCount++;
					}
					Thread.sleep(CRAWL_THROTTLE_MS);
					continue;
				}

				BigDecimal weightKg = product.getLogisticsInfo() != null
					? product.getLogisticsInfo().getWeight() : null;
				VendorPricePolicy vendorPolicy = vendorPricePolicyService.find(product.getVendor())
					.orElse(null);
				BigDecimal buyPrice = LandedCostCalculator.buyPricePerUnit(result.costPrice(), weightKg,
					bundleQty, vendorPolicy, result.fxRate());

				BigDecimal coupangFee = marketFeeService.feeRate(MarketType.COUPANG);
				BigDecimal salePrice = (vendorPolicy == null || vendorPolicy.getDomesticFee() == null)
					? marginCalculator.calculateSalePrice(buyPrice, bundleQty, marginRate,
						couponRate, minMarginPrice, coupangFee)
					: marginCalculator.calculateSalePrice(buyPrice, bundleQty, marginRate,
						couponRate, minMarginPrice, coupangFee,
						vendorPolicy.getDomesticFee(), vendorPolicy.getDomesticFreeOver());

				BigDecimal oldSalePrice = product.getSalePrice();
				StockStatus oldStatus = product.getStockStatus();
				boolean priceChanged = (salePrice == null) != (oldSalePrice == null)
					|| (salePrice != null && oldSalePrice != null && salePrice.compareTo(oldSalePrice) != 0);
				boolean statusChanged = result.status() != oldStatus;
				boolean changed = statusChanged
					|| (priceChanged && result.status() != StockStatus.OUT_OF_STOCK);

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
				product.recordCrawlSuccess();
				productWriter.save(product);

				MarketRepublishResult sync = productMarketSyncService.syncPriceStockPerMarket(
					productId,
					new PricingInputs(buyPrice, bundleQty, marginRate, couponRate, minMarginPrice),
					result.status(), changed);
				if (recordOutcome(batchId, productId, sync,
					String.format("[%s] 가격:%s, 재고:%d%s",
						product.getSbCode(), salePrice, result.stock(), renderMarketOutcome(sync)))) {
					partialCount++;
				}
				Thread.sleep(CRAWL_THROTTLE_MS);
			} catch (Exception e) {
				log.error("배치 업데이트 실패: productId={}", productId, e);
				recordCrawlFailure(productId, e);
				processStatusService.markFailed(batchId, String.valueOf(productId), e.getMessage());
				failCount++;
			}
		}
		eventPublisher.publishEvent(new BatchCompletedEvent(this, batchId,
			actionType,
			failCount == 0 && partialCount == 0,
			batchMessage("배치 완료", failCount, partialCount)));
	}

	@Async("productBatchExecutor")
	public void manualUpdatePriceStock(String batchId,
		List<PriceStockItem> items) {
		int failCount = 0;
		int partialCount = 0;
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
					productId, price != null ? price.intValue() : null, newStatus,
					statusChanged || (priceChanged && newStatus != StockStatus.OUT_OF_STOCK));
				if (recordOutcome(batchId, productId, sync,
					String.format("[%s] 가격:%s->%s, 판매상태:%s->%s%s",
						product.getSbCode(), oldPrice, price, oldStatus, newStatus, renderMarketOutcome(sync)))) {
					partialCount++;
				}
			} catch (Exception e) {
				log.error("수동 업데이트 실패: productId={}", productId, e);
				processStatusService.markFailed(batchId, String.valueOf(productId), e.getMessage());
				failCount++;
			}
		}
		eventPublisher.publishEvent(new BatchCompletedEvent(this, batchId,
			ActionLogConstants.BATCH_MANUAL_UPDATE,
			failCount == 0 && partialCount == 0,
			batchMessage("수동 배치 완료", failCount, partialCount)));
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
