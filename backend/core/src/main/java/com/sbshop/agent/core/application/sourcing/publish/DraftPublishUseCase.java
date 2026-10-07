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
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.product.vo.ProductWeight;
import com.sbshop.agent.core.domain.sourcing.MarketDraft;
import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import com.sbshop.agent.core.domain.sourcing.enums.DraftStatus;
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
	private final ProductReader productReader;
	private final MarketRegistrationRepository registrations;

	public PublishResult publish(Long draftId) {
		ProductDraft draft = draftPublishTxService.requireDraft(draftId);
		if (draft.getDraftStatus() != DraftStatus.READY && draft.getDraftStatus() != DraftStatus.FAILED) {
			throw new IllegalStateException("초안이 이미 등록 중이거나 등록 가능한 상태가 아닙니다: " + draftId);
		}

		publishableTargets(draft);
		if (draft.getProductId() == null)
			toCreateCommand(draft);
		draftPublishTxService.markPublishing(draftId);
		Long productId = draft.getProductId();
		List<MarketOutcome> outcomes = new ArrayList<>();
		try {
			ProductDraft claimedDraft = draftPublishTxService.requireDraft(draftId);
			productId = claimedDraft.getProductId();
			List<MarketDraft> targets = publishableTargets(claimedDraft);
			Product product = productId == null ? createProduct(toCreateCommand(claimedDraft))
				: productReader.findById(productId).orElseThrow(() -> new IllegalStateException(
					"기존 등록 상품을 찾을 수 없습니다: " + claimedDraft.getProductId()));
			if (product.isDeleted())
				throw new IllegalStateException("폐기된 상품은 마켓에 등록할 수 없습니다: " + product.getId());
			productId = product.getId();
			draftPublishTxService.attachProduct(draftId, productId);
			for (MarketDraft md : targets) {
				outcomes.add(publishToMarket(productId, product, md));
			}

			boolean allOk = outcomes.stream().allMatch(MarketOutcome::ok);
			draftPublishTxService.finish(draftId, productId, allOk, outcomes);

			log.info("[초안등록] draftId={} productId={} 성공 {}/{}",
				draftId, productId, outcomes.stream().filter(MarketOutcome::ok).count(), outcomes.size());
			return new PublishResult(draftId, productId, product.getSbCode(), outcomes);
		} catch (RuntimeException e) {
			draftPublishTxService.finish(draftId, productId, false, outcomes);
			throw e;
		}
	}

	private List<MarketDraft> publishableTargets(ProductDraft draft) {
		if (!Boolean.TRUE.equals(draft.getCustomsAck())) {
			throw new IllegalStateException(
				"통관 확인이 필요한 상품입니다. 성분을 확인하고 승인한 뒤 등록하세요.");
		}
		List<MarketDraft> targets = draft.enabledMarketDrafts().stream()
			.filter(MarketDraft::isValid).toList();
		if (targets.isEmpty()) {
			throw new IllegalStateException(
				"등록 가능한 마켓이 없습니다. 마켓별 필수필드를 채운 뒤 다시 시도하세요.");
		}
		return targets;
	}

	private Product createProduct(ProductCreateCommand command) {
		BulkProductCreateResult result = productCreateUseCase.createBulk(List.of(command));
		if (result.succeeded().isEmpty()) {
			String reason = result.failed().isEmpty() ? "알 수 없는 오류"
				: result.failed().get(0).reason();
			throw new IllegalStateException("상품 생성 실패: " + reason);
		}
		return result.succeeded().get(0).product();
	}

	private MarketOutcome publishToMarket(Long productId, Product product, MarketDraft md) {
		MarketType marketType = md.getMarketType();
		MarketRegistration registration = null;
		try {
			var previous = registrations.findByProductIdAndMarketType(productId, marketType);
			if (previous.isPresent()) {
				MarketRegistration existing = previous.get();
				if (existing.connectionWriteBlock() == null && existing.extractLiveLookupId() != null
					&& Boolean.TRUE.equals(existing.getIsSynced()))
					return MarketOutcome.ok(marketType, existing.getMarketIdentifiers());
				return MarketOutcome.failed(marketType,
					"이전 마켓 등록 결과를 확인해야 합니다. 상품 " + productId + "의 마켓 연결을 확인한 뒤 처리하세요.");
			}
			if (md.getMarketIdentifiers() != null && !readStringMap(md.getMarketIdentifiers()).isEmpty())
				return MarketOutcome.failed(marketType, "이전 등록의 마켓 연결을 찾을 수 없습니다. 상품 " + productId + "을 확인하세요.");
			if (!marketClientRouter.hasClient(marketType))
				return MarketOutcome.failed(marketType, "지원하지 않는 마켓");
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
			ProductWeight.fromGrams(draft.getWeightG()),
			draft.getCapacity(),
			draft.getMeasureUnit() != null ? draft.getMeasureUnit() : MeasureUnit.EA,
			readList(draft.getSourceImages()),

			readList(draft.getHostedImages()),
			draft.getDetailHtml(),
			draft.getCategory(),
			true,
			draft.getBundleQty() != null ? draft.getBundleQty() : 1,
			draft.getMarginRate() != null ? draft.getMarginRate() : BigDecimal.ZERO,
			parseVendor(draft.getVendor()),
			draft.getBarcode());
	}

	private MarketPublishContext toContext(MarketDraft md) {
		return new MarketPublishContext(
			md.getCategoryId(),
			md.getCategoryPath(),
			md.getSalePrice(),
			readList(md.getKeywords()),
			readStringMap(md.getNoticeFields()),
			readObjectMap(md.getExtraFields()),
			md.getProductName());
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
