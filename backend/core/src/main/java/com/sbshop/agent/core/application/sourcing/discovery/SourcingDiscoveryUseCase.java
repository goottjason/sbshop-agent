package com.sbshop.agent.core.application.sourcing.discovery;

import com.sbshop.agent.core.application.sourcing.dto.DiscoveryCrawlResult;
import com.sbshop.agent.core.application.sourcing.dto.DiscoverySummary;
import com.sbshop.agent.core.application.sourcing.port.BestsellerCrawlerPort;
import com.sbshop.agent.core.domain.sourcing.SourcingCandidate;
import com.sbshop.agent.core.domain.sourcing.SourcingConfig;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 신규 상품 후보 발굴 파이프라인 오케스트레이터 (S0 발굴 → S1 중복제외 → S2 통관 → S3 스코어링).
 *
 * <p><b>트랜잭션을 열지 않는다.</b> 베스트셀러 크롤은 카테고리×페이지만큼 브라우저 렌더를 돌려
 * 수 분이 걸리고, 통관 게이트는 후보마다 상세 페이지를 또 연다. 이 외부 I/O를 트랜잭션이 감싸면
 * DB 커넥션을 그 시간 내내 붙잡는다. DB 쓰기는 {@link CandidateIngestTxService} 등
 * 짧은 트랜잭션 빈에 위임한다(기존 {@code ProductCreateUseCase}와 같은 패턴).
 *
 * <p>단계별 실패는 조용히 삼키지 않고 {@link DiscoverySummary#warnings()}에 모아 올린다 —
 * 일부 카테고리가 차단당했는데 "인기 상품이 없네"로 오인하면 추천 품질이 조용히 무너진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourcingDiscoveryUseCase {

	private final SourcingConfigService configService;
	private final BestsellerCrawlerPort bestsellerCrawler;
	private final CandidateIngestTxService ingestTxService;
	private final CandidateEnrichmentPipeline enrichmentPipeline;

	public DiscoverySummary run() {
		LocalDateTime startedAt = LocalDateTime.now();
		SourcingConfig config = configService.getOrCreate();
		List<String> categories = config.categoryList();
		log.info("[소싱발굴] 시작 — 카테고리 {} · 카테고리당 {}페이지",
			categories, config.getPagesPerCategory());

		// 거절 쿨다운이 지난 후보를 먼저 풀어 이번 회차 재평가 대상에 넣는다.
		int released = ingestTxService.releaseExpiredRejections(config);

		// S0: 크롤 (트랜잭션 밖, 수 분 소요)
		DiscoveryCrawlResult crawl = bestsellerCrawler.discover(categories, config.getPagesPerCategory());
		if (crawl.candidates().isEmpty()) {
			log.warn("[소싱발굴] 수집된 후보가 없습니다. 실패: {}", crawl.failures());
			return DiscoverySummary.failed(startedAt, crawl.failures());
		}

		// S1: 적재 + 중복·부적격 제외 (짧은 트랜잭션)
		CandidateIngestTxService.IngestResult ingest =
			ingestTxService.ingest(crawl.candidates(), config);

		// S2~S3: 통관 게이트 + 수요신호 + 스코어링 (외부 I/O 포함 — 트랜잭션 밖)
		CandidateEnrichmentPipeline.Outcome outcome =
			enrichmentPipeline.process(ingest.survivors(), config);

		DiscoverySummary summary = new DiscoverySummary(
			startedAt,
			LocalDateTime.now(),
			crawl.candidates().size(),
			ingest.created(),
			ingest.updated(),
			ingest.excluded() + outcome.excluded(),
			outcome.scored(),
			outcome.customsBlocked(),
			outcome.customsReview(),
			released,
			concat(crawl.failures(), outcome.warnings()));

		log.info("[소싱발굴] 완료 — 수집 {} · 신규 {} · 추천대상 {} · 통관차단 {} · 소요 {}s",
			summary.crawled(), summary.created(), summary.scored(), summary.customsBlocked(),
			Duration.between(startedAt, summary.finishedAt()).toSeconds());
		return summary;
	}

	private List<String> concat(List<String> a, List<String> b) {
		return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
	}

	/** 후보 목록을 직접 넘겨 통관·스코어링만 다시 돌린다(설정 변경 후 재계산용). */
	public DiscoverySummary rescore(List<SourcingCandidate> candidates) {
		LocalDateTime startedAt = LocalDateTime.now();
		SourcingConfig config = configService.getOrCreate();
		CandidateEnrichmentPipeline.Outcome outcome = enrichmentPipeline.process(candidates, config);
		return new DiscoverySummary(startedAt, LocalDateTime.now(), candidates.size(), 0, 0,
			outcome.excluded(), outcome.scored(), outcome.customsBlocked(), outcome.customsReview(),
			0, outcome.warnings());
	}
}
