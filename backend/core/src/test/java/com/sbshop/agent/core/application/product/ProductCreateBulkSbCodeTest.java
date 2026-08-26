package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductCreateBulkSbCodeTest {
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
	@DisplayName("createBulk는 배치당 getNextSbCodeSequence를 정확히 1회 호출하고 sbCode가 연속이어야 한다")
	void createBulk_callsGetNextSbCodeSequenceOnce_andAssignsConsecutiveCodes() {
		when(productReader.getNextSbCodeSequence(anyString()))
			.thenAnswer(inv -> ((String) inv.getArgument(0)) + "006");

		List<ProductCreateCommand> commands = List.of(
			minimalCommand("상품A"),
			minimalCommand("상품B"),
			minimalCommand("상품C"));

		var result = useCase.createBulk(commands);

		verify(productReader, times(1)).getNextSbCodeSequence(anyString());

		assertThat(result.succeeded()).hasSize(3);

		assertThat(result.succeeded().get(0).product().getSbCode()).endsWith("IHB006");
		assertThat(result.succeeded().get(1).product().getSbCode()).endsWith("IHB007");
		assertThat(result.succeeded().get(2).product().getSbCode()).endsWith("IHB008");
	}

	private static ProductCreateCommand minimalCommand(String name) {
		return new ProductCreateCommand(
			"url", new BigDecimal("25"), name, name, "Brand", "KR",
			BigDecimal.ONE, new BigDecimal("500"), MeasureUnit.TABLET,
			null, null, "html", "카테고리",
			true, 1, new BigDecimal("20"), VendorType.IHB, null);
	}
}
