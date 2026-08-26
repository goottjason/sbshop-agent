package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.product.dto.BulkProductCreateResult;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductCreateBulkPartialFailureTest {
	@Mock
	private ProductReader productReader;
	@Mock
	private ProductPersistTxService productPersistTxService;
	@Mock
	private ImageDownloadClient imageDownloadClient;
	@Mock
	private ImageStorageClient imageStorageClient;
	@InjectMocks
	private ProductCreateUseCase useCase;

	@Test
	@DisplayName("일부 항목 생성 실패 시 성공/실패가 각각 집계되어 반환된다")
	void createBulk_surfacesPartialFailures() {
		when(productReader.getNextSbCodeSequence(anyString()))
			.thenAnswer(inv -> ((String) inv.getArgument(0)) + "001");

		ProductCreateCommand ok1 = command("정상A");
		ProductCreateCommand bad = command("불량B");
		ProductCreateCommand ok2 = command("정상C");

		try (MockedStatic<Product> productStatic = mockStatic(Product.class)) {
			productStatic.when(() -> Product.create(anyString(), any())).thenAnswer(inv -> {
				String sbCode = inv.getArgument(0);
				if (sbCode.endsWith("002")) {
					throw new RuntimeException("상품 생성 실패");
				}
				return mock(Product.class);
			});

			BulkProductCreateResult result = useCase.createBulk(List.of(ok1, bad, ok2));

			assertThat(result.succeeded()).hasSize(2);
			assertThat(result.failed()).hasSize(1);
			assertThat(result.failed().get(0).index()).isEqualTo(1);
			assertThat(result.failed().get(0).baseName()).isEqualTo("불량B");
			assertThat(result.failed().get(0).reason()).isNotBlank();

			assertThat(result.succeeded()).extracting(BulkProductCreateResult.Success::index)
				.containsExactly(0, 2);
			assertThat(result.succeeded().get(0).product()).isNotNull();
		}
	}

	private static ProductCreateCommand command(String name) {
		return new ProductCreateCommand(
			"url", new BigDecimal("25"), name, name, "Brand", "KR",
			BigDecimal.ONE, new BigDecimal("500"), MeasureUnit.TABLET,
			null, null, "html", "카테고리",
			true, 1, new BigDecimal("20"), VendorType.IHB, null);
	}
}
