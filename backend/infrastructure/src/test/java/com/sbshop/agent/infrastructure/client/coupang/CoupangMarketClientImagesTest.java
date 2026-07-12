package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.coupang.adapter.CoupangMarketClient;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangCategoryPredictor;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangMetaService;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangSearchTagGenerator;
import com.sbshop.agent.infrastructure.client.coupang.config.CoupangProperties;
import com.sbshop.agent.infrastructure.client.coupang.mapper.CoupangDataMapper;
import com.sbshop.agent.infrastructure.client.coupang.parser.CoupangProductParser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SP-C Task 4: CoupangMarketClient.syncImagesAndHtml — sellerProductId 경로 포함 + 실패 전파 검증.
 */
@ExtendWith(MockitoExtension.class)
class CoupangMarketClientImagesTest {

    @Mock private CoupangProperties properties;
    @Mock private ObjectMapper objectMapper;
    @Mock private CoupangRestClient restClient;
    @Mock private CoupangCategoryPredictor categoryPredictor;
    @Mock private CoupangProductParser productParser;
    @Mock private CoupangSearchTagGenerator searchTagGenerator;
    @Mock private CoupangDataMapper dataMapper;
    @Mock private CoupangMetaService metaService;

    private CoupangMarketClient client;

    private static final String BASE_PATH =
        "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products";

    @BeforeEach
    void setUp() {
        client = new CoupangMarketClient(properties, objectMapper, restClient, categoryPredictor,
            productParser, searchTagGenerator, dataMapper, metaService);
    }

    @Test
    @DisplayName("sellerProductId=305 → PUT .../seller-products/305 호출")
    void syncImagesAndHtml_putsToSellerProductIdPath() {
        Map<String, Object> firstItem = new HashMap<>();
        Map<String, Object> raw = new HashMap<>();
        raw.put("items", List.of(firstItem));
        raw.put("sellerProductId", 305L);

        client.syncImagesAndHtml("V1", raw, List.of("u0", "u1"), "<html>");

        verify(restClient).put(eq(BASE_PATH + "/305"), any());
    }

    @Test
    @DisplayName("sellerProductId 부재 → IllegalStateException 전파")
    void syncImagesAndHtml_missingSellerProductId_throwsIllegalStateException() {
        Map<String, Object> firstItem = new HashMap<>();
        Map<String, Object> raw = new HashMap<>();
        raw.put("items", List.of(firstItem));
        // sellerProductId 없음

        assertThatThrownBy(() -> client.syncImagesAndHtml("V1", raw, List.of("u0"), "<html>"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("첫 번째 이미지는 REPRESENTATION 타입")
    void syncImagesAndHtml_firstImageIsRepresentation() {
        Map<String, Object> firstItem = new HashMap<>();
        Map<String, Object> raw = new HashMap<>();
        raw.put("items", List.of(firstItem));
        raw.put("sellerProductId", 305L);

        client.syncImagesAndHtml("V1", raw, List.of("u0", "u1"), "<html>");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> images = (List<Map<String, Object>>) firstItem.get("images");
        assertThat(images).isNotNull().hasSize(2);
        assertThat(images.get(0).get("imageType")).isEqualTo("REPRESENTATION");
        assertThat(images.get(1).get("imageType")).isEqualTo("DETAIL");
    }
}
