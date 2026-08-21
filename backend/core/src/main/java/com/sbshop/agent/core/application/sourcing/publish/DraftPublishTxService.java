package com.sbshop.agent.core.application.sourcing.publish;

import com.sbshop.agent.core.domain.sourcing.MarketDraft;
import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import com.sbshop.agent.core.domain.sourcing.repository.ProductDraftRepository;
import com.sbshop.agent.core.domain.sourcing.repository.SourcingCandidateRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DraftPublishTxService {
	private final ProductDraftRepository draftRepository;
	private final SourcingCandidateRepository candidateRepository;

	@Transactional(readOnly = true)
	public ProductDraft requireDraft(Long draftId) {
		return draftRepository.findById(draftId)
			.orElseThrow(() -> new IllegalArgumentException("초안을 찾을 수 없습니다: " + draftId));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markPublishing(Long draftId) {
		draftRepository.findById(draftId).ifPresent(d -> {
			d.markPublishing();
			draftRepository.save(d);
		});
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void finish(Long draftId, Long productId, boolean allOk,
		List<DraftPublishUseCase.MarketOutcome> outcomes) {
		Optional<ProductDraft> found = draftRepository.findById(draftId);
		if (found.isEmpty())
			return;
		ProductDraft draft = found.get();

		for (DraftPublishUseCase.MarketOutcome outcome : outcomes) {
			Optional<MarketDraft> md = draft.findMarketDraft(outcome.marketType());
			if (md.isEmpty())
				continue;
			if (outcome.ok())
				md.get().markPublished(outcome.identifiers());
			else
				md.get().markFailed(outcome.error());
		}

		if (allOk)
			draft.markPublished(productId);
		else

			draft.markFailed(productId);
		draftRepository.save(draft);

		if (allOk && draft.getCandidateId() != null) {
			candidateRepository.findById(draft.getCandidateId()).ifPresent(c -> {
				c.markPublished();
				candidateRepository.save(c);
			});
		}
	}
}
