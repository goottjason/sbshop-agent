package com.sbshop.agent.core.application.sourcing.port;

import com.sbshop.agent.core.application.sourcing.dto.DiscoveryCrawlResult;
import java.util.List;

/** 벤더 베스트셀러 목록 크롤 포트. 구현: ScraplingIherbClient(사이드카 HTTP). */
public interface BestsellerCrawlerPort {

	/**
	 * 카테고리별 베스트셀러를 긁는다.
	 *
	 * @param categorySlugs 벤더 카테고리 slug 목록
	 * @param pagesPerCategory 카테고리당 페이지 수
	 */
	DiscoveryCrawlResult discover(List<String> categorySlugs, int pagesPerCategory);
}
