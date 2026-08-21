package com.sbshop.agent.infrastructure.client.smartstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.smartstore.adapter.SmartstoreMarketClient;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SP-C Task 2: SmartstoreMarketClient syncImagesAndHtml — optionalImages(다중이미지) + 실패 표면화 특성화 테스트.
 */
@ExtendWith(MockitoExtension.class)
class SmartstoreMarketClientImagesTest {

	@Mock
	private SmartstoreRestClient restClient;

	private SmartstoreMarketClient client;

	private static final String ITEM_ID = "OP1";

	@BeforeEach
	void setUp() {
		client = new SmartstoreMarketClient(
			// 신규 등록(publish) 전용 협력자 — 이 테스트가 검증하는 경로에서는 호출되지 않는다.
			null, null, null, null,
			restClient, new ObjectMapper());
	}

	private void stubGet(String json) {
        when(restClient.get(any())).thenReturn(json);
    }

	@Test
	@DisplayName("다중이미지: images.representativeImage.url==hostedImages[0], images.optionalImages==[{url}..], detailContent 세팅")
	void multipleImages_setsRepresentativeAndOptionalAndDetailContent() throws Exception {
		// 커머스API 스키마: originProduct.images.representativeImage.url (오브젝트)
		stubGet("{\"originProduct\":{\"images\":{\"representativeImage\":{\"url\":\"old\"}}}}");
		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

		client.syncImagesAndHtml(null, ITEM_ID, new HashMap<>(), List.of("u0.jpg", "u1.jpg", "u2.jpg"), "<html>");

		verify(restClient).put(eq("/v2/products/origin-products/" + ITEM_ID), captor.capture());
		@SuppressWarnings("unchecked") Map<String, Object> originProduct = (Map<String, Object>)captor.getValue()
			.get("originProduct");
		@SuppressWarnings("unchecked") Map<String, Object> images = (Map<String, Object>)originProduct.get("images");
		@SuppressWarnings("unchecked") Map<String, Object> representativeImage = (Map<String, Object>)images
			.get("representativeImage");
		assertThat(representativeImage.get("url")).isEqualTo("u0.jpg");
		assertThat(images.get("optionalImages")).isEqualTo(List.of(Map.of("url", "u1.jpg"), Map.of("url", "u2.jpg")));
		assertThat(originProduct.get("detailContent")).isNotNull();
	}

	@Test
	@DisplayName("단일이미지: images.representativeImage.url 세팅, optionalImages 세팅 안 함")
	void singleImage_setsRepresentativeOnly_noOptionalImages() throws Exception {
		stubGet("{\"originProduct\":{\"images\":{\"representativeImage\":{\"url\":\"old\"}}}}");
		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

		client.syncImagesAndHtml(null, ITEM_ID, new HashMap<>(), List.of("u0.jpg"), null);

		verify(restClient).put(eq("/v2/products/origin-products/" + ITEM_ID), captor.capture());
		@SuppressWarnings("unchecked") Map<String, Object> originProduct = (Map<String, Object>)captor.getValue()
			.get("originProduct");
		@SuppressWarnings("unchecked") Map<String, Object> images = (Map<String, Object>)originProduct.get("images");
		@SuppressWarnings("unchecked") Map<String, Object> representativeImage = (Map<String, Object>)images
			.get("representativeImage");
		assertThat(representativeImage.get("url")).isEqualTo("u0.jpg");
		assertThat(images.containsKey("optionalImages")).isFalse();
	}

	@Test
    @DisplayName("실패 표면화: GET 예외 시 예외가 호출자로 전파된다")
    void getFailure_propagatesException() {
        when(restClient.get(any())).thenThrow(new RuntimeException("네트워크 오류"));

        assertThatThrownBy(() ->
            client.syncImagesAndHtml(null, ITEM_ID, new HashMap<>(), List.of("u0.jpg"), null)
        ).isInstanceOf(RuntimeException.class)
         .hasMessageContaining("네트워크 오류");
    }
}
