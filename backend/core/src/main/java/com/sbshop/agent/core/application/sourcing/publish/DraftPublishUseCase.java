package com.sbshop.agent.core.application.sourcing.publish;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.MarketRegistrationTxService;
import com.sbshop.agent.core.application.product.ProductCreateUseCase;
import com.sbshop.agent.core.application.product.dto.BulkProductCreateResult;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.sourcing.MarketDraft;
import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DraftPublishUseCase {
	private final ProductCreateUseCase productCreateUseCase;
	private final MarketClientRouter marketClientRouter;
	private final MarketRegistrationTxService registrationTxService;
	private final DraftPublishTxService draftPublishTxService;
	private final ObjectMapper objectMapper;

	public PublishResult publish(Long draftId) {
		ProductDraft draft = draftPublishTxService.requireDraft(draftId);

		if (!Boolean.TRUE.equals(draft.getCustomsAck())) {
			throw new IllegalStateException(
				"통관 확인이 필요한 상품입니다. 성분을 확인하고 승인한 뒤 등록하세요.");
		}

		List<MarketDraft> targets = draft.enabledMarketDrafts().stream()
			.filter(MarketDraft::isValid)
			.toList();
		if (targets.isEmpty()) {
			throw new IllegalStateException(
				"등록 가능한 마켓이 없습니다. 마켓별 필수필드를 채운 뒤 다시 시도하세요.");
		}

		draftPublishTxService.markPublishing(draft.getId());

		Product product = createProduct(draft);
		Long productId = product.getId();

		List<MarketOutcome> outcomes = new ArrayList<>();
		for (MarketDraft md : targets) {
			outcomes.add(publishToMarket(productId, product, md));
		}

		boolean allOk = outcomes.stream().allMatch(MarketOutcome::ok);
		draftPublishTxService.finish(draft.getId(), productId, allOk, outcomes);

		log.info("[초안등록] draftId={} productId={} 성공 {}/{}",
			draftId, productId, outcomes.stream().filter(MarketOutcome::ok).count(), outcomes.size());
		return new PublishResult(draftId, productId, product.getSbCode(), outcomes);
	}

	private Product createProduct(ProductDraft draft) {
		BulkProductCreateResult result = productCreateUseCase.createBulk(List.of(toCreateCommand(draft)));
		if (result.succeeded().isEmpty()) {
			String reason = result.failed().isEmpty() ? "알 수 없는 오류"
				: result.failed().get(0).reason();
			throw new IllegalStateException("상품 생성 실패: " + reason);
		}
		return result.succeeded().get(0).product();
	}

	private MarketOutcome publishToMarket(Long productId, Product product, MarketDraft md) {
		MarketType marketType = md.getMarketType();
		if (!marketClientRouter.hasClient(marketType)) {
			return MarketOutcome.failed(marketType, "지원하지 않는 마켓");
		}
		MarketRegistration registration = null;
		try {
			registration = registrationTxService.savePending(productId, marketType, md.getProductName());

			MarketClient client = marketClientRouter.getClient(marketType);
			Map<String, String> identifiers = client.publish(product, toContext(md));

			String identifiersJson = objectMapper.writeValueAsString(identifiers);
			registrationTxService.markPublished(registration, identifiersJson);
			return MarketOutcome.ok(marketType, identifiersJson);
		} catch (Exception e) {
			log.error("[초안등록] 마켓 게시 실패 productId={} market={}", productId, marketType, e);
			return MarketOutcome.failed(marketType, e.getMessage());
		}
	}

	private ProductCreateCommand toCreateCommand(ProductDraft draft) {
		return new ProductCreateCommand(
			draft.getSourceUrl(),
			draft.getCostPrice(),
			draft.getBaseNameKo(),
			draft.getOriginalName(),
			draft.getBrand(),
			draft.getOrigin(),
			draft.getWeightG(),
			draft.getCapacity(),
			draft.getMeasureUnit() != null ? draft.getMeasureUnit() : MeasureUnit.EA,
			readList(draft.getSourceImages()),

			readList(draft.getHostedImages()),
			draft.getDetailHtml(),
			draft.getCategory(),
			true,
			draft.getBundleQty() != null ? draft.getBundleQty() : 1,
			draft.getMarginRate() != null ? draft.getMarginRate() : BigDecimal.ZERO,
			parseVendor(draft.getVendor()));
	}

	private MarketPublishContext toContext(MarketDraft md) {
		return new MarketPublishContext(
			md.getCategoryId(),
			md.getCategoryPath(),
			md.getSalePrice(),
			readList(md.getKeywords()),
			readStringMap(md.getNoticeFields()),
			readObjectMap(md.getExtraFields()));
	}

	private VendorType parseVendor(String raw) {
		try {
			return VendorType.valueOf(raw);
		} catch (Exception e) {
			return VendorType.IHB;
		}
	}

	private List<String> readList(String json) {
		if (json == null || json.isBlank())
			return List.of();
		try {
			return objectMapper.readValue(json, new TypeReference<List<String>>() {});
		} catch (Exception e) {
			return List.of();
		}
	}

	private Map<String, String> readStringMap(String json) {
		if (json == null || json.isBlank())
			return Map.of();
		try {
			return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
		} catch (Exception e) {
			return Map.of();
		}
	}

	private Map<String, Object> readObjectMap(String json) {
		if (json == null || json.isBlank())
			return Map.of();
		try {
			return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
		} catch (Exception e) {
			return Map.of();
		}
	}

	public record MarketOutcome(MarketType marketType, boolean ok, String identifiers, String error) {
		static MarketOutcome ok(MarketType m, String identifiers) {
			return new MarketOutcome(m, true, identifiers, null);
		}

		static MarketOutcome failed(MarketType m, String error) {
			return new MarketOutcome(m, false, null, error);
		}
	}

	public record PublishResult(Long draftId, Long productId, String sbCode,
		List<MarketOutcome> outcomes) {
		public long successCount() {
			return outcomes.stream().filter(MarketOutcome::ok).count();
		}
	}
}
