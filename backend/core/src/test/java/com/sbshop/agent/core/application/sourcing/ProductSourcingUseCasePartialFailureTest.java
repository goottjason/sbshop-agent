package com.sbshop.agent.core.application.sourcing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.product.port.ProductInfoCrawlerPort;
import com.sbshop.agent.core.application.sourcing.dto.ScrapedProductDto;
import com.sbshop.agent.core.application.sourcing.dto.SourcingCrawlResult;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductSourcingUseCasePartialFailureTest {
	@Mock
	private ProductInfoCrawlerPort crawlerPort;
	@InjectMocks
	private ProductSourcingUseCase useCase;

	@Test
	@DisplayName("3개 URL 중 1개 크롤 실패 시 성공 2 + 실패 1(url·사유)이 결과에 담긴다")
	void sourceFromIherb_surfacesPartialFailures() {
		List<String> urls = List.of("url-1", "url-2", "url-3");
		SourcingCrawlResult crawled = new SourcingCrawlResult(
			List.of(
				ScrapedProductDto.builder().sourceUrl("url-1").baseName("A").vendor(VendorType.IHB).build(),
				ScrapedProductDto.builder().sourceUrl("url-3").baseName("C").vendor(VendorType.IHB).build()),
			List.of(new SourcingCrawlResult.SourcingFailure("url-2", "크롤 실패")));
		when(crawlerPort.crawlProducts(urls)).thenReturn(crawled);

		SourcingCrawlResult result = useCase.sourceFromIherb(urls);

		assertThat(result.succeeded()).hasSize(2);
		assertThat(result.failed()).hasSize(1);
		assertThat(result.failed().get(0).url()).isEqualTo("url-2");
		assertThat(result.failed().get(0).reason()).isNotBlank();
	}
}
