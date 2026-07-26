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

/**
 * 초안 등록 과정의 DB 쓰기 — 외부 게시를 감싸지 않는 짧은 트랜잭션들.
 *
 * <p>{@link DraftPublishUseCase}는 마켓 API를 순차 호출하므로 전체가 한 트랜잭션이면
 * 커넥션을 게시 내내 붙잡고, 마지막 마켓이 실패하면 앞선 등록 기록까지 롤백된다
 * (마켓에는 이미 올라갔는데 DB에는 없는 고아가 된다).
 */
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

	/** 게시 결과를 초안·마켓초안·후보에 반영한다. */
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
			// 일부 실패는 FAILED로 둔다 — 실패 마켓만 재시도할 수 있어야 하므로
			// productId는 채워 두고 상태만 구분한다.
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
