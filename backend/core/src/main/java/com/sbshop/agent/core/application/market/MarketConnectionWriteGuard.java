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
		"syncBarcode", "syncProductFields", "repairProductNotice", "requestApproval", "removeSellerImmediateDiscount");

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public void requireWritable(MarketType market, Object[] args) {
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
		for (var reg : rows) {
			String reason = reg.connectionWriteBlock();
			if (reason != null)
				throw new IllegalStateException(market.getLabel() + ": " + reason);
		}
	}

	private boolean matchesIdentifier(MarketRegistration reg, String id) {
		return List.of("sellerProductId", "vendorItemId", "productId", "originProductNo", "channelProductNo",
			"prdNo", "elevenstId", "product_no", "product_code", "goodsNo", "itemNo", "goodsCode")
			.stream().anyMatch(key -> id.equals(reg.identifier(key)));
	}
}
