package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.dto.product.BulkProductCreateResponse;
import com.sbshop.agent.api.dto.product.ProductSaveRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.ProductCreateUseCase;
import com.sbshop.agent.core.application.product.ProductPublishUseCase;
import com.sbshop.agent.core.application.product.dto.BulkProductCreateResult;
import com.sbshop.agent.core.application.sourcing.ProductSourcingUseCase;
import com.sbshop.agent.core.domain.product.Product;
import java.math.BigDecimal;
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
    @DisplayName("saveProductsBulk: 성공 항목(index·productId·sbCode)을 응답 바디로 반환한다")
    void saveProductsBulk_returnsCreatedIds() {
        Product p1 = mock(Product.class);
        Product p2 = mock(Product.class);
        when(p1.getId()).thenReturn(1L);
        when(p2.getId()).thenReturn(2L);
        BulkProductCreateResult result = new BulkProductCreateResult(
            List.of(new BulkProductCreateResult.Success(0, p1),
                new BulkProductCreateResult.Success(1, p2)),
            List.of());
        when(productCreateUseCase.createBulk(any())).thenReturn(result);

        ResponseEntity<BulkProductCreateResponse> response = controller().saveProductsBulk(
            List.of(requestWithCost(new BigDecimal("1000")), requestWithCost(new BigDecimal("2000"))));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().succeeded())
            .extracting(BulkProductCreateResponse.Success::productId)
            .containsExactly(1L, 2L);
        assertThat(response.getBody().failed()).isEmpty();
    }

    private ProductSaveRequest requestWithCost(BigDecimal costPrice) {
        return new ProductSaveRequest(
            "http://x", costPrice, "name", "orig", "brand", "KR",
            null, null, null, null, null, null, true, null, null, null);
    }

    @Test
    @DisplayName("F-PSRC-7: requests가 null → IllegalArgumentException(400), NPE(500) 아님")
    void nullRequests_throwsIllegalArgument() {
        assertThatThrownBy(() -> controller().saveProductsBulk(null))
            .isInstanceOf(IllegalArgumentException.class);
        verify(productCreateUseCase, never()).createBulk(any());
    }

    @Test
    @DisplayName("F-PSRC-11: requests가 빈 목록 → IllegalArgumentException(400)")
    void emptyRequests_throwsIllegalArgument() {
        assertThatThrownBy(() -> controller().saveProductsBulk(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        verify(productCreateUseCase, never()).createBulk(any());
    }

    @Test
    @DisplayName("F-PSRC-11: costPrice 음수 항목 포함 → IllegalArgumentException(400)")
    void negativeCostPrice_throwsIllegalArgument() {
        assertThatThrownBy(() -> controller().saveProductsBulk(
            List.of(requestWithCost(new BigDecimal("-1")))))
            .isInstanceOf(IllegalArgumentException.class);
        verify(productCreateUseCase, never()).createBulk(any());
    }

    @Test
    @DisplayName("F-PSRC-1: sourceFromIherb urls가 null → IllegalArgumentException(400), UseCase NPE 아님")
    void nullUrls_throwsIllegalArgument() {
        assertThatThrownBy(() -> controller().sourceFromIherb(null))
            .isInstanceOf(IllegalArgumentException.class);
        verify(productSourcingUseCase, never()).sourceFromIherb(any());
    }

    @Test
    @DisplayName("F-PSRC-1: sourceFromIherb urls가 빈 목록 → IllegalArgumentException(400)")
    void emptyUrls_throwsIllegalArgument() {
        assertThatThrownBy(() -> controller().sourceFromIherb(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        verify(productSourcingUseCase, never()).sourceFromIherb(any());
    }
}
