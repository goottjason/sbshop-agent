package com.sbshop.agent.core.application.sourcing.port;

import com.sbshop.agent.core.application.sourcing.dto.DiscoveryCrawlResult;
import java.util.List;

public interface BestsellerCrawlerPort {
	DiscoveryCrawlResult discover(List<String> categorySlugs, int pagesPerCategory);
}
