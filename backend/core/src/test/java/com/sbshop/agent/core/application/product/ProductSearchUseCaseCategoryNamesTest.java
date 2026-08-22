package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductSearchUseCaseCategoryNamesTest {

	@Mock
	private ProductReader productReader;

	@InjectMocks
	private ProductSearchUseCase productSearchUseCase;

	@Test
	@DisplayName("getCategoryNames: 중복을 제거하고 enum 이름 오름차순으로 정렬해 반환한다")
	void deduplicatesAndSorts() {
		when(productReader.findDistinctCategories()).thenReturn(Arrays.asList(
			ProductCategory.SUPPLEMENT, ProductCategory.FOOD, ProductCategory.SUPPLEMENT,
			ProductCategory.COSMETICS));

		assertThat(productSearchUseCase.getCategoryNames())
			.containsExactly("COSMETICS", "FOOD", "SUPPLEMENT");
	}

	@Test
	@DisplayName("getCategoryNames: null 카테고리는 제외한다")
	void dropsNulls() {
		when(productReader.findDistinctCategories()).thenReturn(Arrays.asList(
			ProductCategory.FOOD, null));

		assertThat(productSearchUseCase.getCategoryNames()).containsExactly("FOOD");
	}
}
