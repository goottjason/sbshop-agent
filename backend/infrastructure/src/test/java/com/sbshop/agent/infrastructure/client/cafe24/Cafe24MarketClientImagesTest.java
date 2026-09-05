package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.cafe24.adapter.Cafe24MarketClient;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import com.sbshop.agent.infrastructure.client.cafe24.component.Cafe24CategoryResolver;
import com.sbshop.agent.infrastructure.client.common.util.HtmlImageExtractor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Cafe24MarketClientImagesTest {

	@Mock
	private Cafe24RestClient cafe24RestClient;
	@Mock
	private HtmlImageExtractor imageExtractor;
	@Mock
	private Cafe24CategoryResolver categoryResolver;

	private Cafe24MarketClient client;

	private static final String ITEM_ID = "C100";
	private static final String IMAGE_URL = "https://cdn.example.com/u0.jpg";
	private static final String PRODUCT_PATH = "/admin/products/" + ITEM_ID;
	private static final String IMAGES_PATH = PRODUCT_PATH + "/images";

	@BeforeEach
	void setUp() {
		client = new Cafe24MarketClient(new ObjectMapper(), cafe24RestClient, imageExtractor, categoryResolver, null, null);
	}

	@Test
	@DisplayName("D-167: 상세설명 PUT 실패 → 예외가 호출자로 전파된다")
	void detailHtmlPutFailure_propagatesException() {
		when(cafe24RestClient.put(eq(PRODUCT_PATH), any())).thenThrow(new RuntimeException("Cafe24 API PUT 호출 실패(422)"));

		assertThatThrownBy(
			() -> client.syncImagesAndHtml(null, ITEM_ID, new HashMap<>(), List.of(IMAGE_URL), "<html>"))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("카페24")
				.hasMessageContaining(ITEM_ID);
	}

	@Test
	@DisplayName("D-167: 이미지 POST 실패 → 예외가 호출자로 전파된다")
	void imageUploadFailure_propagatesException() {
		when(cafe24RestClient.getExternalImageBytes(IMAGE_URL)).thenReturn(new byte[] {1, 2, 3});
		when(cafe24RestClient.post(eq(IMAGES_PATH), any())).thenThrow(new RuntimeException("Cafe24 API POST 호출 실패(500)"));

		assertThatThrownBy(
			() -> client.syncImagesAndHtml(null, ITEM_ID, new HashMap<>(), List.of(IMAGE_URL), "<html>"))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("카페24")
				.hasMessageContaining(ITEM_ID);
	}

	@Test
	@DisplayName("D-167: 외부 이미지 바이트가 null → 조용한 스킵이 아니라 예외 전파")
	void nullImageBytes_propagatesException() {
		when(cafe24RestClient.getExternalImageBytes(IMAGE_URL)).thenReturn(null);

		assertThatThrownBy(
			() -> client.syncImagesAndHtml(null, ITEM_ID, new HashMap<>(), List.of(IMAGE_URL), "<html>"))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("카페24")
				.hasMessageContaining(ITEM_ID);
	}

	@Test
	@DisplayName("D-167: 기존 이미지 삭제 실패는 삼키고 업로드를 계속한다")
	void existingImageDeleteFailure_isSwallowedAndUploadProceeds() {
		doThrow(new RuntimeException("Cafe24 API DELETE 호출 실패(404)")).when(cafe24RestClient).delete(IMAGES_PATH);
		when(cafe24RestClient.getExternalImageBytes(IMAGE_URL)).thenReturn(new byte[] {1, 2, 3});

		Map<String, Object> rawData = new HashMap<>();
		Map<String, Object> result = client.syncImagesAndHtml(null, ITEM_ID, rawData, List.of(IMAGE_URL), "<html>");

		verify(cafe24RestClient).post(eq(IMAGES_PATH), any());
		assertThat(result).isSameAs(rawData);
	}

	@Test
	@DisplayName("전부 성공 → rawData 의 detail_image·description 이 갱신되어 반환된다")
	void allSucceed_updatesRawData() {
		when(cafe24RestClient.getExternalImageBytes(IMAGE_URL)).thenReturn(new byte[] {1, 2, 3});

		Map<String, Object> rawData = new HashMap<>();
		Map<String, Object> result = client.syncImagesAndHtml(null, ITEM_ID, rawData, List.of(IMAGE_URL), "<html>");

		verify(cafe24RestClient).put(eq(PRODUCT_PATH), any());
		verify(cafe24RestClient).post(eq(IMAGES_PATH), any());
		assertThat(result.get("detail_image")).isEqualTo(IMAGE_URL);
		assertThat(result.get("description")).isEqualTo("<html>");
	}
}
