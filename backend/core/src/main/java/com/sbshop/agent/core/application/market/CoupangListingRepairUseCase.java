package com.sbshop.agent.core.application.market;

import com.sbshop.agent.core.domain.market.MarketFailureClassifier;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.ListingAttributeRepair;
import com.sbshop.agent.core.domain.market.client.dto.ListingAttributeRepairOutcome;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoupangListingRepairUseCase {

	private final ProductRepository productRepository;
	private final MarketRegistrationRepository registrations;
	private final MarketClientRouter marketClientRouter;

	public record Command(List<String> sellerProductIds, String brand, boolean dryRun, long throttleMs) {
		public boolean hasTargets() {
			return (sellerProductIds != null && !sellerProductIds.isEmpty()) || (brand != null && !brand.isBlank());
		}
	}

	public record Outcome(Long productId, String sbCode, String sellerProductId, String result,
		ListingAttributeRepair repair, String detail) {
	}

	private record Target(String sellerProductId, Product product, MarketRegistration registration, String skip) {
	}

	public List<Outcome> repair(Command command) {
		if (command == null || !command.hasTargets())
			throw new IllegalArgumentException("sellerProductIds 또는 brand 가 필요합니다.");
		List<Target> targets = command.sellerProductIds() != null && !command.sellerProductIds().isEmpty()
			? byIds(command.sellerProductIds())
			: byBrand(command.brand().trim());
		MarketClient client = marketClientRouter.getClient(MarketType.COUPANG);
		List<Outcome> outcomes = new ArrayList<>();
		boolean called = false;
		for (Target target : targets) {
			Long productId = target.product() == null ? null : target.product().getId();
			String sbCode = target.product() == null ? null : target.product().getSbCode();
			if (target.skip() != null) {
				outcomes.add(new Outcome(productId, sbCode, target.sellerProductId(), "SKIPPED", null, target.skip()));
				continue;
			}
			if (called)
				sleepQuietly(command.throttleMs());
			called = true;
			outcomes.add(repairOne(client, target, productId, sbCode, !command.dryRun()));
		}
		return outcomes;
	}

	private Outcome repairOne(MarketClient client, Target target, Long productId, String sbCode, boolean submit) {
		String id = target.sellerProductId();
		ListingAttributeRepair repair;
		try {
			repair = client.repairListingAttributes(target.product(), id, submit);
		} catch (UnsupportedOperationException unsupported) {
			return new Outcome(productId, sbCode, id, "UNSUPPORTED", null, unsupported.getMessage());
		} catch (Exception e) {
			recordFailure(target.registration(), e.getMessage(), MarketFailureClassifier.classifyError(e));
			log.error("[쿠팡 구매옵션 보정] 실패: productId={}, sellerProductId={}, error={}", productId, id,
				e.getMessage(), e);
			return new Outcome(productId, sbCode, id, "FAILED", null, e.getMessage());
		}
		if (repair.outcome() == ListingAttributeRepairOutcome.FAILED)
			recordFailure(target.registration(), repair.detail(),
				MarketFailureClassifier.classifyError(repair.detail()));
		return new Outcome(productId, sbCode, id, repair.outcome().name(), repair, repair.detail());
	}

	private void recordFailure(MarketRegistration reg, String message,
		com.sbshop.agent.core.domain.market.SyncErrorType type) {
		reg.recordSyncError(type, message);
		registrations.save(reg);
	}

	private List<Target> byIds(List<String> ids) {
		List<Target> targets = new ArrayList<>();
		for (String raw : ids) {
			String id = raw == null ? "" : raw.trim();
			MarketRegistration reg = id.isEmpty() ? null
				: registrations.findIdentifierCandidates(MarketType.COUPANG, id).stream()
					.filter(r -> id.equals(r.identifier("sellerProductId"))).findFirst().orElse(null);
			if (reg == null) {
				targets.add(new Target(id, null, null, "쿠팡 등록 없음"));
				continue;
			}
			Product product = productRepository.findById(reg.getProductId()).orElse(null);
			targets.add(new Target(id, product, reg, skipReason(product, reg)));
		}
		return targets;
	}

	private List<Target> byBrand(String brand) {
		List<Target> targets = new ArrayList<>();
		for (Product product : productRepository.findByBrand(brand)) {
			MarketRegistration reg = registrations.findByProductIdAndMarketType(product.getId(), MarketType.COUPANG)
				.orElse(null);
			String id = reg == null ? null : reg.extractDeleteCode();
			if (reg == null || id == null) {
				targets.add(new Target(id, product, reg, "쿠팡 등록 없음"));
				continue;
			}
			targets.add(new Target(id, product, reg, skipReason(product, reg)));
		}
		return targets;
	}

	private static String skipReason(Product product, MarketRegistration reg) {
		if (product == null)
			return "상품 없음";
		if (product.isDeleted())
			return "폐기된 상품";
		return reg.connectionWriteBlock();
	}

	private static void sleepQuietly(long millis) {
		if (millis <= 0)
			return;
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
