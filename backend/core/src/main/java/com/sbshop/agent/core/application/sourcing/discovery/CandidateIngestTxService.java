package com.sbshop.agent.core.application.sourcing.discovery;

import com.sbshop.agent.core.application.sourcing.dto.DiscoveredCandidateDto;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.sourcing.SourcingCandidate;
import com.sbshop.agent.core.domain.sourcing.SourcingConfig;
import com.sbshop.agent.core.domain.sourcing.component.VendorProductIdExtractor;
import com.sbshop.agent.core.domain.sourcing.enums.CandidateStatus;
import com.sbshop.agent.core.domain.sourcing.repository.SourcingCandidateRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발굴 결과 적재(S0) + 중복·부적격 제외(S1). DB 쓰기만 담당하는 짧은 트랜잭션 빈이다.
 *
 * <p>브라우저 렌더 크롤은 수 분이 걸리므로 {@code SourcingDiscoveryUseCase}가 트랜잭션 밖에서
 * 끝내고, 그 결과만 여기로 넘긴다(기존 {@code ProductCreateUseCase}/{@code ProductPersistTxService}와
 * 같은 패턴 — 외부 I/O를 트랜잭션이 감싸지 않는다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateIngestTxService {

	private final SourcingCandidateRepository candidateRepository;
	private final ProductRepository productRepository;

	/**
	 * 후보를 upsert하고 자동 제외 규칙을 적용한다.
	 *
	 * @return 통관 게이트·스코어링으로 넘길 생존 후보
	 */
	@Transactional
	public IngestResult ingest(List<DiscoveredCandidateDto> discovered, SourcingConfig config) {
		Set<String> registeredIds = registeredIherbIds();
		int created = 0, updated = 0, excluded = 0, skippedUserDecided = 0;

		List<SourcingCandidate> survivors = new java.util.ArrayList<>();
		for (DiscoveredCandidateDto dto : discovered) {
			if (dto.externalId() == null || dto.sourceUrl() == null)
				continue;

			VendorType vendor = parseVendor(dto.vendor());
			SourcingCandidate candidate = candidateRepository
				.findByVendorAndExternalId(vendor, dto.externalId())
				.orElse(null);

			if (candidate == null) {
				candidate = toEntity(dto, vendor);
				created++;
			} else {
				candidate.refreshFromDiscovery(
					dto.listPrice(), dto.discountPrice(), dto.discountPct(), dto.rating(),
					dto.reviewCount(), dto.sales30d(), dto.rankPosition(), dto.sponsored(),
					dto.outOfStock(), dto.discontinued(), dto.nameKo(), dto.imageUrl(),
					dto.categorySlug());
				updated++;
			}

			// 사용자가 이미 판단한 후보(거절/초안/등록완료)는 상태를 되돌리지 않는다.
			if (candidate.isUserDecided()) {
				candidateRepository.save(candidate);
				skippedUserDecided++;
				continue;
			}

			String reason = disqualify(candidate, registeredIds, config);
			if (reason != null) {
				candidate.exclude(reason);
				excluded++;
			} else {
				survivors.add(candidate);
			}
			candidateRepository.save(candidate);
		}

		log.info("[소싱발굴] 적재 완료 — 신규 {} · 갱신 {} · 제외 {} · 사용자판단보존 {} · 생존 {}",
			created, updated, excluded, skippedUserDecided, survivors.size());
		return new IngestResult(created, updated, excluded, skippedUserDecided, survivors);
	}

	/**
	 * 쿨다운이 지난 거절 후보를 다시 발굴 대상으로 되돌린다.
	 *
	 * <p>거절은 "지금은 아니다"이지 영구 차단이 아니다. 가격·경쟁 상황이 바뀌면 다시 볼 가치가 있다.
	 */
	@Transactional
	public int releaseExpiredRejections(SourcingConfig config) {
		int days = config.getRejectCooldownDays() != null ? config.getRejectCooldownDays() : 90;
		if (days <= 0)
			return 0;
		LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
		List<SourcingCandidate> expired = candidateRepository
			.findByCandidateStatusAndRejectedAtBefore(CandidateStatus.REJECTED, cutoff);
		for (SourcingCandidate c : expired) {
			c.exclude("거절 쿨다운 만료 — 다음 발굴에서 재평가");
			candidateRepository.save(c);
		}
		if (!expired.isEmpty())
			log.info("[소싱발굴] 거절 쿨다운 만료 {}건 해제", expired.size());
		return expired.size();
	}

	// --- 제외 규칙 ---

	/** 제외 사유. null이면 통과. */
	private String disqualify(SourcingCandidate c, Set<String> registeredIds, SourcingConfig config) {
		if (registeredIds.contains(c.getExternalId()))
			return "이미 등록된 상품";
		if (Boolean.TRUE.equals(c.getIsDiscontinued()))
			return "단종";
		if (Boolean.TRUE.equals(c.getIsOutOfStock()))
			return "품절";
		if (Boolean.TRUE.equals(config.getExcludeSponsored()) && Boolean.TRUE.equals(c.getIsSponsored()))
			return "광고 노출 상품(유기적 랭킹 아님)";
		if (c.effectiveBuyPrice().signum() <= 0)
			return "매입가를 확인할 수 없음";

		Integer minReviews = config.getMinReviewCount();
		if (minReviews != null && minReviews > 0) {
			int reviews = c.getReviewCount() != null ? c.getReviewCount() : 0;
			if (reviews < minReviews)
				return "리뷰 %d건 < 기준 %d건".formatted(reviews, minReviews);
		}
		if (config.getMinRating() != null && c.getRating() != null
			&& c.getRating().compareTo(config.getMinRating()) < 0) {
			return "평점 %s < 기준 %s".formatted(c.getRating(), config.getMinRating());
		}
		return null;
	}

	/** 이미 등록된 상품의 iHerb ID 집합. URL 문자열이 아니라 숫자 ID로 비교해야 한다. */
	private Set<String> registeredIherbIds() {
		Set<String> ids = new HashSet<>();
		for (String url : productRepository.findAllSourceUrls()) {
			String id = VendorProductIdExtractor.iherbId(url);
			if (id != null)
				ids.add(id);
		}
		return ids;
	}

	private SourcingCandidate toEntity(DiscoveredCandidateDto dto, VendorType vendor) {
		return SourcingCandidate.builder()
			.vendor(vendor)
			.externalId(dto.externalId())
			.sourceUrl(dto.sourceUrl())
			.partNumber(dto.partNumber())
			.brand(dto.brand())
			.brandCode(dto.brandCode())
			.nameKo(dto.nameKo())
			.categorySlug(dto.categorySlug())
			.imageUrl(dto.imageUrl())
			.listPrice(dto.listPrice())
			.discountPrice(dto.discountPrice())
			.discountPct(dto.discountPct())
			.rating(dto.rating())
			.reviewCount(dto.reviewCount())
			.sales30d(dto.sales30d())
			.rankPosition(dto.rankPosition())
			.isSponsored(dto.sponsored())
			.isOutOfStock(dto.outOfStock())
			.isDiscontinued(dto.discontinued())
			.build();
	}

	private VendorType parseVendor(String raw) {
		try {
			return VendorType.valueOf(raw);
		} catch (Exception e) {
			return VendorType.IHB;
		}
	}

	/** 적재 결과 요약. */
	public record IngestResult(
		int created, int updated, int excluded, int skippedUserDecided,
		List<SourcingCandidate> survivors) {

		public Map<String, Object> toMap() {
			Map<String, Object> m = new HashMap<>();
			m.put("created", created);
			m.put("updated", updated);
			m.put("excluded", excluded);
			m.put("skippedUserDecided", skippedUserDecided);
			m.put("survivors", survivors.size());
			return m;
		}
	}
}
