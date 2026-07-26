package com.sbshop.agent.infrastructure.client.smartstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.smartstore.adapter.SmartstoreMarketClient;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SP-B Fix ①: SmartstoreMarketClient 품절 처리 — 재고 0 자동품절 방식.
 * statusType OUTOFSTOCK 는 v2 수정 API에서 무효(400)이므로 stockQuantity=0으로 품절을 표현한다.
 */
@ExtendWith(MockitoExtension.class)
class SmartstoreMarketClientSoldOutTest {

    @Mock private SmartstoreRestClient restClient;

    private SmartstoreMarketClient client;

    private static final String ITEM_ID = "OP123";

    @BeforeEach
    void setUp() {
        client = new SmartstoreMarketClient(
            // 신규 등록(publish) 전용 협력자 — 이 테스트가 검증하는 경로에서는 호출되지 않는다.
            null, null, null, null,
            restClient, new ObjectMapper());
    }

    private void stubGetWithStatusType(String statusType) throws Exception {
        String json = "{\"originProduct\":{\"productName\":\"Test\",\"salePrice\":1000,"
            + "\"stockQuantity\":10,\"statusType\":\"" + statusType + "\"}}";
        when(restClient.get(any())).thenReturn(json);
    }

    @Test
    @DisplayName("soldOut=true → PUT 바디 stockQuantity==0, status/statusType OUTOFSTOCK 없음 (자동품절)")
    void soldOutSendsStockQuantityZeroAndNoOutofstockField() throws Exception {
        stubGetWithStatusType("SALE");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

        client.syncPriceAndStock(ITEM_ID, new HashMap<>(), 5000, 1, true);

        verify(restClient).put(eq("/v2/products/origin-products/" + ITEM_ID), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> originProduct = (Map<String, Object>) captor.getValue().get("originProduct");
        // 재고 0 → API가 자동 품절 처리
        assertThat(originProduct.get("stockQuantity")).isEqualTo(0);
        // 수정 API에서 무효인 OUTOFSTOCK 값을 직접 지정하지 않음
        assertThat(originProduct.get("status")).isNotEqualTo("OUTOFSTOCK");
        assertThat(originProduct.get("statusType")).isNotEqualTo("OUTOFSTOCK");
    }

    @Test
    @DisplayName("soldOut=false → PUT 바디 stockQuantity==999, statusType==SALE")
    void inStockSetsSaleStatusTypeAndQuantity999() throws Exception {
        stubGetWithStatusType("OUTOFSTOCK");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

        client.syncPriceAndStock(ITEM_ID, new HashMap<>(), 5000, 999, false);

        verify(restClient).put(eq("/v2/products/origin-products/" + ITEM_ID), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> originProduct = (Map<String, Object>) captor.getValue().get("originProduct");
        assertThat(originProduct.get("stockQuantity")).isEqualTo(999);
        assertThat(originProduct.get("statusType")).isEqualTo("SALE");
    }

    @Test
    @DisplayName("soldOut=false, 기존 statusType==SUSPENSION → statusType 여전히 SUSPENSION (잠금 상태 보존)")
    void inStockDoesNotOverrideLockedStatusType() throws Exception {
        stubGetWithStatusType("SUSPENSION");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

        client.syncPriceAndStock(ITEM_ID, new HashMap<>(), 1000, 999, false);

        verify(restClient).put(eq("/v2/products/origin-products/" + ITEM_ID), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> originProduct = (Map<String, Object>) captor.getValue().get("originProduct");
        assertThat(originProduct.get("statusType")).isEqualTo("SUSPENSION");
        assertThat(originProduct.get("stockQuantity")).isEqualTo(999);
    }
}
