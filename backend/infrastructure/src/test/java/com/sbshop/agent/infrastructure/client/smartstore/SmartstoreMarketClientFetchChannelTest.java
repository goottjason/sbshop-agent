package com.sbshop.agent.infrastructure.client.smartstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.smartstore.adapter.SmartstoreMarketClient;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SmartstoreMarketClient.fetchChannelProductNo — originProductNo → STOREFARM channelProductNo 매핑 테스트.
 * 주의: /v1/products/search 요청/응답 스키마는 라이브 검증 필요(리스크 최고 가정).
 */
@ExtendWith(MockitoExtension.class)
class SmartstoreMarketClientFetchChannelTest {

    @Mock private SmartstoreRestClient restClient;

    private SmartstoreMarketClient client;

    @BeforeEach
    void setUp() {
        client = new SmartstoreMarketClient(restClient, new ObjectMapper());
    }

    @Test
    @DisplayName("STOREFARM 채널상품이 있으면 그 channelProductNo 를 반환한다")
    void returnsStorefarmChannelProductNo() {
        when(restClient.post(eq("/v1/products/search"), any())).thenReturn(
            "{\"contents\":[{\"originProductNo\":5327391662,\"channelProducts\":"
                + "[{\"channelProductNo\":5348874248,\"channelServiceType\":\"STOREFARM\"}]}]}");

        assertThat(client.fetchChannelProductNo("5327391662")).isEqualTo(Optional.of("5348874248"));
    }

    @Test
    @DisplayName("STOREFARM 이 없으면 첫 채널상품의 channelProductNo 를 반환한다(폴백)")
    void fallsBackToFirstChannelProduct() {
        when(restClient.post(eq("/v1/products/search"), any())).thenReturn(
            "{\"contents\":[{\"originProductNo\":5327391662,\"channelProducts\":"
                + "[{\"channelProductNo\":9999999999,\"channelServiceType\":\"WINDOW\"}]}]}");

        assertThat(client.fetchChannelProductNo("5327391662")).isEqualTo(Optional.of("9999999999"));
    }

    @Test
    @DisplayName("여러 채널상품 중 STOREFARM 을 우선 선택한다(순서 무관)")
    void prefersStorefarmOverOthers() {
        when(restClient.post(eq("/v1/products/search"), any())).thenReturn(
            "{\"contents\":[{\"originProductNo\":5327391662,\"channelProducts\":["
                + "{\"channelProductNo\":9999999999,\"channelServiceType\":\"WINDOW\"},"
                + "{\"channelProductNo\":5348874248,\"channelServiceType\":\"STOREFARM\"}]}]}");

        assertThat(client.fetchChannelProductNo("5327391662")).isEqualTo(Optional.of("5348874248"));
    }

    @Test
    @DisplayName("contents 가 비어 있으면 empty 를 반환한다")
    void emptyContents_returnsEmpty() {
        when(restClient.post(eq("/v1/products/search"), any())).thenReturn(
            "{\"contents\":[],\"totalElements\":0}");

        assertThat(client.fetchChannelProductNo("5327391662")).isEmpty();
    }

    @Test
    @DisplayName("originProductNo 가 일치하지 않으면 empty 를 반환한다")
    void noMatchingOrigin_returnsEmpty() {
        when(restClient.post(eq("/v1/products/search"), any())).thenReturn(
            "{\"contents\":[{\"originProductNo\":1111111111,\"channelProducts\":"
                + "[{\"channelProductNo\":5348874248,\"channelServiceType\":\"STOREFARM\"}]}]}");

        assertThat(client.fetchChannelProductNo("5327391662")).isEmpty();
    }

    @Test
    @DisplayName("restClient 가 예외를 던져도 전파하지 않고 empty 를 반환한다(best-effort)")
    void restClientThrows_returnsEmpty() {
        when(restClient.post(eq("/v1/products/search"), any())).thenThrow(new RuntimeException("네트워크 오류"));

        assertThat(client.fetchChannelProductNo("5327391662")).isEmpty();
    }

    @Test
    @DisplayName("blank 입력은 restClient 를 호출하지 않고 empty 를 반환한다")
    void blankInput_returnsEmptyWithoutCall() {
        assertThat(client.fetchChannelProductNo("   ")).isEmpty();
        assertThat(client.fetchChannelProductNo(null)).isEmpty();
        verify(restClient, never()).post(any(), any());
    }

    @Test
    @DisplayName("파싱 불가한 originProductNo 는 empty 를 반환한다")
    void unparseableInput_returnsEmpty() {
        assertThat(client.fetchChannelProductNo("not-a-number")).isEmpty();
    }
}
