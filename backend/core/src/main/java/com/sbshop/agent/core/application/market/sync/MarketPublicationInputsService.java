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
	private final MarketPublicationService publicationReads;

	public record PreviousEvidence(String id, String label, String categoryId, MarketPublishContext context) {
	}
	public record Inputs(Map<String, Object> schema, List<PreviousEvidence> previousEvidence) {
	}

	public Inputs inputs(Long id, MarketType market, String categoryId) {
		if (id == null || id <= 0 || market != MarketType.COUPANG && market != MarketType.ELEVEN_STREET)
			throw new IllegalArgumentException("쿠팡 또는 11번가 상품별 등록 입력을 선택하세요.");
		if (categoryId != null && !categoryId.matches("[1-9][0-9]{0,17}"))
			throw new IllegalArgumentException("카테고리 번호가 올바르지 않습니다.");
		if (TransactionSynchronizationManager.isActualTransactionActive())
			throw new IllegalStateException("등록 입력 메타 조회 중 DB transaction을 유지할 수 없습니다.");
		var product = products.findById(id).orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다."));
		var client = clients.getClient(market);
		String account = client.inspectionAccountReference();
		Map<String, Object> schema = market == MarketType.ELEVEN_STREET
			? publicationReads.withPreparationReadScope(market, () -> client.describePublication(product, categoryId))
			: client.describePublication(product, categoryId);
		String selected = Objects.toString(schema.get("categoryId"), "");
		if (account == null || !account.equals(client.inspectionAccountReference())
			|| market == MarketType.COUPANG && selected.isBlank())
			throw new IllegalStateException("등록 메타 조회 계정·카테고리를 확인할 수 없습니다.");
		if (market == MarketType.ELEVEN_STREET) {
			requireInputOnly(schema);
			var pinned = new LinkedHashMap<String, Object>(schema);
			pinned.put("accountReference", account);
			return new Inputs(pinned, List.of());
		}
		List<PreviousEvidence> previous = new ArrayList<>();
		registrations.findByProductIdAndMarketType(id, market)
			.ifPresent(reg -> client.previousPublicationContext(product, categoryId, reg.getMarketDetailedInfo())
				.ifPresent(context -> previous.add(new PreviousEvidence(reg.getId().toString(),
					"같은 상품·마켓·카테고리의 과거 자료 · 선택 후 현재 값 확인", context.categoryId(), context))));
		if (!account.equals(client.inspectionAccountReference()))
			throw new IllegalStateException("과거 자료 조회 중 마켓 계정이 변경되었습니다.");
		return new Inputs(schema, List.copyOf(previous));
	}

	public Map<String, Object> reviewElevenstInputs(Long id, long revision, String expectedAccount, MarketPublishContext context) {
		if (id == null || id <= 0 || revision < 0 || context == null || expectedAccount == null || expectedAccount.isBlank())
			throw new IllegalArgumentException("11번가 상품·현재 revision·검토 입력을 확인하세요.");
		if (TransactionSynchronizationManager.isActualTransactionActive())
			throw new IllegalStateException("등록 입력 점검 중 DB transaction을 유지할 수 없습니다.");
		var product = products.findById(id).orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다."));
		if (product.getRevision() != revision)
			throw new com.sbshop.agent.core.application.product.edit.ProductEditConflictException("상품이 변경되었습니다. 입력 자료를 다시 조회하세요.");
		var client = clients.getClient(MarketType.ELEVEN_STREET);
		String account = client.inspectionAccountReference();
		if (account == null || account.isBlank())
			throw new IllegalStateException("11번가 계정을 확인하지 못했습니다.");
		if (!account.equals(expectedAccount))
			throw new com.sbshop.agent.core.application.product.edit.ProductEditConflictException("주소 선택 후 11번가 계정이 변경되었습니다. 주소 목록부터 다시 조회하세요.");
		var result = publicationReads.withPreparationReadScope(MarketType.ELEVEN_STREET,
			() -> client.reviewPublicationInputs(product, context));
		if (!account.equals(client.inspectionAccountReference()))
			throw new IllegalStateException("입력 점검 중 11번가 계정이 변경되었습니다.");
		if (products.findById(id).orElseThrow().getRevision() != revision)
			throw new com.sbshop.agent.core.application.product.edit.ProductEditConflictException("입력 점검 중 상품이 변경되었습니다.");
		requireInputOnly(result);
		return result;
	}

	private void requireInputOnly(Map<String, Object> result) {
		if (result == null || !"INPUT_ONLY".equals(result.get("stage")) || !Boolean.FALSE.equals(result.get("executable")))
			throw new IllegalStateException("11번가 입력 준비와 등록 실행 상태를 확인하지 못했습니다.");
	}
}
