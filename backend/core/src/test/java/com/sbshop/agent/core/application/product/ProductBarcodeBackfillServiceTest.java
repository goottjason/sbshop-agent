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
class ProductBarcodeBackfillServiceTest {
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
	private ProductBarcodeBackfillService service;

	@Test
	@DisplayName("소싱처를 지정하면 그 소싱처의 미보유 상품만 대상으로 뽑는다")
	void findTargets_filtersByVendor() {
		when(productRepository.findBarcodeBackfillTargetIds(VendorType.VTB)).thenReturn(List.of(11L, 12L));

		List<Long> targets = service.findTargets(VendorType.VTB, 0);

		assertThat(targets).containsExactly(11L, 12L);
		verify(productRepository, never()).findBarcodeBackfillTargetIds();
	}

	@Test
	@DisplayName("소싱처를 지정하지 않으면 전 소싱처의 미보유 상품을 대상으로 뽑는다")
	void findTargets_withoutVendor_scansAll() {
		when(productRepository.findBarcodeBackfillTargetIds()).thenReturn(List.of(1L, 2L, 3L));

		assertThat(service.findTargets(null, 0)).containsExactly(1L, 2L, 3L);
	}

	@Test
	@DisplayName("limit을 주면 그 개수만큼만 대상으로 자른다")
	void findTargets_appliesLimit() {
		when(productRepository.findBarcodeBackfillTargetIds()).thenReturn(List.of(1L, 2L, 3L));

		assertThat(service.findTargets(null, 2)).containsExactly(1L, 2L);
	}

	@Test
	@DisplayName("수집한 바코드를 상품에 저장하고 성공으로 기록한다")
	void backfill_savesCollectedBarcode() {
		Product product = product("SB-1", null);
		when(productReader.findById(1L)).thenReturn(Optional.of(product));
		when(productDetailCrawlerPort.fetchDetail(anyString())).thenReturn(detail(true, "068958016375"));

		service.backfillBarcodes("b1", List.of(1L), "ACT");

		assertThat(product.getProductSpec().getBarcode()).isEqualTo("068958016375");
		verify(productWriter).save(product);
		verify(processStatusService).markSuccess(eq("b1"), eq("1"), contains("068958016375"));
	}

	@Test
	@DisplayName("이미 바코드가 있으면 크롤하지 않고 건너뛴다(멱등)")
	void backfill_skipsProductThatAlreadyHasBarcode() {
		Product product = product("SB-2", "5021265244171");
		when(productReader.findById(2L)).thenReturn(Optional.of(product));

		service.backfillBarcodes("b1", List.of(2L), "ACT");

		verify(productDetailCrawlerPort, never()).fetchDetail(anyString());
		verify(productWriter, never()).save(any());
		verify(processStatusService).markSuccess(eq("b1"), eq("2"), contains("건너뜀"));
	}

	@Test
	@DisplayName("크롤이 실패한 건은 사유를 기록하고 나머지는 계속 진행한다")
	void backfill_isolatesFailureAndContinues() {
		Product failing = product("SB-3", null);
		Product ok = product("SB-4", null);
		when(productReader.findById(3L)).thenReturn(Optional.of(failing));
		when(productReader.findById(4L)).thenReturn(Optional.of(ok));
		when(productDetailCrawlerPort.fetchDetail(anyString()))
			.thenReturn(detail(false, null), detail(true, "5021265244171"));

		service.backfillBarcodes("b1", List.of(3L, 4L), "ACT");

		verify(processStatusService).markFailed(eq("b1"), eq("3"), contains("크롤 실패"));
		assertThat(ok.getProductSpec().getBarcode()).isEqualTo("5021265244171");
		verify(processStatusService).markSuccess(eq("b1"), eq("4"), contains("5021265244171"));
		verify(productWriter, times(1)).save(any());
	}

