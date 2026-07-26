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

/**
 * 검수 완료된 초안 → 상품 생성 → 마켓 등록(S6).
 *
 * <p>흐름은 기존 {@code ProductPublishUseCase}의 규율을 그대로 따른다:
 * <b>PENDING 선-저장 → publish(트랜잭션 밖) → identifiers+SYNCED 갱신</b>.
 * 외부 게시는 되돌릴 수 없으므로, 게시 전에 등록행을 커밋해 두어야 게시 성공 후 DB 쓰기가 실패해도
 * 고아가 아니라 복구 가능한 미완료 상태로 남는다(F-PSRC-14).
 *
 * <p>마켓 하나가 실패해도 나머지는 계속 등록한다 — 4마켓 중 1곳 실패로 전부 롤백하면
 * 사용자가 처음부터 다시 해야 한다. 실패는 {@code MarketDraft.publishError}에 남겨
 * 그 마켓만 재시도할 수 있게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftPublishUseCase {

	private final ProductCreateUseCase productCreateUseCase;
	private final MarketClientRouter marketClientRouter;
	private final MarketRegistrationTxService registrationTxService;
	private final DraftPublishTxService draftPublishTxService;
	private final ObjectMapper objectMapper;

	/**
	 * @throws IllegalStateException 통관 확인이 필요한데 승인되지 않았거나, 등록 가능한 마켓이 없을 때
	 */
	public PublishResult publish(Long draftId) {
		ProductDraft draft = draftPublishTxService.requireDraft(draftId);

		// 통관 REVIEW 미승인 상태로는 등록하지 않는다. 경고만 띄우고 등록을 허용하면 경고가 무의미하다.
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

		// 1) 상품 생성 — 기존 대량 생성 경로를 그대로 탄다(SKU 채번·이미지 호스팅 재사용).
		Product product = createProduct(draft);
		Long productId = product.getId();

		// 2) 마켓별 게시 — 하나가 실패해도 나머지는 계속한다.
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
		BulkProductCreateResult result =
			productCreateUseCase.createBulk(List.of(toCreateCommand(draft)));
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
			// 되돌릴 수 없는 외부 게시 전에 PENDING 등록행을 먼저 커밋한다(F-PSRC-14).
			registration = registrationTxService.savePending(productId, marketType, md.getProductName());

			MarketClient client = marketClientRouter.getClient(marketType);
			Map<String, String> identifiers = client.publish(product, toContext(md));

			String identifiersJson = objectMapper.writeValueAsString(identifiers);
			registrationTxService.markPublished(registration, identifiersJson);
			return MarketOutcome.ok(marketType, identifiersJson);
		} catch (Exception e) {
			// 게시가 성공한 뒤 DB 갱신에서 터졌을 수 있다 — 그 경우 PENDING 행이 남아 복구 가능하다.
			log.error("[초안등록] 마켓 게시 실패 productId={} market={}", productId, marketType, e);
			return MarketOutcome.failed(marketType, e.getMessage());
		}
	}

	// --- 변환 ---

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
			// 이미 R2에 올려 둔 이미지를 넘겨 재업로드를 막는다.
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
			return objectMapper.readValue(json, new TypeReference<List<String>>() { });
		} catch (Exception e) {
			return List.of();
		}
	}

	private Map<String, String> readStringMap(String json) {
		if (json == null || json.isBlank())
			return Map.of();
		try {
			return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() { });
		} catch (Exception e) {
			return Map.of();
		}
	}

	private Map<String, Object> readObjectMap(String json) {
		if (json == null || json.isBlank())
			return Map.of();
		try {
			return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
		} catch (Exception e) {
			return Map.of();
		}
	}

	/** 마켓 1곳의 등록 결과. */
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
