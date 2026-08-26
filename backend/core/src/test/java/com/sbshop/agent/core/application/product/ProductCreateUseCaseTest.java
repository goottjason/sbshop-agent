package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductCreateUseCaseTest {
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
	@DisplayName("bulk 생성 시 SKU를 자동 생성하고 R2에 이미지를 업로드한다")
	void createBulk_generatesSkuAndUploadsImages() {
		ProductCreateCommand cmd = new ProductCreateCommand(
			"url", new BigDecimal("25"), "Magnesium", "Mag 400mg", "KAL", "US",
			BigDecimal.ONE, new BigDecimal("400"), MeasureUnit.TABLET,
			List.of("https://img.iherb.com/1.jpg"), null, "html", "비타민",
			true, 2, new BigDecimal("20"), VendorType.IHB, null);

		when(productReader.getNextSbCodeSequence(any()))
			.thenReturn("260707IHB001");
		when(imageDownloadClient.downloadAndConvert(any()))
			.thenReturn(List.of(new ImageUploadFile("img.jpg", "image/jpeg", null, 100)));
		when(imageStorageClient.uploadImages(any()))
			.thenReturn(Map.of("img.jpg", "https://r2.dev/img.jpg"));

		var result = useCase.createBulk(List.of(cmd));

		assertThat(result.succeeded()).hasSize(1);
		assertThat(result.failed()).isEmpty();
		assertThat(result.succeeded().get(0).product().getSbCode()).contains("IHB");
		assertThat(result.succeeded().get(0).product().getHostedImages()).contains("https://r2.dev/img.jpg");
		verify(productPersistTxService).saveAll(any());
	}

	@Test
	@DisplayName("이미지 업로드 실패 시 원본 이미지로 진행한다")
	void createBulk_imageUploadFails_continuesWithoutHostedImages() {
		ProductCreateCommand cmd = new ProductCreateCommand(
			"url", new BigDecimal("25"), "Magnesium", "Mag", "KAL", "US",
			BigDecimal.ONE, new BigDecimal("400"), MeasureUnit.TABLET,
			List.of("https://img.iherb.com/1.jpg"), null, "html", "비타민",
			true, 1, new BigDecimal("20"), VendorType.IHB, null);

		when(productReader.getNextSbCodeSequence(any()))
			.thenReturn("260707IHB001");
		when(imageDownloadClient.downloadAndConvert(any()))
			.thenThrow(new RuntimeException("다운로드 실패"));

		var result = useCase.createBulk(List.of(cmd));

		assertThat(result.succeeded()).hasSize(1);
		assertThat(result.succeeded().get(0).product().getHostedImages()).isEmpty();
	}
}
