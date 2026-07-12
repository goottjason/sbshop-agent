package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
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
	private ProductWriter productWriter;
	@Mock
	private ImageDownloadClient imageDownloadClient;
	@Mock
	private ImageStorageClient imageStorageClient;
	@InjectMocks
	private ProductCreateUseCase useCase;

	private static ProductCreateCommand minimalCommand(String name) {
		return new ProductCreateCommand(
			"url", new BigDecimal("25"), name, name, "Brand", "KR",
			BigDecimal.ONE, new BigDecimal("500"), MeasureUnit.TABLET,
			null, null, "html", "카테고리",
			true, 1, new BigDecimal("20"), VendorType.IHB);
	}

	@Test
	@DisplayName("createBulk는 배치당 getNextSbCodeSequence를 정확히 1회 호출하고 sbCode가 연속이어야 한다")
	void createBulk_callsGetNextSbCodeSequenceOnce_andAssignsConsecutiveCodes() {
		// getNextSbCodeSequence가 prefix+"006"을 반환 — 기존 max는 005
		when(productReader.getNextSbCodeSequence(anyString()))
			.thenAnswer(inv -> ((String) inv.getArgument(0)) + "006");

		List<ProductCreateCommand> commands = List.of(
			minimalCommand("상품A"),
			minimalCommand("상품B"),
			minimalCommand("상품C"));

		var result = useCase.createBulk(commands);

		// 1) getNextSbCodeSequence는 정확히 1회만 호출되어야 한다
		verify(productReader, times(1)).getNextSbCodeSequence(anyString());

		// 2) 결과 3개
		assertThat(result).hasSize(3);

		// 3) sbCode 끝 3자리가 006, 007, 008로 연속
		assertThat(result.get(0).getSbCode()).endsWith("IHB006");
		assertThat(result.get(1).getSbCode()).endsWith("IHB007");
		assertThat(result.get(2).getSbCode()).endsWith("IHB008");
	}
}
