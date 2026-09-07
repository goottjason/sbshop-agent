package com.sbshop.agent.core.application.market.sync;

import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class MarketPublicationInputsService {
	private final ProductRepository products;
	private final MarketRegistrationRepository registrations;
	private final MarketClientRouter clients;

	public record PreviousEvidence(String id, String label, String categoryId, MarketPublishContext context) {
	}
	public record Inputs(Map<String, Object> schema, List<PreviousEvidence> previousEvidence) {
	}

	public Inputs inputs(Long id, MarketType market, String categoryId) {
		if (id == null || id <= 0 || market != MarketType.COUPANG)
			throw new IllegalArgumentException("쿠팡 상품별 등록 입력을 선택하세요.");
		if (categoryId != null && !categoryId.matches("[1-9][0-9]{0,17}"))
			throw new IllegalArgumentException("카테고리 번호가 올바르지 않습니다.");
		if (TransactionSynchronizationManager.isActualTransactionActive())
			throw new IllegalStateException("등록 입력 메타 조회 중 DB transaction을 유지할 수 없습니다.");
		var product = products.findById(id).orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다."));
		var client = clients.getClient(market);
		String account = client.inspectionAccountReference();
		Map<String, Object> schema = client.describePublication(product, categoryId);
		String selected = Objects.toString(schema.get("categoryId"), "");
		if (account == null || !account.equals(client.inspectionAccountReference()) || selected.isBlank())
			throw new IllegalStateException("등록 메타 조회 계정·카테고리를 확인할 수 없습니다.");
		List<PreviousEvidence> previous = new ArrayList<>();
		registrations.findByProductIdAndMarketType(id, market)
			.ifPresent(reg -> client.previousPublicationContext(product, categoryId, reg.getMarketDetailedInfo())
				.ifPresent(context -> previous.add(new PreviousEvidence(reg.getId().toString(),
					"같은 상품·마켓·카테고리의 과거 자료 · 선택 후 현재 값 확인", context.categoryId(), context))));
		if (!account.equals(client.inspectionAccountReference()))
			throw new IllegalStateException("과거 자료 조회 중 마켓 계정이 변경되었습니다.");
		return new Inputs(schema, List.copyOf(previous));
	}
}
