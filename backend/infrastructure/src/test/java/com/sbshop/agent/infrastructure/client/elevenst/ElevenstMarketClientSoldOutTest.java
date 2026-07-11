package com.sbshop.agent.infrastructure.client.elevenst;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.infrastructure.client.elevenst.adapter.ElevenstMarketClient;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SP-B Task 2: ElevenstMarketClient soldOut 판매상태 분기 특성화 테스트.
 */
@ExtendWith(MockitoExtension.class)
class ElevenstMarketClientSoldOutTest {

    @Mock private ElevenstMarketRestClient restClient;

    private ElevenstMarketClient client;

    @BeforeEach
    void setUp() {
        client = new ElevenstMarketClient(restClient);
    }

    @Test
    @DisplayName("soldOut=true → stopdisplay PUT 호출")
    void soldOutCallsStopDisplay() {
        client.syncPriceAndStock("P001", new HashMap<>(), null, 1, true);

        verify(restClient).put(eq("/rest/prodstatservice/stat/stopdisplay/P001"), anyString());
        verify(restClient, never()).put(eq("/rest/prodstatservice/stat/restartdisplay/P001"), anyString());
    }

    @Test
    @DisplayName("soldOut=false → restartdisplay PUT 호출")
    void inStockCallsRestartDisplay() {
        client.syncPriceAndStock("P001", new HashMap<>(), null, 999, false);

        verify(restClient).put(eq("/rest/prodstatservice/stat/restartdisplay/P001"), anyString());
        verify(restClient, never()).put(eq("/rest/prodstatservice/stat/stopdisplay/P001"), anyString());
    }

    @Test
    @DisplayName("price!=null → 가격 GET 호출")
    void withPriceCallsGetPrice() {
        when(restClient.get(anyString())).thenReturn("");
        client.syncPriceAndStock("P001", new HashMap<>(), 5000, 999, false);

        verify(restClient).get("/rest/prodservices/product/price/P001/5000");
    }

    @Test
    @DisplayName("price==null → 가격 GET 미호출")
    void withNullPriceSkipsGetPrice() {
        client.syncPriceAndStock("P001", new HashMap<>(), null, 1, true);

        verify(restClient, never()).get(anyString());
    }
}
