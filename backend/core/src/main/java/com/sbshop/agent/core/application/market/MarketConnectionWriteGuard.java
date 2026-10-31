package com.sbshop.agent.core.application.market;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class MarketConnectionWriteGuard {
	private final MarketRegistrationRepository registrations;
	private final com.sbshop.agent.core.domain.product.retirement.ProductRetirementRepository retirements;

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public void requirePublicationIntent(MarketType market, Product product, String operationId) {
		var reg = registrations.findByProductIdAndMarketType(product.getId(), market).orElseThrow();
		if (operationId == null || !operationId.equals(reg.getPublicationOperationId())
			|| reg
				.getConnectionState() == com.sbshop.agent.core.domain.market.MarketConnectionState.DETACHED_PROHIBITED)
			throw new IllegalStateException("검토 후 접수된 등록 의도가 없거나 영구 판매금지입니다.");
	}

	// New mutating methods must be classified explicitly by the router.
	public static final Set<String> WRITES = Set.of("publish", "writeSalePrice", "syncPriceAndStock",
		"syncImagesAndHtml",
		"deleteFromMarket",
		"syncBarcode", "syncProductFields", "repairProductNotice", "repairListingAttributes", "requestApproval",
		"removeSellerImmediateDiscount");

	public static final Set<String> RETIREMENT_BLOCKED = Set.of("publish", "submitPreparedPublication",
		"finalizePreparedPublication", "syncPriceAndStock", "writeSalePrice", "writeStockQuantity",
		"writePreparedProductFields", "syncProductFields", "syncImagesAndHtml", "syncBarcode", "requestApproval",
		"removeSellerImmediateDiscount", "repairListingAttributes", "repairProductNotice");

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public void requireWritable(MarketType market, Object[] args) {
		requireWritable(market, null, args);
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public void requireWritable(MarketType market, String method, Object[] args) {
		List<MarketRegistration> rows = targets(market, args);
		boolean delete = "deleteFromMarket".equals(method);
		for (var reg : rows) {
			String reason = delete ? deleteBlock(reg) : reg.connectionWriteBlock();
			if (reason != null)
				throw new IllegalStateException(market.getLabel() + ": " + reason);
		}
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public void requireNotRetiring(MarketType market, Object[] args) {
		Set<Long> ids = new LinkedHashSet<>();
		Arrays.stream(args).filter(Product.class::isInstance).map(a -> ((Product)a).getId()).filter(Objects::nonNull)
			.forEach(ids::add);
		if (ids.isEmpty() && args.length > 0 && args[0] instanceof String)
			targets(market, args).forEach(r -> ids.add(r.getProductId()));
		List<Long> productIds = List.copyOf(ids);
		if (!productIds.isEmpty() && retirements.existsByProductIdInAndRetirementStatus(productIds,
			com.sbshop.agent.core.domain.product.retirement.RetirementStatus.IN_PROGRESS))
			throw new IllegalStateException(market.getLabel() + ": 퇴출 진행 중인 상품이라 가격·재고·판매상태를 보내지 않습니다.");
	}

	private static String deleteBlock(MarketRegistration reg) {
		if (reg.getPublicationOperationId() != null || reg.getConnectionState().detached())
			return reg.connectionWriteBlock();
		return null;
	}

	private List<MarketRegistration> targets(MarketType market, Object[] args) {
		List<MarketRegistration> rows;
		Product product = Arrays.stream(args).filter(Product.class::isInstance).map(Product.class::cast).findFirst()
			.orElse(null);
		if (product != null)
			rows = registrations.findByProductIdAndMarketType(product.getId(), market).stream().toList();
		else if (args.length > 0 && args[0] instanceof String id) {
			rows = registrations.findIdentifierCandidates(market, id).stream()
				.filter(r -> matchesIdentifier(r, id)).toList();
		} else
			throw new IllegalArgumentException("마켓 쓰기 대상 식별자가 없습니다.");
		return rows;
	}

	private boolean matchesIdentifier(MarketRegistration reg, String id) {
		return List.of("sellerProductId", "vendorItemId", "productId", "originProductNo", "channelProductNo",
			"prdNo", "elevenstId", "product_no", "product_code", "goodsNo", "itemNo", "goodsCode")
			.stream().anyMatch(key -> id.equals(reg.identifier(key)));
	}
}
