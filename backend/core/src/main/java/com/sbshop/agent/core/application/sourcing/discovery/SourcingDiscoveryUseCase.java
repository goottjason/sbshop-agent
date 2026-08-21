package com.sbshop.agent.core.application.sourcing.discovery;

import com.sbshop.agent.core.application.sourcing.dto.DiscoveryCrawlResult;
import com.sbshop.agent.core.application.sourcing.dto.DiscoverySummary;
import com.sbshop.agent.core.application.sourcing.port.BestsellerCrawlerPort;
import com.sbshop.agent.core.domain.sourcing.SourcingCandidate;
import com.sbshop.agent.core.domain.sourcing.SourcingConfig;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

		int released = ingestTxService.releaseExpiredRejections(config);

		DiscoveryCrawlResult crawl = bestsellerCrawler.discover(categories, config.getPagesPerCategory());
		if (crawl.candidates().isEmpty()) {
			log.warn("[소싱발굴] 수집된 후보가 없습니다. 실패: {}", crawl.failures());
			return DiscoverySummary.failed(startedAt, crawl.failures());
		}

		CandidateIngestTxService.IngestResult ingest = ingestTxService.ingest(crawl.candidates(), config);

		CandidateEnrichmentPipeline.Outcome outcome = enrichmentPipeline.process(ingest.survivors(), config);

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

	public DiscoverySummary rescore(List<SourcingCandidate> candidates) {
		LocalDateTime startedAt = LocalDateTime.now();
		SourcingConfig config = configService.getOrCreate();
		CandidateEnrichmentPipeline.Outcome outcome = enrichmentPipeline.process(candidates, config);
		return new DiscoverySummary(startedAt, LocalDateTime.now(), candidates.size(), 0, 0,
			outcome.excluded(), outcome.scored(), outcome.customsBlocked(), outcome.customsReview(),
			0, outcome.warnings());
	}

	private List<String> concat(List<String> a, List<String> b) {
		return Stream.concat(a.stream(), b.stream()).toList();
	}
}
