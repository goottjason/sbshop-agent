package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.ProductCreateUseCase;
import com.sbshop.agent.core.application.product.ProductPublishUseCase;
import com.sbshop.agent.core.application.sourcing.ProductSourcingUseCase;
import com.sbshop.agent.core.domain.product.Product;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/**
 * SP-D Task 1: POST /api/v1/products/bulk 이 생성된 productId 목록을 반환하는지 검증.
 */
@ExtendWith(MockitoExtension.class)
class ProductSourcingBulkTest {

    @Mock
    private ProductSourcingUseCase productSourcingUseCase;
    @Mock
    private ProductCreateUseCase productCreateUseCase;
    @Mock
    private ProductPublishUseCase productPublishUseCase;
    @Mock
    private ActionLogService actionLogService;

    private ProductSourcingController controller() {
        return new ProductSourcingController(
            productSourcingUseCase, productCreateUseCase, productPublishUseCase, actionLogService);
    }

    @Test
    @DisplayName("saveProductsBulk: 생성된 Product 의 id 목록을 응답 바디로 반환한다")
    void saveProductsBulk_returnsCreatedIds() {
        Product p1 = mock(Product.class);
        Product p2 = mock(Product.class);
        when(p1.getId()).thenReturn(1L);
        when(p2.getId()).thenReturn(2L);
        when(productCreateUseCase.createBulk(any())).thenReturn(List.of(p1, p2));

        ResponseEntity<List<Long>> response = controller().saveProductsBulk(List.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsExactly(1L, 2L);
    }
}
