package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import com.sbshop.agent.core.domain.product.component.HtmlImageReplacer;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * F-PROD-5 / F-PROD-26 / F-PROD-29: 미존재 상품 id 조회 실패는 404(ResourceNotFoundException)여야 한다.
 *
 * <p>기존 결함 — 미존재 리소스 조회를 {@link IllegalArgumentException}(GlobalExceptionHandler가 400으로 매핑)으로
 * 던져 클라이언트에 "잘못된 입력(400)"으로 잘못 전달됐다. 입력값 자체는 유효하나 대상이 없는 경우이므로
 * 404가 옳다. 수정 후: "상품을 찾을 수 없습니다" 조회 실패는 {@link ResourceNotFoundException}으로 던진다.
 */
@ExtendWith(MockitoExtension.class)
class ProductNotFoundExceptionTest {

	@Mock
	private ProductReader productReader;
	@Mock
	private ProductWriter productWriter;
	@Mock
	private ImageStorageClient imageStorageClient;
	@Mock
	private HtmlImageReplacer htmlImageReplacer;
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private MarketClientRouter marketClientRouter;
	@Mock
	private ProductMarketSyncService productMarketSyncService;

	private ProductSearchUseCase searchUseCase;
	private ProductManageUseCase manageUseCase;

	private static final Long MISSING_ID = 999L;

	@BeforeEach
	void setUp() {
		searchUseCase = new ProductSearchUseCase(productReader);
		manageUseCase = new ProductManageUseCase(productReader, productWriter, imageStorageClient,
			htmlImageReplacer, marketRegistrationRepository, marketClientRouter, productMarketSyncService);
		when(productReader.findById(MISSING_ID)).thenReturn(Optional.empty());
	}

	@Test
	@DisplayName("getProductDetail: 미존재 id는 ResourceNotFoundException(404)을 던진다")
	void getProductDetail_missingId_throwsNotFound() {
		assertThatThrownBy(() -> searchUseCase.getProductDetail(MISSING_ID))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessageContaining("상품을 찾을 수 없습니다");
	}

	@Test
	@DisplayName("updatePriceStock: 미존재 id는 ResourceNotFoundException(404)을 던진다")
	void updatePriceStock_missingId_throwsNotFound() {
		assertThatThrownBy(() -> manageUseCase.updatePriceStock(MISSING_ID, new BigDecimal("10000"), null))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessageContaining("상품을 찾을 수 없습니다");
	}

	@Test
	@DisplayName("updateImagesAndHtml: 미존재 id는 ResourceNotFoundException(404)을 던진다")
	void updateImagesAndHtml_missingId_throwsNotFound() {
		assertThatThrownBy(() -> manageUseCase.updateImagesAndHtml(MISSING_ID, List.of()))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessageContaining("상품을 찾을 수 없습니다");
	}

	@Test
	@DisplayName("updateProduct: 미존재 id는 ResourceNotFoundException(404)을 던진다")
	void updateProduct_missingId_throwsNotFound() {
		assertThatThrownBy(() -> manageUseCase.updateProduct(MISSING_ID, ProductUpdateCommand.builder().build()))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessageContaining("상품을 찾을 수 없습니다");
	}

	@Test
	@DisplayName("deleteProduct: 미존재 id는 ResourceNotFoundException(404)을 던진다")
	void deleteProduct_missingId_throwsNotFound() {
		assertThatThrownBy(() -> manageUseCase.deleteProduct(MISSING_ID))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessageContaining("상품을 찾을 수 없습니다");
	}
}
