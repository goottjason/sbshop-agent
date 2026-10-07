package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sbshop.agent.core.application.product.edit.ProductEditService;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import com.sbshop.agent.core.domain.product.component.HtmlImageReplacer;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProductManageImageIntegrityTest {

	private final ProductReader reader = mock(ProductReader.class);
	private final ImageStorageClient storage = mock(ImageStorageClient.class);
	private final ProductEditService edits = mock(ProductEditService.class);
	private final MarketRegistrationRepository registrations = mock(MarketRegistrationRepository.class);
	private final ProductManageUseCase useCase = new ProductManageUseCase(reader, mock(ProductWriter.class), storage,
		new HtmlImageReplacer(), registrations, mock(MarketClientRouter.class), mock(ProductMarketSyncService.class),
		null, null, edits);

	@Test
	void replacingHostedUuidImagesUpdatesDetailHtmlAndPreservesUnrelatedImages() {
		Product product = mock(Product.class);
		String oldImage = "https://r2.example/previous-uuid.jpg";
		String newImage = "https://r2.example/new-uuid.jpg";
		String banner = "<img src=\"https://example.com/banner.jpg\">";
		when(reader.findById(1L)).thenReturn(Optional.of(product));
		when(product.getSbCode()).thenReturn("SB001");
		when(product.getHostedImages()).thenReturn(List.of(oldImage));
		when(product.getDetailHtml()).thenReturn(banner + "<img src=\"" + oldImage + "\"><p>상품 설명</p>");
		when(storage.uploadImages(any())).thenReturn(Map.of("new.jpg", newImage));

		useCase.updateImagesAndHtml(1L, List.of(new ImageUploadFile("new.jpg", "image/jpeg", null, 10)));

		ArgumentCaptor<ProductUpdateCommand> command = ArgumentCaptor.forClass(ProductUpdateCommand.class);
		verify(edits).saveExisting(eq(1L), command.capture(), eq(0L), eq("system:image-edit"));
		assertThat(command.getValue().hostedImages()).containsExactly(newImage);
		assertThat(command.getValue().detailHtml()).contains(newImage, banner, "상품 설명").doesNotContain(oldImage);
	}

	@Test
	void failedImagePreparationCannotClearExistingImagesOrPublishEmptyImages() {
		when(reader.findById(1L)).thenReturn(Optional.of(mock(Product.class)));

		assertThatThrownBy(() -> useCase.updateImagesAndHtml(1L, List.of()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("이미지");

		verifyNoInteractions(storage, edits, registrations);
	}
}
