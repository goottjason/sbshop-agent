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
		ProductDraft draft = requireDraft(draftId);

		draft.updateCommon(update.baseNameKo(), update.bundleQty(), update.marginRate(),
			update.costPrice(), update.origin(), update.hsCode(), update.barcode(),
			update.weightG(), update.capacity(), parseUnit(update.measureUnit()),
			update.detailHtml());
		if (update.customsAck() != null)
			draft.acknowledgeCustoms(update.customsAck());

		for (MarketDraftUpdate mu : update.marketDrafts()) {
			MarketType type = MarketType.valueOf(mu.marketType().toUpperCase());
			draft.findMarketDraft(type)
				.ifPresent(md -> md.update(mu.productName(), mu.categoryId(), mu.categoryPath(), mu.salePrice(),
					mu.keywords() != null ? toJson(mu.keywords()) : null,
					null, null, mu.enabled()));
		}

		revalidate(draft);
		return draftRepository.save(draft);
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
