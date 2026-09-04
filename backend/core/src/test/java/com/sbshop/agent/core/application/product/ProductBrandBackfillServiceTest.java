package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.event.BatchCompletedEvent;
import com.sbshop.agent.core.application.sourcing.dto.ProductDetailDto;
import com.sbshop.agent.core.application.sourcing.port.ProductDetailCrawlerPort;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ProductBrandBackfillServiceTest {
	@Mock
	private ProductReader productReader;
	@Mock
	private ProductWriter productWriter;
	@Mock
	private ProductRepository productRepository;
	@Mock
	private ProductDetailCrawlerPort productDetailCrawlerPort;
	@Mock
	private ProcessStatusService processStatusService;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@InjectMocks
	private ProductBrandBackfillService service;

	@Test
	@DisplayName("D-261: 소싱처를 지정하면 그 소싱처의 대상만 뽑는다")
	void findTargets_filtersByVendor() {
		when(productRepository.findBrandBackfillTargetIds(VendorType.VTB)).thenReturn(List.of(11L, 12L));

		List<Long> targets = service.findTargets(VendorType.VTB, 0);

		assertThat(targets).containsExactly(11L, 12L);
		verify(productRepository, never()).findBrandBackfillTargetIds();
	}

	@Test
	@DisplayName("D-261: 소싱처를 지정하지 않으면 전 소싱처의 대상을 뽑는다")
	void findTargets_withoutVendor_scansAll() {
		when(productRepository.findBrandBackfillTargetIds()).thenReturn(List.of(1L, 2L, 3L));

		assertThat(service.findTargets(null, 0)).containsExactly(1L, 2L, 3L);
	}

	@Test
	@DisplayName("D-261: limit을 주면 그 개수만큼만 대상으로 자른다")
	void findTargets_appliesLimit() {
		when(productRepository.findBrandBackfillTargetIds()).thenReturn(List.of(1L, 2L, 3L));

		assertThat(service.findTargets(null, 2)).containsExactly(1L, 2L);
	}

	@Test
	@DisplayName("D-261: 온전한 브랜드로 갱신하고 성공으로 기록한다")
	void backfill_updatesToFullBrand() {
		Product product = product("SB-1", "Nature's", "Nature's Way Chlorofresh Liquid Chlorophyll");
		when(productReader.findById(1L)).thenReturn(Optional.of(product));
		when(productDetailCrawlerPort.fetchDetail(anyString()))
			.thenReturn(detail(true, "Nature's Way (네이처스웨이)"));

		service.backfillBrands("b1", List.of(1L), "ACT");

		assertThat(product.getBrand()).isEqualTo("Nature's Way");
		verify(productWriter).save(product);
		verify(processStatusService).markSuccess(eq("b1"), eq("1"), contains("Nature's Way"));
	}

	@Test
	@DisplayName("D-261: 크롤이 빈 값이면 스킵하고 기존 값을 유지한다")
	void backfill_skipsWhenCrawlBrandBlank() {
		Product product = product("SB-2", "Garden", "Garden Primal Defense Ultra");
		when(productReader.findById(2L)).thenReturn(Optional.of(product));
		when(productDetailCrawlerPort.fetchDetail(anyString())).thenReturn(detail(true, null));

		service.backfillBrands("b1", List.of(2L), "ACT");

		assertThat(product.getBrand()).isEqualTo("Garden");
		verify(productWriter, never()).save(any());
		verify(processStatusService).markSuccess(eq("b1"), eq("2"), contains("건너뜀"));
	}

	@Test
	@DisplayName("D-261: 크롤 결과가 첫 단어와 같으면 고쳐진 게 없으므로 스킵한다")
	void backfill_skipsWhenExtractedEqualsFirstWord() {
		Product product = product("SB-3", "Nature's", "Nature's Way Chlorofresh Liquid");
		when(productReader.findById(3L)).thenReturn(Optional.of(product));
		when(productDetailCrawlerPort.fetchDetail(anyString())).thenReturn(detail(true, "Nature's"));

		service.backfillBrands("b1", List.of(3L), "ACT");

		assertThat(product.getBrand()).isEqualTo("Nature's");
		verify(productWriter, never()).save(any());
		verify(processStatusService).markSuccess(eq("b1"), eq("3"), contains("건너뜀"));
	}

	@Test
	@DisplayName("D-261: 크롤 중 예외가 나면 실패로 기록하고 나머지는 계속 진행한다")
	void backfill_marksFailedOnCrawlException_andContinues() {
		Product failing = product("SB-4", "Doctor's", "Doctor's Best Fisetin");
		Product ok = product("SB-5", "Nordic", "Nordic Naturals Omega-3");
		when(productReader.findById(4L)).thenReturn(Optional.of(failing));
		when(productReader.findById(5L)).thenReturn(Optional.of(ok));
		when(productDetailCrawlerPort.fetchDetail(anyString()))
			.thenThrow(new RuntimeException("스크래퍼 호출 실패: timeout"))
			.thenReturn(detail(true, "Nordic Naturals (노르딕내추럴스)"));

		service.backfillBrands("b1", List.of(4L, 5L), "ACT");

		verify(processStatusService).markFailed(eq("b1"), eq("4"), contains("timeout"));
		assertThat(ok.getBrand()).isEqualTo("Nordic Naturals");
		verify(processStatusService).markSuccess(eq("b1"), eq("5"), contains("Nordic Naturals"));
		verify(productWriter, times(1)).save(any());
	}

	@Test
	@DisplayName("D-261: 소싱 URL이 없는 상품은 크롤하지 않고 실패로 기록한다")
	void backfill_failsWhenSourceUrlMissing() {
		Product product = productWithoutUrl("SB-6");
		when(productReader.findById(6L)).thenReturn(Optional.of(product));

		service.backfillBrands("b1", List.of(6L), "ACT");

		verify(productDetailCrawlerPort, never()).fetchDetail(anyString());
		verify(processStatusService).markFailed(eq("b1"), eq("6"), contains("소싱 URL 없음"));
	}

	@Test
	@DisplayName("D-261: 배치가 끝나면 완료 이벤트를 발행해 가드를 해제한다")
	void backfill_publishesCompletionEvent() {
		Product product = product("SB-7", "Garden", "Garden Primal Defense Ultra");
		when(productReader.findById(7L)).thenReturn(Optional.of(product));
		when(productDetailCrawlerPort.fetchDetail(anyString())).thenReturn(detail(true, null));

		service.backfillBrands("b1", List.of(7L), "ACT");

		ArgumentCaptor<BatchCompletedEvent> captor = ArgumentCaptor.forClass(BatchCompletedEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());
		assertThat(captor.getValue().getBatchId()).isEqualTo("b1");
		assertThat(captor.getValue().getMessage()).contains("건너뜀 1");
	}

	private static ProductDetailDto detail(boolean ok, String brandKo) {
		return new ProductDetailDto(ok, ok ? "ok" : "error", "https://x/1", null, null, brandKo, null,
			null, false, null, null, null, null, null, null, null, null, null, null, null, null,
			null, null, List.of(), ok ? null : "차단됨");
	}

	private static Product product(String sbCode, String brand, String originalName) {
		return Product.create(sbCode, new ProductCreateCommand(
			"https://kr.iherb.com/pr/x/1", new BigDecimal("25"), "n", originalName, brand, "US",
			BigDecimal.ONE, BigDecimal.TEN, MeasureUnit.TABLET, List.of(), List.of(), "h", "c",
			true, 1, BigDecimal.TEN, VendorType.IHB, null));
	}

	private static Product productWithoutUrl(String sbCode) {
		return Product.create(sbCode, new ProductCreateCommand(
			null, new BigDecimal("25"), "n", "n", "b", "US",
			BigDecimal.ONE, BigDecimal.TEN, MeasureUnit.TABLET, List.of(), List.of(), "h", "c",
			true, 1, BigDecimal.TEN, VendorType.IHB, null));
	}
}
