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
import com.sbshop.agent.core.domain.sourcing.enums.DraftStatus;
import com.sbshop.agent.core.domain.sourcing.repository.ProductDraftRepository;
import com.sbshop.agent.core.domain.sourcing.repository.SourcingCandidateRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SourcingQueryService {
	private final SourcingCandidateRepository candidateRepository;
	private final ProductDraftRepository draftRepository;
	private final SourcingConfigService configService;
	private final ObjectMapper objectMapper;

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

	@Transactional(readOnly = true)
	public ProductDraft requireDraft(Long id) {
		return draftRepository.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("초안을 찾을 수 없습니다: " + id));
	}

	@Transactional(readOnly = true)
	public List<ProductDraft> drafts(List<String> statuses) {
		if (statuses == null || statuses.isEmpty())
			return draftRepository.findAll();
		List<DraftStatus> parsed = statuses.stream()
			.map(s -> DraftStatus.valueOf(s.toUpperCase()))
			.toList();
		return draftRepository.findByDraftStatusIn(parsed);
	}

	@Transactional
	public ProductDraft updateDraft(Long draftId, DraftUpdate update) {
		ProductDraft draft = draftRepository.findForUpdate(draftId)
			.orElseThrow(() -> new IllegalArgumentException("초안을 찾을 수 없습니다: " + draftId));
		if (draft.getDraftStatus() != DraftStatus.READY && draft.getDraftStatus() != DraftStatus.FAILED) {
			throw new IllegalStateException("등록 중이거나 등록 완료된 초안은 수정할 수 없습니다: " + draftId);
		}

		if (draft.getProductId() != null) {
			if (commonChanged(draft, update))
				throw new IllegalStateException("이미 생성된 상품의 공통정보는 상품 관리에서 수정하세요: " + draft.getProductId());
		} else {
			draft.updateCommon(update.baseNameKo(), update.bundleQty(), update.marginRate(),
				update.costPrice(), update.origin(), update.hsCode(), update.barcode(),
				update.weightG(), update.capacity(), parseUnit(update.measureUnit()),
				update.detailHtml());
		}
		if (update.customsAck() != null)
			draft.acknowledgeCustoms(update.customsAck());

		for (MarketDraftUpdate mu : update.marketDrafts()) {
			MarketType type = MarketType.valueOf(mu.marketType().toUpperCase());
			draft.findMarketDraft(type).ifPresent(md -> {
				if (hasPublishedIdentifiers(md) && (changed(mu.productName(), md.getProductName())
					|| changed(mu.categoryId(), md.getCategoryId()) || changed(mu.categoryPath(), md.getCategoryPath())
					|| changed(mu.salePrice(), md.getSalePrice()) || keywordsChanged(mu.keywords(), md.getKeywords())))
					throw new IllegalStateException("이미 등록된 마켓의 정보는 상품 관리에서 수정하세요: " + type);
				md.update(mu.productName(), mu.categoryId(), mu.categoryPath(), mu.salePrice(),
					mu.keywords() != null ? toJson(mu.keywords()) : null, null, null, mu.enabled());
			});
		}

		revalidate(draft);
		return draftRepository.save(draft);
	}

	private boolean commonChanged(ProductDraft draft, DraftUpdate update) {
		return changed(update.baseNameKo(), draft.getBaseNameKo())
			|| changed(update.bundleQty(), draft.getBundleQty()) || changed(update.marginRate(), draft.getMarginRate())
			|| changed(update.costPrice(), draft.getCostPrice()) || changed(update.origin(), draft.getOrigin())
			|| changed(update.hsCode(), draft.getHsCode()) || changed(update.barcode(), draft.getBarcode())
			|| changed(update.weightG(), draft.getWeightG()) || changed(update.capacity(), draft.getCapacity())
			|| changed(parseUnit(update.measureUnit()), draft.getMeasureUnit())
			|| changed(update.detailHtml(), draft.getDetailHtml());
	}

	private boolean changed(Object next, Object previous) {
		if (next == null || (previous == null && next instanceof String text && text.isBlank()))
			return false;
		if (next instanceof BigDecimal number && previous instanceof BigDecimal previousNumber)
			return number.compareTo(previousNumber) != 0;
		return !Objects.equals(next, previous);
	}

	private boolean keywordsChanged(List<String> next, String previous) {
		if (next == null)
			return false;
		if (previous == null || previous.isBlank())
			return !next.isEmpty();
		try {
			return !objectMapper.valueToTree(next).equals(objectMapper.readTree(previous));
		} catch (Exception e) {
			return true;
		}
	}

	private boolean hasPublishedIdentifiers(MarketDraft draft) {
		try {
			var identifiers = objectMapper.readTree(draft.getMarketIdentifiers());
			return identifiers != null && identifiers.isObject() && !identifiers.isEmpty();
		} catch (Exception e) {
			return false;
		}
	}

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
