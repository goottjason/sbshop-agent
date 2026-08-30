package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.pricing.VendorPricePolicyService;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.pricing.VendorPricePolicy;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductCreateMarginFromPolicyTest {

	@Mock
	private ProductReader productReader;
	@Mock
	private ImageDownloadClient imageDownloadClient;
	@Mock
	private ImageStorageClient imageStorageClient;
	@Mock
	private ProductPersistTxService productPersistTxService;
	@Mock
	private VendorPricePolicyService vendorPricePolicyService;

	private ProductCreateUseCase useCase() {
		lenient().when(productReader.getNextSbCodeSequence(any())).thenReturn("260830IHB001");
		return new ProductCreateUseCase(productReader, imageDownloadClient, imageStorageClient,
			productPersistTxService, vendorPricePolicyService);
	}

	private ProductCreateCommand command(BigDecimal marginRate, VendorType vendor) {
		return new ProductCreateCommand(
			"https://example.com/p/1", new BigDecimal("10000"), "n", "on", "b", "US",
			BigDecimal.ONE, BigDecimal.TEN, MeasureUnit.TABLET, List.of(), List.of(), "h", "c",
			true, 1, marginRate, vendor, null);
	}

	@Test
	@DisplayName("마진율을 주지 않으면 소싱처 정책값이 들어간다 — 0 으로 채우면 그 상품은 영원히 0% 로 계산된다")
	void fillsMarginFromVendorPolicy() {
		when(vendorPricePolicyService.find(VendorType.VTB)).thenReturn(Optional.of(
			VendorPricePolicy.builder().vendor(VendorType.VTB)
				.marginRate(new BigDecimal("25")).build()));

		var result = useCase().createBulk(List.of(command(null, VendorType.VTB)));

		assertThat(result.succeeded()).hasSize(1);
		assertThat(result.succeeded().get(0).product().getPriceInfo().getMarginRate())
			.isEqualByComparingTo("25");
	}

	@Test
	@DisplayName("명시한 마진율이 있으면 정책보다 우선한다")
	void explicitMarginWins() {
		var result = useCase().createBulk(List.of(command(new BigDecimal("30"), VendorType.VTB)));

		assertThat(result.succeeded().get(0).product().getPriceInfo().getMarginRate())
			.isEqualByComparingTo("30");
	}

	@Test
	@DisplayName("마진율도 정책도 없으면 상품을 만들지 않는다 — 0% 마진 상품이 조용히 생기면 안 된다")
	void refusesWhenNeitherMarginNorPolicy() {
		when(vendorPricePolicyService.find(any())).thenReturn(Optional.empty());

		var result = useCase().createBulk(List.of(command(null, VendorType.OCD)));

		assertThat(result.succeeded()).isEmpty();
		assertThat(result.failed()).hasSize(1);
		assertThat(result.failed().get(0).reason()).contains("마진");
	}
}
