package com.sbshop.agent.core.application.sourcing.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.MarketRegistrationTxService;
import com.sbshop.agent.core.application.product.ProductCreateUseCase;
import com.sbshop.agent.core.application.product.dto.BulkProductCreateResult;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.sourcing.MarketDraft;
import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DraftPublishBarcodeTest {
	@Mock
	private ProductCreateUseCase productCreateUseCase;
	@Mock
	private MarketClientRouter marketClientRouter;
	@Mock
	private MarketRegistrationTxService registrationTxService;
	@Mock
	private DraftPublishTxService draftPublishTxService;
	@Spy
	private ObjectMapper objectMapper = new ObjectMapper();
	@InjectMocks
	private DraftPublishUseCase useCase;

	@Test
	@DisplayName("초안이 수집한 바코드가 상품 생성 커맨드로 전달된다")
	void publish_passesDraftBarcodeToCreateCommand() {
		ProductDraft draft = draftWithBarcode("068958016375");
		when(draftPublishTxService.requireDraft(anyLong())).thenReturn(draft);
		lenient().when(marketClientRouter.hasClient(any())).thenReturn(false);

		Product created = Product.create("SB-D-1", minimalCommand());
		when(productCreateUseCase.createBulk(any()))
			.thenReturn(
				new BulkProductCreateResult(List.of(new BulkProductCreateResult.Success(0, created)), List.of()));

		useCase.publish(1L);

		ArgumentCaptor<List<ProductCreateCommand>> captor = ArgumentCaptor.forClass(List.class);
		org.mockito.Mockito.verify(productCreateUseCase).createBulk(captor.capture());
		assertThat(captor.getValue().get(0).barcode()).isEqualTo("068958016375");
	}

	private static ProductDraft draftWithBarcode(String barcode) {
		ProductDraft draft = ProductDraft.builder()
			.baseNameKo("마그네슘")
			.originalName("Magnesium 400mg")
			.brand("KAL")
			.bundleQty(1)
			.marginRate(new BigDecimal("20"))
			.costPrice(new BigDecimal("25"))
			.sourceUrl("https://kr.iherb.com/pr/x/1")
			.vendor("IHB")
			.origin("USA")
			.barcode(barcode)
			.weightG(new BigDecimal("100"))
			.capacity(new BigDecimal("400"))
			.measureUnit(MeasureUnit.TABLET)
			.category("비타민")
			.build();
		draft.acknowledgeCustoms(true);
		MarketDraft md = MarketDraft.builder()
			.marketType(MarketType.COUPANG)
			.productName("KAL 마그네슘")
			.categoryId("1")
			.categoryPath("건강")
			.salePrice(new BigDecimal("10000"))
			.build();
		md.applyValidation("[]", true);
		draft.putMarketDraft(md);
		return draft;
	}

	private static ProductCreateCommand minimalCommand() {
		return new ProductCreateCommand(
			"https://kr.iherb.com/pr/x/1", new BigDecimal("25"), "마그네슘", "Magnesium 400mg",
			"KAL", "USA", new BigDecimal("100"), new BigDecimal("400"), MeasureUnit.TABLET,
			List.of(), List.of(), "<div>d</div>", "비타민", true, 1, new BigDecimal("20"),
			VendorType.IHB, null);
	}
}
