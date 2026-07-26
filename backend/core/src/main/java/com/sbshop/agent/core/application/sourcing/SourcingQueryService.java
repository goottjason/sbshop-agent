package com.sbshop.agent.core.application.sourcing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.sourcing.discovery.SourcingConfigService;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.sourcing.MarketDraft;
import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import com.sbshop.agent.core.domain.sourcing.SourcingCandidate;
import com.sbshop.agent.core.domain.sourcing.component.MarketRequiredFieldValidator;
import com.sbshop.agent.core.domain.sourcing.enums.CandidateStatus;
import com.sbshop.agent.core.domain.sourcing.enums.CustomsVerdict;
import com.sbshop.agent.core.domain.sourcing.repository.ProductDraftRepository;
import com.sbshop.agent.core.domain.sourcing.repository.SourcingCandidateRepository;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 추천 목록 조회·거절, 초안 조회·검수 수정. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourcingQueryService {

	private final SourcingCandidateRepository candidateRepository;
	private final ProductDraftRepository draftRepository;
	private final SourcingConfigService configService;
	private final ObjectMapper objectMapper;

	// ── 후보 ──────────────────────────────────────────────────────────────

	/**
	 * 추천 목록. 점수 내림차순으로 설정된 개수만큼.
	 *
	 * @param limit          null이면 설정값(recommendCount) 사용
	 * @param includeReview  통관 확인필요(REVIEW) 후보를 포함할지. 기본 포함(경고 배지로 노출).
	 */
	@Transactional(readOnly = true)
	public List<SourcingCandidate> recommended(Integer limit, boolean includeReview) {
		int size = limit != null && limit > 0
			? limit : configService.getOrCreate().getRecommendCount();
		List<SourcingCandidate> found = candidateRepository.findTopScored(
			CandidateStatus.SCORED, PageRequest.of(0, Math.max(size, 1)));
		if (includeReview)
			return found;
		return found.stream()
			.filter(c -> c.getCustomsVerdict() != CustomsVerdict.REVIEW)
			.toList();
	}

	/** 통관 차단으로 제외된 후보 — 왜 안 올라오는지 확인할 수 있어야 한다. */
	@Transactional(readOnly = true)
	public List<SourcingCandidate> customsBlocked() {
		return candidateRepository.findByCandidateStatusIn(List.of(CandidateStatus.EXCLUDED)).stream()
			.filter(c -> c.getCustomsVerdict() == CustomsVerdict.BLOCKED)
			.toList();
	}

	@Transactional(readOnly = true)
	public SourcingCandidate requireCandidate(Long id) {
		return candidateRepository.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("후보를 찾을 수 없습니다: " + id));
	}

	@Transactional
	public SourcingCandidate reject(Long id) {
		SourcingCandidate c = requireCandidate(id);
		c.reject();
		return candidateRepository.save(c);
	}

	@Transactional(readOnly = true)
	public List<SourcingCandidate> findAllById(List<Long> ids) {
		return candidateRepository.findAllById(ids);
	}

	// ── 초안 ──────────────────────────────────────────────────────────────

	@Transactional(readOnly = true)
	public ProductDraft requireDraft(Long id) {
		return draftRepository.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("초안을 찾을 수 없습니다: " + id));
	}

	@Transactional(readOnly = true)
	public List<ProductDraft> drafts(List<String> statuses) {
		if (statuses == null || statuses.isEmpty())
			return draftRepository.findAll();
		List<com.sbshop.agent.core.domain.sourcing.enums.DraftStatus> parsed = statuses.stream()
			.map(s -> com.sbshop.agent.core.domain.sourcing.enums.DraftStatus.valueOf(s.toUpperCase()))
			.toList();
		return draftRepository.findByDraftStatusIn(parsed);
	}

	/**
	 * 검수 수정 반영.
	 *
	 * <p>공통 필드(묶음수량·마진율·원가)가 바뀌면 <b>마켓별 판매가를 다시 계산해야</b> 하지만,
	 * 여기서는 사용자가 명시적으로 넘긴 판매가만 반영한다. 가격 재계산은 별도 호출
	 * ({@code recalculatePrices})로 분리해 두었다 — 사용자가 손으로 맞춘 가격을
	 * 다른 필드 수정 때문에 조용히 덮어쓰면 안 되기 때문이다.
	 *
	 * <p>수정 후 필수필드를 다시 검사한다. 상품명을 지우는 식의 수정으로 등록 불가 상태가 되면
	 * 즉시 표시돼야 한다.
	 */
	@Transactional
	public ProductDraft updateDraft(Long draftId, DraftUpdate update) {
		ProductDraft draft = requireDraft(draftId);

		draft.updateCommon(update.baseNameKo(), update.bundleQty(), update.marginRate(),
			update.costPrice(), update.origin(), update.hsCode(), update.barcode(),
			update.weightG(), update.capacity(), parseUnit(update.measureUnit()),
			update.detailHtml());
		if (update.customsAck() != null)
			draft.acknowledgeCustoms(update.customsAck());

		for (MarketDraftUpdate mu : update.marketDrafts()) {
			MarketType type = MarketType.valueOf(mu.marketType().toUpperCase());
			draft.findMarketDraft(type).ifPresent(md ->
				md.update(mu.productName(), mu.categoryId(), mu.categoryPath(), mu.salePrice(),
					mu.keywords() != null ? toJson(mu.keywords()) : null,
					null, null, mu.enabled()));
		}

		revalidate(draft);
		return draftRepository.save(draft);
	}

	/** 마켓별 필수필드 재검사. 수정으로 등록 불가가 되면 즉시 드러나야 한다. */
	public void revalidate(ProductDraft draft) {
		for (MarketDraft md : draft.getMarketDrafts()) {
			List<String> missing = MarketRequiredFieldValidator.validate(draft, md);
			md.applyValidation(toJson(missing), missing.isEmpty());
		}
	}

	private MeasureUnit parseUnit(String raw) {
		if (raw == null || raw.isBlank())
			return null;
		try {
			return MeasureUnit.valueOf(raw.toUpperCase());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (Exception e) {
			return "[]";
		}
	}

	/** 검수 수정 입력(공통). null은 "변경 없음". */
	public record DraftUpdate(
		String baseNameKo, Integer bundleQty, BigDecimal marginRate, BigDecimal costPrice,
		String origin, String hsCode, String barcode, BigDecimal weightG, BigDecimal capacity,
		String measureUnit, String detailHtml, Boolean customsAck,
		List<MarketDraftUpdate> marketDrafts) {

		public DraftUpdate {
			if (marketDrafts == null)
				marketDrafts = List.of();
		}
	}

	public record MarketDraftUpdate(
		String marketType, String productName, String categoryId, String categoryPath,
		BigDecimal salePrice, List<String> keywords, Boolean enabled) {
	}
}
