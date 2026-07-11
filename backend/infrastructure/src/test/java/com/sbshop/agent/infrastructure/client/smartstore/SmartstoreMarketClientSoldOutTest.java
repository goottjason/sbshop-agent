package com.sbshop.agent.infrastructure.client.smartstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
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
 * SP-B Task 2: SmartstoreMarketClient soldOut → status OUTOFSTOCK/SALE + quantity≥1 특성화 테스트.
 */
@ExtendWith(MockitoExtension.class)
class SmartstoreMarketClientSoldOutTest {

    @Mock private SmartstoreRestClient restClient;

    private SmartstoreMarketClient client;

    private static final String ITEM_ID = "OP123";

    @BeforeEach
    void setUp() {
        client = new SmartstoreMarketClient(restClient, new ObjectMapper());
    }

    private void stubGetWithStatus(String status) throws Exception {
        String json = "{\"originProduct\":{\"productName\":\"Test\",\"salePrice\":1000,"
            + "\"stockQuantity\":10,\"status\":\"" + status + "\"}}";
        when(restClient.get(any())).thenReturn(json);
    }

    @Test
    @DisplayName("soldOut=true → PUT 바디에 status==OUTOFSTOCK, stockQuantity==1")
    void soldOutSetsOutOfStockStatusAndQuantityOne() throws Exception {
        stubGetWithStatus("SALE");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

        client.syncPriceAndStock(ITEM_ID, new HashMap<>(), 5000, 1, true);

        verify(restClient).put(eq("/v2/products/origin-products/" + ITEM_ID), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> originProduct = (Map<String, Object>) captor.getValue().get("originProduct");
        assertThat(originProduct.get("status")).isEqualTo("OUTOFSTOCK");
        assertThat(originProduct.get("stockQuantity")).isEqualTo(1);
    }

    @Test
    @DisplayName("soldOut=false → PUT 바디에 status==SALE, stockQuantity==999")
    void inStockSetsSaleStatusAndQuantity999() throws Exception {
        stubGetWithStatus("OUTOFSTOCK");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

        client.syncPriceAndStock(ITEM_ID, new HashMap<>(), 5000, 999, false);

        verify(restClient).put(eq("/v2/products/origin-products/" + ITEM_ID), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> originProduct = (Map<String, Object>) captor.getValue().get("originProduct");
        assertThat(originProduct.get("status")).isEqualTo("SALE");
        assertThat(originProduct.get("stockQuantity")).isEqualTo(999);
    }
}