	@Test
	@DisplayName("수집값이 형식 위반이면 저장하지 않고 사유를 남긴다")
	void backfill_rejectsInvalidBarcodeWithReason() {
		Product product = product("SB-5", null);
		when(productReader.findById(5L)).thenReturn(Optional.of(product));
		when(productDetailCrawlerPort.fetchDetail(anyString())).thenReturn(detail(true, "068958016374"));

		service.backfillBarcodes("b1", List.of(5L), "ACT");

		assertThat(product.getProductSpec().getBarcode()).isEmpty();
		verify(productWriter, never()).save(any());
		verify(processStatusService).markFailed(eq("b1"), eq("5"), contains("체크디짓"));
	}

	@Test
	@DisplayName("소스가 바코드를 안 주면 미보유로 기록하고 저장하지 않는다")
	void backfill_recordsMissingBarcode() {
		Product product = product("SB-6", null);
		when(productReader.findById(6L)).thenReturn(Optional.of(product));
		when(productDetailCrawlerPort.fetchDetail(anyString())).thenReturn(detail(true, null));

		service.backfillBarcodes("b1", List.of(6L), "ACT");

		verify(productWriter, never()).save(any());
		verify(processStatusService).markFailed(eq("b1"), eq("6"), contains("바코드 값 없음"));
	}

	@Test
	@DisplayName("성분 미검출로 ok=false여도 UPC가 오면 저장한다(iHerb 실측 응답 형태)")
	void backfill_savesUpcEvenWhenDetailNotOk() {
		Product product = product("SB-9", null);
		when(productReader.findById(9L)).thenReturn(Optional.of(product));
		when(productDetailCrawlerPort.fetchDetail(anyString())).thenReturn(detail(false, "835776002206"));

		service.backfillBarcodes("b1", List.of(9L), "ACT");

		assertThat(product.getProductSpec().getBarcode()).isEqualTo("835776002206");
		verify(productWriter).save(product);
	}

	@Test
	@DisplayName("소싱 URL이 없는 상품은 크롤하지 않고 실패로 기록한다")
	void backfill_failsWhenSourceUrlMissing() {
		Product product = productWithoutUrl("SB-7");
		when(productReader.findById(7L)).thenReturn(Optional.of(product));

		service.backfillBarcodes("b1", List.of(7L), "ACT");

		verify(productDetailCrawlerPort, never()).fetchDetail(anyString());
		verify(processStatusService).markFailed(eq("b1"), eq("7"), contains("소싱 URL 없음"));
	}

	@Test
	@DisplayName("배치가 끝나면 완료 이벤트를 발행해 가드를 해제한다")
	void backfill_publishesCompletionEvent() {
		Product product = product("SB-8", "5021265244171");
		when(productReader.findById(8L)).thenReturn(Optional.of(product));

		service.backfillBarcodes("b1", List.of(8L), "ACT");

		ArgumentCaptor<BatchCompletedEvent> captor = ArgumentCaptor.forClass(BatchCompletedEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());
		assertThat(captor.getValue().getBatchId()).isEqualTo("b1");
		assertThat(captor.getValue().getMessage()).contains("건너뜀 1");
	}

	private static ProductDetailDto detail(boolean ok, String upc) {
		return new ProductDetailDto(ok, ok ? "ok" : "error", "https://x/1", null, null, null, null,
			null, false, null, upc, null, null, null, null, null, null, null, null, null, null,
			null, null, List.of(), ok ? null : "차단됨");
	}

	private static Product product(String sbCode, String barcode) {
		return Product.create(sbCode, new ProductCreateCommand(
			"https://kr.iherb.com/pr/x/1", new BigDecimal("25"), "n", "on", "b", "US",
			BigDecimal.ONE, BigDecimal.TEN, MeasureUnit.TABLET, List.of(), List.of(), "h", "c",
			true, 1, BigDecimal.TEN, VendorType.IHB, barcode));
	}

	private static Product productWithoutUrl(String sbCode) {
		return Product.create(sbCode, new ProductCreateCommand(
			null, new BigDecimal("25"), "n", "on", "b", "US",
			BigDecimal.ONE, BigDecimal.TEN, MeasureUnit.TABLET, List.of(), List.of(), "h", "c",
			true, 1, BigDecimal.TEN, VendorType.IHB, null));
	}
}
